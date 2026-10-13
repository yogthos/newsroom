(ns newsroom.readers-test
  "Newsroom with several readers, over HTTP: each signs in, and each sees
  and sets up only their own newsroom."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.auth :as auth]
            [newsroom.config :as config]
            [newsroom.core :as core]
            [newsroom.pipeline :as pipeline]
            [newsroom.settings :as settings]
            [newsroom.store :as store]
            [ring.util.codec :as codec]))

(def ^:private issuer "https://auth.example")

(def ^:private file
  {:single-user false
   :oauth {:issuer issuer :client-id "newsroom" :redirect-uri "https://news.example/oauth/callback"}})

(defn- b64 [s]
  (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) (.getBytes s "UTF-8")))

(defn- server
  "A request-json answering as the sign-in server, for the reader `sub`."
  [sub]
  (fn [{:keys [url body]}]
    (cond
      (str/ends-with? url "/.well-known/openid-configuration")
      {:status 200 :body {:issuer issuer :authorization_endpoint (str issuer "/auth")
                          :token_endpoint (str issuer "/token") :userinfo_endpoint (str issuer "/me")
                          :end_session_endpoint (str issuer "/session/end")}}
      (str/ends-with? url "/token")
      {:status 200 :body {:access_token "at"
                          :id_token (str (b64 "{}") "."
                                         (b64 (json/write-str {:iss issuer :aud "newsroom" :sub sub
                                                               :exp (+ (quot (System/currentTimeMillis) 1000) 600)
                                                               :nonce (get (codec/form-decode body) "code")}))
                                         ".sig")}}
      (str/ends-with? url "/me") {:status 200 :body {:sub sub :name (str "Reader " sub)}}
      :else {:status 404 :body ""})))

(defn- get-page [uri cookie]
  (core/app {:uri uri :request-method :get
             :headers (cond-> {"accept" "text/html"} cookie (assoc "cookie" (str auth/cookie-name "=" cookie)))}))

(defn- sign-in!
  "Sign `sub` in as a browser would, returning the session cookie's value."
  [sub]
  (with-redefs [auth/request-json (server sub)]
    (let [login (core/app {:uri "/login" :query-string "next=%2Fstories" :request-method :get :headers {}})
          q (codec/form-decode (second (str/split (get-in login [:headers "Location"]) #"\?" 2)))
          back (core/app {:uri "/oauth/callback"
                          :query-string (codec/form-encode {"code" (get q "nonce") "state" (get q "state")})
                          :request-method :get :headers {}})]
      (is (= 302 (:status back)) (:body back))
      (is (= "/stories" (get-in back [:headers "Location"])) "back to where the reader was going")
      (second (re-find (re-pattern (str auth/cookie-name "=([^;]+)")) (get-in back [:headers "Set-Cookie"]))))))

(defn- no-schedules
  "`f` with the readers' schedules kept from starting: a schedule whose
  time has passed today starts a real run, which would outlive the test's
  store."
  [f]
  (with-redefs [pipeline/start-schedule! (constantly nil)] (f)))

(defn- each-reader-signs-in []
  (let [st (store/open "sqlite::memory:")]
    (auth/forget!)
    (reset! core/system {:config (config/effective file {}) :store st :file file})
    (try
      (let [ann (sign-in! "ann")
            bob (sign-in! "bob")
            ann-id (:user-id (store/session st (config/sha-256 ann)))
            bob-id (:user-id (store/session st (config/sha-256 bob)))]
        (is (and ann-id bob-id (not= ann-id bob-id)))
        (is (not-any? #{store/local-user-id} [ann-id bob-id]))
        (store/save-day! (store/for-user st ann-id)
                         {:day "2026-09-30" :sources [] :cited [] :markdown "# Ann's day" :model "m" :provider "p"})
        (testing "a reader's page is theirs"
          (is (str/includes? (:body (get-page "/day/2026-09-30" ann)) "Ann"))
          (is (not (str/includes? (:body (get-page "/day/2026-09-30" bob)) "Ann")))
          (is (str/includes? (:body (get-page "/day/2026-09-30" ann)) "Reader ann") "and says who is signed in")
          (is (= 404 (:status (get-page "/day/2026-09-30.md" bob))))
          (is (= "# Ann's day" (:body (get-page "/day/2026-09-30.md" ann)))))
        (testing "without signing in, the browser is sent to sign in"
          (is (= 302 (:status (get-page "/day/2026-09-30" nil)))))
        (testing "a reader's settings are theirs"
          (let [form (settings/form-params (settings/to-form (assoc (:config (#'core/user-ctx bob-id)) :max-items 7)))
                resp (core/app {:uri "/config" :request-method :post :params form
                                :headers {"cookie" (str auth/cookie-name "=" bob)}})]
            (is (= 303 (:status resp)) (:body resp))
            (is (= 7 (:max-items (store/settings (store/for-user st bob-id)))))
            (is (= 7 (:max-items (:config (#'core/user-ctx bob-id)))) "and run with from now on")
            (is (not= 7 (:max-items (store/settings (store/for-user st ann-id)))))
            (is (not= 7 (:max-items (:config @core/system))) "the local reader's are left alone")))
        (testing "signing out ends the session"
          (with-redefs [auth/request-json (server "ann")]
            (let [resp (core/app {:uri "/logout" :request-method :post
                                  :headers {"cookie" (str auth/cookie-name "=" ann)}})]
              (is (= 303 (:status resp)))
              (is (str/starts-with? (get-in resp [:headers "Location"]) (str issuer "/session/end?")))
              (is (str/includes? (get-in resp [:headers "Set-Cookie"]) "Max-Age=0"))))
          (is (= 302 (:status (get-page "/day/2026-09-30" ann))))))
      (finally
        (doseq [uid (keys (:users @core/system))]
          (pipeline/cancel-run! uid)
          (is (not (pipeline/running? uid)) "no run outlives the test"))
        (reset! core/system nil)
        (store/close st)))))

(deftest each-reader-signs-in-to-their-own-newsroom
  (no-schedules #(each-reader-signs-in)))

(deftest a-sign-in-the-server-refuses-is-turned-away
  (let [st (store/open "sqlite::memory:")]
    (auth/forget!)
    (reset! core/system {:config (config/effective file {}) :store st :file file})
    (try
      (with-redefs [auth/request-json (server "ann")]
        (let [resp (core/app {:uri "/oauth/callback" :query-string "code=x&state=forged" :request-method :get :headers {}})]
          (is (= 403 (:status resp)))
          (is (nil? (get-in resp [:headers "Set-Cookie"])))
          (is (= 1 (count (store/users st))) "no one is added")))
      (finally
        (reset! core/system nil)
        (store/close st)))))

(deftest one-reader-has-no-sign-in
  (let [st (store/open "sqlite::memory:")]
    (reset! core/system {:config (config/effective {} {}) :store st :file {}})
    (try
      (is (= 302 (:status (core/app {:uri "/login" :request-method :get :headers {}}))))
      (is (= "/" (get-in (core/app {:uri "/login" :request-method :get :headers {}}) [:headers "Location"])))
      (is (= 200 (:status (get-page "/stories" nil))) "every page is the local reader's")
      (finally
        (reset! core/system nil)
        (store/close st)))))
