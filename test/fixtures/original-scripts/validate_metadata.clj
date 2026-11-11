(ns tools.validate-metadata
  "Validate OKF + RDF YAML frontmatter across a docs bundle against its schema.

  Run from the repo root:

      bb tools/validate_metadata.clj

  Paths default to this repo's layout (docs/ and docs/metadata-schema.edn) and
  are overridable, so this file can be copied into another repo unedited:

      OKF_DOCS_DIR=knowledge bb tools/validate_metadata.clj
      OKF_SCHEMA=cfg/okf.edn bb tools/validate_metadata.clj

  Exits non-zero if any document has an error. Warnings do not fail the run.
  See CLAUDE.md (\"AI metadata on documents\") for the convention this enforces."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clj-yaml.core :as yaml]
            [cheshire.core :as json]))

(def docs-dir    (or (not-empty (System/getenv "OKF_DOCS_DIR")) "docs"))
(let [dir (io/file docs-dir)]
  (when-not (and (.isDirectory dir) (.canRead dir))
    (binding [*out* *err*]
      (println (str "validate_metadata: docs directory " docs-dir " is missing or unreadable.")))
    (System/exit 2)))
(def schema-file (or (not-empty (System/getenv "OKF_SCHEMA"))
                     (str docs-dir "/metadata-schema.edn")))

(defn- read-schema!
  "Making the path configurable adds a way to point at nothing; a typo'd
  OKF_SCHEMA must not surface as a slurp stack trace. Same doctrine as the
  missing-key checks below: unreadable configuration exits 2."
  [path]
  (try
    (edn/read-string (slurp path))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "validate_metadata: cannot read schema at " path
                      " -- " (.getMessage e))))
      (System/exit 2))))

(def schema (read-schema! schema-file))

;; Hints built from the schema so error messages list the actual allowed values.
(def types-hint    (str/join ", " (sort (:types schema))))

