(ns pi-logseq.db-test
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [logseq.db.frontend.schema :as db-schema]
            [pi-logseq.db :as db]))

(defn new-test-conn
  []
  (let [conn (d/create-conn db-schema/schema)]
    (db/ensure-built-ins! conn)
    conn))

(defn fetch-task-row
  [conn task-id]
  (let [db @conn
        entity-id (db/find-task-entity-id db task-id)
        entity (when entity-id (d/touch (d/entity db entity-id)))
        status-ident (:db/ident (:logseq.property/status entity))
        tag-ident (:db/ident (first (:block/tags entity)))
        description (db/scalar-value db (get entity :user.property/pi-task-description))]
    [(:block/title entity) description status-ident tag-ident]))

(defn message-id-set
  [conn]
  (let [db @conn]
    (->> (d/q '[:find ?value
                :in $ ?property
                :where [?b ?property ?value]]
              db
              db/pi-message-id-property)
         (map first)
         (map #(db/scalar-value db %))
         (filter string?)
         set)))

(defn journal-link-count
  [conn journal-day conversation-page-title]
  (d/q '[:find (count ?b) .
         :in $ ?journal-day ?link-title
         :where
         [?p :block/journal-day ?journal-day]
         [?b :block/page ?p]
         [?b :block/title ?link-title]]
       @conn
       journal-day
       (str "[[" conversation-page-title "]]")))

(deftest create-task-stores-task-tag-and-logseq-status
  (let [conn (new-test-conn)
        created (db/handle-action! conn {:action "createTask"
                                         :subject "Write regression tests"
                                         :description "Cover task sync behavior."})
        [title description status-ident tag-ident] (fetch-task-row conn "1")]
    (is (= true (:created created)))
    (is (= "1" (:id created)))
    (is (= "todo" (:status created)))
    (is (= "Write regression tests" title))
    (is (= "Cover task sync behavior." description))
    (is (= :logseq.property/status.todo status-ident))
    (is (= :logseq.class/Task tag-ident))))

(deftest list-tasks-returns-logseq-statuses
  (let [conn (new-test-conn)]
    (db/handle-action! conn {:action "createTask"
                             :subject "Task A"
                             :description "A"
                             :status "todo"})
    (db/handle-action! conn {:action "createTask"
                             :subject "Task B"
                             :description "B"
                             :status "done"})
    (let [listed (db/handle-action! conn {:action "listTasks"})
          tasks (:tasks listed)]
      (is (= 2 (count tasks)))
      (is (= ["todo" "done"]
             (mapv :status tasks))))))

(deftest update-task-translates-status-to-logseq-doing
  (let [conn (new-test-conn)]
    (db/handle-action! conn {:action "createTask"
                             :subject "Initial title"
                             :description "Initial description"})
    (let [updated (db/handle-action! conn {:action "updateTask"
                                           :taskId "1"
                                           :status "doing"
                                           :subject "Updated title"})
          [title description status-ident _] (fetch-task-row conn "1")]
      (is (= true (:updated updated)))
      (is (= "doing" (:status updated)))
      (is (= "Updated title" title))
      (is (= "Initial description" description))
      (is (= :logseq.property/status.doing status-ident)))))

(deftest create-task-invalid-status-falls-back-to-default
  (let [conn (new-test-conn)
        created (db/handle-action! conn {:action "createTask"
                                         :subject "Work item"
                                         :status "working"})
        [_ _ status-ident _] (fetch-task-row conn "1")]
    (is (= true (:created created)))
    (is (= "todo" (:status created)))
    (is (= :logseq.property/status.todo status-ident))))

(deftest create-task-requires-non-empty-subject
  (let [conn (new-test-conn)
        created (db/handle-action! conn {:action "createTask"
                                         :subject "   "
                                         :description "ignored"})]
    (is (= false (:created created)))
    (is (= "Task subject is required" (:error created)))
    (is (empty? (:tasks (db/handle-action! conn {:action "listTasks"}))))))

