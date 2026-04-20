(ns pi-logseq.db
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [logseq.db.common.sqlite :as sqlite]
            [logseq.db.common.sqlite-cli :as sqlite-cli]
            [logseq.db.sqlite.create-graph :as sqlite-create-graph]
            [logseq.db.sqlite.export :as sqlite-export]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def pi-message-id-property :user.property/pi-message-id)
(def pi-message-role-property :user.property/pi-message-role)

(def pi-memory-id-property :user.property/pi-memory-id)
(def pi-memory-normalized-property :user.property/pi-memory-normalized)
(def pi-memory-scope-property :user.property/pi-memory-scope)
(def pi-memory-type-property :user.property/pi-memory-type)
(def pi-memory-confidence-property :user.property/pi-memory-confidence)
(def pi-memory-project-property :user.property/pi-memory-project)
(def pi-memory-source-session-property :user.property/pi-memory-source-session)
(def pi-memory-source-turn-property :user.property/pi-memory-source-turn)
(def pi-memory-deleted-property :user.property/pi-memory-deleted)

(def pi-task-id-property :user.property/pi-task-id)
(def pi-task-description-property :user.property/pi-task-description)
(def pi-task-active-form-property :user.property/pi-task-active-form)
(def pi-task-owner-property :user.property/pi-task-owner)

(def task-tag-ident :logseq.class/Task)
(def default-task-status "todo")

(def task-status->logseq-status
  {"backlog" :logseq.property/status.backlog
   "todo" :logseq.property/status.todo
   "doing" :logseq.property/status.doing
   "in_review" :logseq.property/status.in-review
   "done" :logseq.property/status.done
   "canceled" :logseq.property/status.canceled})

(def logseq-status->task-status
  {:logseq.property/status.backlog "backlog"
   :logseq.property/status.todo "todo"
   :logseq.property/status.doing "doing"
   :logseq.property/status.in-review "in_review"
   :logseq.property/status.done "done"
   :logseq.property/status.canceled "canceled"})

