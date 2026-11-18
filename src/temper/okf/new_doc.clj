(ns temper.okf.new-doc
  "Scaffold a new Markdown file under the docs directory with valid OKF + RDF
  YAML frontmatter.

  From a project that depends on this library (see README.md):

      bb -m temper.okf.new-doc --type Research --title \"My Doc\" --path docs/roadshow/my-doc.md
      bb -m temper.okf.new-doc --type Guide --title \"...\" --path docs/guild/foo.md \\
                        --description \"One-line summary.\" --tags guild,onboarding

  For an AI-assisted document, pass provenance too. The convention makes these
  mandatory, and temper.okf.validate enforces it, so a doc scaffolded without them
  would fail validation:

      bb -m temper.okf.new-doc --type Research --title \"...\" --path docs/roadshow/x.md \\
                        --ai-assisted \"Claude Opus 5 via Claude Code\" \\
                        --session c625e009 --tools babashka,Glean \\
                        --source https://docs.google.com/document/d/abc123

  Which keys are required alongside --ai-assisted is read from
  :provenance-required in the schema, the same source the validator reads, so
  the two cannot drift apart.

  Writes `created`/`modified` as today's date, and `review_maturity` L1 (Human-directed), or L0 when --ai-assisted is given, by
  default (freshly scaffolded, not yet reviewed) — bump review_maturity by
  hand once the doc has been looked at. Refuses to overwrite an existing file unless --force is
  given.

  Paths resolve from options (--docs-dir, --schema), then OKF_DOCS_DIR /
  OKF_SCHEMA, then the default layout -- see temper.okf.config."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [temper.okf.cli :as cli]
            [temper.okf.config :as config]
            [temper.okf.frontmatter :as fm]
            [temper.okf.schema :as schema]))

(def ^:private tool
  "Prefix for configuration errors on stderr; the original script's name."
  "new_doc")

(def api-only-keys
  "Options -main drops from the command line, so `--today` stays as inert as
  it was in the original script; they are reachable only from Clojure. Every
  other `--flag value` is passed through, because :provenance-required in a
  schema may name any key and the original looked each one up among all the
  flags given."
  [:today :now])

