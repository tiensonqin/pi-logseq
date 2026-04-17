(ns sync-logseq-db
  (:require ["fs" :as fs]
            [datascript.core :as d]
            [logseq.db.common.sqlite-cli :as sqlite-cli]
            [logseq.db.sqlite.create-graph :as sqlite-create-graph]
            [logseq.db.sqlite.export :as sqlite-export]))

(def pi-message-id-property :user.property/pi-message-id)
(def pi-message-role-property :user.property/pi-message-role)
(def pi-message-ts-property :user.property/pi-message-ts)

(def custom-properties
  {pi-message-id-property {:logseq.property/type :default
                           :db/cardinality :db.cardinality/one}
   pi-message-role-property {:logseq.property/type :default
                             :db/cardinality :db.cardinality/one}
   pi-message-ts-property {:logseq.property/type :number
                           :db/cardinality :db.cardinality/one}})

(defn built-in-template-tx
  []
  (sqlite-create-graph/build-db-initial-data "{}"))

(defn ensure-built-ins!
  [conn]
  ;; Mirrors logseq.outliner.cli/init-conn setup-init-data behavior for DB graphs:
  ;; always transact initial built-in ontology/config entities before importing.
  (d/transact! conn (built-in-template-tx)))

(defn read-payload [payload-path]
  (-> (fs/readFileSync payload-path "utf8")
      js/JSON.parse
      (js->clj :keywordize-keys true)))

(defn message-id-set
  [conn candidate-ids]
  (if (seq candidate-ids)
    (let [db @conn
          candidate-id-set (set candidate-ids)
          value->id
          (fn [value]
            (cond
              (string? value)
              value
              (number? value)
              (let [value-ent (d/entity db value)]
                (or (:block/title value-ent)
                    (:block/name value-ent)
                    (:logseq.property/value value-ent)))
              (map? value)
              (or (:block/title value)
                  (:block/name value)
                  (:logseq.property/value value))
              :else
              nil))]
      (->> (d/q '[:find ?value
                  :where [?b :user.property/pi-message-id ?value]]
                db)
           (map first)
           (keep value->id)
           (filter (fn [id] (contains? candidate-id-set id)))
           set))
    #{}))

(defn journal-session-linked?
  [conn conversation-page-title journal-int-date]
  (boolean
   (d/q '[:find ?b .
          :in $ ?journal-day ?link-title
          :where
          [?p :block/journal-day ?journal-day]
          [?b :block/page ?p]
          [?b :block/title ?link-title]]
        @conn
        journal-int-date
        (str "[[" conversation-page-title "]]"))))

(defn ->message-block [{:keys [id role text timestamp]}]
  {:block/title (str "[" role "] " text)
   :build/properties {pi-message-id-property id
                      pi-message-role-property role
                      pi-message-ts-property timestamp}})

(defn build-export-map
  [{:keys [conversationPageTitle journalIntDate]} new-records add-journal-link?]
  (let [conversation-page {:page {:block/title conversationPageTitle
                                  :build/keep-uuid? true}
                           :blocks (mapv ->message-block new-records)}
        journal-page {:page {:build/journal journalIntDate
                             :build/keep-uuid? true}
                      :blocks [{:block/title (str "[[" conversationPageTitle "]]")}]}]
    {:properties custom-properties
     :classes {}
     :pages-and-blocks (cond-> [conversation-page]
                         add-journal-link? (conj journal-page))}))

(defn transact-import!
  [conn export-map]
  (let [txs (sqlite-export/build-import export-map @conn {})]
    (when (seq (:init-tx txs))
      (d/transact! conn (:init-tx txs)))
    (when (seq (:block-props-tx txs))
      (d/transact! conn (:block-props-tx txs)))
    (when (seq (:misc-tx txs))
      (d/transact! conn (:misc-tx txs)))))

(defn sync!
  [{:keys [graphPath records journalIntDate] :as payload}]
  (let [open-db-args (sqlite-cli/->open-db-args graphPath)
        conn (apply sqlite-cli/open-db! open-db-args)
        _ (ensure-built-ins! conn)
        ids (->> records (map :id) (filter string?) vec)
        existing-ids (message-id-set conn ids)
        new-records (->> records
                         (filter (fn [{:keys [id text]}]
                                   (and (string? id)
                                        (not (contains? existing-ids id))
                                        (string? text)
                                        (not= "" text))))
                         vec)
        add-journal-link? (not (journal-session-linked? conn (:conversationPageTitle payload) journalIntDate))
        export-map (build-export-map payload new-records add-journal-link?)]
    (transact-import! conn export-map)
    {:createdMessages (count new-records)
     :linkedInJournal add-journal-link?}))

(defn -main [& args]
  (let [payload-path (first args)]
    (when-not (and payload-path (fs/existsSync payload-path))
      (js/console.error "Expected payload path as first argument")
      (js/process.exit 1))
    (try
      (let [result (-> payload-path read-payload sync!)]
        (js/console.log (js/JSON.stringify (clj->js (assoc result :ok true)))))
      (catch :default err
        (js/console.error (or (.-stack err) (str err)))
        (js/process.exit 1)))))

(apply -main *command-line-args*)