(def uuid-pattern #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

(def custom-properties
  {pi-message-id-property {:logseq.property/type :default
                           :db/cardinality :db.cardinality/one
                           :logseq.property/hide? true}
   pi-message-role-property {:logseq.property/type :default
                             :db/cardinality :db.cardinality/one
                             :logseq.property/hide? true}
   pi-memory-id-property {:logseq.property/type :default
                          :db/cardinality :db.cardinality/one
                          :logseq.property/hide? true}
   pi-memory-normalized-property {:logseq.property/type :default
                                  :db/cardinality :db.cardinality/one
                                  :logseq.property/hide? true}
   pi-memory-scope-property {:logseq.property/type :default
                             :db/cardinality :db.cardinality/one
                             :logseq.property/hide? true}
   pi-memory-type-property {:logseq.property/type :default
                            :db/cardinality :db.cardinality/one
                            :logseq.property/hide? true}
   pi-memory-confidence-property {:logseq.property/type :number
                                  :db/cardinality :db.cardinality/one
                                  :logseq.property/hide? true}
   pi-memory-project-property {:logseq.property/type :default
                               :db/cardinality :db.cardinality/one
                               :logseq.property/hide? true}
   pi-memory-source-session-property {:logseq.property/type :default
                                      :db/cardinality :db.cardinality/one
                                      :logseq.property/hide? true}
   pi-memory-source-turn-property {:logseq.property/type :default
                                   :db/cardinality :db.cardinality/one
                                   :logseq.property/hide? true}
   pi-memory-deleted-property {:logseq.property/type :checkbox
                               :db/cardinality :db.cardinality/one
                               :logseq.property/hide? true}
   pi-task-id-property {:logseq.property/type :default
                        :db/cardinality :db.cardinality/one
                        :logseq.property/hide? true}
   pi-task-description-property {:logseq.property/type :default
                                 :db/cardinality :db.cardinality/one
                                 :logseq.property/hide? true}
   pi-task-active-form-property {:logseq.property/type :default
                                 :db/cardinality :db.cardinality/one
                                 :logseq.property/hide? true}
   pi-task-owner-property {:logseq.property/type :default
                           :db/cardinality :db.cardinality/one
                           :logseq.property/hide? true}})

(defn built-in-template-tx
  []
  (sqlite-create-graph/build-db-initial-data "{}"))

(defn ensure-built-ins!
  [conn]
  (d/transact! conn (built-in-template-tx)))

(defn scalar-value
  [db value]
  (let [entity-scalar (fn [entity]
                        (or (:block/title entity)
                            (:block/name entity)
                            (:logseq.property/value entity)
                            (some-> (:block/uuid entity) str)))
        direct-scalar (entity-scalar value)]
    (cond
      (some? direct-scalar) direct-scalar
      (string? value) value
      (boolean? value) value
      (nil? value) nil
      (number? value) (let [ent (d/entity db value)]
                        (or (and ent (entity-scalar (d/touch ent)))
                            value))
      (some? (:db/id value)) (scalar-value db (:db/id value))
      :else
      (let [ent (d/entity db value)]
        (or (and ent (entity-scalar (d/touch ent)))
            value)))))

(defn property-id-set
  [conn property candidate-ids]
  (if (seq candidate-ids)
    (let [db @conn
          candidate-id-set (set candidate-ids)]
      (->> (d/q '[:find ?value
                  :in $ ?property
                  :where [?b ?property ?value]]
                db
                property)
           (map first)
           (map #(scalar-value db %))
           (filter string?)
           (filter #(contains? candidate-id-set %))
           set))
    #{}))

(defn truthy?
  [value]
  (cond
    (true? value) true
    (false? value) false
    (string? value) (contains? #{"true" "1" "yes" "on"} (str/lower-case value))
    :else false))

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

(defn ->message-block
  [{:keys [id role text timestamp]}]
  {:block/title (str "[" role "] " text)
   :block/created-at (long timestamp)
   :block/updated-at (long timestamp)
   :build/properties {pi-message-id-property id
                      pi-message-role-property role}})

(defn build-conversation-export-map
  [{:keys [conversationPageTitle journalIntDate]} new-records add-journal-link?]
  (let [conversation-page {:page {:block/title conversationPageTitle
                                  :build/keep-uuid? true
                                  :build/tags [:user.class/Chat]}
                           :blocks (mapv ->message-block new-records)}
        journal-page {:page {:build/journal journalIntDate
                             :build/keep-uuid? true}
                      :blocks [{:block/title (str "[[" conversationPageTitle "]]")}]}]
    {:properties custom-properties
     :classes {:user.class/Chat {}}
     :pages-and-blocks (cond-> [conversation-page]
                         add-journal-link? (conj journal-page))}))

(defn current-journal-int-date
  []
  (let [now (js/Date.)
        year (.getFullYear now)
        month (-> (inc (.getMonth now)) str (.padStart 2 "0"))
        day (-> (.getDate now) str (.padStart 2 "0"))]
    (js/parseInt (str year month day) 10)))

(defn normalize-task-status
  [value]
  (let [normalized (if (string? value)
                     (-> value str/trim str/lower-case (str/replace "-" "_"))
                     "")]
    (cond
      (contains? task-status->logseq-status normalized) normalized
      :else default-task-status)))

(defn resolve-task-status-ident
  [status]
  (get task-status->logseq-status
       (normalize-task-status status)
       :logseq.property/status.todo))

(defn parse-task-id-number
  [value]
  (if (and (string? value) (boolean (re-matches #"^\d+$" value)))
    (js/parseInt value 10)
    nil))

(defn next-task-id
  [db]
  (let [rows (d/q '[:find ?value
                    :in $ ?property
                    :where [?b ?property ?value]]
                  db
                  pi-task-id-property)
        max-id (reduce (fn [acc [value]]
                         (if-let [parsed (parse-task-id-number (scalar-value db value))]
                           (max acc parsed)
                           acc))
                       0
                       rows)]
    (str (inc max-id))))

(defn ->task-block
  [{:keys [id subject description status activeForm owner createdAt updatedAt]}]
  (let [created-at (long (or createdAt updatedAt (.now js/Date)))
        updated-at (long (or updatedAt created-at))
        title (if (and (string? subject) (not= "" (str/trim subject)))
                (str/trim subject)
                "Untitled task")
        properties (cond-> {pi-task-id-property id
                            pi-task-description-property (if (string? description) description "")
                            :logseq.property/status (resolve-task-status-ident status)}
                     (and (string? activeForm) (not= "" (str/trim activeForm)))
                     (assoc pi-task-active-form-property (str/trim activeForm))
                     (and (string? owner) (not= "" (str/trim owner)))
                     (assoc pi-task-owner-property (str/trim owner)))]
    {:block/title title
     :block/created-at created-at
     :block/updated-at updated-at
     :build/properties properties
     :block/tags #{task-tag-ident}}))

(defn build-task-export-map
  [task-record conversation-page-title]
  {:properties custom-properties
   :classes {:user.class/Chat {}}
   :pages-and-blocks [{:page {:block/title conversation-page-title
                              :build/keep-uuid? true
                              :build/tags [:user.class/Chat]}
                       :blocks [(->task-block task-record)]}]})

(defn ->memory-block
  [{:keys [id text normalizedText scope type confidence projectKey sourceSessionId sourceTurnId createdAt updatedAt deleted]}]
  (let [created-at (long (or createdAt updatedAt (.now js/Date)))
        updated-at (long (or updatedAt created-at))
        properties (cond-> {pi-memory-id-property id
                            pi-memory-normalized-property normalizedText
                            pi-memory-scope-property scope
                            pi-memory-type-property type
                            pi-memory-confidence-property (double (or confidence 0.0))
                            pi-memory-project-property projectKey
                            pi-memory-source-session-property sourceSessionId
                            pi-memory-deleted-property (boolean deleted)}
                     (some? sourceTurnId) (assoc pi-memory-source-turn-property sourceTurnId))]
    {:block/title text
     :block/created-at created-at
     :block/updated-at updated-at
     :build/properties properties
     :block/tags #{:user.class/Memory}}))

(defn build-memory-export-map
  [records]
  {:properties custom-properties
   :classes {:user.class/Memory {}}
   :pages-and-blocks [{:page {:build/journal (current-journal-int-date)
                              :build/keep-uuid? true}
                       :blocks (mapv ->memory-block records)}]})

(defn transact-import!
  [conn export-map]
  (let [txs (sqlite-export/build-import export-map @conn {})]
    (when (seq (:init-tx txs))
      (d/transact! conn (:init-tx txs)))
    (when (seq (:block-props-tx txs))
      (d/transact! conn (:block-props-tx txs)))
    (when (seq (:misc-tx txs))
      (d/transact! conn (:misc-tx txs)))))

(defn sync-conversation!
  [conn {:keys [records journalIntDate] :as payload}]
  (if (seq records)
    (let [ids (->> records (map :id) (filter string?) vec)
          existing-ids (property-id-set conn pi-message-id-property ids)
          new-records (->> records
                           (filter (fn [{:keys [id text]}]
                                     (and (string? id)
                                          (not (contains? existing-ids id))
                                          (string? text)
                                          (not= "" text))))
                           vec)
          add-journal-link? (not (journal-session-linked? conn (:conversationPageTitle payload) journalIntDate))
          export-map (build-conversation-export-map payload new-records add-journal-link?)]
      (transact-import! conn export-map)
      {:createdMessages (count new-records)
       :linkedInJournal add-journal-link?})
    {:createdMessages 0
     :linkedInJournal false}))

(defn valid-memory-record?
  [{:keys [id text scope projectKey]}]
  (and (string? id)
       (boolean (re-matches uuid-pattern id))
       (string? text)
       (not= "" (str/trim text))
       (contains? #{"global" "project"} scope)
       (string? projectKey)
       (not= "" (str/trim projectKey))))

(defn upsert-memory!
  [conn {:keys [memoryRecords]}]
  (let [records (->> memoryRecords
                     (filter valid-memory-record?)
                     (map (fn [record]
                            (assoc record
                                   :deleted (boolean (:deleted record))
                                   :updatedAt (long (or (:updatedAt record) (.now js/Date)))
                                   :createdAt (long (or (:createdAt record) (:updatedAt record) (.now js/Date))))))
                     vec)
        ids (->> records (map :id) vec)
        existing-ids (property-id-set conn pi-memory-id-property ids)
        new-records (->> records
                         (filter (fn [{:keys [id]}]
                                   (not (contains? existing-ids id))))
                         vec)]
    (when (seq new-records)
      (transact-import! conn (build-memory-export-map new-records)))
    {:createdMemories (count new-records)}))

(defn memory-entity-record
  [db entity-id]
  (let [entity (d/touch (d/entity db entity-id))
        memory-id (scalar-value db (get entity pi-memory-id-property))
        text (:block/title entity)
        normalized (scalar-value db (get entity pi-memory-normalized-property))
        scope (scalar-value db (get entity pi-memory-scope-property))
        type (scalar-value db (get entity pi-memory-type-property))
        confidence (double (or (scalar-value db (get entity pi-memory-confidence-property)) 0.0))
        project-key (scalar-value db (get entity pi-memory-project-property))
        source-session (scalar-value db (get entity pi-memory-source-session-property))
        source-turn (scalar-value db (get entity pi-memory-source-turn-property))
        created-at (long (or (:block/created-at entity) 0))
        updated-at (long (or (:block/updated-at entity) created-at 0))
        deleted? (truthy? (scalar-value db (get entity pi-memory-deleted-property)))]
    {:id memory-id
     :text (if (string? text) text "")
     :normalizedText (if (string? normalized) normalized "")
     :scope (if (string? scope) scope "project")
     :type (if (string? type) type "fact")
     :confidence confidence
     :projectKey (if (string? project-key) project-key "")
     :sourceSessionId (if (string? source-session) source-session "")
     :sourceTurnId (when (string? source-turn) source-turn)
     :createdAt created-at
     :updatedAt updated-at
     :deleted deleted?}))

(defn memory-visible?
  [{:keys [scope projectKey]} requested-scope requested-project-key]
  (case requested-scope
    "global" (= scope "global")
    "project" (or (= scope "global")
                   (and (= scope "project") (= projectKey requested-project-key)))
    false))

(defn query-memory!
  [conn {:keys [scope projectKey limit]}]
  (let [requested-scope (if (contains? #{"global" "project"} scope) scope "project")
        requested-project-key (if (string? projectKey) projectKey "")
        requested-limit (max 1 (int (or limit 48)))
        db @conn
        rows (d/q '[:find ?e
                    :in $ ?property
                    :where [?e ?property _]]
                  db
                  pi-memory-id-property)
        records (->> rows
                     (map first)
                     (map #(memory-entity-record db %))
                     (filter (fn [{:keys [deleted text]}]
                               (and (not deleted)
                                    (string? text)
                                    (not= "" (str/trim text)))))
                     (filter #(memory-visible? % requested-scope requested-project-key))
                     (sort-by (juxt (comp - :updatedAt) (comp - :confidence) :id))
                     (take requested-limit)
                     vec)]
    {:memories records}))

(defn find-memory-entity-id
  [db memory-id]
  (let [rows (d/q '[:find ?e ?value
                    :in $ ?property
                    :where [?e ?property ?value]]
                  db
                  pi-memory-id-property)]
    (some (fn [[entity-id value]]
            (when (= memory-id (scalar-value db value))
              entity-id))
          rows)))

(defn forget-memory!
  [conn {:keys [memoryId]}]
  (if (not (string? memoryId))
    {:removed false}
    (let [db @conn
          entity-id (find-memory-entity-id db memoryId)]
      (if (nil? entity-id)
        {:removed false}
        (do
          (d/transact! conn [{:db/id entity-id
                              pi-memory-deleted-property true
                              :block/updated-at (long (.now js/Date))}])
          {:removed true})))))

(defn status-ident-from-value
  [db value]
  (cond
    (qualified-keyword? value) value
    (number? value) (:db/ident (d/entity db value))
    (some? (:db/ident value)) (:db/ident value)
    :else nil))

(defn task-entity-record
  [db entity-id]
  (let [entity (d/touch (d/entity db entity-id))
        task-id (scalar-value db (get entity pi-task-id-property))
        status-ident (status-ident-from-value db (:logseq.property/status entity))
        status (get logseq-status->task-status status-ident default-task-status)
        subject (:block/title entity)
        description (scalar-value db (get entity pi-task-description-property))
        active-form (scalar-value db (get entity pi-task-active-form-property))
        owner (scalar-value db (get entity pi-task-owner-property))
        created-at (long (or (:block/created-at entity) 0))
        updated-at (long (or (:block/updated-at entity) created-at 0))]
    {:id (if (string? task-id) task-id "")
     :subject (if (string? subject) subject "")
     :description (if (string? description) description "")
     :status status
     :activeForm (when (string? active-form) active-form)
     :owner (when (string? owner) owner)
     :createdAt created-at
     :updatedAt updated-at
     :logseqStatus status-ident}))

(defn find-task-entity-id
  [db task-id]
  (let [rows (d/q '[:find ?e ?value
                    :in $ ?property
                    :where [?e ?property ?value]]
                  db
                  pi-task-id-property)]
    (some (fn [[entity-id value]]
            (when (= task-id (scalar-value db value))
              entity-id))
          rows)))

(defn create-task!
  [conn {:keys [subject description status activeForm owner conversationPageTitle]}]
  (let [title (if (string? subject) (str/trim subject) "")
        desc (if (string? description) description "")
        conversation-page-title (if (string? conversationPageTitle) (str/trim conversationPageTitle) "")
        now (.now js/Date)]
    (cond
      (= "" title)
      {:created false
       :error "Task subject is required"}

      (= "" conversation-page-title)
      {:created false
       :error "Conversation page title is required"}

      :else
      (let [task-id (next-task-id @conn)
            task-record {:id task-id
                         :subject title
                         :description desc
                         :status (normalize-task-status status)
                         :activeForm activeForm
                         :owner owner
                         :createdAt now
                         :updatedAt now}]
        (transact-import! conn (build-task-export-map task-record conversation-page-title))
        {:created true
         :id task-id
         :status (:status task-record)}))))

(defn list-tasks!
  [conn _payload]
  (let [db @conn
        rows (d/q '[:find ?e
                    :in $ ?property
                    :where [?e ?property _]]
                  db
                  pi-task-id-property)
        tasks (->> rows
                   (map first)
                   (map #(task-entity-record db %))
                   (sort-by (fn [{:keys [id]}]
                              (or (parse-task-id-number id) 0)))
                   vec)]
    {:tasks tasks}))

(defn get-task!
  [conn {:keys [taskId]}]
  (if-not (string? taskId)
    {:task nil}
    (let [db @conn
          entity-id (find-task-entity-id db taskId)]
      (if (nil? entity-id)
        {:task nil}
        {:task (task-entity-record db entity-id)}))))

(defn update-task!
  [conn {:keys [taskId] :as payload}]
  (if-not (string? taskId)
    {:updated false}
    (let [db @conn
          entity-id (find-task-entity-id db taskId)]
      (if (nil? entity-id)
        {:updated false}
        (let [title (when (string? (:subject payload)) (str/trim (:subject payload)))
              has-status? (contains? payload :status)
              tx (cond-> {:db/id entity-id
                          :block/updated-at (long (.now js/Date))}
                   (and (string? title) (not= "" title))
                   (assoc :block/title title)
                   has-status?
                   (assoc :logseq.property/status (resolve-task-status-ident (:status payload))))]
          (d/transact! conn [tx])
          (let [task (task-entity-record @conn entity-id)]
            {:updated true
             :taskId taskId
             :status (:status task)}))))))

(defn handle-action!
  [conn payload]
  (let [action (or (:action payload) "syncConversation")]
    (case action
      "syncConversation" (sync-conversation! conn payload)
      "upsertMemory" (upsert-memory! conn payload)
      "queryMemory" (query-memory! conn payload)
      "forgetMemory" (forget-memory! conn payload)
      "createTask" (create-task! conn payload)
      "listTasks" (list-tasks! conn payload)
      "getTask" (get-task! conn payload)
      "updateTask" (update-task! conn payload)
      (throw (js/Error. (str "Unsupported action: " action))))))

(defn resolve-db-path
  [open-db-args]
  (case (count open-db-args)
    1 (first open-db-args)
    2 (let [[db-dir graph-name] open-db-args]
        (second (sqlite/get-db-full-path db-dir graph-name)))
    nil))

(defn ensure-db-parent-dir!
  [open-db-args]
  (when-let [db-path (resolve-db-path open-db-args)]
    (let [parent-dir (.dirname path db-path)]
      (when (and (string? parent-dir) (not= "" (str/trim parent-dir)))
        (.mkdirSync fs parent-dir #js {:recursive true}))))
  open-db-args)

(defn execute!
  [{:keys [graphPath] :as payload}]
  (let [open-db-args (sqlite-cli/->open-db-args graphPath)
        _ (ensure-db-parent-dir! open-db-args)
        conn (apply sqlite-cli/open-db! open-db-args)]
    (ensure-built-ins! conn)
    (handle-action! conn payload)))
