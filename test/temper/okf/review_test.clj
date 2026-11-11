(ns temper.okf.review-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [temper.okf.review :as r]))

(def schema
  {:review-maturity [{:level "L0" :label "AI-generated"} {:level "L1" :label "Human-directed"}
                     {:level "L2" :label "Human-reviewed"} {:level "L3" :label "Human-verified"}
                     {:level "L4" :label "Human-endorsed"}]
   :reserved-files #{"index.md" "log.md"}})

(def doc
  "---\ntype: Research\ntitle: \"T\"\nreview_maturity: L0 (AI-generated)\nreview_note: \"Not yet human-reviewed.\"\n---\n\n# T\n\nBody: review_maturity: L0 stays untouched here.\n")

(deftest rank
  (is (= 0 (r/rank schema "L0 (AI-generated)")))
  (is (= 2 (r/rank schema "L2")))
  (is (nil? (r/rank schema "garbage"))))

(deftest bump-rewrites-only-the-frontmatter
  (let [out (r/bump schema doc {:target "L2" :reviewed-at "2026-11-11T10:00:00Z" :note "Viewed by lambduhh in PR #4 on 2026-11-11."})]
    (is (str/includes? out "review_maturity: L2 (Human-reviewed)\n"))
    (is (str/includes? out "reviewed_at: 2026-11-11T10:00:00Z\n"))
    (is (str/includes? out "review_note: \"Viewed by lambduhh in PR #4 on 2026-11-11.\"\n"))
    (is (str/includes? out "Body: review_maturity: L0 stays untouched here.") "body is not edited")
    (is (= 1 (count (re-seq #"(?m)^review_maturity:" out))) "no duplicate key")
    (is (str/starts-with? out "---\ntype: Research\n") "other keys and order kept")))

(deftest bump-without-target-keeps-the-level
  (let [l2 (str/replace doc "L0 (AI-generated)" "L2 (Human-reviewed)")
        out (r/bump schema l2 {:reviewed-at "2026-11-11T12:00:00Z" :note "refreshed"})]
    (is (str/includes? out "review_maturity: L2 (Human-reviewed)\n"))
    (is (str/includes? out "reviewed_at: 2026-11-11T12:00:00Z\n"))))

(deftest bump-adds-missing-keys
  (let [out (r/bump schema "---\ntype: Guide\n---\n\n# G\n" {:target "L2" :reviewed-at "2026-11-11T10:00:00Z" :note "n"})]
    (is (re-find #"(?s)^---\ntype: Guide\nreview_maturity: L2 \(Human-reviewed\)\nreviewed_at: 2026-11-11T10:00:00Z\nreview_note: \"n\"\n---\n" out))))

(deftest quotes-are-escaped
  (is (str/includes? (r/bump schema doc {:target "L2" :reviewed-at "t" :note "say \"hi\""})
                     "review_note: \"say \\\"hi\\\"\"")))

(deftest timestamps-differ-within-a-day
  (is (re-matches #"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ" (r/now-utc))))

(deftest candidates
  (let [dir (str (fs/create-temp-dir))
        w   (fn [rel s] (fs/create-dirs (fs/parent (fs/path dir rel))) (spit (str (fs/path dir rel)) s))]
    (w "docs/a.md" doc)                                                         ; viewed, L0 -> bump
    (w "docs/b.md" (str/replace doc "L0 (AI-generated)" "L3 (Human-verified)")) ; viewed, above; no git baseline -> already
    (w "docs/c.md" doc)                                                         ; not viewed
    (w "docs/d.md" doc)                                                         ; DISMISSED (changed after viewing)
    (w "src/x.md" doc)                                                          ; outside docs dir
    (w "docs/e.md" "# no frontmatter\n")
    (w "docs/index.md" "---\nokf_version: \"0.1\"\n---\n\n# Index\n")           ; reserved
    (let [files [{:path "docs/a.md" :viewerViewedState "VIEWED"} {:path "docs/b.md" :viewerViewedState "VIEWED"}
                 {:path "docs/c.md" :viewerViewedState "UNVIEWED"} {:path "docs/d.md" :viewerViewedState "DISMISSED"}
                 {:path "src/x.md" :viewerViewedState "VIEWED"} {:path "docs/e.md" :viewerViewedState "VIEWED"}
                 {:path "docs/z.clj" :viewerViewedState "VIEWED"} {:path "docs/index.md" :viewerViewedState "VIEWED"}]
          res (r/plan {:schema schema :root dir :docs-dir "docs" :target "L2" :files files})]
      (is (= ["docs/a.md"] (map :path (:bump res))))
      (is (= #{"docs/b.md"} (set (map :path (:already res)))))
      (is (empty? (:refresh res)))
      (is (= #{"docs/e.md"} (set (map :path (:no-frontmatter res)))))
      (is (= #{"docs/index.md"} (set (map :path (:reserved res)))) "OKF reserved files carry only okf_version"))))

;; ---------------------------------------------------------------------------
;; With git

(defn- git! [dir & args] (apply p/shell {:dir dir :out :string :err :string} "git" args))
(defn- commit! [dir msg] (git! dir "add" "-A") (git! dir "commit" "-q" "-m" msg))

(defn- repo []
  (let [dir (str (fs/create-temp-dir))]
    (git! dir "init" "-q" "-b" "pr-branch")
    (git! dir "config" "user.email" "t@example.com") (git! dir "config" "user.name" "T")
    (fs/create-dirs (fs/path dir "docs"))
    (spit (str (fs/path dir "docs/metadata-schema.edn")) (pr-str schema))
    (spit (str (fs/path dir "docs/a.md")) doc)
    (commit! dir "init")
    dir))

(defn- head [dir] (str/trim (:out (git! dir "rev-parse" "HEAD"))))

(defn- fetch-for [dir & paths]
  (fn [_] {:viewer "lambduhh" :head "pr-branch" :head-oid (head dir) :cross-repo? false
           :files (mapv (fn [p] {:path p :viewerViewedState "VIEWED"}) paths)}))

(deftest run-raises-and-commits
  (let [dir (repo)
        res (r/run {:root dir :pr 4 :now "2026-11-11T10:00:00Z" :commit true :fetch (fetch-for dir "docs/a.md")})]
    (is (= 0 (:exit res)) (:error res))
    (is (= ["docs/a.md"] (:changed res)))
    (is (str/includes? (slurp (str (fs/path dir "docs/a.md"))) "Viewed by lambduhh in PR #4 on 2026-11-11."))
    (is (str/includes? (:out (git! dir "log" "-1" "--format=%s")) "Mark docs viewed in PR #4"))))

(deftest viewed-l2-doc-changed-since-review-is-refreshed
  (testing "a doc still at L2 whose body moved on gets a new reviewed_at, so drift measures from it (review finding)"
    (let [dir (repo)
          f (str (fs/path dir "docs/a.md"))]
      (r/run {:root dir :pr 4 :now "2026-11-11T10:00:00Z" :commit true :fetch (fetch-for dir "docs/a.md")})
      (spit f (str/replace (slurp f) "stays untouched here." "stays untouched here. One more sentence."))
      (commit! dir "small edit")
      (let [res (r/run {:root dir :pr 4 :now "2026-11-11T11:00:00Z" :commit true :fetch (fetch-for dir "docs/a.md")})]
        (is (= ["docs/a.md"] (:refreshed res)))
        (is (str/includes? (slurp f) "reviewed_at: 2026-11-11T11:00:00Z"))
        (is (str/includes? (slurp f) "review_maturity: L2 (Human-reviewed)") "level kept"))
      (testing "and unchanged since that review: left alone"
        (let [res (r/run {:root dir :pr 4 :now "2026-11-11T12:00:00Z" :commit true :fetch (fetch-for dir "docs/a.md")})]
          (is (empty? (:changed res)))
          (is (= ["docs/a.md"] (map :path (get-in res [:plan :already])))))))))

(deftest commit-preconditions-are-checked-before-writing
  (testing "other staged changes"
    (let [dir (repo)]
      (spit (str (fs/path dir "unrelated.txt")) "x") (git! dir "add" "unrelated.txt")
      (let [res (r/run {:root dir :pr 4 :now "t" :commit true :fetch (fetch-for dir "docs/a.md")})]
        (is (= 1 (:exit res)))
        (is (str/includes? (:error res) "staged"))
        (is (str/includes? (slurp (str (fs/path dir "docs/a.md"))) "L0 (AI-generated)") "nothing written"))))
  (testing "local HEAD isn't the PR head GitHub showed"
    (let [dir (repo)
          res (r/run {:root dir :pr 4 :now "t" :commit true
                      :fetch (fn [_] {:viewer "v" :head "pr-branch" :head-oid "0000000000000000000000000000000000000000"
                                      :files [{:path "docs/a.md" :viewerViewedState "VIEWED"}]})})]
      (is (= 1 (:exit res)))
      (is (str/includes? (:error res) "isn't the PR head"))))
  (testing "a doc with local edits the reviewer never saw"
    (let [dir (repo)]
      (spit (str (fs/path dir "docs/a.md")) (str doc "local edit\n"))
      (let [res (r/run {:root dir :pr 4 :now "t" :commit true :fetch (fetch-for dir "docs/a.md")})]
        (is (= 1 (:exit res)))
        (is (str/includes? (:error res) "local edits")))))
  (testing "a fork PR"
    (let [dir (repo)
          res (r/run {:root dir :pr 4 :now "t" :commit true
                      :fetch (fn [_] (assoc ((fetch-for dir "docs/a.md") nil) :cross-repo? true))})]
      (is (= 1 (:exit res)))
      (is (str/includes? (:error res) "fork")))))
