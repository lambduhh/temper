(ns temper.okf.drift-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [temper.okf.drift :as d]))

(deftest word-edit-distance
  (is (= 0 (d/edit-distance ["a" "b" "c"] ["a" "b" "c"])))
  (is (= 1 (d/edit-distance ["a" "b" "c"] ["a" "c"])) "one deletion")
  (is (= 2 (d/edit-distance ["a" "b" "c"] ["a" "x" "c"])) "a substitution is a delete plus an insert")
  (is (= 6 (d/edit-distance ["a" "b" "c"] ["x" "y" "z"])))
  (is (= 3 (d/edit-distance [] ["x" "y" "z"]))))

(deftest changed-fraction
  (testing "words, not lines: reflowing a paragraph is not a change"
    (is (= 0.0 (d/changed-fraction "one two three four\nfive" "one two\nthree four five"))))
  (is (= 0.2 (d/changed-fraction "a b c d e f g h i j" "a b c d e f g h X j")))
  (is (= 1.0 (d/changed-fraction "a b" "x y z w v u")) "capped at 100%")
  (is (= 1.0 (d/changed-fraction "" "new text")) "no baseline words: anything new is a full change"))

(deftest body-ignores-frontmatter
  (is (= "\n# T\n\nBody\n" (d/body "---\ntype: X\nreview_maturity: L2\n---\n\n# T\n\nBody\n"))))

(def schema
  {:review-maturity [{:level "L0" :label "AI-generated"} {:level "L1" :label "Human-directed"}
                     {:level "L2" :label "Human-reviewed"} {:level "L3" :label "Human-verified"}
                     {:level "L4" :label "Human-endorsed"}]
   :review-staleness {:from "L2"}
   :review-drift {:changed-fraction 0.2 :to "L1"}})

(defn- git! [dir & args] (apply p/shell {:dir dir :out :string :err :string} "git" args))

(defn- repo-with-reviewed-doc []
  (let [dir (str (fs/create-temp-dir))
        doc (str "---\ntype: Research\nreview_maturity: L2 (Human-reviewed)\nreviewed_at: 2026-11-11\n"
                 "review_note: \"Viewed by x in PR #1 on 2026-11-11.\"\n---\n\n"
                 (str/join " " (map #(str "w" %) (range 100))) "\n")]
    (git! dir "init" "-q" "-b" "main")
    (git! dir "config" "user.email" "t@example.com") (git! dir "config" "user.name" "T")
    (fs/create-dirs (fs/path dir "docs"))
    (spit (str (fs/path dir "docs/metadata-schema.edn")) (pr-str schema))
    (spit (str (fs/path dir "docs/a.md")) doc)
    (git! dir "add" "-A") (git! dir "commit" "-q" "-m" "reviewed")
    {:dir dir :doc doc}))

(deftest lowers-above-threshold-and-only-then
  (let [{:keys [dir doc]} (repo-with-reviewed-doc)
        f (str (fs/path dir "docs/a.md"))]
    (testing "a small edit (10 of 100 words) stays L2"
      (spit f (str/replace doc #"w1 w2 w3 w4 w5 " "v1 v2 v3 v4 v5 "))
      (let [res (d/run {:root dir})]
        (is (= 0 (:exit res)))
        (is (empty? (:lowered res)))
        (is (str/includes? (slurp f) "review_maturity: L2 (Human-reviewed)"))))
    (testing "an edit at the threshold (10 words replaced = 20 edits / 100 words = 20%) is lowered to L1 with a note"
      (spit f (str/replace doc #"w(1\d) " "z$1 "))
      (let [res (d/run {:root dir :today "2026-11-12"})
            out (slurp f)]
        (is (= ["docs/a.md"] (map :path (:lowered res))))
        (is (str/includes? out "review_maturity: L1 (Human-directed)\n"))
        (is (re-find #"review_note: \"Lowered to L1 on 2026-11-12: at least 20% of words changed since the review at [0-9a-f]{7} \(threshold 20%\)\.\"" out))
        (is (str/includes? out " w9 z10 z11 ") "body untouched by the rewrite")))))

(deftest check-mode-reports-without-writing
  (let [{:keys [dir doc]} (repo-with-reviewed-doc)
        f (str (fs/path dir "docs/a.md"))
        changed (str/replace doc #"w(\d\d) " "q$1 ")]
    (spit f changed)
    (let [res (d/run {:root dir :check true})]
      (is (= 1 (:exit res)))
      (is (= ["docs/a.md"] (map :path (:lowered res))))
      (is (= changed (slurp f)) "nothing written"))))

(deftest frontmatter-only-changes-are-not-drift
  (let [{:keys [dir doc]} (repo-with-reviewed-doc)
        f (str (fs/path dir "docs/a.md"))]
    (spit f (str/replace doc "type: Research" "type: Research\ntitle: \"A new title\""))
    (is (empty? (:lowered (d/run {:root dir}))))))

(deftest below-threshold-levels-and-unreviewed-docs-are-skipped
  (let [{:keys [dir doc]} (repo-with-reviewed-doc)]
    (spit (str (fs/path dir "docs/b.md")) (str/replace doc "L2 (Human-reviewed)" "L1 (Human-directed)"))
    (spit (str (fs/path dir "docs/c.md")) "---\ntype: X\nreview_maturity: L3 (Human-verified)\nreviewed_at: 2026-11-11\n---\n\nnot committed yet\n")
    (let [res (d/run {:root dir})]
      (is (not-any? #{"docs/b.md"} (map :path (concat (:lowered res) (:checked res)))) "L1 is below the floor")
      (is (= ["docs/c.md"] (map :path (:no-baseline res))) "no commit set its reviewed_at yet"))))

(deftest missing-schema-key-is-a-config-error
  (let [{:keys [dir]} (repo-with-reviewed-doc)]
    (spit (str (fs/path dir "docs/metadata-schema.edn")) (pr-str (dissoc schema :review-drift)))
    (is (= 2 (:exit (d/run {:root dir}))))))

(deftest bounded-search
  (is (nil? (d/edit-distance ["a" "b" "c"] ["x" "y" "z"] 3)) "gives up past max-d")
  (is (= 2 (d/edit-distance ["a" "b" "c"] ["a" "x" "c"] 5)))
  (is (= {:fraction 0.1 :at-least? false} (d/drift "a b c d e f g h i j" "a b c d e f g h i X j" 0.2)) "exact below threshold")
  (is (= {:fraction 0.2 :at-least? true} (d/drift "a b c d e f g h i j" "x y z w v u t s r q" 0.2)) "stops at threshold")
  (testing "fast on large documents"
    (let [a (str/join " " (map #(str "w" %) (range 5000)))
          b (str/join " " (map #(str "z" %) (range 5000)))
          t0 (System/currentTimeMillis)]
      (is (:at-least? (d/drift a b 0.2)))
      (is (< (- (System/currentTimeMillis) t0) 2000) "a full rewrite of 5,000 words is decided by the lower bound"))))
