(ns pi-logseq.extension
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [pi-logseq.lib :as lib]
            [pi-logseq.db :as logseq-db]
            ["@mariozechner/pi-ai" :refer [complete]]))

(def default-memory-scope "project")
(def default-memory-max-items 12)
(def default-memory-max-chars 6000)
(def default-memory-dedup-threshold 0.9)
(def default-memory-verify-threshold 0.7)
(def default-memory-verify-max-per-turn 2)
(def default-memory-verify-max-per-hour 30)
(def one-hour-ms (* 60 60 1000))

(defn parse-number-flag
  [value fallback]
  (if (string? value)
    (let [parsed (js/Number value)]
      (if (js/isFinite parsed)
        parsed
        fallback))
    fallback))

(defn parse-boolean-flag
  [value fallback]
  (cond
    (boolean? value) value
    (string? value) (let [normalized (-> value str/trim str/lower-case)]
                      (cond
                        (= normalized "true") true
                        (= normalized "false") false
                        :else fallback))
    :else fallback))

(defn get-memory-config
  [^js pi]
  (let [scope-flag (.getFlag pi "logseq-memory-scope")
        verify-model-flag (.getFlag pi "logseq-memory-verify-model")]
    {:autoEnabled (parse-boolean-flag (.getFlag pi "logseq-memory-auto") true)
     :scope (lib/parse-scope (when (string? scope-flag) scope-flag))
     :maxItems (-> (.getFlag pi "logseq-memory-max-items")
                   (parse-number-flag default-memory-max-items)
                   js/Math.floor
                   (js/Math.max 1))
     :maxChars (-> (.getFlag pi "logseq-memory-max-chars")
                   (parse-number-flag default-memory-max-chars)
                   js/Math.floor
                   (js/Math.max 250))
     :dedupThreshold (-> (.getFlag pi "logseq-memory-dedup-threshold")
                         (parse-number-flag default-memory-dedup-threshold)
                         (js/Math.max 0.5)
                         (js/Math.min 0.99))
     :verifyModel (let [trimmed (when (string? verify-model-flag) (str/trim verify-model-flag))]
                    (when (and (string? trimmed) (not= "" trimmed))
                      trimmed))
     :verifyThreshold (-> (.getFlag pi "logseq-memory-verify-threshold")
                          (parse-number-flag default-memory-verify-threshold)
                          (js/Math.max 0.35)
                          (js/Math.min 0.95))
     :verifyMaxPerTurn (-> (.getFlag pi "logseq-memory-verify-max-per-turn")
                           (parse-number-flag default-memory-verify-max-per-turn)
                           js/Math.floor
                           (js/Math.max 0))
     :verifyMaxPerHour (-> (.getFlag pi "logseq-memory-verify-max-per-hour")
                           (parse-number-flag default-memory-verify-max-per-hour)
                           js/Math.floor
                           (js/Math.max 0))}))

(defn get-graph-path
  [^js pi]
  (let [graph-path-flag (.getFlag pi "logseq-graph")]
    (when (string? graph-path-flag)
      (let [trimmed (str/trim graph-path-flag)]
        (when (not= "" trimmed)
          trimmed)))))

(defn collect-sync-records
  [^js ctx]
  (let [branch (.getBranch ^js (gobj/get ctx "sessionManager"))
        records (->> (array-seq branch)
                     (map lib/extract-text-from-session-entry)
                     (filter some?)
                     (sort-by :timestamp))
        deduped (reduce (fn [acc record]
                          (if (contains? acc (:id record))
                            acc
                            (assoc acc (:id record) record)))
                        {}
                        records)]
    (vals deduped)))

(defn build-sync-payload
  [ctx graph-path]
  (let [^js session-manager (gobj/get ctx "sessionManager")
        header (.getHeader session-manager)
        records (collect-sync-records ctx)]
    {:action "syncConversation"
     :graphPath graph-path
     :conversationPageTitle (lib/build-conversation-page-title records (gobj/get header "timestamp"))
     :sessionId (.getSessionId session-manager)
     :sessionFile (.getSessionFile session-manager)
     :journalIntDate (lib/compute-journal-int-date)
     :records records}))

(defn merge-memory-records
  [existing incoming]
  (let [keep-non-deleted (fn [records]
                           (reduce (fn [acc record]
                                     (if (:deleted record)
                                       acc
                                       (assoc acc (:id record) record)))
                                   {}
                                   records))
        merged (merge (keep-non-deleted existing)
                      (keep-non-deleted incoming))]
    (vals merged)))

