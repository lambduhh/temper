(ns temper.okf.validate
  "Validate OKF + RDF YAML frontmatter across a docs bundle against its schema.

  From a project that depends on this library (see README.md):

      bb -m temper.okf.validate
      bb -m temper.okf.validate --docs-dir knowledge --schema cfg/okf.edn

  or from Clojure, where nothing is printed and the process is not exited:

      (temper.okf.validate/run {:docs-dir \"docs\"})   ;=> {:exit 0 :results [...] ...}

  Paths resolve from options, then OKF_DOCS_DIR / OKF_SCHEMA, then the original
  layout (docs/ and docs/metadata-schema.edn) -- see temper.okf.config. The schema's
  own path-valued keys (:bundle-root, :context-file) are relative to the
  working directory, not to the docs directory.

  Exits non-zero if any document has an error. Warnings do not fail the run.
  Exit codes: 0 clean · 1 findings · 2 unreadable configuration.
  The convention it enforces is defined by the schema; see the README."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clj-yaml.core :as yaml]
            [cheshire.core :as json]
            [temper.okf.cli :as cli]
            [temper.okf.config :as config]
            [temper.okf.schema :as schema]))

(def ^:private tool
  "Prefix for configuration errors on stderr; the original script's name."
  "validate_metadata")

(defn- missing-key! [schema-file k & more]
  (config/config-error!
   (apply str tool ": " schema-file " is missing " k
          " -- refusing to run a rule this tool cannot read." more)))

(defn load-config
  "Resolve paths, read the schema, check it, and derive what a run needs.

  The checks run in the order the original script ran them at load time, so
  the first problem reported is the same one. Ends the run via
  temper.okf.config/config-error! (exit 2) on configuration this tool cannot read."
  [opts]
  (let [{:keys [docs-dir schema-file]} (config/resolve-paths opts)
        _      (config/require-docs-dir! tool docs-dir)
        schema (config/read-schema! tool schema-file)
        ;; Hints built from the schema so error messages list the actual allowed values.
        derived {:types-hint        (schema/types-hint schema)
                 :maturity-values   (schema/maturity-values schema)
                 :maturity-hint     (schema/maturity-hint schema)
                 :maturity-by-level (schema/maturity-by-level schema)}]
    (when-not (contains? schema :review-staleness)
      (missing-key! schema-file :review-staleness))
    ;; Absent key = configuration error; present-but-empty = a deliberate opt-out.
    ;; `(or (:key schema) #{})` would turn a rename or typo into a silently
    ;; disabled rule and a green run -- the failure mode that let check_links.bb
    ;; report "0 errors" while checking nothing.
    (doseq [k [:provenance-required :provenance-unknown]]
      (when-not (contains? schema k)
        (missing-key! schema-file k " Use an empty value to disable it deliberately.")))
    (when-not (contains? schema :context-file)
      (missing-key! schema-file :context-file " Use an empty value to disable it deliberately."))
    (let [context-file (not-empty (str/trim (str (:context-file schema))))]
      ;; The JSON-LD context is out-of-band and optional, so a blank :context-file is
      ;; a deliberate opt-out -- but a path that is set and absent is a broken bundle,
      ;; not an opt-out, and must not surface as a slurp stack trace.
      (when (and context-file
                 (let [f (io/file context-file)]
                   (not (and (.isFile f) (.canRead f)))))
        (config/config-error!
         (str tool ": :context-file points at " context-file
              ", which is missing or unreadable. Create it, or set :context-file to \"\""
              " in " schema-file " to skip the JSON-LD check.")))
      (merge derived
             {:docs-dir            docs-dir
              :schema-file         schema-file
              :spec-doc            (schema/spec-doc schema)
              :schema              schema
              :staleness-from      (get-in schema [:review-staleness :from])
              :reviewed-at-levels  (schema/reviewed-at-levels schema)
              :provenance-required (:provenance-required schema)
              :provenance-unknown  (:provenance-unknown schema)
              :context-file        context-file}))))

(defn- parse-date [s]
  (try (java.time.LocalDate/parse (subs (str s) 0 (min 10 (count (str s)))))
       (catch Exception _ nil)))

(defn- provenance-values
  "Frontmatter values may be a scalar or a YAML list; normalise to a seq of
  trimmed strings so both `session: unrecorded` and `tools: [unrecorded]`
  can be checked the same way."
  [v]
  (map (comp str/trim str) (if (sequential? v) v [v])))

(defn- provenance-blank? [v]
  (let [vs (provenance-values v)]
    (or (empty? vs) (every? str/blank? vs))))

(defn- provenance-unknown? [unknown v]
  (every? #(= % unknown) (provenance-values v)))

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

(defn validate-concept
  "Check one concept document. `cfg` is the map load-config returns. Returns
  {:path path :errors [msg ...] :warnings [msg ...]}."
  [cfg path content]
  (let [{:keys [schema schema-file spec-doc types-hint maturity-values maturity-hint
                maturity-by-level staleness-from reviewed-at-levels
                provenance-required provenance-unknown]} cfg
        ;; The originals said "see CLAUDE.md", a file in their own repo. Point
        ;; at the consumer's :spec-doc, or else at the schema, which exists.
        see (or spec-doc schema-file)
        canonical #(schema/canonical-maturity schema %)
        errs (atom []) warns (atom [])
        err! #(swap! errs conj %) warn! #(swap! warns conj %)
        fm (extract-frontmatter content)]
    (if-not fm
      (err! (str "no YAML frontmatter — add a `---` … `---` block at the very top of the file,"
                 " with at least `type:` (see " see ")"))
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
            (let [[level label] (or (schema/parse-maturity rm) [nil nil])]
              (cond
                (not (contains? maturity-values level))
                (err! (str "invalid review_maturity " (pr-str (str rm)) " — use one of " maturity-hint
                           ", optionally with its label, e.g. " (pr-str (canonical "L2"))
                           " (L0 AI-generated → L4 human-endorsed; see " see ")"))

                ;; A label is encouraged so readers need not look the code up,
                ;; but a *wrong* label is worse than none -- check it against
                ;; :review-maturity in the schema.
                (and label (not= (str/lower-case label)
                                 (str/lower-case (:label (maturity-by-level level)))))
                (err! (str "review_maturity " (pr-str (str rm)) " has the wrong label for " level
                           " — write " (pr-str (canonical level))
                           ", or drop the parenthetical."))

                (nil? label)
                (warn! (str "review_maturity is the bare code " (pr-str level)
                            " — prefer " (pr-str (canonical level))
                            " so a reader does not have to look the code up.")))))
          ;; A review is an event against a version, not a permanent property of
          ;; the file. Above :from the claim is unfalsifiable unless it says when
          ;; it was made, so a missing `reviewed_at` is an error. Editing a
          ;; reviewed document afterwards is normal and must not fail CI, so a
          ;; stale review only warns.
          (when-let [[level _] (schema/parse-maturity (:review_maturity data))]
            (when (contains? reviewed-at-levels level)
              (let [r (raw-value fm :reviewed_at)
                    m (raw-value fm :modified)]
                (cond
                  (str/blank? (str r))
                  (err! (str "review_maturity is " level " but `reviewed_at` is missing"
                             " — a review is an event against a version, so a level at or above "
                             staleness-from " must record when it was reviewed"
                             " (e.g. `reviewed_at: 2026-09-09`). See " see "."))

                  (and (parse-date r) (parse-date m)
                       (.isAfter (parse-date m) (parse-date r)))
                  (warn! (str "review claims " level " as of " r " but the document was modified "
                              m " — the level covers content the reviewer may not have seen."
                              " Re-review and bump `reviewed_at`, or lower `review_maturity`."))))))
          ;; Provenance: CLAUDE.md makes these mandatory once a doc declares
          ;; `ai_assisted`. Enforced here rather than left to prose, so a
          ;; hand-written frontmatter block is held to the same bar as a
          ;; scaffolded one. The key set lives in the schema.
          (when-not (str/blank? (str (:ai_assisted data)))
            (doseq [k (sort provenance-required)]
              (let [v (get data k)]
                (cond
                  (provenance-blank? v)
                  (err! (str "`ai_assisted` is set but `" (name k) "` is missing or blank"
                             " — an AI-assisted document must record its provenance ("
                             (str/join ", " (map name (sort provenance-required))) ")."
                             " If it genuinely was not captured, set it to "
                             (pr-str provenance-unknown) ". See " see "."))

                  (provenance-unknown? provenance-unknown v)
                  (warn! (str "`" (name k) "` is " (pr-str provenance-unknown)
                              " — provenance for this document was never captured."
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
              (warn! (str "unknown key `" (name k) "` — check the spelling"
                          ;; Without :spec-doc the field list *is* :known-keys
                          ;; in schema-file; do not name the same file twice.
                          (when spec-doc (str " against the field list in " spec-doc))
                          ", or add it to :known-keys in " schema-file)))))))
    {:path path :errors @errs :warnings @warns}))

(defn validate-bundle-root
  "Check the OKF bundle root (index.md): its frontmatter may hold only
  okf_version, equal to :okf-version in the schema."
  [cfg content]
  (let [schema (:schema cfg)
        errs (atom []) err! #(swap! errs conj %)
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

(defn validate-context
  "Every known key (minus :context-exempt) must have a JSON-LD @context mapping.
  Returns one message per missing mapping. No-op when :context-file is blank."
  [cfg]
  (let [{:keys [schema context-file]} cfg]
    (when context-file
      (let [ctx (-> context-file slurp json/parse-string (get "@context"))
            expected (remove (:context-exempt schema) (:known-keys schema))
            missing (remove #(contains? ctx (name %)) expected)]
        (map #(str context-file " is missing a JSON-LD mapping for `" (name %)
                   "` — add it under @context (e.g. \"" (name %) "\": \"dcterms:" (name %) "\")") missing)))))

(defn run
  "Validate every Markdown document under the docs directory. Prints nothing
  and never exits.

  opts: :docs-dir, :schema (see temper.okf.config/resolve-paths).

  Returns, on a completed run:
    {:exit         0 or 1
     :results      [{:path :errors [..] :warnings [..]} ...]   sorted by :path
     :documents    number of documents checked
     :errors       number of documents with at least one error
     :warnings     total number of warnings
     :context-file the JSON-LD context path, or nil when opted out
     :context-gaps [msg ...]                                   known keys with no mapping
     :docs-dir .. :schema-file ..}
  or, on unreadable configuration, {:exit 2 :config-errors [line ...]}."
  ([] (run {}))
  ([opts]
   (config/catch-config-errors
    (fn []
      (let [{:keys [schema docs-dir] :as cfg} (load-config opts)
            files     (config/md-files docs-dir)
            reserved? #(contains? (:reserved-files schema) (.getName (io/file %)))
            grouped   (group-by reserved? files)
            concepts  (get grouped false [])
            reserved  (get grouped true [])
            results   (mapv #(validate-concept cfg % (slurp %)) concepts)
            ;; only docs/index.md gets bundle-root treatment; other reserved files are noted
            root-res  (when-let [root (first (filter #(= % (:bundle-root schema)) reserved))]
                        (validate-bundle-root cfg (slurp root)))
            all       (vec (sort-by :path (cond-> results root-res (conj root-res))))
            ctx-gaps  (vec (validate-context cfg))
            err-files (count (filter #(seq (:errors %)) all))]
        {:exit         (if (or (pos? err-files) (seq ctx-gaps)) 1 0)
         :results      all
         :documents    (count all)
         :errors       err-files
         :warnings     (reduce + (map #(count (:warnings %)) all))
         :context-file (:context-file cfg)
         :context-gaps ctx-gaps
         :docs-dir     docs-dir
         :schema-file  (:schema-file cfg)})))))

(defn report
  "The text the original script printed to stdout for `result`. Empty for a
  configuration error, whose lines go to stderr instead."
  [{:keys [exit results context-file context-gaps documents] :as result}]
  (if (= 2 exit)
    ""
    (with-out-str
      (doseq [{:keys [path errors warnings]} results]
        (println (if (seq errors) "FAIL" "PASS") path)
        (doseq [e errors]   (println "    ERROR:" e))
        (doseq [w warnings] (println "    warn: " w)))
      (doseq [w context-gaps] (println (str "FAIL " context-file "\n    ERROR:") w))
      (println)
      (printf "%d documents checked · %d with errors · %d warnings · %d context gaps\n"
              documents (:errors result) (:warnings result) (count context-gaps))
      (println (if (zero? exit) "OK" "FAILED")))))

(defn -main
  "CLI entry point. Flags: --docs-dir DIR, --schema FILE. Prints the report and
  exits 0 (clean), 1 (findings) or 2 (unreadable configuration)."
  [& args]
  (let [result (run (cli/parse-flags args {:value cli/path-flags}))]
    (cli/exit! result (report result))))
