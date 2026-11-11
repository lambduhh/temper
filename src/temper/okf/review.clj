(ns temper.okf.review
  "Raise the review level of docs that a reviewer has marked 'Viewed' in a
  GitHub pull request.

  GitHub records a per-file 'Viewed' checkbox (GraphQL viewerViewedState). It
  is visible only to the person who ticked it, and no webhook fires when it
  changes, so this runs with the reviewer's own gh login: locally, or from a
  GitHub Action holding the reviewer's token.

  For each VIEWED Markdown file under the docs dir whose review_maturity is
  below the target (default L2), it rewrites three frontmatter lines:
  review_maturity, reviewed_at (today) and review_note (who, which PR). It never
  lowers a level. A file changed after it was viewed shows as DISMISSED, not
  VIEWED, so only the version the reviewer saw is raised.

  Exit codes: 0 done (including nothing to do) · 1 a git step failed ·
  2 unreadable configuration or a GitHub query failure."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [temper.okf.cli :as cli]
            [temper.okf.config :as config]
            [temper.okf.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Pure parts

(defn rank
  "Position of a review_maturity value on the schema's ladder, or nil."
  [schema value]
  (when-let [[level] (schema/parse-maturity value)]
    (first (keep-indexed (fn [i {l :level}] (when (= l level) i)) (schema/maturity-levels schema)))))

(defn yaml-str [s] (str "\"" (str/replace (str s) "\"" "\\\"") "\""))

(defn set-key
  "Replace `key: ...` in frontmatter text, or append it."
  [fm k v]
  (let [re (re-pattern (str "(?m)^" k ":.*$"))]
    (if (re-find re fm)
      (str/replace fm re (str/re-quote-replacement (str k ": " v)))
      (str (str/trimr fm) "\n" k ": " v))))

(defn frontmatter-split
  "[frontmatter-text rest] for content starting with a --- block, else nil."
  [content]
  (when (str/starts-with? content "---\n")
    (when-let [idx (str/index-of content "\n---\n" 3)]
      [(subs content 4 idx) (subs content (+ idx 5))])))

(defn current-maturity [content]
  (some->> (frontmatter-split content) first (re-find #"(?m)^review_maturity:\s*(.*)$") second str/trim))

(defn bump
  "Content with review_maturity, reviewed_at and review_note set. Only the
  frontmatter is edited; key order is kept and missing keys are appended."
  [schema content {:keys [target today note]}]
  (let [[fm body] (frontmatter-split content)
        fm (-> fm
               (set-key "review_maturity" (schema/canonical-maturity schema target))
               (set-key "reviewed_at" today)
               (set-key "review_note" (yaml-str note)))]
    (str "---\n" fm "\n---\n" body)))

(defn plan
  "Sort the PR's files into :bump, :already (at or above target),
  :no-frontmatter and :reserved. Only VIEWED .md files under docs-dir are
  considered. Reserved files (the schema's :reserved-files, e.g. index.md) are
  never raised: OKF lets the bundle root carry only okf_version."
  [{:keys [schema root docs-dir target files]}]
  (let [prefix (str (str/replace docs-dir #"/+$" "") "/")
        target-rank (rank schema target)
        reserved    (set (:reserved-files schema))]
    (reduce (fn [acc {:keys [path viewerViewedState]}]
              (let [f (fs/path root path)]
                (if-not (and (= "VIEWED" viewerViewedState) (str/starts-with? path prefix)
                             (str/ends-with? path ".md") (fs/regular-file? f))
                  acc
                  (let [content (slurp (str f)) current (current-maturity content)]
                    (cond
                      (reserved (fs/file-name path))               (update acc :reserved conj {:path path})
                      (nil? (frontmatter-split content))           (update acc :no-frontmatter conj {:path path})
                      (and current (some-> (rank schema current) (>= target-rank))) (update acc :already conj {:path path :current current})
                      :else (update acc :bump conj {:path path :current current}))))))
            {:bump [] :already [] :no-frontmatter [] :reserved []}
            files)))

;; ---------------------------------------------------------------------------
;; GitHub

(def ^:private query
  "query($owner:String!,$name:String!,$pr:Int!,$after:String){viewer{login}
   repository(owner:$owner,name:$name){pullRequest(number:$pr){headRefName
   files(first:100,after:$after){pageInfo{hasNextPage endCursor} nodes{path viewerViewedState}}}}}")

(defn- current-repo []
  (-> (p/shell {:out :string :err :string} "gh" "repo" "view" "--json" "nameWithOwner" "--jq" ".nameWithOwner")
      :out str/trim))

(defn fetch-github
  "{:viewer login :head branch :files [{:path :viewerViewedState}]} for PR `pr`
  in `repo` (\"owner/name\"; default: the current directory's repo), via gh."
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
              acc  (-> (or acc {:viewer (get-in data [:viewer :login]) :head (:headRefName pull) :files []})
                       (update :files into (get-in pull [:files :nodes])))
              {:keys [hasNextPage endCursor]} (get-in pull [:files :pageInfo])]
          (if hasNextPage (recur endCursor acc) acc))))))

;; ---------------------------------------------------------------------------
;; run / -main

(defn- git! [root & args]
  (let [res (apply p/shell {:dir (str root) :out :string :err :string :continue true} "git" args)]
    (when-not (zero? (:exit res)) (throw (ex-info (str "git " (str/join " " args) " failed: " (:err res)) {})))
    (str/trim (:out res))))

(defn run
  "Options: :pr (required), :repo, :target (default \"L2\"), :docs-dir, :schema,
  :root (default \".\"), :today, :dry-run, :commit, :push, :fetch (fn of opts,
  for tests). Returns {:exit :changed :plan :viewer} and writes files unless
  :dry-run."
  [opts]
  (config/catch-config-errors
   (fn []
     (let [root   (or (:root opts) ".")
           {:keys [docs-dir schema-file]} (config/resolve-paths opts)
           sch    (config/read-schema! "review" (str (fs/path root schema-file)))
           target (or (:target opts) "L2")
           _      (when-not (rank sch target) (config/config-error! (str "review: unknown target level " target)))
           today  (or (:today opts) (str (java.time.LocalDate/now)))
           {:keys [viewer head files]} ((or (:fetch opts) fetch-github) opts)
           pl     (plan {:schema sch :root root :docs-dir docs-dir :target target :files files})
           note   (str "Viewed by " viewer " in PR #" (:pr opts) " on " today ".")
           changed (mapv :path (:bump pl))]
       (when-not (:dry-run opts)
         (doseq [path changed :let [f (str (fs/path root path))]]
           (spit f (bump sch (slurp f) {:target target :today today :note note}))))
       (try
         (when (and (:commit opts) (seq changed) (not (:dry-run opts)))
           (let [branch (git! root "rev-parse" "--abbrev-ref" "HEAD")]
             (when (not= branch head)
               (throw (ex-info (str "on branch " branch ", but PR #" (:pr opts) " is " head) {})))
             (apply git! root "add" "--" changed)
             (git! root "commit" "-m" (str "Mark docs viewed in PR #" (:pr opts) " as " target)
                   "-m" (str "Raised by temper.okf.review from " viewer "'s Viewed checkboxes:\n"
                             (str/join "\n" (map #(str "- " %) changed))))
             (when (:push opts) (git! root "push"))))
         {:exit 0 :changed changed :plan pl :viewer viewer}
         (catch clojure.lang.ExceptionInfo e
           {:exit 1 :changed changed :plan pl :viewer viewer :error (ex-message e)}))))))

(defn report [{:keys [changed plan viewer error dry-run]}]
  (str (str/join (for [p changed] (str (if dry-run "would raise " "raised ") p "\n")))
       (str/join (for [{:keys [path current]} (:already plan)] (str "already " current ": " path "\n")))
       (str/join (for [{:keys [path]} (:no-frontmatter plan)] (str "skip (no frontmatter): " path "\n")))
       (str/join (for [{:keys [path]} (:reserved plan)] (str "skip (reserved file): " path "\n")))
       (when viewer (str (count changed) " doc(s) " (if dry-run "would be " "") "raised, from " viewer "'s Viewed checkboxes\n"))
       (when error (str "error: " error "\n"))))

(defn -main [& args]
  (let [pr    (some->> args (remove #(str/starts-with? % "--")) first parse-long)
        opts  (cli/parse-flags args {:value (merge cli/path-flags {"--target" :target "--repo" :repo})
                                     :bool  {"--dry-run" :dry-run "--commit" :commit "--push" :push}})]
    (if-not pr
      (do (println "usage: bb -m temper.okf.review <pr-number> [--dry-run] [--commit [--push]] [--target L2] [--repo owner/name]")
          (System/exit 2))
      (let [res (run (assoc opts :pr pr))]
        (cli/exit! res (report (assoc res :dry-run (:dry-run opts))))))))