(defn pick-candidate-id
  [records input]
  (let [trimmed (str/trim input)]
    (when (not= "" trimmed)
      (let [by-id (first (filter #(= (:id %) trimmed) records))]
        (if by-id
          (:id by-id)
          (let [lower (str/lower-case trimmed)
                by-text (first (filter #(str/includes? (str/lower-case (:text %)) lower) records))]
            (:id by-text)))))))

(defn extract-json-object
  [text]
  (let [direct (str/trim text)]
    (cond
      (and (str/starts-with? direct "{") (str/ends-with? direct "}")) direct
      :else (second (re-find #"(\{[\s\S]*\})" text)))))

(defn notify!
  [ctx message level]
  (if (gobj/get ctx "hasUI")
    (.notify ^js (gobj/get ctx "ui") message level)
    (.write (.-stderr js/process) (str message "\n"))))

(defn write-out!
  [ctx text]
  (if (gobj/get ctx "hasUI")
    (.notify ^js (gobj/get ctx "ui") text "info")
    (.write (.-stdout js/process) (str text "\n"))))

(defn enqueue-task
  [queue-atom task]
  (let [run (.then @queue-atom task task)]
    (reset! queue-atom (.then run (fn [_] nil) (fn [_] nil)))
    run))

(defn run-action
  [queue-atom payload]
  (enqueue-task
   queue-atom
   (fn []
     (try
       (js/Promise.resolve (assoc (logseq-db/execute! payload) :ok true))
       (catch :default err
         (js/Promise.reject err))))))

(defn should-verify?
  [candidate config]
  (let [confidence (:confidence candidate)]
    (cond
      (>= confidence 0.82) false
      (< confidence 0.35) false
      (and (>= confidence (:verifyThreshold config))
           (lib/has-strong-memory-signal (:text candidate))) false
      :else (boolean (:verifyModel config)))))

(defn resolve-verifier-model
  [ctx verify-model]
  (-> (.getAvailable ^js (gobj/get ctx "modelRegistry"))
      (.then (fn [available]
               (first (filter (fn [model]
                                (= (str (gobj/get model "provider") "/" (gobj/get model "id"))
                                   verify-model))
                              (array-seq available)))))))

(defn parse-verifier-response
  [candidate config fallback response]
  (let [response-text (->> (array-seq (gobj/get response "content"))
                           (filter #(= "text" (gobj/get % "type")))
                           (map #(gobj/get % "text"))
                           (filter string?)
                           (str/join "\n"))
        json-chunk (extract-json-object response-text)]
    (if-not json-chunk
      (fallback)
      (try
        (let [parsed (js/JSON.parse json-chunk)
              keep? (gobj/get parsed "keep")
              rewrite (gobj/get parsed "rewrite")
              parsed-confidence (gobj/get parsed "confidence")]
          (if-not (true? keep?)
            (js/Promise.resolve nil)
            (let [rewritten (if (and (string? rewrite) (not= "" (str/trim rewrite)))
                              (str/trim rewrite)
                              (:text candidate))
                  verifier-confidence (if (and (number? parsed-confidence) (js/isFinite parsed-confidence))
                                        (-> parsed-confidence (js/Math.max 0) (js/Math.min 1))
                                        (:confidence candidate))]
              (js/Promise.resolve (assoc candidate
                                         :text rewritten
                                         :confidence (max (:confidence candidate) verifier-confidence))))))
        (catch :default _
          (fallback))))))

(defn verify-candidate
  [ctx candidate config verify-calls-this-turn verify-window-started-at verify-calls-in-window]
  (let [fallback (fn []
                   (js/Promise.resolve
                    (if (>= (:confidence candidate) (:verifyThreshold config))
                      candidate
                      nil)))]
    (if (or (nil? (:verifyModel config))
            (not (should-verify? candidate config)))
      (fallback)
      (let [now (.now js/Date)]
        (when (> (- now @verify-window-started-at) one-hour-ms)
          (reset! verify-window-started-at now)
          (reset! verify-calls-in-window 0))

        (if (or (>= @verify-calls-this-turn (:verifyMaxPerTurn config))
                (>= @verify-calls-in-window (:verifyMaxPerHour config)))
          (fallback)
          (-> (resolve-verifier-model ctx (:verifyModel config))
              (.then
               (fn [model]
                 (if-not model
                   (fallback)
                   (-> (.getApiKeyAndHeaders ^js (gobj/get ctx "modelRegistry") model)
                       (.then
                        (fn [auth]
                          (if (or (not (gobj/get auth "ok"))
                                  (not (gobj/get auth "apiKey")))
                            (fallback)
                            (do
                              (swap! verify-calls-this-turn inc)
                              (swap! verify-calls-in-window inc)
                              (let [prompt (str
                                            "You validate if a sentence is durable long-term memory for a coding assistant.\n"
                                            "Keep only stable preferences, constraints, conventions, or workflow rules.\n"
                                            "Reject transient task status, temporary logs, timestamps, or one-off updates.\n\n"
                                            "Return strict JSON only with keys: keep(boolean), rewrite(string), confidence(number 0..1).\n\n"
                                            "Sentence: "
                                            (:text candidate))]
                                (-> (complete
                                     model
                                     #js {:messages #js [#js {:role "user"
                                                              :content #js [#js {:type "text" :text prompt}]
                                                              :timestamp (.now js/Date)}]}
                                     #js {:apiKey (gobj/get auth "apiKey")
                                          :headers (gobj/get auth "headers")})
                                    (.then (fn [response]
                                             (parse-verifier-response candidate config fallback response)))
                                    (.catch (fn [_] (fallback)))))))))))))
              (.catch (fn [_] (fallback)))))))))

(defn process-candidates
  [ctx candidates config verify-calls-this-turn verify-window-started-at verify-calls-in-window accepted]
  (if (empty? candidates)
    (js/Promise.resolve accepted)
    (-> (verify-candidate ctx
                          (first candidates)
                          config
                          verify-calls-this-turn
                          verify-window-started-at
                          verify-calls-in-window)
        (.then (fn [verified]
                 (process-candidates ctx
                                     (rest candidates)
                                     config
                                     verify-calls-this-turn
                                     verify-window-started-at
                                     verify-calls-in-window
                                     (cond-> accepted verified (conj verified))))))))

(defn ^:export logseq-db-sync-extension
  [^js pi]
  (.registerFlag pi "logseq-graph"
                 (clj->js {:description "Logseq DB graph name/path used by logseq-db-sync extension"
                           :type "string"}))

  (.registerFlag pi "logseq-memory-auto"
                 (clj->js {:description "Enable automatic memory capture to Logseq"
                           :type "boolean"
                           :default true}))

  (.registerFlag pi "logseq-memory-scope"
                 (clj->js {:description "Memory scope (global|project)"
                           :type "string"
                           :default default-memory-scope}))

  (.registerFlag pi "logseq-memory-max-items"
                 (clj->js {:description "Maximum memory items injected per turn"
                           :type "string"
                           :default (str default-memory-max-items)}))

  (.registerFlag pi "logseq-memory-max-chars"
                 (clj->js {:description "Maximum memory characters injected per turn"
                           :type "string"
                           :default (str default-memory-max-chars)}))

  (.registerFlag pi "logseq-memory-dedup-threshold"
                 (clj->js {:description "Near-duplicate threshold for memory insertion"
                           :type "string"
                           :default (str default-memory-dedup-threshold)}))

  (.registerFlag pi "logseq-memory-verify-model"
                 (clj->js {:description "Optional verifier model in provider/model format"
                           :type "string"
                           :default ""}))

  (.registerFlag pi "logseq-memory-verify-threshold"
                 (clj->js {:description "Minimum confidence to accept candidate without verifier"
                           :type "string"
                           :default (str default-memory-verify-threshold)}))

  (.registerFlag pi "logseq-memory-verify-max-per-turn"
                 (clj->js {:description "Maximum verifier calls per turn"
                           :type "string"
                           :default (str default-memory-verify-max-per-turn)}))

  (.registerFlag pi "logseq-memory-verify-max-per-hour"
                 (clj->js {:description "Maximum verifier calls per hour"
                           :type "string"
                           :default (str default-memory-verify-max-per-hour)}))

  (let [script-queue (atom (js/Promise.resolve nil))
        memory-cache (atom [])
        memory-cache-loaded (atom false)
        auto-capture-enabled (atom (parse-boolean-flag (.getFlag pi "logseq-memory-auto") true))
        verify-window-started-at (atom (.now js/Date))
        verify-calls-in-window (atom 0)
        sync-conversation (fn [ctx reason]
                            (let [graph-path (get-graph-path pi)]
                              (if-not graph-path
                                (do
                                  (when (and (= reason "manual") (gobj/get ctx "hasUI"))
                                    (.notify ^js (gobj/get ctx "ui") "Set --logseq-graph to enable Logseq DB sync" "warning"))
                                  (js/Promise.resolve nil))
                                (-> (run-action script-queue (build-sync-payload ctx graph-path))
                                    (.then (fn [parsed] parsed))))))
        load-memory (fn [ctx]
                      (let [graph-path (get-graph-path pi)]
                        (if-not graph-path
                          (do
                            (reset! memory-cache [])
                            (reset! memory-cache-loaded true)
                            (js/Promise.resolve []))
                          (let [config (get-memory-config pi)
                                payload {:action "queryMemory"
                                         :graphPath graph-path
                                         :scope (:scope config)
                                         :projectKey (lib/derive-project-key (gobj/get ctx "cwd"))
                                         :limit (max (* (:maxItems config) 4) 24)}]
                            (-> (run-action script-queue payload)
                                (.then
                                 (fn [parsed]
                                   (let [memories (->> (:memories parsed)
                                                       (filter #(not (:deleted %)))
                                                       vec)]
                                     (reset! memory-cache memories)
                                     (reset! memory-cache-loaded true)
                                     memories))))))))
        upsert-memory (fn [ctx records]
                        (if (empty? records)
                          (js/Promise.resolve 0)
                          (let [graph-path (get-graph-path pi)]
                            (if-not graph-path
                              (js/Promise.resolve 0)
                              (let [payload {:action "upsertMemory"
                                             :graphPath graph-path
                                             :memoryRecords records}]
                                (-> (run-action script-queue payload)
                                    (.then
                                     (fn [parsed]
                                       (reset! memory-cache (merge-memory-records @memory-cache records))
                                       (reset! memory-cache-loaded true)
                                       (or (:createdMemories parsed) 0)))))))))
        forget-memory (fn [ctx memory-id]
                        (let [graph-path (get-graph-path pi)]
                          (if-not graph-path
                            (js/Promise.resolve false)
                            (let [payload {:action "forgetMemory"
                                           :graphPath graph-path
                                           :memoryId memory-id}]
                              (-> (run-action script-queue payload)
                                  (.then
                                   (fn [parsed]
                                     (when (:removed parsed)
                                       (reset! memory-cache (vec (remove #(= (:id %) memory-id) @memory-cache))))
                                     (= true (:removed parsed)))))))))
        auto-capture-memory (fn [messages ctx]
                              (if (or (not @auto-capture-enabled)
                                      (nil? (get-graph-path pi)))
                                (js/Promise.resolve nil)
                                (let [config (get-memory-config pi)
                                      extracted (lib/extract-auto-memory-candidates messages {:scope (:scope config)})]
                                  (if (empty? extracted)
                                    (js/Promise.resolve nil)
                                    (letfn [(save-accepted [accepted]
                                              (if (empty? accepted)
                                                (js/Promise.resolve nil)
                                                (let [now (.now js/Date)
                                                      project-key (lib/derive-project-key (gobj/get ctx "cwd"))
                                                      ^js session-manager (gobj/get ctx "sessionManager")
                                                      source-session-id (.getSessionId session-manager)
                                                      source-turn-id (or (.getLeafId session-manager) nil)
                                                      records (mapv (fn [candidate]
                                                                      (lib/to-memory-record
                                                                       {:candidate candidate
                                                                        :projectKey project-key
                                                                        :sourceSessionId source-session-id
                                                                        :sourceTurnId source-turn-id
                                                                        :timestamp now}))
                                                                    accepted)]
                                                  (.then
                                                   (upsert-memory ctx records)
                                                   (fn [created]
                                                     (when (and (> created 0) (gobj/get ctx "hasUI"))
                                                       (.notify ^js (gobj/get ctx "ui")
                                                                (str "Logseq memory captured: "
                                                                     created
                                                                     " item"
                                                                     (if (= created 1) "" "s"))
                                                                "info")))))))
                                            (run-capture []
                                              (let [deduped (lib/dedupe-memory-candidates
                                                             extracted
                                                             @memory-cache
                                                             (:dedupThreshold config))]
                                                (if (empty? deduped)
                                                  (js/Promise.resolve nil)
                                                  (let [verify-calls-this-turn (atom 0)]
                                                    (.then
                                                     (process-candidates ctx
                                                                         deduped
                                                                         config
                                                                         verify-calls-this-turn
                                                                         verify-window-started-at
                                                                         verify-calls-in-window
                                                                         [])
                                                     save-accepted)))))]
                                      (if @memory-cache-loaded
                                        (run-capture)
                                        (.then (load-memory ctx)
                                               (fn [_] (run-capture)))))))))]

    (.on pi "session_start"
         (fn [_event ctx]
           (-> (sync-conversation ctx "session_start")
               (.then (fn [_] (load-memory ctx)))
               (.catch (fn [error]
                         (notify! ctx (if (instance? js/Error error)
                                        (.-message error)
                                        (str error))
                                  "error"))))))

    (.on pi "context"
         (fn [event ctx]
           (if (nil? (get-graph-path pi))
             (js/Promise.resolve nil)
             (let [apply-memory (fn []
                                  (let [config (get-memory-config pi)
                                        block (lib/build-memory-context-block @memory-cache
                                                                              (:maxItems config)
                                                                              (:maxChars config))]
                                    (if (= "" block)
                                      nil
                                      (let [messages (array-seq (gobj/get event "messages"))
                                            filtered (->> messages
                                                          (filter (fn [message]
                                                                    (if (= "custom" (gobj/get message "role"))
                                                                      (not= "logseq-memory-context" (gobj/get message "customType"))
                                                                      true)))
                                                          vec)
                                            memory-message {:role "custom"
                                                            :customType "logseq-memory-context"
                                                            :content block
                                                            :display false
                                                            :details {:count (count @memory-cache)}
                                                            :timestamp (.now js/Date)}]
                                        #js {:messages (clj->js (conj filtered memory-message))}))))]
               (if @memory-cache-loaded
                 (js/Promise.resolve (apply-memory))
                 (-> (load-memory ctx)
                     (.then (fn [_] (apply-memory)))))))))

    (.on pi "agent_end"
         (fn [event ctx]
           (-> (sync-conversation ctx "agent_end")
               (.then (fn [_] (auto-capture-memory (gobj/get event "messages") ctx)))
               (.catch (fn [error]
                         (notify! ctx (if (instance? js/Error error)
                                        (.-message error)
                                        (str error))
                                  "error"))))))

    (.registerCommand pi "logseq-sync"
                      (clj->js {:description "Sync current session and memory to Logseq DB graph"
                                :handler (fn [_args ctx]
                                           (-> (sync-conversation ctx "manual")
                                               (.then (fn [parsed]
                                                        (when parsed
                                                          (let [message-count (or (:createdMessages parsed) 0)
                                                                linked-suffix (if (:linkedInJournal parsed)
                                                                                ", linked in journal"
                                                                                "")]
                                                            (when (gobj/get ctx "hasUI")
                                                              (.notify ^js (gobj/get ctx "ui")
                                                                       (str "Logseq sync complete: " message-count " new records" linked-suffix)
                                                                       "info"))))))
                                               (.catch (fn [error]
                                                         (notify! ctx
                                                                  (if (instance? js/Error error)
                                                                    (.-message error)
                                                                    (str error))
                                                                  "error")))))}))

    (.registerCommand pi "logseq-sync-status"
                      (clj->js {:description "Show logseq-db-sync extension config"
                                :handler (fn [_args ctx]
                                           (let [graph (get-graph-path pi)
                                                 config (get-memory-config pi)
                                                 lines [(str "logseq-graph: " (or graph "(not set)"))
                                                        "runtime: embedded CLJS"
                                                        (str "memory auto: " @auto-capture-enabled)
                                                        (str "memory scope: " (:scope config))
                                                        (str "memory cache size: " (count @memory-cache))
                                                        (str "memory verify model: " (or (:verifyModel config) "(disabled)"))
                                                        (str "memory verify calls/hour: " @verify-calls-in-window "/" (:verifyMaxPerHour config))]]
                                             (write-out! ctx (str/join "\n" lines))))}))

    (.registerCommand pi "memory"
                      (clj->js {:description "Show memory currently loaded from Logseq"
                                :handler (fn [_args ctx]
                                           (let [print-memory (fn []
                                                                (if (empty? @memory-cache)
                                                                  (when (gobj/get ctx "hasUI")
                                                                    (.notify ^js (gobj/get ctx "ui") "No Logseq memory loaded" "info"))
                                                                  (let [preview (->> @memory-cache
                                                                                     (take 12)
                                                                                     (map #(str (:id %) " [" (:scope %) "] " (:text %))))
                                                                        text (str/join "\n" (concat [(str "Loaded memory (" (count @memory-cache) "):")] preview))]
                                                                    (write-out! ctx text))))]
                                             (if @memory-cache-loaded
                                               (js/Promise.resolve (print-memory))
                                               (-> (load-memory ctx)
                                                   (.then (fn [_] (print-memory)))))))}))

    (.registerCommand pi "memory-sync"
                      (clj->js {:description "Reload memory cache from Logseq"
                                :handler (fn [_args ctx]
                                           (-> (load-memory ctx)
                                               (.then (fn [loaded]
                                                        (when (gobj/get ctx "hasUI")
                                                          (.notify ^js (gobj/get ctx "ui")
                                                                   (str "Logseq memory cache refreshed (" (count loaded) " items)")
                                                                   "info"))))))}))

    (.registerCommand pi "memory-on"
                      (clj->js {:description "Enable automatic memory capture"
                                :handler (fn [_args ctx]
                                           (reset! auto-capture-enabled true)
                                           (when (gobj/get ctx "hasUI")
                                             (.notify ^js (gobj/get ctx "ui") "Automatic Logseq memory capture enabled" "info")))}))

    (.registerCommand pi "memory-off"
                      (clj->js {:description "Disable automatic memory capture"
                                :handler (fn [_args ctx]
                                           (reset! auto-capture-enabled false)
                                           (when (gobj/get ctx "hasUI")
                                             (.notify ^js (gobj/get ctx "ui") "Automatic Logseq memory capture disabled" "info")))}))

    (.registerCommand pi "remember"
                      (clj->js {:description "Persist explicit memory in Logseq"
                                :handler (fn [args ctx]
                                           (let [text (str/trim args)]
                                             (if (= "" text)
                                               (when (gobj/get ctx "hasUI")
                                                 (.notify ^js (gobj/get ctx "ui") "Usage: /remember <text>" "warning"))
                                               (let [config (get-memory-config pi)
                                                     now (.now js/Date)
                                                     candidate {:text text
                                                                :normalizedText (lib/normalize-for-dedup text)
                                                                :scope (:scope config)
                                                                :type "preference"
                                                                :confidence 0.99}
                                                     session-manager (gobj/get ctx "sessionManager")
                                                     record (lib/to-memory-record
                                                             {:candidate candidate
                                                              :projectKey (lib/derive-project-key (gobj/get ctx "cwd"))
                                                              :sourceSessionId (.getSessionId session-manager)
                                                              :sourceTurnId (or (.getLeafId session-manager) nil)
                                                              :timestamp now})]
                                                 (-> (upsert-memory ctx [record])
                                                     (.then
                                                      (fn [created]
                                                        (when (gobj/get ctx "hasUI")
                                                          (.notify ^js (gobj/get ctx "ui")
                                                                   (str "Memory saved" (if (> created 0) "" " (already existed)"))
                                                                   "info")))))))))}))

    (.registerCommand pi "forget"
                      (clj->js {:description "Forget memory by id or text snippet"
                                :handler (fn [args ctx]
                                           (let [handle-forget (fn []
                                                                 (let [memory-id (pick-candidate-id @memory-cache args)]
                                                                   (if-not memory-id
                                                                     (when (gobj/get ctx "hasUI")
                                                                       (.notify ^js (gobj/get ctx "ui") "No matching memory found" "warning"))
                                                                     (-> (forget-memory ctx memory-id)
                                                                         (.then
                                                                          (fn [removed]
                                                                            (when (gobj/get ctx "hasUI")
                                                                              (.notify ^js (gobj/get ctx "ui")
                                                                                       (if removed
                                                                                         (str "Forgot memory " memory-id)
                                                                                         "Memory not found in graph")
                                                                                       (if removed "info" "warning")))))))))]
                                             (if @memory-cache-loaded
                                               (js/Promise.resolve (handle-forget))
                                               (-> (load-memory ctx)
                                                   (.then (fn [_] (handle-forget)))))))}))))
