(ns newsroom.json
  "clojure.data.json as jolt needs it. A jolt character is a code point, so
  an emoji is one character past U+FFFF, where data.json, written for
  Java's UTF-16, escapes it as five hex digits, which the other end reads
  as some other character and a letter, and throws on the surrogate pair
  another writer escapes it as. So JSON is written with its characters as
  they are, which JSON allows, and a surrogate pair is read back as the
  character it encodes."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(defn write-str
  "`x` as JSON, every character but those JSON has to escape as it is."
  [x & opts]
  (apply json/write-str x :escape-unicode false opts))

(def ^:private surrogate-escape
  "A \\u escape of a surrogate: a pair of them, or one alone. The backslashes
  before it are taken along, since only an odd run of them escapes it."
  #"(\\+)u([dD][89abAB][0-9a-fA-F]{2})(?:\\u([dD][c-fC-F][0-9a-fA-F]{2}))?|(\\+)u([dD][c-fC-F][0-9a-fA-F]{2})")

(defn- unescape-surrogates
  "`text` with each escaped surrogate pair as the character it encodes, and
  a surrogate alone as U+FFFD, which has no character on jolt to be."
  [text]
  (str/replace text surrogate-escape
               (fn [[whole slashes hi lo slashes2]]
                 (let [run (or slashes slashes2)]
                   (if (even? (count run))
                     whole
                     (str (subs run 0 (dec (count run)))
                          (if (and hi lo)
                            (str (char (+ 0x10000
                                          (bit-shift-left (- (Long/parseLong hi 16) 0xD800) 10)
                                          (- (Long/parseLong lo 16) 0xDC00))))
                            "�")))))))

(defn read-str
  "The value the JSON `text` holds, as clojure.data.json/read-str reads it
  with `opts`, an emoji escaped as a surrogate pair included."
  [text & opts]
  (apply json/read-str (unescape-surrogates (str text)) opts))
