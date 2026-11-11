(ns temper.okf.drift
  "Lower the review level of docs that have changed too much since review.

  A review covers one version of a document. For each doc at or above the
  schema's :review-staleness :from level (default L2), the reviewed version is
  the most recent commit that changed its `reviewed_at` line. That covers
  reviews by hand and by temper.okf.review (Viewed), and is found from history
  each run, so it survives squash merges and rebases.

  The current body is compared with the reviewed body by WORDS, frontmatter
  excluded on both sides: (words deleted + words inserted) / words at review,
  capped at 1. Words rather than lines, because a Markdown paragraph is often
  one long line, and reflowing it is not a change.

  At or above :review-drift :changed-fraction, review_maturity is rewritten to
  :review-drift :to (e.g. L1) and review_note says why. Ticking Viewed on the
  new version, or reviewing it by hand, raises it again and sets a new
  baseline.

  Exit codes: 0 done (with :check, nothing to lower) · 1 (:check only) docs
  would be lowered · 2 unreadable configuration."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [temper.okf.cli :as cli]
            [temper.okf.config :as config]
            [temper.okf.review :as review]
            [temper.okf.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Words

(defn words [s] (vec (remove str/blank? (str/split (str s) #"\s+"))))

(defn- trim-common
  "Drop the shared prefix and suffix: most edits are local, so this removes
  most of the text before the diff runs."
  [a b]
  (let [n (min (count a) (count b))
        pre (loop [i 0] (if (and (< i n) (= (a i) (b i))) (recur (inc i)) i))
        a (subvec a pre) b (subvec b pre)
        n (min (count a) (count b))
        suf (loop [i 0] (if (and (< i n) (= (a (- (count a) 1 i)) (b (- (count b) 1 i)))) (recur (inc i)) i))]
    [(subvec a 0 (- (count a) suf)) (subvec b 0 (- (count b) suf))]))

(defn edit-distance
  "Insertions + deletions needed to turn `a` into `b` (Myers' O((N+M)D)
  algorithm), so a substituted word counts 2. With `max-d`, gives up and
  returns nil once the distance is known to exceed it: Myers' cost grows with
  D, and the caller only needs to know whether a threshold was crossed."
  ([a b] (edit-distance a b nil))
  ([a b max-d]
  (let [[a b] (trim-common (vec a) (vec b)) n (count a) m (count b) mx (+ n m)]
    (if (zero? mx)
      0
      (let [off mx v (int-array (inc (* 2 mx)))]
        (loop [d 0]
          (let [found
                (loop [k (- d)]
                  (when (<= k d)
                    (let [x (if (or (= k (- d)) (and (not= k d) (< (aget v (+ off k -1)) (aget v (+ off k 1)))))
                              (aget v (+ off k 1))
                              (inc (aget v (+ off k -1))))
                          x (loop [x x] (let [y (- x k)] (if (and (< x n) (< y m) (= (a x) (b y))) (recur (inc x)) x)))]
                      (aset v (+ off k) (int x))
                      (if (and (>= x n) (>= (- x k) m)) d (recur (+ k 2))))))]
            (cond found found
                  (and max-d (>= d max-d)) nil
                  :else (recur (inc d))))))))))

(defn changed-fraction
  "Share of the reviewed text that changed, by words, capped at 1.0."
  [before after]
  (let [a (words before) b (words after)]
    (if (empty? a)
      (if (empty? b) 0.0 1.0)
      (min 1.0 (double (/ (edit-distance a b) (count a)))))))

(defn drift
  "{:fraction f :at-least? bool} against `threshold`. Below the threshold the
  fraction is exact; at or above it the search stops early and :fraction is
  the threshold, flagged :at-least?."
  [before after threshold]
  (let [a (words before) b (words after)]
    (if (empty? a)
      {:fraction (if (empty? b) 0.0 1.0) :at-least? false}
      (let [limit (dec (long (Math/ceil (* threshold (count a)))))
            ;; Bag-of-words lower bound: a word that is in only one version must be
            ;; inserted or deleted, so D >= |a| + |b| - 2 * |shared words|. O(n),
            ;; and decisive for rewrites, so Myers only runs on near misses.
            fa (frequencies a) fb (frequencies b)
            shared (reduce-kv (fn [s w c] (+ s (min c (get fb w 0)))) 0 fa)
            lower  (- (+ (count a) (count b)) (* 2 shared))
            d     (when (<= lower limit) (edit-distance a b (max 0 limit)))]
        (if d
          {:fraction (min 1.0 (double (/ d (count a)))) :at-least? false}
          {:fraction threshold :at-least? true})))))

(defn body
  "Content without its leading --- frontmatter block."
  [content]
  (if-let [[_ rest] (review/frontmatter-split content)] rest content))

(defn- fm-value [content k]
  (some->> (review/frontmatter-split content) first
           (re-find (re-pattern (str "(?m)^" k ":\\s*(.*)$"))) second str/trim))

;; ---------------------------------------------------------------------------
;; Git

(defn- git [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string :continue true} "git" args))

(defn baseline
  "{:sha :content} of the most recent commit that changed `path`'s
  reviewed_at line, or nil."
  [root path]
  (let [sha (str/trim (:out (git root "log" "-1" "--format=%H" "-G" "^reviewed_at:" "--" path)))]
    (when (seq sha)
      (let [res (git root "show" (str sha ":" path))]
        (when (zero? (:exit res)) {:sha sha :content (:out res)})))))

;; ---------------------------------------------------------------------------
;; run / -main

(defn- load-drift-config! [sch schema-file]
  (when-not (contains? sch :review-drift)
    (config/config-error! (str "drift: " schema-file " has no :review-drift key."
                               " Add e.g. :review-drift {:changed-fraction 0.2 :to \"L1\"}.")))
  (let [{:keys [changed-fraction to]} (:review-drift sch)]
    (when-not (and (number? changed-fraction) (< 0 changed-fraction 1))
      (config/config-error! "drift: :review-drift :changed-fraction must be a number between 0 and 1"))
    (when-not (review/rank sch to)
      (config/config-error! (str "drift: :review-drift :to is not a level on :review-maturity: " to)))
    {:threshold changed-fraction :to to}))

(defn- pct [x] (str (Math/round (* 100.0 x)) "%"))

(defn run
  "Options: :root (default \".\"), :docs-dir, :schema, :check, :today.
  Returns {:exit :lowered [{:path :fraction :sha}] :checked [...] :no-baseline
  [...] :pending [...]} and rewrites lowered docs unless :check."
  [opts]
  (config/catch-config-errors
   (fn []
     (let [root (or (:root opts) ".")
           {:keys [docs-dir schema-file]} (config/resolve-paths opts)
           sch  (config/read-schema! "drift" (str (fs/path root schema-file)))
           {:keys [threshold to]} (load-drift-config! sch schema-file)
           floor (or (review/rank sch (get-in sch [:review-staleness :from] "L2")) 2)
           today (or (:today opts) (str (java.time.LocalDate/now)))
           docs  (->> (fs/glob (fs/path root docs-dir) "**.md")
                      (map #(str (fs/relativize root %))) sort)
           acc   (reduce
                  (fn [acc path]
                    (let [content (slurp (str (fs/path root path)))
                          level   (review/current-maturity content)
                          r       (some->> level (review/rank sch))]
                      (if (or (nil? r) (< r floor))
                        acc
                        (if-let [{:keys [sha] :as b} (baseline root path)]
                          (if (not= (fm-value content "reviewed_at") (fm-value (:content b) "reviewed_at"))
                            (update acc :pending conj {:path path}) ; re-reviewed, not yet committed
                            (let [{f :fraction :keys [at-least?]} (drift (body (:content b)) (body content) threshold)
                                  entry {:path path :fraction f :at-least? at-least? :sha (subs sha 0 7)}]
                              (if (>= f threshold)
                                (update acc :lowered conj entry)
                                (update acc :checked conj entry))))
                          (update acc :no-baseline conj {:path path})))))
                  {:lowered [] :checked [] :no-baseline [] :pending []}
                  docs)]
       (when-not (:check opts)
         (doseq [{:keys [path fraction sha at-least?]} (:lowered acc)
                 :let [f (str (fs/path root path)) c (slurp f)
                       [fm rest] (review/frontmatter-split c)
                       note (str "Lowered to " to " on " today ": " (when at-least? "at least ") (pct fraction)
                                 " of words changed since the review at " sha
                                 " (threshold " (pct threshold) ").")
                       fm (-> fm
                              (review/set-key "review_maturity" (schema/canonical-maturity sch to))
                              (review/set-key "review_note" (review/yaml-str note)))]]
           (spit f (str "---\n" fm "\n---\n" rest))))
       (assoc acc :exit (if (and (:check opts) (seq (:lowered acc))) 1 0)
                  :check (:check opts) :threshold threshold :to to)))))

(defn report [{:keys [lowered checked no-baseline pending check threshold to]}]
  (str (str/join (for [{:keys [path fraction sha at-least?]} lowered]
                   (str (if check "would lower " "lowered ") path " to " to ": " (when at-least? "at least ") (pct fraction)
                        " of words changed since " sha "\n")))
       (str/join (for [{:keys [path fraction]} checked] (str "ok " path " (" (pct fraction) " changed)\n")))
       (str/join (for [{:keys [path]} no-baseline] (str "skip (no commit set reviewed_at yet): " path "\n")))
       (str/join (for [{:keys [path]} pending] (str "skip (reviewed_at changed, not committed): " path "\n")))
       (when threshold
         (str (count lowered) " doc(s) " (if check "would be " "") "lowered (threshold " (pct threshold) ")\n"))))

(defn -main [& args]
  (let [opts (cli/parse-flags args {:value cli/path-flags :bool {"--check" :check}})
        res  (run opts)]
    (cli/exit! res (report res))))
