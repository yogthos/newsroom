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

(defn- chmod! [f mode]
  (java.nio.file.Files/setPosixFilePermissions
   (path f) (java.nio.file.attribute.PosixFilePermissions/fromString mode)))

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
  (doseq [mode ["rw-r-----" "rw----r--" "rw--w----"]]
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
