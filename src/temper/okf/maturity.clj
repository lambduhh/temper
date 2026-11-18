(ns temper.okf.maturity
  "Generate the review-maturity ladder into prose docs from the schema.

  The ladder was previously duplicated as prose in three files, so rewording one
  copy silently desynced the others. `:review-maturity` in the schema is now the
  single source of truth; this namespace renders it into marked regions:

      <!-- review-maturity:table:begin -->
      ...generated...
      <!-- review-maturity:table:end -->

  Two renderings are available:

      table    a full | Level | Label | Meaning | markdown table
      inline   a one-line summary, for a table legend or a sentence

  From a project that depends on this library (see README.md):

      bb -m temper.okf.maturity            # rewrite marked regions in place
      bb -m temper.okf.maturity --check    # exit 1 if any region is stale

  Use --check in CI or a pre-commit hook. Files with no markers are skipped, so
  adding a new target is just a matter of pasting the marker pair into it.

  Target files resolve from the :targets option (--targets a.md,b.md), then
  OKF_MATURITY_TARGETS, then README.md, CLAUDE.md and <docs-dir>/README.md.
  Explicitly listed targets must exist (exit 2 otherwise); the defaults are
  skipped when absent."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [temper.okf.cli :as cli]
            [temper.okf.config :as config]
            [temper.okf.schema :as schema]))

(def ^:private tool
  "Prefix for configuration errors on stderr: this namespace. The original
  script's name, `gen_maturity_table`, named a file only its own repo had."
  "temper.okf.maturity")

