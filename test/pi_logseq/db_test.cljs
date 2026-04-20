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
