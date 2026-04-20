(ns pi-logseq.lib-test
  (:require [cljs.test :refer [deftest is]]
            [pi-logseq.lib :as lib]))

(defn candidate
  [text scope confidence]
  {:text (lib/normalize-memory-text text)
   :normalizedText (lib/normalize-for-dedup text)
   :scope scope
   :type (lib/classify-memory-type text)
   :confidence confidence})

(deftest extract-auto-memory-candidates-uses-latest-user-message
  (let [messages (clj->js [{:role "user" :content "Remember we use Clojure style rules."}
                           {:role "assistant" :content "Noted"}
                           {:role "user" :content "we use pnpm for installs in this project. today build finished."}])
        candidates (lib/extract-auto-memory-candidates messages {:scope "project"})]
    (is (= 1 (count candidates)))
    (let [first-candidate (first candidates)]
      (is (= "project" (:scope first-candidate)))
      (is (= "we use pnpm for installs in this project." (:text first-candidate)))
      (is (= "fact" (:type first-candidate)))
      (is (>= (:confidence first-candidate) 0.7)))))

(deftest dedupe-memory-candidates-removes-known-normalized-text-across-scopes
  (let [candidates [(candidate "Always use pnpm for installs." "project" 0.8)
                    (candidate "Always use pnpm." "project" 0.75)
                    (candidate "Always use pnpm for installs." "global" 0.9)]
        existing-records [{:scope "project" :text "Always use pnpm for installs."}]
        deduped (lib/dedupe-memory-candidates candidates existing-records 0.6)]
    (is (= [] deduped))))

(deftest build-memory-context-block-respects-priority-and-item-limit
  (let [records [{:text "Use pnpm" :scope "project" :updatedAt 10 :confidence 0.7}
                 {:text "Prefer small PRs" :scope "project" :updatedAt 20 :confidence 0.95}
                 {:text "Never force push" :scope "global" :updatedAt 30 :confidence 0.6}]
        block (lib/build-memory-context-block records 2 300)]
    (is (= "## Long-term Memory\n- [global] Never force push.\n- [project] Prefer small PRs."
           block))))

(deftest build-memory-context-block-returns-empty-when-first-line-exceeds-budget
  (let [records [{:text "Prefer concise commit messages" :scope "project" :updatedAt 10 :confidence 0.9}]
        block (lib/build-memory-context-block records 5 10)]
    (is (= "" block))))
