(ns temper.okf.drift
  "Lower the review level of docs that have changed too much since review.

  A review covers one version of a document. For each doc at or above the
  schema's :review-staleness :from level (default L2), the reviewed version is
  the commit that introduced its current `reviewed_at` value (see
  temper.okf.history: renames are followed, and values are compared parsed, so
  reformatting the line or a body line starting with `reviewed_at:` doesn't
  move it). Reviews by hand and by temper.okf.review (Viewed) both set it.

  The current body is compared with the reviewed body by WORDS, frontmatter
  excluded on both sides: (words deleted + words inserted) / words at review,
  capped at 1. Words rather than lines, because a Markdown paragraph is often
  one long line, and reflowing it is not a change.

  At or above :review-drift :changed-fraction, review_maturity is rewritten to
  :review-drift :to (which must be below the floor) and review_note says why.
  Ticking Viewed on the new version (temper.okf.review) raises it again, and
  its new reviewed_at becomes the next baseline.

  Run it on a branch before merging: after a squash merge the squash commit is
  the baseline, so drift made on the branch after review is absorbed.

  Exit codes: 0 done (with :check, nothing to lower) · 1 (:check only) docs
  would be lowered · 2 unreadable configuration, not a git work tree, or a
  shallow clone."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [temper.okf.cli :as cli]
            [temper.okf.config :as config]
            [temper.okf.frontmatter :as fm]
            [temper.okf.history :as history]
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

(defn- first-lowering-distance
  "Smallest d with (>= (double (/ d n)) t): the same comparison the lowering
  decision uses, so floating-point rounding of t*n can't make them disagree."
  [t n]
  (loop [k (max 0 (long (Math/ceil (* t n))))]
    (cond (and (pos? k) (>= (double (/ (dec k) n)) t)) (recur (dec k))
          (< (double (/ k n)) t) (recur (inc k))
          :else k)))

(defn drift
  "{:fraction f :at-least? bool} against `threshold`. Below the threshold the
  fraction is exact; at or above it the search may stop early, in which case
  :fraction is the threshold and :at-least? is true."
  [before after threshold]
  (let [a (words before) b (words after)]
    (if (empty? a)
      {:fraction (if (empty? b) 0.0 1.0) :at-least? false}
      (let [limit (dec (first-lowering-distance threshold (count a)))
            ;; Bag-of-words lower bound: a word that is in only one version must be
            ;; inserted or deleted, so D >= |a| + |b| - 2 * |shared words|. O(n),
            ;; and decisive for rewrites, so Myers only runs on near misses.
            fa (frequencies a) fb (frequencies b)
            shared (reduce-kv (fn [s w c] (+ s (min c (get fb w 0)))) 0 fa)
            lower  (- (+ (count a) (count b)) (* 2 shared))
            d      (when (<= lower limit) (edit-distance a b (max 0 limit)))]
        (if d
          {:fraction (min 1.0 (double (/ d (count a)))) :at-least? false}
          {:fraction threshold :at-least? true})))))

(defn- lowered? [{:keys [fraction at-least?]} threshold]
  (or at-least? (>= fraction threshold)))

;; ---------------------------------------------------------------------------
;; run / -main

(defn- load-drift-config! [sch schema-file floor]
  (when-not (contains? sch :review-drift)
    (config/config-error! (str "drift: " schema-file " has no :review-drift key."
                               " Add e.g. :review-drift {:changed-fraction 0.2 :to \"L1\"}.")))
  (let [{:keys [changed-fraction to]} (:review-drift sch)]
    (when-not (and (number? changed-fraction) (< 0 changed-fraction 1))
      (config/config-error! "drift: :review-drift :changed-fraction must be a number between 0 and 1"))
    (when-not (schema/rank sch to)
      (config/config-error! (str "drift: :review-drift :to is not a level on :review-maturity: " to)))
    (when-not (< (schema/rank sch to) floor)
      (config/config-error! (str "drift: :review-drift :to (" to ") must be below :review-staleness :from,"
                                 " or a lowered doc would stay in scope and be lowered again on every run")))
    {:threshold (double changed-fraction) :to to}))

(defn- changed-paths
  "Paths (relative to root) changed between `ref` and the working tree."
  [root ref]
  (let [res (p/shell {:dir (str root) :out :string :err :string :continue true}
                     "git" "diff" "--name-only" "--relative" (str ref "...HEAD"))
        wt  (p/shell {:dir (str root) :out :string :err :string :continue true}
                     "git" "diff" "--name-only" "--relative" "HEAD")]
    (when-not (zero? (:exit res))
      (config/config-error! (str "drift: can't diff against " ref " -- " (str/trim (:err res)))))
    (set (remove str/blank? (concat (str/split-lines (:out res)) (str/split-lines (:out wt)))))))

(defn- pct [x] (str (Math/round (* 100.0 (double x))) "%"))

(defn run
  "Options: :root (default \".\"), :docs-dir, :schema, :check, :changed-since
  (a git ref: only docs changed since it are considered), :today.
  Returns {:exit :lowered [{:path :fraction :at-least? :sha}] :checked [...]
  :no-baseline [...] :pending [...]} and rewrites lowered docs unless :check."
  [opts]
  (config/catch-config-errors
   (fn []
     (let [root (or (:root opts) ".")
           {:keys [docs-dir schema-file]} (config/resolve-paths opts)
           sch   (config/read-schema! "drift" (str (fs/path root schema-file)))
           floor (or (schema/rank sch (get-in sch [:review-staleness :from] "L2")) 2)
           {:keys [threshold to]} (load-drift-config! sch schema-file floor)
           _     (when-let [problem (history/check-repo! root)] (config/config-error! (str "drift: " problem)))
           only  (some->> (:changed-since opts) (changed-paths root))
           today (or (:today opts) (str (java.time.LocalDate/now)))
           docs  (->> (fs/glob (fs/path root docs-dir) "**.md")
                      (map #(str (fs/relativize root %))) sort
                      (filter #(or (nil? only) (only %))))
           acc   (reduce
                  (fn [acc path]
                    (let [content (slurp (str (fs/path root path)))
                          r       (some->> (fm/value content :review_maturity) (schema/rank sch))]
                      (if (or (nil? r) (< r floor))
                        acc
                        (let [{:keys [status sha] b :content} (history/baseline root path content)]
                          (case status
                            :pending (update acc :pending conj {:path path})
                            :none    (update acc :no-baseline conj {:path path})
                            :ok (let [res (drift (fm/body b) (fm/body content) threshold)
                                      entry (assoc res :path path :sha (subs sha 0 7))]
                                  (if (lowered? res threshold)
                                    (update acc :lowered conj entry)
                                    (update acc :checked conj entry))))))))
                  {:lowered [] :checked [] :no-baseline [] :pending []}
                  docs)]
       (when-not (:check opts)
         (doseq [{:keys [path fraction sha at-least?]} (:lowered acc)
                 :let [f (str (fs/path root path))
                       note (str "Lowered to " to ": " (when at-least? "at least ") (pct fraction)
                                 " of words changed since the review at " sha
                                 " (threshold " (pct threshold) ").")]]
           ;; Under OKF 0.2, `verified` alone decides the trust tier a consumer
           ;; sees (§5.3). The review it records no longer covers this version,
           ;; so it goes; review_note and git history keep what happened.
           (spit f (cond-> (fm/set-keys (slurp f) [["review_maturity" (schema/canonical-maturity sch to)]
                                                   ["review_note" (fm/yaml-str note)]])
                     (schema/okf-v02? sch) (fm/remove-keys ["verified"])))))
       (assoc acc :exit (if (and (:check opts) (seq (:lowered acc))) 1 0)
                  :check (:check opts) :threshold threshold :to to :today today)))))

(defn report [{:keys [lowered checked no-baseline pending check threshold to]}]
  (str (str/join (for [{:keys [path fraction sha at-least?]} lowered]
                   (str (if check "would lower " "lowered ") path " to " to ": " (when at-least? "at least ") (pct fraction)
                        " of words changed since " sha "\n")))
       (str/join (for [{:keys [path fraction]} checked] (str "ok " path " (" (pct fraction) " changed)\n")))
       (str/join (for [{:keys [path]} no-baseline] (str "skip (no commit has its reviewed_at yet): " path "\n")))
       (str/join (for [{:keys [path]} pending] (str "skip (reviewed_at changed but not committed): " path "\n")))
       (when threshold
         (str (count lowered) " doc(s) " (if check "would be " "") "lowered (threshold " (pct threshold) ")\n"))))

(defn -main [& args]
  (let [opts (cli/parse-flags args {:value (merge cli/path-flags {"--changed-since" :changed-since})
                                    :bool {"--check" :check}})
        res  (run opts)]
    (cli/exit! res (report res))))
