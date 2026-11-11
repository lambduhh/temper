(ns temper.okf.review
  "Raise the review level of docs that a reviewer has marked 'Viewed' in a
  GitHub pull request.

  GitHub records a per-file 'Viewed' checkbox (GraphQL viewerViewedState). It
  is visible only to the person who ticked it, and no webhook fires when it
  changes, so this runs with the reviewer's own gh login: locally, or from a
  GitHub Action holding the reviewer's token.

  For each VIEWED Markdown file under the docs dir:
  - below the target level (default L2): raise it, setting review_maturity,
    reviewed_at and review_note;
  - at or above the target, but changed since its last review: refresh
    reviewed_at and review_note and keep the level, so temper.okf.drift
    measures from the version just viewed;
  - otherwise leave it. It never lowers a level.

  reviewed_at is a UTC timestamp, so every review changes it, even a second
  one on the same day; temper.okf.drift finds the reviewed version by that
  change. A file changed on GitHub after it was viewed shows as DISMISSED, not
  VIEWED, so only the version the reviewer saw counts.

  Exit codes: 0 done (including nothing to do) · 1 a git precondition or step
  failed (nothing is written when a --commit precondition fails) · 2
  unreadable configuration or a GitHub query failure."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [temper.okf.cli :as cli]
            [temper.okf.config :as config]
            [temper.okf.frontmatter :as fm]
            [temper.okf.history :as history]
            [temper.okf.schema :as schema]))

;; Kept for callers of the earlier API.
(def rank schema/rank)
(def frontmatter-split fm/split)
(defn current-maturity [content] (fm/value content :review_maturity))

(defn now-utc
  "The current instant as an ISO-8601 UTC timestamp to the second."
  []
  (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS)))

(defn bump
  "Content with review_maturity (when :target is given), reviewed_at and
  review_note set. Only the frontmatter is edited; key order is kept and
  missing keys are appended."
  [schema content {:keys [target reviewed-at note]}]
  (fm/set-keys content (cond-> []
                         target (conj ["review_maturity" (schema/canonical-maturity schema target)])
                         true   (conj ["reviewed_at" reviewed-at] ["review_note" (fm/yaml-str note)]))))

(defn- changed-since-review?
  "True when the doc's body differs from the version its reviewed_at points
  at. Nil when that version can't be found (not in git, or not committed)."
  [root path content]
  (let [{:keys [status] b :content} (history/baseline root path content)]
    (when (= :ok status)
      (not= (fm/body b) (fm/body content)))))

