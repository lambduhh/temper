(ns temper.okf.history
  "Find the reviewed version of a document in git history.

  The reviewed version is the commit that introduced the doc's current
  `reviewed_at` value: walk the doc's history (following renames) from newest
  to oldest while the frontmatter value of reviewed_at stays equal to the one
  in the working tree; the oldest commit in that run is the baseline. Values
  are compared parsed (trimmed, unquoted), so reformatting the line, or a body
  line that happens to start with `reviewed_at:`, does not move the baseline.

  Limits: a squash merge makes the squash commit the baseline, so edits made on
  the branch after the review are absorbed. Run drift on the branch (e.g. on
  every PR push) before merging."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [temper.okf.frontmatter :as fm]))

(defn- git [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string :continue true} "git" args))

(defn check-repo!
  "Nil if `root` is inside a git work tree with full history, else a message."
  [root]
  (let [inside (git root "rev-parse" "--is-inside-work-tree")]
    (cond
      (not (and (zero? (:exit inside)) (= "true" (str/trim (:out inside)))))
      (str root " is not inside a git work tree")

      (= "true" (str/trim (:out (git root "rev-parse" "--is-shallow-repository"))))
      (str "the repository is a shallow clone, so the reviewed version can't be found;"
           " fetch full history (e.g. actions/checkout with fetch-depth: 0)"))))

(defn- commits-with-paths
  "[[sha path-at-sha] ...] newest first, following renames. Paths are relative
  to the repository top level, as `git show sha:path` expects."
  [root path]
  (let [out (:out (git root "log" "--follow" "--format=@%H" "--name-only" "--" path))]
    (->> (str/split out #"\n@")
         (map #(str/replace % #"\A@" ""))
         (map str/split-lines)
         (keep (fn [[sha & paths]]
                 (when-let [p (first (remove str/blank? paths))] [(str/trim sha) (str/trim p)]))))))

(defn- show [root sha path]
  (let [res (git root "show" (str sha ":" path))]
    (when (zero? (:exit res)) (:out res))))

(defn baseline
  "{:status :ok :sha :content} for the commit that introduced the working
  tree's reviewed_at value; {:status :pending} when the working tree's value
  isn't committed yet; {:status :none} when there is no reviewed_at, or no
  commit has it."
  [root path content]
  (let [target (fm/value content :reviewed_at)]
    (if-not target
      {:status :none}
      (let [history (commits-with-paths root path)]
        (if (empty? history)
          {:status :none}
          (let [[[head-sha head-path]] history
                head-content (show root head-sha head-path)]
            (if (not= target (fm/value head-content :reviewed_at))
              {:status :pending}
              (loop [[[sha p] & more] history best {:sha head-sha :content head-content}]
                (if-not sha
                  (assoc best :status :ok)
                  (let [c (show root sha p)]
                    (if (= target (fm/value c :reviewed_at))
                      (recur more {:sha sha :content c})
                      (assoc best :status :ok))))))))))))
