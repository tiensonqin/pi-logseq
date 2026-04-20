(ns pi-logseq.lib
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [goog.object :as gobj]
            ["uuid" :as uuid]))

(def min-sentence-length 12)
(def max-sentence-length 220)

(def strong-memory-patterns
  [#"\bremember\b"
   #"\bfrom now on\b"
   #"\balways\b"
   #"\bnever\b"
   #"\bmust\b"
   #"\bdo not\b"
   #"\bdon't\b"])

(def memory-patterns
  (concat strong-memory-patterns
          [#"\bprefer\b"
           #"\bplease use\b"
           #"\bwe use\b"
           #"\bwe do not\b"
           #"\bwe don't\b"
           #"\bconvention\b"
           #"\bworkflow\b"
           #"\bstyle\b"
           #"\bpolicy\b"]))

(def ephemeral-patterns
  [#"\b(today|now|currently|just now)\b"
   #"\b(done|finished|passed|failed|running|started)\b"
   #"^\$"
   #"\bexit code\b"
   #"\berror:\b"])

(def memory-id-namespace "f3db30bf-4ca3-4e8a-9be9-98a335fdf2e8")

(defn is-valid-date?
  [value]
  (if-not (string? value)
    false
    (not (js/isNaN (.getTime (js/Date. value))))))

(defn format-iso-date
  [^js date]
  (let [year (.getFullYear date)
        month (-> (inc (.getMonth date)) str (.padStart 2 "0"))
        day (-> (.getDate date) str (.padStart 2 "0"))]
    (str year "-" month "-" day)))

(defn format-hour-minute
  [^js date]
  (let [hours (-> (.getHours date) str (.padStart 2 "0"))
        minutes (-> (.getMinutes date) str (.padStart 2 "0"))]
    (str hours ":" minutes)))

(defn summarize-conversation
  [records]
  (let [preferred (or (first (filter #(and (= "user" (:role %)) (pos? (count (str/trim (:text %))))) records))
                      (first records))
        raw (some-> (:text preferred) str/trim)
        compact (-> (or raw "") (str/replace #"\s+" " ") str/trim)
        sentence (or (some-> (first (str/split compact #"[.!?]")) str/trim)
                     compact)
        cleaned (-> sentence (str/replace #"[\[\]`*_~]" "") str/trim)]
    (when (not= "" cleaned)
      (if (<= (count cleaned) 72)
        cleaned
        (str (str/trimr (subs cleaned 0 69)) "...")))))

(defn build-conversation-page-title
  [records session-timestamp]
  (let [date (if (is-valid-date? session-timestamp)
               (js/Date. session-timestamp)
               (js/Date.))
        summary (summarize-conversation records)
        base (str "Pi Conversation "
                  (format-iso-date date)
                  " "
                  (format-hour-minute date))]
    (if summary
      (str base " - " summary)
      base)))

(defn compute-journal-int-date
  ([]
   (compute-journal-int-date (js/Date.)))
  ([^js date]
   (js/parseInt (str (.getFullYear date)
                     (-> (inc (.getMonth date)) str (.padStart 2 "0"))
                     (-> (.getDate date) str (.padStart 2 "0")))
                10)))

(defn extract-text-from-content
  [content]
  (cond
    (string? content) (str/trim content)
    (array? content)
    (let [parts (->> (array-seq content)
                     (map (fn [block]
                            (let [block-type (gobj/get block "type")]
                              (cond
                                (and (= block-type "text") (string? (gobj/get block "text")))
                                (gobj/get block "text")
                                (and (= block-type "thinking") (string? (gobj/get block "thinking")))
                                (gobj/get block "thinking")
                                :else
                                nil))))
                     (filter string?))]
      (-> (str/join "\n" parts) str/trim))
    :else
    ""))

(defn parse-iso-timestamp
  [timestamp]
  (let [value (.getTime (js/Date. timestamp))]
    (if (js/isNaN value)
      (.now js/Date)
      value)))

(defn extract-message-record
  [entry]
  (let [message (gobj/get entry "message")
        role (gobj/get message "role")]
    (if (= role "toolResult")
      nil
      (let [text (case role
                   ("user" "assistant" "custom")
                   (extract-text-from-content (gobj/get message "content"))
                   "bashExecution"
                   (->> [(some-> (gobj/get message "command") (str "$ "))
                         (gobj/get message "output")]
                        (filter string?)
                        (str/join "\n")
                        str/trim)
                   ("branchSummary" "compactionSummary")
                   (some-> (gobj/get message "summary") str/trim)
                   "")]
        (when (and (string? text) (not= "" text))
          {:id (gobj/get entry "id")
           :role role
           :text text
           :timestamp (parse-iso-timestamp (gobj/get entry "timestamp"))})))))

(defn extract-text-from-session-entry
  [entry]
  (let [entry-type (gobj/get entry "type")
        id (gobj/get entry "id")
        timestamp (gobj/get entry "timestamp")]
    (case entry-type
      "message"
      (extract-message-record entry)
      "branch_summary"
      (let [summary (some-> (gobj/get entry "summary") str/trim)]
        (when (and (string? summary) (not= "" summary))
          {:id (str "branch:" id)
           :role "branchSummary"
           :text summary
           :timestamp (parse-iso-timestamp timestamp)}))
      "compaction"
      (let [summary (some-> (gobj/get entry "summary") str/trim)]
        (when (and (string? summary) (not= "" summary))
          {:id (str "compaction:" id)
           :role "compactionSummary"
           :text summary
           :timestamp (parse-iso-timestamp timestamp)}))
      nil)))

(defn split-into-sentences
  [text]
  (->> (str/split text #"\n+")
       (mapcat #(str/split % #"[.!?]+"))
       (map str/trim)
       (filter #(<= min-sentence-length (count %) max-sentence-length))))

(defn is-likely-ephemeral?
  [text]
  (or (< (count text) min-sentence-length)
      (> (count text) max-sentence-length)
      (boolean (re-find #"\d{6,}" text))
      (boolean (some #(re-find % text) ephemeral-patterns))))

(defn normalize-sentence-for-memory
  [raw-sentence]
  (let [sentence (-> raw-sentence
                     str/trim
                     (str/replace #"^\s*[-*]\s+" "")
                     (#(if (and (re-matches #"^`[^`]+`$" %) (> (count %) 1))
                         (-> % (subs 1 (dec (count %))) str/trim)
                         %)))]
    (cond
      (= "" sentence) nil
      (re-find #"(?i)^/[a-z0-9-]+(?:\s|$)" sentence) nil
      (re-find #"(?i)\busage:\s*/[a-z0-9-]+" sentence) nil
      (re-find #"(?i)`/[a-z0-9-]+`" sentence) nil
      (and (re-find #"(?i)\bsupports?\b" sentence) (re-find #"(?i)/[a-z0-9-]+" sentence)) nil
      :else
      (let [remember-match (re-matches #"(?i)^/remember\s+(.+)$" sentence)
            cleaned (if remember-match
                      (str/trim (second remember-match))
                      (-> sentence
                          (str/replace #"^[\"'`“”‘’]+|[\"'`“”‘’]+$" "")
                          str/trim))]
        (when (not= "" cleaned)
          cleaned)))))

(defn classify-memory-type
  [text]
  (cond
    (re-find #"(?i)\b(prefer|style|convention|policy)\b" text) "preference"
    (re-find #"(?i)\b(always|never|must|do not|don't)\b" text) "constraint"
    (re-find #"(?i)\b(when|before|after|workflow|process|run )\b" text) "workflow"
    :else "fact"))

(defn score-candidate
  [text]
  (let [score (cond-> 0.55
                (re-find #"(?i)\bremember\b" text) (+ 0.35)
                (re-find #"(?i)\b(always|never|must|do not|don't)\b" text) (+ 0.2)
                (re-find #"(?i)\b(from now on|please use|we use|we do not|we don't)\b" text) (+ 0.15))]
    (min score 0.99)))

(defn normalize-memory-text
  [text]
      (let [compact (-> text (str/replace #"\s+" " ") str/trim)]
    (cond
      (= "" compact) ""
      (re-find #"[.!?]$" compact) compact
      :else (str compact "."))))

(defn normalize-for-dedup
  [text]
  (-> text
      str/lower-case
      (str/replace #"[`*_~]" "")
      (str/replace #"[^a-z0-9\s]" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn token-set
  [text]
  (->> (str/split (normalize-for-dedup text) #" ")
       (filter #(> (count %) 1))
       set))

(defn similarity-score
  [a b]
  (let [a-tokens (token-set a)
        b-tokens (token-set b)]
    (if (or (empty? a-tokens) (empty? b-tokens))
      0
      (let [overlap (count (set/intersection a-tokens b-tokens))
            union (- (+ (count a-tokens) (count b-tokens)) overlap)]
        (if (zero? union)
          0
          (/ overlap union))))))

(defn extract-text-from-agent-message
  [message]
  (let [role (gobj/get message "role")]
    (case role
      ("user" "assistant" "custom")
      (extract-text-from-content (gobj/get message "content"))
      "toolResult"
      ""
      "bashExecution"
      (->> [(some-> (gobj/get message "command") (str "$ "))
            (gobj/get message "output")]
           (filter string?)
           (str/join "\n")
           str/trim)
      ("branchSummary" "compactionSummary")
      (some-> (gobj/get message "summary") str/trim)
      "")))

(defn extract-auto-memory-candidates
  [messages options]
  (let [scope (:scope options)
        user-message (first (filter #(= "user" (gobj/get % "role")) (reverse (vec (array-seq messages)))))]
    (if-not user-message
      []
      (let [user-text (extract-text-from-agent-message user-message)
            candidates (reduce (fn [acc sentence]
                                 (let [normalized-sentence (normalize-sentence-for-memory sentence)]
                                   (if (or (nil? normalized-sentence)
                                           (not-any? #(re-find % normalized-sentence) memory-patterns)
                                           (is-likely-ephemeral? normalized-sentence))
                                     acc
                                     (let [normalized-text (normalize-memory-text normalized-sentence)
                                           key (normalize-for-dedup normalized-text)
                                           candidate {:text normalized-text
                                                      :normalizedText key
                                                      :scope scope
                                                      :type (classify-memory-type normalized-sentence)
                                                      :confidence (score-candidate normalized-sentence)}
                                           existing (get acc key)]
                                       (if (or (nil? existing)
                                               (< (:confidence existing) (:confidence candidate)))
                                         (assoc acc key candidate)
                                         acc)))))
                               {}
                               (split-into-sentences user-text))]
        (vals candidates)))))

(defn dedupe-memory-candidates
  [candidates existing-records similarity-threshold]
  (let [known-normalized (->> existing-records
                              (map :text)
                              (map normalize-sentence-for-memory)
                              (filter some?)
                              (map normalize-memory-text)
                              (map normalize-for-dedup)
                              set)]
    (loop [remaining candidates
           accepted []
           known known-normalized]
      (if (empty? remaining)
        accepted
        (let [candidate (first remaining)
              already-known? (contains? known (:normalizedText candidate))
              near-duplicate? (some (fn [record]
                                      (and (= (:scope record) (:scope candidate))
                                           (when-let [normalized-record (normalize-sentence-for-memory (:text record))]
                                             (>= (similarity-score normalized-record (:text candidate))
                                                 similarity-threshold))))
                                    existing-records)
              batch-duplicate? (some (fn [existing]
                                       (and (= (:scope existing) (:scope candidate))
                                            (>= (similarity-score (:text existing) (:text candidate))
                                                similarity-threshold)))
                                     accepted)]
          (if (or already-known? near-duplicate? batch-duplicate?)
            (recur (rest remaining) accepted known)
            (recur (rest remaining)
                   (conj accepted candidate)
                   (conj known (:normalizedText candidate)))))))))

(defn derive-project-key
  [cwd]
  (let [normalized (-> cwd
                       (str/replace #"\\" "/")
                       (str/replace #"/+$" ""))
        parts (->> (str/split normalized #"/")
                   (filter seq))
        tail (or (last parts) "project")]
    (-> tail
        (str/replace #"[^a-zA-Z0-9._-]" "-")
        str/lower-case)))

(defn make-memory-id
  [{:keys [scope projectKey normalizedText]}]
  (let [base (str/join "|" [scope projectKey normalizedText])]
    ((.-v5 uuid) base memory-id-namespace)))

(defn rank-memory-records
  [records]
  (sort (fn [a b]
          (cond
            (not= (:updatedAt a) (:updatedAt b)) (compare (:updatedAt b) (:updatedAt a))
            (not= (:confidence a) (:confidence b)) (compare (:confidence b) (:confidence a))
            :else (compare (:text a) (:text b))))
        records))

(defn build-memory-context-block
  [records max-items max-chars]
  (loop [remaining (seq (rank-memory-records records))
         lines []
         total-chars 0]
    (if (or (nil? remaining) (>= (count lines) max-items))
      (if (empty? lines)
        ""
        (str "## Long-term Memory\n" (str/join "\n" lines)))
      (let [record (first remaining)
            normalized-text (normalize-sentence-for-memory (:text record))]
        (if-not normalized-text
          (recur (next remaining) lines total-chars)
          (let [line (str "- [" (:scope record) "] " (normalize-memory-text normalized-text))
                next-len (+ total-chars (count line) 1)]
            (if (> next-len max-chars)
              (if (empty? lines)
                ""
                (str "## Long-term Memory\n" (str/join "\n" lines)))
              (recur (next remaining) (conj lines line) next-len))))))))

(defn parse-scope
  [value]
  (if (contains? #{"global" "project"} value)
    value
    "project"))

(defn to-memory-record
  [{:keys [candidate projectKey sourceSessionId sourceTurnId timestamp]}]
  {:id (make-memory-id {:scope (:scope candidate)
                        :projectKey projectKey
                        :normalizedText (:normalizedText candidate)})
   :text (:text candidate)
   :normalizedText (:normalizedText candidate)
   :scope (:scope candidate)
   :type (:type candidate)
   :confidence (:confidence candidate)
   :projectKey projectKey
   :sourceSessionId sourceSessionId
   :sourceTurnId sourceTurnId
   :createdAt timestamp
   :updatedAt timestamp
   :deleted false})

(defn has-strong-memory-signal
  [text]
  (boolean (some #(re-find % text) strong-memory-patterns)))