(deftest sync-conversation-dedupes-messages-and-links-journal-once
  (let [conn (new-test-conn)
        payload {:action "syncConversation"
                 :conversationPageTitle "Pi Conversation 2026-04-20 13:33"
                 :journalIntDate 20260420
                 :records [{:id "m-1" :role "user" :text "Hello" :timestamp 1000}
                           {:id "m-2" :role "assistant" :text "Hi" :timestamp 2000}]}
        first-sync (db/handle-action! conn payload)
        second-sync (db/handle-action! conn (assoc payload
                                                   :records [{:id "m-2" :role "assistant" :text "Hi" :timestamp 2000}
                                                             {:id "m-3" :role "assistant" :text "New update" :timestamp 3000}]))]
    (is (= {:createdMessages 2 :linkedInJournal true} first-sync))
    (is (= {:createdMessages 1 :linkedInJournal false} second-sync))
    (is (= #{"m-1" "m-2" "m-3"} (message-id-set conn)))
    (is (= 1 (journal-link-count conn 20260420 "Pi Conversation 2026-04-20 13:33")))))

(deftest memory-lifecycle-upsert-query-forget
  (let [conn (new-test-conn)
        records [{:id "11111111-1111-4111-8111-111111111111"
                  :text "Always use pnpm for installs."
                  :normalizedText "always use pnpm for installs"
                  :scope "project"
                  :type "constraint"
                  :confidence 0.8
                  :projectKey "proj-a"
                  :sourceSessionId "s1"
                  :sourceTurnId "t1"
                  :createdAt 1000
                  :updatedAt 1000}
                 {:id "22222222-2222-4222-8222-222222222222"
                  :text "Never force push."
                  :normalizedText "never force push"
                  :scope "global"
                  :type "constraint"
                  :confidence 0.9
                  :projectKey "proj-a"
                  :sourceSessionId "s1"
                  :createdAt 1100
                  :updatedAt 1100}
                 {:id "33333333-3333-4333-8333-333333333333"
                  :text "Use sqlite for this project."
                  :normalizedText "use sqlite for this project"
                  :scope "project"
                  :type "fact"
                  :confidence 0.7
                  :projectKey "proj-b"
                  :sourceSessionId "s2"
                  :createdAt 1200
                  :updatedAt 1200}
                 {:id "not-a-uuid"
                  :text "invalid"
                  :scope "project"
                  :projectKey "proj-a"}]
        upserted (db/handle-action! conn {:action "upsertMemory"
                                          :memoryRecords records})
        project-view (db/handle-action! conn {:action "queryMemory"
                                              :scope "project"
                                              :projectKey "proj-a"
                                              :limit 10})
        global-view (db/handle-action! conn {:action "queryMemory"
                                             :scope "global"
                                             :projectKey "proj-a"
                                             :limit 10})
        forgot (db/handle-action! conn {:action "forgetMemory"
                                        :memoryId "11111111-1111-4111-8111-111111111111"})
        project-after-forget (db/handle-action! conn {:action "queryMemory"
                                                      :scope "project"
                                                      :projectKey "proj-a"
                                                      :limit 10})]
    (is (= 3 (:createdMemories upserted)))
    (is (= #{"11111111-1111-4111-8111-111111111111"
             "22222222-2222-4222-8222-222222222222"}
           (set (map :id (:memories project-view)))))
    (is (= ["22222222-2222-4222-8222-222222222222"]
           (mapv :id (:memories global-view))))
    (is (= true (:removed forgot)))
    (is (= ["22222222-2222-4222-8222-222222222222"]
           (mapv :id (:memories project-after-forget))))))

(deftest update-task-invalid-or-missing-id-returns-false
  (let [conn (new-test-conn)]
    (is (= {:updated false}
           (db/handle-action! conn {:action "updateTask" :taskId "99" :status "done"})))
    (is (= {:updated false}
           (db/handle-action! conn {:action "updateTask" :taskId nil :status "done"})))))

(deftest update-task-accepts-hyphenated-status-values
  (let [conn (new-test-conn)]
    (db/handle-action! conn {:action "createTask"
                             :subject "Review PR"})
    (let [updated (db/handle-action! conn {:action "updateTask"
                                           :taskId "1"
                                           :status "in-review"})
          [_ _ status-ident _] (fetch-task-row conn "1")]
      (is (= true (:updated updated)))
      (is (= "in_review" (:status updated)))
      (is (= :logseq.property/status.in-review status-ident)))))
