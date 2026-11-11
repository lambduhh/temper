(ns temper.okf.review-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [temper.okf.review :as r]))

(def schema
  {:review-maturity [{:level "L0" :label "AI-generated"} {:level "L1" :label "Human-directed"}
                     {:level "L2" :label "Human-reviewed"} {:level "L3" :label "Human-verified"}
                     {:level "L4" :label "Human-endorsed"}]})

(def doc
  "---\ntype: Research\ntitle: \"T\"\nreview_maturity: L0 (AI-generated)\nreview_note: \"Not yet human-reviewed.\"\n---\n\n# T\n\nBody: review_maturity: L0 stays untouched here.\n")

(deftest rank
  (is (= 0 (r/rank schema "L0 (AI-generated)")))
  (is (= 2 (r/rank schema "L2")))
  (is (nil? (r/rank schema "garbage"))))

(deftest bump-rewrites-only-the-frontmatter
  (let [out (r/bump schema doc {:target "L2" :today "2026-11-11" :note "Viewed by lambduhh in PR #4 on 2026-11-11."})]
    (is (str/includes? out "review_maturity: L2 (Human-reviewed)\n"))
    (is (str/includes? out "reviewed_at: 2026-11-11\n"))
    (is (str/includes? out "review_note: \"Viewed by lambduhh in PR #4 on 2026-11-11.\"\n"))
    (is (str/includes? out "Body: review_maturity: L0 stays untouched here.") "body is not edited")
    (is (= 1 (count (re-seq #"(?m)^review_maturity:" out))) "no duplicate key")
    (is (str/starts-with? out "---\ntype: Research\n") "other keys and order kept")))

(deftest bump-adds-missing-keys
  (let [out (r/bump schema "---\ntype: Guide\n---\n\n# G\n" {:target "L2" :today "2026-11-11" :note "n"})]
    (is (re-find #"(?s)^---\ntype: Guide\nreview_maturity: L2 \(Human-reviewed\)\nreviewed_at: 2026-11-11\nreview_note: \"n\"\n---\n" out))))

(deftest quotes-are-escaped
  (is (str/includes? (r/bump schema doc {:target "L2" :today "2026-11-11" :note "say \"hi\""})
                     "review_note: \"say \\\"hi\\\"\"")))

(deftest candidates
  (let [dir (str (fs/create-temp-dir))
        w   (fn [rel s] (fs/create-dirs (fs/parent (fs/path dir rel))) (spit (str (fs/path dir rel)) s))]
    (w "docs/a.md" doc)                                                 ; viewed, L0 -> bump
    (w "docs/b.md" (str/replace doc "L0 (AI-generated)" "L3 (Human-verified)")) ; viewed, already above
    (w "docs/c.md" doc)                                                 ; not viewed
    (w "docs/d.md" doc)                                                 ; viewed, then changed (DISMISSED)
    (w "src/x.md" doc)                                                  ; viewed, outside docs dir
    (w "docs/e.md" "# no frontmatter\n")                                ; viewed, no frontmatter
    (let [files [{:path "docs/a.md" :viewerViewedState "VIEWED"} {:path "docs/b.md" :viewerViewedState "VIEWED"}
                 {:path "docs/c.md" :viewerViewedState "UNVIEWED"} {:path "docs/d.md" :viewerViewedState "DISMISSED"}
                 {:path "src/x.md" :viewerViewedState "VIEWED"} {:path "docs/e.md" :viewerViewedState "VIEWED"}
                 {:path "docs/z.clj" :viewerViewedState "VIEWED"}]
          res (r/plan {:schema schema :root dir :docs-dir "docs" :target "L2" :files files})]
      (is (= ["docs/a.md"] (map :path (:bump res))))
      (is (= #{"docs/b.md"} (set (map :path (:already res)))))
      (is (= #{"docs/e.md"} (set (map :path (:no-frontmatter res))))))))

(deftest run-with-injected-github
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "docs"))
    (spit (str (fs/path dir "docs/a.md")) doc)
    (spit (str (fs/path dir "docs/metadata-schema.edn")) (pr-str schema))
    (let [res (r/run {:root dir :pr 4 :today "2026-11-11"
                      :fetch (fn [_] {:viewer "lambduhh" :head "docs/okf-bundle"
                                      :files [{:path "docs/a.md" :viewerViewedState "VIEWED"}]})})]
      (is (= 0 (:exit res)))
      (is (= ["docs/a.md"] (:changed res)))
      (is (str/includes? (slurp (str (fs/path dir "docs/a.md"))) "Viewed by lambduhh in PR #4 on 2026-11-11.")))))
