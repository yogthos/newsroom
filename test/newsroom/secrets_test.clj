(ns newsroom.secrets-test
  "secrets.edn: a name resolves from the environment first, then the file.
  A file that other users can read, or that is not a map of strings, stops
  the start, and the error never shows a value from it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [newsroom.config :as config]
            [newsroom.llm.providers :as providers]
            [newsroom.plugin :as plugin]))

(defn- path [f] (java.nio.file.Paths/get (str f) (into-array String [])))

(defn- permissions [f]
  (try (set (map str (java.nio.file.Files/getPosixFilePermissions
                      (path f) (into-array java.nio.file.LinkOption []))))
       (catch UnsupportedOperationException _ nil)))

(def ^:private posix?
  "Whether this file system has POSIX permissions; Windows' doesn't."
  (some? (permissions (fs/create-temp-dir))))

(defn- chmod! [f mode]
  (when posix?
    (java.nio.file.Files/setPosixFilePermissions
     (path f) (java.nio.file.attribute.PosixFilePermissions/fromString mode))))

(defn- secrets-file
  "A secrets.edn in a new temporary home, holding `text`, set to `mode`."
  [text mode]
  (let [f (io/file (str (fs/create-temp-dir)) "secrets.edn")]
    (spit f text)
    (chmod! f mode)
    f))

(defn- message [f]
  (try (f) nil (catch Throwable e (ex-message e))))

(deftest a-name-resolves-from-the-environment-then-the-file
  (let [f (secrets-file (pr-str {"HOME" "from-file" "NEWSROOM_TEST_KEY" "sk-file"}) "rw-------")]
    (try
      (config/load-secrets! f)
      (testing "the environment wins"
        (is (= (System/getenv "HOME") (config/secret "HOME"))))
      (testing "the file serves a name the environment does not have"
        (is (= "sk-file" (config/secret "NEWSROOM_TEST_KEY"))))
      (testing "a name in neither is nil"
        (is (nil? (config/secret "NEWSROOM_TEST_UNSET"))))
      (testing "${VAR} in plugin settings reads the file"
        (plugin/set-config! {:plugins {:gamma {:token "${NEWSROOM_TEST_KEY}"}}})
        (is (= "sk-file" (:token (plugin/config :gamma)))))
      (testing "a provider's :api-key-env and ${VAR} read the file"
        (let [cfg {:providers {:a {:type :openai :api-key-env "NEWSROOM_TEST_KEY"}
                               :b {:type :openai :api-key "${NEWSROOM_TEST_KEY}"}}}]
          (is (= "sk-file" (:api-key (providers/resolve-provider cfg :a))))
          (is (= "sk-file" (:api-key (providers/resolve-provider cfg :b))))))
      (testing "an unset ${VAR} is still an error naming it"
        (is (str/includes? (message #(providers/resolve-provider
                                      {:providers {:c {:type :openai :api-key "${NEWSROOM_TEST_UNSET}"}}} :c))
                           "NEWSROOM_TEST_UNSET")))
      (finally (config/load-secrets! nil)))))

(deftest no-file-is-no-secrets
  (config/load-secrets! (io/file (str (fs/create-temp-dir)) "secrets.edn"))
  (is (nil? (config/secret "NEWSROOM_TEST_KEY")))
  (config/load-secrets! nil))