(defn plan
  "Sort the PR's files into :bump (below target), :refresh (at or above target
  and changed since its last review), :already, :no-frontmatter and
  :reserved. Only VIEWED .md files under docs-dir are considered. Reserved
  files (the schema's :reserved-files, e.g. index.md) are never raised: OKF
  lets the bundle root carry only okf_version."
  [{:keys [schema root docs-dir target files]}]
  (let [prefix (str (str/replace docs-dir #"/+$" "") "/")
        target-rank (schema/rank schema target)
        reserved    (set (:reserved-files schema))]
    (reduce (fn [acc {:keys [path viewerViewedState]}]
              (let [f (fs/path root path)]
                (if-not (and (= "VIEWED" viewerViewedState) (str/starts-with? path prefix)
                             (str/ends-with? path ".md") (fs/regular-file? f))
                  acc
                  (let [content (slurp (str f)) current (current-maturity content)
                        r (some->> current (schema/rank schema))]
                    (cond
                      (reserved (fs/file-name path))  (update acc :reserved conj {:path path})
                      (nil? (fm/split content))       (update acc :no-frontmatter conj {:path path})
                      (and r (>= r target-rank))
                      (if (changed-since-review? root path content)
                        (update acc :refresh conj {:path path :current current})
                        (update acc :already conj {:path path :current current}))
                      :else (update acc :bump conj {:path path :current current}))))))
            {:bump [] :refresh [] :already [] :no-frontmatter [] :reserved []}
            files)))

;; ---------------------------------------------------------------------------
;; GitHub

(def ^:private query
  "query($owner:String!,$name:String!,$pr:Int!,$after:String){viewer{login}
   repository(owner:$owner,name:$name){pullRequest(number:$pr){headRefName headRefOid
   isCrossRepository files(first:100,after:$after){pageInfo{hasNextPage endCursor}
   nodes{path viewerViewedState}}}}}")

(defn- current-repo []
  (-> (p/shell {:out :string :err :string} "gh" "repo" "view" "--json" "nameWithOwner" "--jq" ".nameWithOwner")
      :out str/trim))

(defn fetch-github
  "{:viewer :head :head-oid :cross-repo? :files [{:path :viewerViewedState}]}
  for PR `pr` in `repo` (\"owner/name\"; default: the current directory's
  repo), via gh."
  [{:keys [pr repo]}]
  (let [[owner name] (str/split (or repo (current-repo)) #"/" 2)]
    (loop [after nil acc nil]
      (let [args (cond-> ["gh" "api" "graphql" "-f" (str "query=" query) "-F" (str "owner=" owner)
                          "-F" (str "name=" name) "-F" (str "pr=" pr)]
                   after (into ["-F" (str "after=" after)]))
            res  (apply p/shell {:out :string :err :string :continue true} args)]
        (when-not (zero? (:exit res))
          (config/config-error! (str "review: gh api graphql failed -- " (str/trim (:err res)))))
        (let [data (:data (json/parse-string (:out res) true))
              pull (get-in data [:repository :pullRequest])
              acc  (-> (or acc {:viewer (get-in data [:viewer :login]) :head (:headRefName pull)
                                :head-oid (:headRefOid pull) :cross-repo? (:isCrossRepository pull) :files []})
                       (update :files into (get-in pull [:files :nodes])))
              {:keys [hasNextPage endCursor]} (get-in pull [:files :pageInfo])]
          (if hasNextPage (recur endCursor acc) acc))))))

;; ---------------------------------------------------------------------------
;; Git

(defn- git [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string :continue true} "git" args))

(defn- git! [root & args]
  (let [res (apply git root args)]
    (when-not (zero? (:exit res)) (throw (ex-info (str "git " (str/join " " args) " failed: " (:err res)) {})))
    (str/trim (:out res))))

(defn commit-problems
  "Reasons --commit must not proceed, checked BEFORE anything is written: the
  branch or commit isn't the PR head GitHub showed, the PR comes from a fork,
  something unrelated is staged, or a doc to change has local edits the
  reviewer never saw."
  [root {:keys [head head-oid cross-repo?]} paths]
  (let [branch (str/trim (:out (git root "rev-parse" "--abbrev-ref" "HEAD")))
        sha    (str/trim (:out (git root "rev-parse" "HEAD")))]
    (cond-> []
      cross-repo?                     (conj "the PR comes from a fork; run it from that repo")
      (not= branch head)              (conj (str "on branch " branch ", but the PR's branch is " head))
      (and head-oid (not= sha head-oid))
      (conj (str "local HEAD " (subs sha 0 (min 7 (count sha))) " isn't the PR head " (subs head-oid 0 7)
                 " that GitHub showed as viewed; pull or push first"))
      (not (zero? (:exit (git root "diff" "--cached" "--quiet"))))
      (conj "other changes are staged; commit or unstage them first")
      true (into (for [p paths :when (not (zero? (:exit (git root "diff" "--quiet" "HEAD" "--" p))))]
                   (str p " has local edits the reviewer didn't see on GitHub"))))))

(defn- push-with-retry! [root]
  (loop [attempt 1]
    (let [res (git root "push")]
      (cond
        (zero? (:exit res)) nil
        (< attempt 3) (do (git! root "pull" "--rebase") (recur (inc attempt)))
        :else (throw (ex-info (str "git push failed after 3 attempts: " (:err res)) {}))))))

;; ---------------------------------------------------------------------------
;; run / -main

(defn run
  "Options: :pr (required), :repo, :target (default \"L2\"), :docs-dir, :schema,
  :root (default \".\"), :now (reviewed_at timestamp), :dry-run, :commit,
  :push, :fetch (fn of opts, for tests). Returns {:exit :changed :plan
  :viewer} and writes files unless :dry-run or a --commit precondition fails."
  [opts]
  (config/catch-config-errors
   (fn []
     (let [root   (or (:root opts) ".")
           {:keys [docs-dir schema-file]} (config/resolve-paths opts)
           sch    (config/read-schema! "review" (str (fs/path root schema-file)))
           target (or (:target opts) "L2")
           _      (when-not (schema/rank sch target) (config/config-error! (str "review: unknown target level " target)))
           now    (or (:now opts) (now-utc))
           gh     ((or (:fetch opts) fetch-github) opts)
           {:keys [viewer files]} gh
           pl     (plan {:schema sch :root root :docs-dir docs-dir :target target :files files})
           note   (str "Viewed by " viewer " in PR #" (:pr opts) " on " (subs now 0 (min 10 (count now))) ".")
           bumped (mapv :path (:bump pl))
           refreshed (mapv :path (:refresh pl))
           changed (into bumped refreshed)
           problems (when (and (:commit opts) (seq changed) (not (:dry-run opts)))
                      (commit-problems root gh changed))]
       (if (seq problems)
         {:exit 1 :changed [] :plan pl :viewer viewer :error (str/join "; " problems)}
         (do
           (when-not (:dry-run opts)
             (doseq [path bumped :let [f (str (fs/path root path))]]
               (spit f (bump sch (slurp f) {:target target :reviewed-at now :note note})))
             (doseq [path refreshed :let [f (str (fs/path root path))]]
               (spit f (bump sch (slurp f) {:reviewed-at now :note note}))))
           (try
             (when (and (:commit opts) (seq changed) (not (:dry-run opts)))
               (apply git! root "add" "--" changed)
               (apply git! root "commit" "-m" (str "Mark docs viewed in PR #" (:pr opts) " as reviewed")
                      "-m" (str "From " viewer "'s Viewed checkboxes (temper.okf.review):\n"
                                (str/join "\n" (concat (map #(str "- raised to " target ": " %) bumped)
                                                       (map #(str "- review refreshed: " %) refreshed))))
                      "--" changed)
               (when (:push opts) (push-with-retry! root)))
             {:exit 0 :changed changed :bumped bumped :refreshed refreshed :plan pl :viewer viewer}
             (catch clojure.lang.ExceptionInfo e
               {:exit 1 :changed changed :plan pl :viewer viewer :error (ex-message e)}))))))))

(defn report [{:keys [plan viewer error dry-run]}]
  (let [would? (or dry-run error)]
    (str (str/join (for [{:keys [path]} (:bump plan)] (str (if would? "would raise " "raised ") path "\n")))
         (str/join (for [{:keys [path]} (:refresh plan)] (str (if would? "would refresh review of " "refreshed review of ") path "\n")))
         (str/join (for [{:keys [path current]} (:already plan)] (str "already " current ", unchanged since review: " path "\n")))
         (str/join (for [{:keys [path]} (:no-frontmatter plan)] (str "skip (no frontmatter): " path "\n")))
         (str/join (for [{:keys [path]} (:reserved plan)] (str "skip (reserved file): " path "\n")))
         (when viewer
           (str (count (:bump plan)) " to raise, " (count (:refresh plan)) " to refresh"
                (cond dry-run " (dry run)" error " (nothing written)" :else "") ", from " viewer "'s Viewed checkboxes\n"))
         (when error (str "error: " error "\n")))))

(defn -main [& args]
  (let [pr    (some->> args (remove #(str/starts-with? % "--")) first parse-long)
        opts  (cli/parse-flags args {:value (merge cli/path-flags {"--target" :target "--repo" :repo})
                                     :bool  {"--dry-run" :dry-run "--commit" :commit "--push" :push}})]
    (if-not pr
      (do (println "usage: bb -m temper.okf.review <pr-number> [--dry-run] [--commit [--push]] [--target L2] [--repo owner/name]")
          (System/exit 2))
      (let [res (run (assoc opts :pr pr))]
        (cli/exit! res (report (assoc res :dry-run (:dry-run opts))))))))
