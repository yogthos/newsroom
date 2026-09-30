(ns slack.core
  "A Slack channel as a source: its recent messages, each one an item.

  Copy this folder to plugins/slack in the config directory. It needs a bot
  token with the channels:history scope (groups:history for a private
  channel), and the bot has to be in the channel. In config.edn:

    :plugins {:slack {:token \"${SLACK_BOT_TOKEN}\"
                      ;; acme for acme.slack.com, to link each message
                      :workspace \"acme\"}}

    :sources [... {:type :slack :name \"Slack #news\" :channel \"C0123456789\"}]

  :limit on a source caps how many messages are read, 50 by default."
  (:require [clojure.string :as str]
            [newsroom.plugin :as plugin]))

(defn- text
  "Slack's markup as plain text: <url|label> as its label, <url> as the url."
  [s]
  (-> (str s)
      (str/replace #"<([^>|]+)\|([^>]+)>" "$2")
      (str/replace #"<([^>]+)>" "$1")
      (str/replace #"\s+" " ")
      str/trim))

(defn- permalink [workspace channel ts]
  (str "https://" workspace ".slack.com/archives/" channel "/p" (str/replace ts "." "")))

(defn- published [ts]
  (str (java.time.Instant/ofEpochSecond (parse-long (first (str/split ts #"\."))))))

(plugin/defsource :slack [source ctx]
  (let [{:keys [token workspace]} (plugin/config :slack)
        channel (:channel source)]
    (when (str/blank? token)
      (throw (ex-info "the slack plugin needs a :token under :plugins :slack" {})))
    (plugin/emit! ctx (str "Reading " (plugin/source-name source)))
    (let [{:keys [ok error messages]}
          (plugin/get-json "https://slack.com/api/conversations.history"
                           {:headers {"Authorization" (str "Bearer " token)}
                            :query-params {"channel" channel
                                           "limit" (str (:limit source 50))}
                            :timeout-ms (:source-timeout-ms (:config ctx) 30000)})]
      (when-not ok
        (throw (ex-info (str "Slack answered " error) {:error error})))
      (vec (for [{:keys [ts subtype] :as m} messages
                 ;; joins, leaves, topic changes and the like
                 :when (nil? subtype)
                 :let [body (text (:text m))]
                 :when (not (str/blank? body))]
             (plugin/item source {:title (text (first (str/split-lines (:text m))))
                                  :url (permalink workspace channel ts)
                                  :summary body
                                  :published (published ts)}))))))
