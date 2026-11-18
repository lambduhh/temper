(ns temper.okf.schema
  "Views derived from a metadata schema (an EDN map such as
  examples/metadata-schema.edn) that more than one tool needs. Pure functions
  of the schema value; nothing is read or cached at load time."
  (:require [clojure.string :as str]))

;; :review-maturity is an ordered vector of {:level :label :meaning}. Derive the
;; legal value set from it rather than keeping a second set in the schema, so the
;; ladder documented in the READMEs and the ladder enforced here cannot diverge.

(defn maturity-levels
  "The ordered review-maturity ladder, least to most reviewed."
  [schema]
  (:review-maturity schema))

(defn maturity-values
  "The set of legal level codes, e.g. #{\"L0\" ... \"L4\"}."
  [schema]
  (into #{} (map :level) (maturity-levels schema)))

(defn maturity-hint
  "Level codes joined for an error message, e.g. \"L0/L1/L2/L3/L4\"."
  [schema]
  (str/join "/" (map :level (maturity-levels schema))))

(defn maturity-by-level
  "Map of level code to its ladder entry. If a code appears twice, the later
  entry wins."
  [schema]
  (into {} (map (juxt :level identity)) (maturity-levels schema)))

(defn canonical-maturity
  "The form we want authors to write: code plus its label, so a reader does not
  have to look the code up. e.g. \"L2 (Human-reviewed)\".

  Shared by temper.okf.validate and temper.okf.new-doc, so the scaffolder writes exactly the
  label the validator checks against."
  [schema level]
  (str level " (" (:label (get (maturity-by-level schema) level)) ")"))

(defn parse-maturity
  "`review_maturity` may be a bare code (`L2`) or a code with its human-readable
  label (`L2 (Human-reviewed)`). Returns [level label-or-nil], or nil if the
  value is not shaped like either."
  [v]
  (when-let [[_ level label] (re-matches #"(?s)\s*(L\d+)\s*(?:\((.*)\))?\s*" (str v))]
    [level (some-> label str/trim not-empty)]))

(defn reviewed-at-levels
  "Levels that must record `reviewed_at`: (:from (:review-staleness schema)) and
  everything above it, taken from the order of :review-maturity rather than by
  comparing level strings."
  [schema]
  (let [from (get-in schema [:review-staleness :from])]
    (->> (maturity-levels schema)
         (drop-while #(not= from (:level %)))
         (map :level)
         set)))

(defn types-hint
  "The :types taxonomy, sorted and comma-joined for messages."
  [schema]
  (str/join ", " (sort (:types schema))))

(defn rank
  "Position of a review_maturity value (`L2` or `L2 (Human-reviewed)`) on the
  schema's ladder, least reviewed first, or nil."
  [schema value]
  (when-let [[level] (parse-maturity value)]
    (first (keep-indexed (fn [i {l :level}] (when (= l level) i)) (maturity-levels schema)))))

;; Messages name things that exist for whoever runs the tool. The originals
;; printed their own script paths (`bb tools/new_doc.bb`), which exist only in
;; the repo they came from. temper cannot know whether a consumer wrapped a
;; tool in a bb task, so the consumer may say; otherwise print the form that
;; works whenever temper is on the classpath.

(defn- non-blank [v]
  (some-> v str str/trim not-empty))

(defn command
  "How to run `tool` (:new-doc, :maturity) in this project, for messages:
  (get-in schema [:commands tool]) when set and non-blank, else
  `bb -m temper.okf.<tool>`."
  [schema tool]
  (or (non-blank (get-in schema [:commands tool]))
      (str "bb -m temper.okf." (name tool))))

(defn spec-doc
  "Where the consumer documents the convention, for validate's \"see ...\"
  pointers: (:spec-doc schema) when set and non-blank, else nil. Callers fall
  back to the schema file, which always exists once the run has got this far."
  [schema]
  (non-blank (:spec-doc schema)))
