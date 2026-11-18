(ns temper.okf.drift-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [temper.okf.drift :as d]
            [temper.okf.frontmatter :as fm]
            [temper.okf.review :as r]))

;; ---------------------------------------------------------------------------
;; Words

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

(deftest float-threshold-matches-the-decision
  (testing "t*n rounding must not lower a doc whose fraction is below t (review finding)"
    (let [a (mapv #(str "w" %) (range 300)) s #(str/join " " %)
          t 0.030000000000000002
          res (d/drift (s a) (s (drop 9 a)) t)]
      (is (= 0.03 (:fraction res)))
      (is (false? (:at-least? res)))
      (is (< (:fraction res) t)))))

;; ---------------------------------------------------------------------------
;; Repos

(def schema
  {:review-maturity [{:level "L0" :label "AI-generated"} {:level "L1" :label "Human-directed"}
                     {:level "L2" :label "Human-reviewed"} {:level "L3" :label "Human-verified"}
                     {:level "L4" :label "Human-endorsed"}]
   :review-staleness {:from "L2"}
   :review-drift {:changed-fraction 0.2 :to "L1"}})

(defn- git! [dir & args] (apply p/shell {:dir dir :out :string :err :string} "git" args))
(defn- commit! [dir msg] (git! dir "add" "-A") (git! dir "commit" "-q" "-m" msg))

(defn- doc [& {:keys [reviewed-at body] :or {reviewed-at "2026-11-11T10:00:00Z"}}]
  (str "---\ntype: Research\nreview_maturity: L2 (Human-reviewed)\nreviewed_at: " reviewed-at "\n"
       "review_note: \"Viewed by x in PR #1 on 2026-11-11.\"\n---\n\n"
       (or body (str/join " " (map #(str "w" %) (range 100)))) "\n"))

(defn- repo [& {:keys [sub]}]
  (let [top (str (fs/create-temp-dir))
        dir (if sub (str (fs/path top sub)) top)]
    (git! top "init" "-q" "-b" "main")
    (git! top "config" "user.email" "t@example.com") (git! top "config" "user.name" "T")
    (fs/create-dirs (fs/path dir "docs"))
    (spit (str (fs/path dir "docs/metadata-schema.edn")) (pr-str schema))
    (spit (str (fs/path dir "docs/a.md")) (doc))
    (commit! top "reviewed")
    {:top top :dir dir :f (str (fs/path dir "docs/a.md"))}))

(defn- edit-words! [f from to]
  (spit f (str/replace (slurp f) (re-pattern (str "\\bw(" from ")\\b")) to)))

(deftest lowers-at-threshold-and-only-then
  (let [{:keys [dir f]} (repo)]
    (testing "a small edit (5 of 100 words replaced = 10%) stays L2"
      (spit f (str/replace (slurp f) #"w1 w2 w3 w4 w5 " "v1 v2 v3 v4 v5 "))
      (let [res (d/run {:root dir})]
        (is (= 0 (:exit res)))
        (is (empty? (:lowered res)))
        (is (str/includes? (slurp f) "review_maturity: L2 (Human-reviewed)"))))
    (testing "10 words replaced = 20 edits / 100 words = 20%: lowered to L1 with a note"
      (spit f (doc :body (str/replace (str/join " " (map #(str "w" %) (range 100))) #"w(1\d) " "z$1 ")))
      (let [res (d/run {:root dir})
            out (slurp f)]
        (is (= ["docs/a.md"] (map :path (:lowered res))))
        (is (str/includes? out "review_maturity: L1 (Human-directed)\n"))
        (is (re-find #"review_note: \"Lowered to L1: at least 20% of words changed since the review at [0-9a-f]{7} \(threshold 20%\)\.\"" out))
        (is (str/includes? out " w9 z10 z11 ") "body untouched by the rewrite")))))

(deftest check-mode-reports-without-writing
  (let [{:keys [dir f]} (repo)
        changed (str/replace (slurp f) #"w(\d\d) " "q$1 ")]
    (spit f changed)
    (let [res (d/run {:root dir :check true})]
      (is (= 1 (:exit res)))
      (is (= ["docs/a.md"] (map :path (:lowered res))))
      (is (= changed (slurp f)) "nothing written"))))

(deftest frontmatter-only-changes-are-not-drift
  (let [{:keys [dir f]} (repo)]
    (spit f (str/replace (slurp f) "type: Research" "type: Research\ntitle: \"A new title\""))
    (is (empty? (:lowered (d/run {:root dir}))))))

(deftest below-floor-and-unreviewed-docs-are-skipped
  (let [{:keys [dir]} (repo)]
    (spit (str (fs/path dir "docs/b.md")) (str/replace (doc) "L2 (Human-reviewed)" "L1 (Human-directed)"))
    (spit (str (fs/path dir "docs/c.md")) "---\ntype: X\nreview_maturity: L3 (Human-verified)\nreviewed_at: 2026-11-11\n---\n\nnot committed yet\n")
    (let [res (d/run {:root dir})]
      (is (not-any? #{"docs/b.md"} (map :path (concat (:lowered res) (:checked res)))) "L1 is below the floor")
      (is (= ["docs/c.md"] (map :path (:no-baseline res))) "no commit has its reviewed_at yet"))))

(deftest a-same-day-re-review-moves-the-baseline
  (testing "lowered, then re-reviewed the same day: the new timestamp is the new baseline (review finding)"
    (let [{:keys [dir f]} (repo)]
      (edit-words! f "\\d\\d" "q")                           ; big edit
      (commit! dir "rewrite")
      (d/run {:root dir})                                     ; lowered to L1
      (commit! dir "lowered")
      (is (str/includes? (slurp f) "L1 (Human-directed)"))
      ;; reviewer ticks Viewed: review raises it with a fresh timestamp, same day
      (spit f (r/bump schema (slurp f) {:target "L2" :reviewed-at "2026-11-11T22:30:00Z" :note "re-viewed"}))
      (commit! dir "re-reviewed")
      (let [res (d/run {:root dir})]
        (is (empty? (:lowered res)))
        (is (= 0.0 (:fraction (first (:checked res)))) "measured from the re-reviewed version")))))

(deftest renames-are-followed
  (testing "rename, then rewrite: the baseline follows the rename (review finding)"
    (let [{:keys [dir]} (repo)]
      (git! dir "mv" "docs/a.md" "docs/renamed.md")
      (commit! dir "rename")
      (edit-words! (str (fs/path dir "docs/renamed.md")) "\\d\\d" "q")
      (commit! dir "rewrite")
      (is (= ["docs/renamed.md"] (map :path (:lowered (d/run {:root dir})))))))
  (testing "known limit: rename AND rewrite most of it in ONE commit -- git can't tell it's a rename
           (under 50% similar), so that commit becomes the baseline. Documented in the README."
    (let [{:keys [dir]} (repo)]
      (git! dir "mv" "docs/a.md" "docs/renamed.md")
      (edit-words! (str (fs/path dir "docs/renamed.md")) "\\d\\d" "q")
      (commit! dir "rename and rewrite")
      (is (empty? (:lowered (d/run {:root dir})))))))

(deftest reformatting-reviewed-at-or-a-body-line-does-not-reset
  (let [{:keys [dir f]} (repo)]
    (edit-words! f "\\d\\d" "q")
    (commit! dir "rewrite")
    (spit f (-> (slurp f)
                (str/replace "reviewed_at: 2026-11-11T10:00:00Z" "reviewed_at: \"2026-11-11T10:00:00Z\"")
                (str "\n```yaml\nreviewed_at: 2026-01-01\n```\n")))
    (commit! dir "quote the date; add a yaml example")
    (is (= ["docs/a.md"] (map :path (:lowered (d/run {:root dir})))))))

(deftest repo-problems-are-config-errors
  (testing "not a git repo"
    (let [dir (str (fs/create-temp-dir))]
      (fs/create-dirs (fs/path dir "docs"))
      (spit (str (fs/path dir "docs/metadata-schema.edn")) (pr-str schema))
      (is (= 2 (:exit (d/run {:root dir}))))))
  (testing "shallow clone (review finding: it used to report 0% for everything)"
    (let [{:keys [top]} (repo)
          clone (str (fs/create-temp-dir))]
      (spit (str (fs/path top "docs/a.md")) (doc :body "changed"))
      (commit! top "second")
      (p/shell {:out :string :err :string} "git" "clone" "-q" "--depth" "1" (str "file://" top) clone)
      (is (= 2 (:exit (d/run {:root clone})))))))

(deftest works-from-a-subdirectory-of-the-repo
  (let [{:keys [dir f]} (repo :sub "project")]
    (edit-words! f "\\d\\d" "q")
    (is (= ["docs/a.md"] (map :path (:lowered (d/run {:root dir})))))))

(deftest schema-checks
  (let [{:keys [dir]} (repo)
        put! #(spit (str (fs/path dir "docs/metadata-schema.edn")) (pr-str %))]
    (put! (dissoc schema :review-drift))
    (is (= 2 (:exit (d/run {:root dir}))) "missing key")
    (put! (assoc-in schema [:review-drift :to] "L2"))
    (is (= 2 (:exit (d/run {:root dir}))) ":to at the floor would lower again on every run")))

(deftest changed-since-limits-scope
  (let [{:keys [dir]} (repo)]
    (spit (str (fs/path dir "docs/b.md")) (doc))
    (commit! dir "b reviewed")
    (git! dir "switch" "-q" "-c" "pr")
    (edit-words! (str (fs/path dir "docs/b.md")) "\\d\\d" "q")
    (commit! dir "edit b")
    ;; a.md drifted on main earlier would also be lowered without the flag
    (let [res (d/run {:root dir :changed-since "main"})]
      (is (= ["docs/b.md"] (map :path (:lowered res))))
      (is (not-any? #{"docs/a.md"} (map :path (concat (:lowered res) (:checked res))))))))

;; ---------------------------------------------------------------------------
;; Frontmatter

(deftest frontmatter-edge-cases
  (is (= ["" "body\n"] (fm/split "---\n---\nbody\n")) "an empty block (used to crash)")
  (is (= ["type: X" "body\n"] (fm/split "---\r\ntype: X\r\n--- \r\nbody\n")) "CRLF and a trailing space")
  (is (= "\n# T\n\nBody\n" (fm/body "---\ntype: X\nreview_maturity: L2\n---\n\n# T\n\nBody\n")))
  (is (= "2026-11-11" (fm/value "---\nreviewed_at: \"2026-11-11\"  \n---\n" :reviewed_at)) "unquoted, trimmed")
  (testing "a block-scalar note is replaced whole, not left dangling (review finding)"
    (let [out (fm/set-keys "---\nreview_note: >-\n  long\n  note\ntype: X\n---\nB\n" [["review_note" "\"new\""]])]
      (is (= "---\nreview_note: \"new\"\ntype: X\n---\nB\n" out)))))
