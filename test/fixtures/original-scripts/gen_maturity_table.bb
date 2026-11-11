#!/usr/bin/env bb
(ns tools.gen-maturity-table
  "Generate the review-maturity ladder into prose docs from docs/metadata-schema.edn.

  The ladder was previously duplicated as prose in three files, so rewording one
  copy silently desynced the others. `:review-maturity` in the schema is now the
  single source of truth; this script renders it into marked regions:

      <!-- review-maturity:table:begin -->
      ...generated...
      <!-- review-maturity:table:end -->

  Two renderings are available:

      table    a full | Level | Label | Meaning | markdown table
      inline   a one-line summary, for a table legend or a sentence

  Run from the repo root:

      bb tools/gen_maturity_table.bb            # rewrite marked regions in place
      bb tools/gen_maturity_table.bb --check    # exit 1 if any region is stale

  Use --check in CI or a pre-commit hook. Files with no markers are skipped, so
  adding a new target is just a matter of pasting the marker pair into it."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(def docs-dir    (or (not-empty (System/getenv "OKF_DOCS_DIR")) "docs"))
(def schema-file (or (not-empty (System/getenv "OKF_SCHEMA"))
                     (str docs-dir "/metadata-schema.edn")))

(defn- read-schema! [path]
  (try
    (edn/read-string (slurp path))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "gen_maturity_table: cannot read schema at " path
                      " -- " (.getMessage e))))
      (System/exit 2))))

(def schema (read-schema! schema-file))
(def levels (:review-maturity schema))

;; Files with no marker pair are skipped, so an over-broad target list is
;; harmless -- but a copied-in repo will have different prose files.
(def targets
  (if-let [env (not-empty (System/getenv "OKF_MATURITY_TARGETS"))]
    (let [paths (->> (str/split env #",") (map str/trim) (remove str/blank?) vec)
          missing (remove #(.isFile (io/file %)) paths)]
      (when (seq missing)
        (binding [*out* *err*]
          (println (str "gen_maturity_table: target file(s) missing or unreadable: "
                        (str/join ", " missing))))
        (System/exit 2))
      paths)
    ["README.md" "CLAUDE.md" (str docs-dir "/README.md")]))

(defn render-table []
  (str/join "\n"
    (concat ["| Level | Label | Meaning |"
             "|---|---|---|"]
            (for [{:keys [level label meaning]} levels]
              (str "| **" level "** | " label " | " meaning " |")))))

(defn render-inline []
  (str/join " · "
    (for [{:keys [level label]} levels]
      (str "**" level "** " label))))

(def renderers {"table" render-table "inline" render-inline})

(defn marker-re [kind]
  (re-pattern (str "(?s)(<!-- review-maturity:" kind ":begin -->\n).*?(\n<!-- review-maturity:"
                   kind ":end -->)")))

(defn apply-kind
  "Replace the marked region for `kind` in `content`. Returns content unchanged
  when the file carries no such marker."
  [content kind]
  (let [re (marker-re kind)]
    (if (re-find re content)
      (str/replace content re (fn [[_ open close]]
                                (str open ((renderers kind)) close)))
      content)))

(defn transform [content]
  (reduce apply-kind content (keys renderers)))

(defn run [args]
  (let [check? (some #{"--check"} args)
        results (for [path targets
                      :let [f (io/file path)]
                      :when (.exists f)
                      :let [before (slurp f)
                            after  (transform before)
                            kinds  (keep #(when (re-find (marker-re %) before) %)
                                         (sort (keys renderers)))]]
                  {:path path :stale? (not= before after) :kinds kinds :after after})]
    (doseq [{:keys [path stale? kinds after]} results]
      (cond
        (empty? kinds) (println "skip  " path "(no markers)")
        (not stale?)   (println "ok    " path (str "(" (str/join ", " kinds) ")"))
        check?         (println "STALE " path (str "(" (str/join ", " kinds) ")"))
        :else          (do (spit path after)
                           (println "wrote " path (str "(" (str/join ", " kinds) ")")))))
    (let [stale (filter :stale? results)
          none  (remove #(seq (:kinds %)) results)]
      (println)
      (printf "%d level(s) · %d file(s) with markers · %d %s\n"
              (count levels)
              (- (count results) (count none))
              (count stale)
              (if check? "stale" "rewritten"))
      (cond
        (and check? (seq stale))
        (do (println "FAILED — run `bb tools/gen_maturity_table.bb` and commit the result") 1)
        check? (do (println "OK") 0)
        :else  (do (println "OK") 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (run *command-line-args*)))