;; :review-maturity is an ordered vector of {:level :label :meaning}. Derive the
;; legal value set from it rather than keeping a second set in the schema, so the
;; ladder documented in the READMEs and the ladder enforced here cannot diverge.
(def maturity-levels (:review-maturity schema))
(def maturity-values (into #{} (map :level) maturity-levels))
(def maturity-hint   (str/join "/" (map :level maturity-levels)))
(def maturity-by-level (into {} (map (juxt :level identity)) maturity-levels))

(when-not (contains? schema :review-staleness)
  (binding [*out* *err*]
    (println (str "validate_metadata: " schema-file " is missing :review-staleness"
                  " -- refusing to run a rule this tool cannot read.")))
  (System/exit 2))

(def staleness-from (get-in schema [:review-staleness :from]))

(def reviewed-at-levels
  "Levels that must record `reviewed_at`: :from and everything above it, taken
  from the order of :review-maturity rather than by comparing level strings."
  (->> maturity-levels
       (drop-while #(not= staleness-from (:level %)))
       (map :level)
       set))

(defn- parse-date [s]
  (try (java.time.LocalDate/parse (subs (str s) 0 (min 10 (count (str s)))))
       (catch Exception _ nil)))

(defn canonical-maturity
  "The form we want authors to write: code plus its label, so a reader does not
  have to look the code up. e.g. \"L2 (Human-reviewed)\"."
  [level]
  (str level " (" (:label (maturity-by-level level)) ")"))

(defn parse-maturity
  "`review_maturity` may be a bare code (`L2`) or a code with its human-readable
  label (`L2 (Human-reviewed)`). Returns [level label-or-nil], or nil if the
  value is not shaped like either."
  [v]
  (when-let [[_ level label] (re-matches #"(?s)\s*(L\d+)\s*(?:\((.*)\))?\s*" (str v))]
    [level (some-> label str/trim not-empty)]))
;; Absent key = configuration error; present-but-empty = a deliberate opt-out.
;; `(or (:key schema) #{})` would turn a rename or typo into a silently
;; disabled rule and a green run -- the failure mode that let check_links.bb
;; report "0 errors" while checking nothing.
(doseq [k [:provenance-required :provenance-unknown]]
  (when-not (contains? schema k)
    (binding [*out* *err*]
      (println (str "validate_metadata: " schema-file " is missing " k
                    " -- refusing to run a rule this tool cannot read."
                    " Use an empty value to disable it deliberately.")))
    (System/exit 2)))

(def provenance-required (:provenance-required schema))
(def provenance-unknown  (:provenance-unknown schema))

(defn- provenance-values
  "Frontmatter values may be a scalar or a YAML list; normalise to a seq of
  trimmed strings so both `session: unrecorded` and `tools: [unrecorded]`
  can be checked the same way."
  [v]
  (map (comp str/trim str) (if (sequential? v) v [v])))

(defn- provenance-blank? [v]
  (let [vs (provenance-values v)]
    (or (empty? vs) (every? str/blank? vs))))

(defn- provenance-unknown? [v]
  (every? #(= % provenance-unknown) (provenance-values v)))

(defn md-files []
  (->> (file-seq (io/file docs-dir))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".md"))
       (sort-by #(.getPath %))))

(defn relpath [f] (str/replace (.getPath f) #"^\./" ""))

(defn extract-frontmatter
  "The YAML text between the opening `---` line and the next `---` line, or nil."
  [content]
  (when (str/starts-with? content "---\n")
    (let [body (subs content 4)
          idx  (str/index-of body "\n---")]
      (when idx (subs body 0 idx)))))

(defn raw-value
  "The verbatim value text for key `k` from the raw frontmatter, unquoted.
  We validate dates against this, not the parsed value: clj-yaml/SnakeYAML is
  lenient and silently rolls 2026-13-45 over into a real Date, so the parsed
  value can't catch a bad date — the authored text can."
  [fm k]
  (when-let [m (re-find (re-pattern (str "(?m)^" (name k) ":[ \\t]*(.+?)[ \\t]*$")) fm)]
    (str/replace (second m) #"^['\"]|['\"]$" "")))

(defn date-ok?
  "True if the leading YYYY-MM-DD of `s` is a real calendar date (rejects
  2026-13-45, requires zero-padding)."
  [s]
  (boolean
   (and (string? s)
        (try (java.time.LocalDate/parse (subs s 0 (min 10 (count s))))
             true
             (catch Exception _ false)))))

(defn validate-concept [path content]
  (let [errs (atom []) warns (atom [])
        err! #(swap! errs conj %) warn! #(swap! warns conj %)
        fm (extract-frontmatter content)]
    (if-not fm
      (err! "no YAML frontmatter — add a `---` … `---` block at the very top of the file, with at least `type:` (see CLAUDE.md)")
      (let [data (try (yaml/parse-string fm)
                      (catch Exception e
                        (err! (str "frontmatter is not valid YAML: " (.getMessage e)
                                   " — check indentation and that values with `:` or `#` are quoted"))
                        nil))]
        (when data
          (let [t (:type data)]
            (cond
              (or (nil? t) (str/blank? (str t)))
              (err! (str "missing required key `type` — add `type:` set to one of: " types-hint
                         " (extend " schema-file " to add a new one)"))
              (not (contains? (:types schema) (str t)))
              (warn! (str "type \"" t "\" is not in the taxonomy (" types-hint
                          ") — fix the value, or add it to :types in " schema-file " if intentional"))))
          (when-let [rm (:review_maturity data)]
            (let [[level label] (or (parse-maturity rm) [nil nil])]
              (cond
                (not (contains? maturity-values level))
                (err! (str "invalid review_maturity " (pr-str (str rm)) " — use one of " maturity-hint
                           ", optionally with its label, e.g. " (pr-str (canonical-maturity "L2"))
                           " (L0 AI-generated → L4 human-endorsed; see CLAUDE.md)"))

                ;; A label is encouraged so readers need not look the code up,
                ;; but a *wrong* label is worse than none -- check it against
                ;; :review-maturity in docs/metadata-schema.edn.
                (and label (not= (str/lower-case label)
                                 (str/lower-case (:label (maturity-by-level level)))))
                (err! (str "review_maturity " (pr-str (str rm)) " has the wrong label for " level
                           " — write " (pr-str (canonical-maturity level))
                           ", or drop the parenthetical."))

                (nil? label)
                (warn! (str "review_maturity is the bare code " (pr-str level)
                            " — prefer " (pr-str (canonical-maturity level))
                            " so a reader does not have to look the code up.")))))
          ;; A review is an event against a version, not a permanent property of
          ;; the file. Above :from the claim is unfalsifiable unless it says when
          ;; it was made, so a missing `reviewed_at` is an error. Editing a
          ;; reviewed document afterwards is normal and must not fail CI, so a
          ;; stale review only warns.
          (when-let [[level _] (parse-maturity (:review_maturity data))]
            (when (contains? reviewed-at-levels level)
              (let [r (raw-value fm :reviewed_at)
                    m (raw-value fm :modified)]
                (cond
                  (str/blank? (str r))
                  (err! (str "review_maturity is " level " but `reviewed_at` is missing"
                             " — a review is an event against a version, so a level at or above "
                             staleness-from " must record when it was reviewed"
                             " (e.g. `reviewed_at: 2026-09-09`). See CLAUDE.md."))

                  (and (parse-date r) (parse-date m)
                       (.isAfter (parse-date m) (parse-date r)))
                  (warn! (str "review claims " level " as of " r " but the document was modified "
                              m " — the level covers content the reviewer may not have seen."
                              " Re-review and bump `reviewed_at`, or lower `review_maturity`."))))))
          ;; Provenance: CLAUDE.md makes these mandatory once a doc declares
          ;; `ai_assisted`. Enforced here rather than left to prose, so a
          ;; hand-written frontmatter block is held to the same bar as a
          ;; scaffolded one. The key set lives in docs/metadata-schema.edn.
          (when-not (str/blank? (str (:ai_assisted data)))
            (doseq [k (sort provenance-required)]
              (let [v (get data k)]
                (cond
                  (provenance-blank? v)
                  (err! (str "`ai_assisted` is set but `" (name k) "` is missing or blank"
                             " \u2014 an AI-assisted document must record its provenance ("
                             (str/join ", " (map name (sort provenance-required))) ")."
                             " If it genuinely was not captured, set it to "
                             (pr-str provenance-unknown) ". See CLAUDE.md."))

                  (provenance-unknown? v)
                  (warn! (str "`" (name k) "` is " (pr-str provenance-unknown)
                              " \u2014 provenance for this document was never captured."
                              " Acceptable for drafts predating the rule; not for new work."))))))
          (doseq [k (:date-keys schema)]
            (when (contains? data k)
              (let [raw (raw-value fm k)]
                (when-not (date-ok? raw)
                  (err! (str (name k) ": " (pr-str (or raw (get data k)))
                             " is not a valid date — use a zero-padded ISO date `YYYY-MM-DD`, e.g. 2026-06-16"
                             " (pad single-digit months/days: 2026-6-6 → 2026-06-06)"))))))
          (when (and (contains? data :tags) (not (sequential? (:tags data))))
            (warn! "`tags` must be a YAML list in square brackets, e.g. tags: [data-model, edn-schema]"))
          (doseq [k (keys data)]
            (when-not (contains? (:known-keys schema) k)
              (warn! (str "unknown key `" (name k) "` — check the spelling against the field list in CLAUDE.md,"
                          " or add it to :known-keys in " schema-file)))))))
    {:path path :errors @errs :warnings @warns}))

(defn validate-bundle-root [content]
  (let [errs (atom []) err! #(swap! errs conj %)
        fm (extract-frontmatter content)]
    (if-not fm
      (err! "bundle root index.md must declare okf_version in frontmatter")
      (let [data (yaml/parse-string fm)
            extra (disj (set (keys data)) :okf_version)]
        (when-not (= (str (:okf_version data)) (:okf-version schema))
          (err! (str "okf_version must be \"" (:okf-version schema) "\"")))
        (when (seq extra)
          (err! (str "index.md frontmatter may contain only okf_version; found also "
                     (str/join ", " (map name extra)))))))
    {:path (:bundle-root schema) :errors @errs :warnings []}))

(when-not (contains? schema :context-file)
  (binding [*out* *err*]
    (println (str "validate_metadata: " schema-file " is missing :context-file"
                  " -- refusing to run a rule this tool cannot read."
                  " Use an empty value to disable it deliberately.")))
  (System/exit 2))

(def context-file (not-empty (str/trim (str (:context-file schema)))))

;; The JSON-LD context is out-of-band and optional, so a blank :context-file is
;; a deliberate opt-out -- but a path that is set and absent is a broken bundle,
;; not an opt-out, and must not surface as a slurp stack trace.
(when (and context-file
           (let [f (io/file context-file)]
             (not (and (.isFile f) (.canRead f)))))
  (binding [*out* *err*]
    (println (str "validate_metadata: :context-file points at " context-file
                  ", which is missing or unreadable. Create it, or set :context-file to \"\""
                  " in " schema-file " to skip the JSON-LD check.")))
  (System/exit 2))

(defn validate-context
  "Every known key (minus :context-exempt) must have a JSON-LD @context mapping.
  No-op when :context-file is blank."
  []
  (when context-file
    (let [ctx (-> context-file slurp json/parse-string (get "@context"))
          expected (remove (:context-exempt schema) (:known-keys schema))
          missing (remove #(contains? ctx (name %)) expected)]
      (map #(str context-file " is missing a JSON-LD mapping for `" (name %)
                 "` — add it under @context (e.g. \"" (name %) "\": \"dcterms:" (name %) "\")") missing))))

(defn run []
  (let [files     (md-files)
        reserved? #(contains? (:reserved-files schema) (.getName %))
        grouped   (group-by reserved? files)
        concepts  (get grouped false [])
        reserved  (get grouped true [])
        results   (mapv #(validate-concept (relpath %) (slurp %)) concepts)
        ;; only docs/index.md gets bundle-root treatment; other reserved files are noted
        root-res  (when-let [root (first (filter #(= (relpath %) (:bundle-root schema)) reserved))]
                    (validate-bundle-root (slurp root)))
        all       (cond-> results root-res (conj root-res))
        ctx-warns (validate-context)]
    (doseq [{:keys [path errors warnings]} (sort-by :path all)]
      (println (if (seq errors) "FAIL" "PASS") path)
      (doseq [e errors]   (println "    ERROR:" e))
      (doseq [w warnings] (println "    warn: " w)))
    (doseq [w ctx-warns] (println (str "FAIL " context-file "\n    ERROR:") w))
    (let [err-files (count (filter #(seq (:errors %)) all))
          n-warn    (reduce + (map #(count (:warnings %)) all))
          n-ctx     (count ctx-warns)]
      (println)
      (printf "%d documents checked · %d with errors · %d warnings · %d context gaps\n"
              (count all) err-files n-warn n-ctx)
      (if (or (pos? err-files) (pos? n-ctx))
        (do (println "FAILED") 1)
        (do (println "OK") 0)))))

;; Run when invoked as a script (bb tools/validate_metadata.clj), not when required.
(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (run)))