(deftest a-file-others-can-read-is-refused
  (doseq [mode (when posix? ["rw-r-----" "rw----r--" "rw--w----"])]
    (let [f (secrets-file (pr-str {"NEWSROOM_TEST_KEY" "sk-file"}) mode)
          msg (message #(config/load-secrets! f))]
      (is (some? msg) mode)
      (is (str/includes? msg (str f)))
      (is (str/includes? msg "chmod 600"))
      (is (not (str/includes? msg "sk-file"))))))

(deftest a-malformed-file-is-refused-without-its-values
  (doseq [text ["{\"A\" sk-secret :b}"
                "[\"sk-secret\"]"
                "{:a \"sk-secret\"}"
                "{\"A\" sk-secret}"]]
    (let [f (secrets-file text "rw-------")
          msg (message #(config/load-secrets! f))]
      (is (some? msg) text)
      (is (str/includes? msg (str f)))
      (is (not (str/includes? msg "sk-secret")) text))))

(deftest the-first-run-writes-an-empty-secrets-file-only-its-owner-can-read
  (let [home (str (fs/create-temp-dir))
        f (io/file home "secrets.edn")]
    (with-redefs [config/home (constantly home)]
      (config/ensure-home!)
      (is (.exists f))
      (when posix?
        (is (= #{"OWNER_READ" "OWNER_WRITE"} (permissions f))))
      (is (= {} (config/read-secrets f)))
      (testing "a file already there is left alone"
        (spit f (pr-str {"NEWSROOM_TEST_KEY" "sk-file"}))
        (chmod! f "rw-------")
        (config/ensure-home!)
        (is (= {"NEWSROOM_TEST_KEY" "sk-file"} (config/read-secrets f)))))))

(deftest an-empty-file-is-no-secrets
  (doseq [text ["" ";; nothing yet\n"]]
    (is (= {} (config/read-secrets (secrets-file text "rw-------"))) (pr-str text))))

(deftest with-several-readers-only-the-shared-secrets-are-read
  (let [f (secrets-file (pr-str {"DEEPSEEK_API_KEY" "sk-shared" "NEWSROOM_TEST_KEY" "sk-private"}) "rw-------")
        multi {:single-user false :shared-secrets ["DEEPSEEK_API_KEY"]}]
    (try
      (config/load-secrets! f)
      (testing "one reader reads every secret, as before"
        (is (= "sk-private" (config/secret-for {} "NEWSROOM_TEST_KEY")))
        (is (= "sk-private" (config/secret-for {:single-user true} "NEWSROOM_TEST_KEY"))))
      (testing "several read only what config.edn shares"
        (is (= (config/secret "DEEPSEEK_API_KEY") (config/secret-for multi "DEEPSEEK_API_KEY")))
        (is (nil? (config/secret-for multi "NEWSROOM_TEST_KEY")))
        (is (nil? (config/secret-for (dissoc multi :shared-secrets) "DEEPSEEK_API_KEY")) "none, unless shared"))
      (testing "a shared key serves its own provider"
        (is (= (config/secret "DEEPSEEK_API_KEY")
               (:api-key (providers/resolve-provider (assoc multi :providers {:deepseek {}}) :deepseek))))
        (is (= :deepseek (:alias (providers/role-llm multi :analyst))) "and is found for the analyst"))
      (testing "but isn't sent anywhere a reader's settings point it"
        (doseq [entry [{:type :deepseek :base-url "https://collector.example/v1"}
                       {:type :openai :api-key-env "DEEPSEEK_API_KEY"}
                       {:type :openai :api-key "${DEEPSEEK_API_KEY}"}
                       {:type :deepseek :base-url "https://collector.example/v1" :headers {"X-Key" "${DEEPSEEK_API_KEY}"}}]]
          (let [msg (message #(providers/resolve-provider (assoc multi :providers {:p entry}) :p))]
            (is (some? msg) (pr-str entry))
            (is (not (str/includes? (str msg) (config/secret "DEEPSEEK_API_KEY")))))))
      (testing "a secret that isn't shared is an error, not a value"
        (is (str/includes? (message #(providers/resolve-provider
                                      (assoc multi :providers {:p {:type :openai :api-key "${NEWSROOM_TEST_KEY}"}}) :p))
                           "isn't shared"))
        (plugin/set-config! (assoc multi :plugins {:gamma {:token "${NEWSROOM_TEST_KEY}"}}))
        (is (str/includes? (message #(plugin/config :gamma)) "isn't shared")))
      (testing "a key the reader typed in is theirs to send"
        (is (= "sk-own" (:api-key (providers/resolve-provider
                                   (assoc multi :providers {:p {:type :openai :base-url "https://mine.example/v1"
                                                                :api-key "sk-own"}})
                                   :p)))))
      (finally
        (plugin/set-config! {})
        (config/load-secrets! nil)))))
