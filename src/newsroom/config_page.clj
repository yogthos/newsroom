(ns newsroom.config-page
  "The config page: every setting, what it does, and a form to change them.

  The form is drawn from the nested maps newsroom.settings/from-form reads,
  so a form sent back with errors is drawn again as it was sent, each error
  under its field. A list's entries are drawn with a <template> for a new
  one, which /js/config.js copies in under a fresh index when the list's
  add button is pressed; a source has a template per type, from the type's
  shape."
  (:require [clojure.string :as str]
            [newsroom.llm.providers :as providers]
            [newsroom.plugin :as plugin]
            [newsroom.settings :as settings]
            [newsroom.sources :as sources]))

(defn- label-of [{:keys [key label]}]
  (or label
      (let [s (-> (name key) (str/replace #"\?$" "") (str/replace "-" " "))]
        (str (str/upper-case (subs s 0 1)) (subs s 1)))))

(defn- token
  "What a list's template stands for an entry's index with, distinct at
  each depth so that a template inside another keeps its own."
  [depth]
  (str "__" depth "__"))

(defn- shown [v] (if (string? v) v ""))

(defn- placeholder [{:keys [default]}]
  (cond
    (nil? default) nil
    (string? default) default
    (keyword? default) (name default)
    (coll? default) nil
    :else (str default)))

(defn- remove-button [what]
  [:button.remove {:type "button" "data-cfg-remove" ""} (str "Remove " what)])

(defn- list-editor
  "A list's entries, drawn by `entry` from their name and form value, with
  a template for a new one and the button that adds it."
  [depth nm value entry add-label]
  [:div.list {"data-cfg-list" ""}
   [:div.entries
    (for [[i v] (settings/indexed value)]
      (entry (settings/field-name nm i) v))]
   [:template {"data-cfg-token" (token depth)}
    (entry (settings/field-name nm (token depth)) nil)]
   [:button.add {:type "button" "data-cfg-add" ""} add-label]])

(declare field-row extra-row)

(defn- input
  "The control for `field`, named `nm`, holding the form value `v`."
  [field nm v errors depth]
  (case (:type field)
    :text [:textarea {:id nm :name nm :rows (:rows field 4)} (shown v)]

    :boolean [:select {:id nm :name nm}
              (for [[value text] [["" "Default"] ["true" "Yes"] ["false" "No"]]]
                [:option {:value value :selected (= value (shown v))} text])]

    :keyword (if-let [options (:options field)]
               [:select {:id nm :name nm}
                [:option {:value ""} "—"]
                (for [o options :let [o (name o)]]
                  [:option {:value o :selected (= o (shown v))} o])
                (when-not (or (str/blank? (shown v)) (some #{(shown v)} (map name options)))
                  [:option {:value v :selected true} v])]
               [:input {:type "text" :id nm :name nm :value (shown v) :list (:datalist field)
                        :placeholder (placeholder field)}])

    :keywords [:span.checks
               [:input {:type "hidden" :name (settings/field-name nm "__") :value "1"}]
               (for [o (:options field) :let [o (name o)]]
                 [:label [:input {:type "checkbox" :name (settings/field-name nm o) :value "true"
                                  :checked (= "true" (get v o))}]
                  " " o])]

    :strings (list-editor depth nm v
                          (fn [n x]
                            [:div.entry.inline {"data-cfg-entry" ""}
                             [:input {:type "text" :name n :value (shown x)}]
                             [:button.remove {:type "button" "data-cfg-remove" "" :title "Remove"} "×"]])
                          (str "Add " (str/lower-case (label-of field))))

    :records (list-editor depth nm v
                          (fn [n r]
                            [:div.entry.record {"data-cfg-entry" ""}
                             (for [f (:fields field)]
                               (field-row f (settings/field-name n (:key f)) (get r (name (:key f)))
                                          errors (inc depth)))
                             (remove-button "")])
                          (str "Add " (str/lower-case (label-of field))))

    [:input {:type "text" :id nm :name nm :value (shown v) :placeholder (placeholder field)
             :inputmode (when (#{:int :number} (:type field)) "decimal")}]))

(defn- field-row
  "A field: its label, its control, what's wrong with it, and what it does."
  [field nm v errors depth]
  (let [error (get errors nm)
        list? (#{:strings :records :keywords} (:type field))]
    [:div.field {:class (when error "invalid")}
     [(if list? :div.label :label.label) (when-not list? {:for nm})
      (label-of field) (when (:required? field) [:span.required " *"])]
     [:div.control
      (input field nm v errors depth)
      (when error [:p.error (label-of field) " " error])
      (when-let [doc (:doc field)] [:p.doc doc])]]))

(defn- extra-row
  "The keys of a map no field shows, as EDN, folded away unless it has some."
  [nm m errors]
  (let [n (settings/field-name nm settings/extra-key)
        text (shown (get m settings/extra-key))
        error (get errors n)]
    [:details.extra {:open (boolean (or error (not (str/blank? text))))}
     [:summary "Other keys, as EDN"]
     [:textarea {:name n :rows 2 :placeholder "{:key value}"} text]
     (when error [:p.error "That " error])]))

;; --- sources -----------------------------------------------------------------------

(defn- adapter? [type]
  (contains? (set (settings/source-types)) type))

(defn- source-title [src]
  (let [s (try (settings/read-source src) (catch Exception _ nil))]
    (or (some-> s sources/source-name) "New source")))

(defn- source-entry [nm src errors fresh?]
  (let [type (some-> (get src "type") not-empty keyword)
        shape (sources/shape type)
        mine? (fn [k] (str/starts-with? k (str nm ".")))]
    [:details.entry.source {"data-cfg-entry" ""
                            :open (boolean (or fresh? (some mine? (keys errors))))}
     [:summary [:span.title (if fresh? "New source" (source-title src))]
      [:span.type (some-> type name)]]
     [:input {:type "hidden" :name (settings/field-name nm "type") :value (some-> type name)}]
     (when-let [doc (:doc shape)] [:p.doc doc])
     (when-not (adapter? type)
       [:p.error "No adapter reads sources of type " (some-> type name)
        ". A plugin may provide it; the source fails until one does."])
     (for [f (settings/source-fields type)]
       (field-row f (settings/field-name nm (:key f)) (get src (name (:key f))) errors 1))
     (extra-row nm src errors)
     (remove-button "source")]))

(defn- sources-section [tree errors]
  (let [types (settings/source-types)]
    [:div.list {"data-cfg-list" ""}
     [:p.doc "Where the news is gathered from. A plugin can add types of its own."]
     [:div.entries
      (for [[i src] (settings/indexed (get tree "sources"))]
        (source-entry (settings/field-name :sources i) src errors false))]
     (for [t types]
       [:template {"data-cfg-token" (token 0) "data-cfg-type" (name t)}
        (source-entry (settings/field-name :sources (token 0)) {"type" (name t)} {} true)])
     [:div.add-source
      [:select {"data-cfg-type-select" "" :aria-label "Type of source"}
       (for [t types] [:option {:value (name t)} (name t)])]
      [:button.add {:type "button" "data-cfg-add" ""} "Add source"]]]))

;; --- models ------------------------------------------------------------------------

(defn- provider-entry [nm entry errors]
  (let [alias-name (settings/field-name nm "_alias")]
    [:details.entry.provider {"data-cfg-entry" ""
                              :open (boolean (or (nil? entry)
                                                 (some #(str/starts-with? % (str nm ".")) (keys errors))))}
     [:summary [:span.title (or (not-empty (get entry "_alias")) "New provider")]
      [:span.type (get entry "model")]]
     (field-row {:key :_alias :label "Alias" :type :string :required? true
                 :doc "What roles call it by. Named after a built-in, it only overrides what it sets."}
                alias-name (get entry "_alias") errors 1)
     (for [f settings/provider-fields]
       (field-row f (settings/field-name nm (:key f)) (get entry (name (:key f))) errors 1))
     (extra-row nm entry errors)
     (remove-button "provider")]))

(defn- models-section [tree errors]
  (let [aliases (distinct (concat (map name (sort (keys providers/presets)))
                                  (keep #(not-empty (get % "_alias"))
                                        (map second (settings/indexed (get tree "providers"))))))]
    (list
     [:p.doc "The endpoints the models are reached at, by alias. The built-ins are "
      (str/join ", " (map name (sort (keys providers/presets))))
      "; a key comes from the built-in's usual variable, like DEEPSEEK_API_KEY, unless the entry says otherwise."]
     [:datalist {:id "provider-names"} (for [a aliases] [:option {:value a}])]
     [:h3 "Providers"]
     (list-editor 0 "providers" (get tree "providers")
                  (fn [n entry] (provider-entry n entry errors))
                  "Add provider")
     [:h3 "Roles"]
     [:p.doc "Which provider does each job. With none given, the first built-in whose key is in the environment does."]
     (for [f settings/role-fields]
       (field-row (assoc f :datalist "provider-names") (settings/field-name :roles (:key f))
                  (get-in tree ["roles" (name (:key f))]) errors 0)))))

;; --- plugins -----------------------------------------------------------------------

(defn- plugins-section [tree errors]
  (let [ps (sort-by key (get tree "plugins"))]
    (if (empty? ps)
      [:p.doc "No plugin has settings. A plugin is a folder in plugins/ in the config directory, "
       "loaded when newsroom starts."]
      (for [[p m] ps
            :let [nm (settings/field-name :plugins p)
                  shape (plugin/settings-shape p)]]
        [:fieldset.plugin
         [:legend p]
         (when-let [doc (:doc shape)] [:p.doc doc])
         (when-not shape
           [:p.doc "This plugin doesn't say what its settings are, so they are kept as EDN."])
         [:p.doc "A value written as ${VAR} is read from the environment."]
         (for [f (:fields shape)]
           (field-row f (settings/field-name nm (:key f)) (get m (name (:key f))) errors 1))
         (extra-row nm m errors)]))))

;; --- the page ----------------------------------------------------------------------

(defn- section [{:keys [title kind fields]} tree errors]
  [:section.settings {:id (str/lower-case title)}
   [:h2 title]
   (case kind
     :sources (sources-section tree errors)
     :models (models-section tree errors)
     :plugins (plugins-section tree errors)
     (for [f fields]
       (field-row f (settings/field-name (:key f)) (get tree (name (:key f))) errors 0)))])

(defn- import-export
  "Taking the settings out as EDN, and putting them back, or a config.edn
  in. `import` is what went wrong with the last import, {:errors :text}."
  [{:keys [errors text]}]
  [:section.settings {:id "import-export"}
   [:h2 "Import & export"]
   [:p.doc "The settings as EDN, in config.edn's form: the same file an older newsroom kept them in, "
    "and examples/config.edn shows every option of. An import replaces the settings it names and keeps the "
    "rest; :host, :port and :db stay in config.edn, so they are passed over."]
   [:p [:a {:href "/config/export" :download "newsroom-settings.edn"} "Export the settings"]]
   [:form.settings-form.import {:method "post" :action "/config/import" :enctype "multipart/form-data"}
    (when (seq errors)
      [:div.import-errors
       [:p.notice.bad "Nothing was imported."]
       [:ul (for [[where message] (sort-by key errors)]
              [:li [:code where] " " message])]])
    [:div.field
     [:label.label {:for "import-file"} "From a file"]
     [:div.control [:input {:type "file" :id "import-file" :name "file" :accept ".edn,text/plain"}]]]
    [:div.field
     [:label.label {:for "import-edn"} "Or pasted"]
     [:div.control [:textarea {:id "import-edn" :name "edn" :rows 6
                               :placeholder "{:max-items 120\n :sources [{:type :rss :name \"BBC World\" :url \"...\"}]}"}
                    (or text "")]]]
    [:button {:type "submit"} "Import"]]])

(defn article
  "The config page's content: the form `tree`, as newsroom.settings/to-form
  makes or from-form reads, with `errors` {name message} from a save that
  failed, whether the last save or import went through, `saved?` and
  `imported?`, and `import`, {:errors :text}, from an import that failed."
  [{:keys [tree errors saved? imported?] import-result :import}]
  [:article.briefing.config
   [:p.dateline "Settings"]
   [:h1 "Config"]
   [:p.standfirst "Saved to the database and in effect as soon as they're saved. "
    "Where the page is served stays in config.edn."]
   (cond
     (seq errors) [:p.notice.bad (count errors) (if (= 1 (count errors)) " field needs" " fields need")
                   " fixing. Nothing was saved."]
     (seq (:errors import-result)) [:p.notice.bad [:a {:href "#import-export"} "The import"] " didn't go through."]
     imported? [:p.notice.ok "Imported."]
     saved? [:p.notice.ok "Saved."])
   [:nav.toc (interpose " · " (concat (for [{:keys [title]} settings/sections]
                                        [:a {:href (str "#" (str/lower-case title))} title])
                                      [[:a {:href "#import-export"} "Import & export"]]))]
   [:form.settings-form {:method "post" :action "/config"}
    (for [s settings/sections] (section s tree errors))
    [:div.save-bar
     [:button {:type "submit"} "Save"]
     [:a {:href "/config"} "Discard changes"]]]
   (import-export import-result)])