(def ^:private optional-value-flags
  "Flags that may stand alone. The usage line has always shown `[--force]`,
  but the original parser gave --force the next argument like any other flag:
  a trailing --force got nil and refused to overwrite, and `--force --type X`
  swallowed --type. So --force now takes the next argument only when that
  argument is not itself a flag, and is true otherwise. `--force <value>`, the
  one form that worked before, still parses exactly as it did."
  #{"--force"})

(defn parse-args
  "Every `--flag value` pair, keyed by the flag name. Each flag consumes the
  argument after it, whatever that is -- except --force, see
  optional-value-flags -- and a stray non-flag argument is skipped together
  with the one that follows it. Otherwise the original script's parsing, kept
  so the command line behaves identically."
  [args]
  (loop [args args opts {}]
    (if (empty? args)
      opts
      (let [[flag value & rest-args] args]
        (cond
          (and (optional-value-flags flag)
               (or (nil? value) (str/starts-with? value "--")))
          (recur (next args) (assoc opts (keyword (subs flag 2)) true))

          (and flag (str/starts-with? flag "--"))
          (recur rest-args (assoc opts (keyword (subs flag 2)) value))

          :else
          (recur rest-args opts))))))

(defn yaml-scalar
  "Quote a string for YAML if it contains `:` or starts with a special char."
  [s]
  (if (re-find #"[:#]" s)
    (str "\"" (str/replace s "\"" "\\\"") "\"")
    s))

(defn- csv
  "Split a comma-separated flag value into a trimmed, non-empty seq. A
  collection (from a Clojure caller) is trimmed and filtered the same way."
  [v]
  (cond
    (nil? v)        nil
    (sequential? v) (->> v (map (comp str/trim str)) (remove str/blank?) seq)
    :else           (->> (str/split v #",") (map str/trim) (remove str/blank?) seq)))

(defn- flow-list [k vs] (str k ": [" (str/join ", " vs) "]\n"))

(defn- source-block
  "`source` is dcterms:source / prov:wasDerivedFrom and is genuinely
  many-valued. Emit a scalar for one value and a block list for several, so
  the common case stays readable."
  [vs]
  (if (= 1 (count vs))
    (str "source: " (first vs) "\n")
    (apply str "source:\n" (map #(str "  - " % "\n") vs))))

(defn frontmatter
  "The full text of a new document: frontmatter block plus an H1. Pure.
  `:tags`, `:tools`, `:agents-skills` and `:source` are seqs of strings."
  [schema {:keys [type title description tags today
                  ai-assisted session tools agents-skills source
                  generated-by now]}]
  (str "---\n"
       "type: " type "\n"
       "title: " (yaml-scalar title) "\n"
       (when description (str "description: " (yaml-scalar description) "\n"))
       (when (seq tags) (flow-list "tags" tags))
       "created: " today "\n"
       "modified: " today "\n"
       (when (seq source) (source-block source))
       ;; Provenance (PROV-O). Emitted only for AI-assisted docs — CLAUDE.md:
       ;; "Omit when not AI-assisted."
       (when ai-assisted
         (str "ai_assisted: " (yaml-scalar ai-assisted) "\n"
              (when session (str "session: " session "\n"))
              (when (seq tools) (flow-list "tools" tools))
              (flow-list "agents_skills" (or agents-skills []))))
       ;; OKF v0.2 §5.2. `run` passes :generated-by only for a 0.2 bundle.
       (when generated-by
         (str "generated: { by: " (fm/yaml-str generated-by) ", at: " now " }\n"))
       ;; A scaffold produced from flags alone is AI-generated; one a human
       ;; specified and directed is L1. See the ladder in CLAUDE.md.
       (let [maturity (if ai-assisted
                        (schema/canonical-maturity schema "L0")
                        (schema/canonical-maturity schema "L1"))
             note     (if ai-assisted
                        "AI-generated; not yet human-reviewed"
                        "Human-directed scaffold; not yet individually reviewed")]
         (str "review_maturity: " maturity "\n"
              "review_note: " note "\n"))
       "---\n\n"
       "# " title "\n"))

(defn- usage-error!
  "End the run as a usage error (exit 1). Thrown so the checks in `run` read
  in the original script's order, where usage-and-exit! ended the process."
  [msg]
  (throw (ex-info msg {::usage-error msg})))

(defn- assert-valid-frontmatter!
  "Guard: the file we are about to write must open and close its frontmatter
  block, or temper.okf.validate rejects it on sight. Cheap to check here, confusing
  to debug later."
  [text path]
  (when-not (and (str/starts-with? text "---\n")
                 (str/index-of (subs text 4) "\n---"))
    (usage-error!
     (str "refusing to write " path
          ": generated frontmatter is not delimited by a closing `---`."
          " This is a bug in tools/new_doc.bb, not in your arguments."))))

(defn- blank-opt?
  "True when an option is absent or blank. A string is tested as the original
  script tested it; a collection is blank when it has no non-blank element."
  [v]
  (if (sequential? v)
    (empty? (csv v))
    (str/blank? (str v))))

(defn run
  "Write a new document. Prints nothing and never exits.

  opts (strings, as from the command line, unless noted):
    :type :title :path      required; :path must be under the docs directory
    :description
    :tags :source           comma-separated string, or a collection
    :force                  truthy to overwrite an existing file
    :ai-assisted            when set, every :provenance-required key is required
    :session
    :tools :agents-skills   comma-separated string, or a collection
    :docs-dir :schema       see temper.okf.config/resolve-paths
    :generated-by           OKF actor for `generated.by`; required with :ai-assisted when the
                            schema targets OKF 0.2, ignored (with a warning) before 0.2
    :today                  date string for created/modified (default: today)
    :now                    timestamp for `generated.at` (default: now, UTC, to the second)

  Returns
    {:exit 0 :path p :text written-text :warnings [..] :docs-dir d}   on success,
    {:exit 1 :error msg :warnings [..] :docs-dir d}                   on a usage error,
    {:exit 2 :config-errors [line ...]}                               if the schema is unreadable.
  :warnings holds messages emitted before the run ended (an unknown :type)."
  [opts]
  (config/catch-config-errors
   (fn []
     (let [{:keys [docs-dir schema-file]} (config/resolve-paths opts)
           schema   (config/read-schema! tool schema-file)
           warnings (atom [])
           {:keys [type title path description tags force
                   ai-assisted session tools agents-skills source generated-by]} opts
           v02      (schema/okf-v02? schema)]
       (try
         (when-not type  (usage-error! "--type is required"))
         (when-not title (usage-error! "--title is required"))
         (when-not path  (usage-error! "--path is required"))
         (let [docs-prefix (str (str/replace docs-dir #"/+$" "") "/")]
           (when-not (str/starts-with? path docs-prefix)
             (usage-error! (str "--path must be under " docs-prefix))))
         ;; Same rule the validator applies, read from the same place, so the
         ;; scaffolder cannot emit a document that would immediately fail
         ;; temper.okf.validate. Flag names use dashes, frontmatter keys use
         ;; underscores.
         (when ai-assisted
           (doseq [k (sort (or (:provenance-required schema) #{}))]
             (let [flag (str "--" (str/replace (name k) "_" "-"))]
               (when (blank-opt? (get opts (keyword (str/replace (name k) "_" "-"))))
                 (usage-error!
                  (str "--ai-assisted was given, so " flag " is required too"
                       " (:provenance-required in " schema-file ")."
                       " If it genuinely was not captured, pass " flag " "
                       (pr-str (:provenance-unknown schema))))))))
         ;; OKF v0.2: an AI-drafted doc must say which agent generated it, as
         ;; an actor (§7). Required only for AI-assisted docs; a human-authored
         ;; one may omit `generated`, which OKF permits. No default identity is
         ;; guessed: that would mean a git or network call.
         (when (and v02 ai-assisted (blank-opt? generated-by))
           (usage-error! (str "--ai-assisted was given and " schema-file " targets OKF " (:okf-version schema)
                              ", so --generated-by is required too: an OKF actor such as"
                              " claude-code/claude-opus-5-5 (OKF v0.2 §7)")))
         (when (and v02 (not (blank-opt? generated-by)) (not (schema/okf-actor? generated-by)))
           (usage-error! (str "--generated-by " (pr-str generated-by) " is not an OKF actor: use"
                              " human:<id>, process:<id> or <producer>/<version> (OKF v0.2 §7)")))
         (when (and (not v02) (not (blank-opt? generated-by)))
           (swap! warnings conj (str "--generated-by ignored: " schema-file " targets OKF "
                                     (pr-str (:okf-version schema)) ", and `generated` is an OKF 0.2 field")))
         (when-not (contains? (:types schema) type)
           (swap! warnings conj
                  (str "type \"" type "\" is not in the taxonomy ("
                       (str/join ", " (sort (:types schema))) ") — proceeding anyway")))
         (let [f (io/file path)]
           (when (and (.exists f) (not force))
             (usage-error! (str path " already exists — pass --force to overwrite")))
           (io/make-parents f)
           (let [text (frontmatter schema
                                   {:type          type
                                    :title         title
                                    :description   description
                                    :tags          (csv tags)
                                    :today         (str (or (:today opts) (java.time.LocalDate/now)))
                                    :ai-assisted   ai-assisted
                                    :session       session
                                    :tools         (csv tools)
                                    :agents-skills (csv agents-skills)
                                    :source        (csv source)
                                    :generated-by  (when (and v02 (not (blank-opt? generated-by))) generated-by)
                                    :now           (or (:now opts)
                                                       (str (.truncatedTo (java.time.Instant/now)
                                                                          java.time.temporal.ChronoUnit/SECONDS)))})]
             (assert-valid-frontmatter! text path)
             (spit f text)
             {:exit 0 :path path :text text :warnings @warnings :docs-dir docs-dir}))
         (catch clojure.lang.ExceptionInfo e
           (if-let [msg (::usage-error (ex-data e))]
             {:exit 1 :error msg :warnings @warnings :docs-dir docs-dir}
             (throw e))))))))

(defn report
  "The text the original script printed to stdout for `result`: any warnings,
  then either `wrote <path>` or the error and usage. Empty for a
  configuration error, whose lines go to stderr instead."
  [{:keys [exit warnings error path docs-dir]}]
  (if (= 2 exit)
    ""
    (with-out-str
      (doseq [w warnings] (println (str "warn: " w)))
      (if error
        (do (println (str "error: " error))
            (println (str "usage: bb tools/new_doc.bb --type <Type> --title <Title> --path <" docs-dir "/...md>"))
            (println "       [--description <desc>] [--tags a,b,c] [--source url,url] [--force]")
            (println "       [--ai-assisted <model via interface>] [--session <id>] [--tools a,b,c] [--agents-skills url,url]"))
        (println (str "wrote " path))))))

(defn -main
  "CLI entry point; see the namespace docstring for flags. Prints the report
  and exits 0 (written), 1 (usage error) or 2 (unreadable schema)."
  [& args]
  (let [result (run (apply dissoc (parse-args args) api-only-keys))]
    (cli/exit! result (report result))))