(defn- split-targets [v]
  (if (sequential? v)
    (->> v (map (comp str/trim str)) (remove str/blank?) vec)
    (->> (str/split v #",") (map str/trim) (remove str/blank?) vec)))

(defn resolve-targets
  "The files to render into. `opts` :targets (a comma-separated string or a
  collection of paths) wins over `env` OKF_MATURITY_TARGETS, which wins over
  the defaults. Explicit targets that are not files end the run with exit 2."
  ([opts docs-dir] (resolve-targets opts docs-dir (System/getenv)))
  ([opts docs-dir env]
   (let [v (:targets opts)
         explicit (cond
                    (sequential? v) v
                    (not-empty (some-> v str)) (str v)
                    :else (not-empty (get env "OKF_MATURITY_TARGETS")))]
     ;; Files with no marker pair are skipped, so an over-broad target list is
     ;; harmless -- but a copied-in repo will have different prose files.
     (if explicit
       (let [paths (split-targets explicit)
             missing (remove #(.isFile (io/file %)) paths)]
         (when (seq missing)
           (config/config-error!
            (str tool ": target file(s) missing or unreadable: "
                 (str/join ", " missing))))
         paths)
       ["README.md" "CLAUDE.md" (str docs-dir "/README.md")]))))

(defn load-config
  "Resolve paths, read the schema, then resolve targets -- the order the
  original script used, so a bad schema is reported before a bad target list.
  Ends the run via temper.okf.config/config-error! (exit 2)."
  [opts]
  (let [{:keys [docs-dir schema-file]} (config/resolve-paths opts)
        schema (config/read-schema! tool schema-file)]
    {:docs-dir    docs-dir
     :schema-file schema-file
     :levels      (:review-maturity schema)
     :command     (schema/command schema :maturity)
     :targets     (resolve-targets opts docs-dir)}))

(defn render-table
  "The ladder as a | Level | Label | Meaning | markdown table."
  [levels]
  (str/join "\n"
    (concat ["| Level | Label | Meaning |"
             "|---|---|---|"]
            (for [{:keys [level label meaning]} levels]
              (str "| **" level "** | " label " | " meaning " |")))))

(defn render-inline
  "The ladder as a one-line summary, e.g. \"**L0** AI-generated · **L1** ...\"."
  [levels]
  (str/join " · "
    (for [{:keys [level label]} levels]
      (str "**" level "** " label))))

(def renderers
  "Marker kind -> renderer of the ladder."
  {"table" render-table "inline" render-inline})

(defn marker-re [kind]
  (re-pattern (str "(?s)(<!-- review-maturity:" kind ":begin -->\n).*?(\n<!-- review-maturity:"
                   kind ":end -->)")))

(defn apply-kind
  "Replace the marked region for `kind` in `content`. Returns content unchanged
  when the file carries no such marker."
  [levels content kind]
  (let [re (marker-re kind)]
    (if (re-find re content)
      (str/replace content re (fn [[_ open close]]
                                (str open ((renderers kind) levels) close)))
      content)))

(defn transform
  "`content` with every marked region regenerated from `levels`."
  [levels content]
  (reduce (partial apply-kind levels) content (keys renderers)))

(defn run
  "Regenerate the marked regions in each target file. Prints nothing and never
  exits. Without :check, stale files are rewritten in place.

  opts: :docs-dir, :schema (see temper.okf.config/resolve-paths), :targets, and
    :check  true to report stale regions instead of rewriting them

  Returns, on a completed run:
    {:exit         0, or 1 when :check finds a stale file
     :files        [{:path :kinds [..] :stale? :after} ...]  existing targets only;
                   :after is the regenerated content
     :levels       number of levels in the ladder
     :with-markers number of files carrying at least one marker
     :stale        number of stale files (rewritten, unless :check)
     :check ..
     :command      how the FAILED line names this tool (temper.okf.schema/command)}
  or, on unreadable configuration, {:exit 2 :config-errors [line ...]}."
  ([] (run {}))
  ([opts]
   (config/catch-config-errors
    (fn []
      (let [{:keys [levels targets command]} (load-config opts)
            check? (boolean (:check opts))
            files  (vec (for [path targets
                              :let [f (io/file path)]
                              :when (.exists f)
                              :let [before (slurp f)
                                    after  (transform levels before)
                                    kinds  (vec (keep #(when (re-find (marker-re %) before) %)
                                                      (sort (keys renderers))))]]
                          {:path path :stale? (not= before after) :kinds kinds :after after}))
            stale  (count (filter :stale? files))]
        (when-not check?
          (doseq [{:keys [path stale? after]} files :when stale?]
            (spit path after)))
        {:exit         (if (and check? (pos? stale)) 1 0)
         :files        files
         :levels       (count levels)
         :with-markers (count (filter #(seq (:kinds %)) files))
         :stale        stale
         :check        check?
         :command      command})))))

(defn report
  "The text the original script printed to stdout for `result`. Empty for a
  configuration error, whose lines go to stderr instead."
  [{:keys [exit files levels with-markers stale check command]}]
  (if (= 2 exit)
    ""
    (with-out-str
      (doseq [{:keys [path stale? kinds]} files]
        (cond
          (empty? kinds) (println "skip  " path "(no markers)")
          (not stale?)   (println "ok    " path (str "(" (str/join ", " kinds) ")"))
          check          (println "STALE " path (str "(" (str/join ", " kinds) ")"))
          :else          (println "wrote " path (str "(" (str/join ", " kinds) ")"))))
      (println)
      (printf "%d level(s) · %d file(s) with markers · %d %s\n"
              levels with-markers stale (if check "stale" "rewritten"))
      (if (and check (pos? stale))
        (println (str "FAILED — run `" command "` and commit the result"))
        (println "OK")))))

(defn -main
  "CLI entry point. Flags: --check, --targets a.md,b.md, --docs-dir DIR,
  --schema FILE. Prints the report and exits 0, 1 (--check found stale files)
  or 2 (unreadable configuration)."
  [& args]
  (let [result (run (cli/parse-flags args {:value (assoc cli/path-flags "--targets" :targets)
                                           :bool  {"--check" :check}}))]
    (cli/exit! result (report result))))
