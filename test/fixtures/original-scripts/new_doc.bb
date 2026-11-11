#!/usr/bin/env bb
(ns tools.new-doc
  "Scaffold a new docs/ Markdown file with valid OKF + RDF YAML frontmatter.

  Run from the repo root:

      bb tools/new_doc.bb --type Research --title \"My Doc\" --path docs/roadshow/my-doc.md
      bb tools/new_doc.bb --type Guide --title \"...\" --path docs/guild/foo.md \\
                           --description \"One-line summary.\" --tags guild,onboarding

  For an AI-assisted document, pass provenance too. CLAUDE.md makes these
  mandatory, and tools/validate_metadata.clj enforces it, so a doc scaffolded
  without them would fail validation:

      bb tools/new_doc.bb --type Research --title \"...\" --path docs/roadshow/x.md \\
                           --ai-assisted \"Claude Opus 5 via Claude Code\" \\
                           --session c625e009 --tools babashka,Glean \\
                           --source https://docs.google.com/document/d/abc123

  Which keys are required alongside --ai-assisted is read from
  :provenance-required in docs/metadata-schema.edn, the same source the
  validator reads, so the two cannot drift apart.

  Writes `created`/`modified` as today's date and `review_maturity: L0` by
  default (freshly scaffolded, not yet reviewed) — bump review_maturity by
  hand once the doc has been looked at. See CLAUDE.md for the convention.
  Refuses to overwrite an existing file unless --force is given."
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
        (println (str "new_doc: cannot read schema at " path
                      " -- " (.getMessage e))))
      (System/exit 2))))

(def schema (read-schema! schema-file))

(defn canonical-maturity
  "Code plus label, e.g. \"L0 (AI-generated)\" -- the form validate_metadata.clj
  prefers, so a reader never has to look the code up."
  [level]
  (let [{:keys [label]} (first (filter #(= level (:level %)) (:review-maturity schema)))]
    (str level " (" label ")")))

(defn parse-args [args]
  (loop [args args opts {}]
    (if (empty? args)
      opts
      (let [[flag value & rest-args] args]
        (if (and flag (str/starts-with? flag "--"))
          (recur rest-args (assoc opts (keyword (subs flag 2)) value))
          (recur rest-args opts))))))

(defn usage-and-exit! [msg]
  (println (str "error: " msg))
  (println (str "usage: bb tools/new_doc.bb --type <Type> --title <Title> --path <" docs-dir "/...md>"))
  (println "       [--description <desc>] [--tags a,b,c] [--source url,url] [--force]")
  (println "       [--ai-assisted <model via interface>] [--session <id>] [--tools a,b,c] [--agents-skills url,url]")
  (System/exit 1))

(defn yaml-scalar
  "Quote a string for YAML if it contains `:` or starts with a special char."
  [s]
  (if (re-find #"[:#]" s)
    (str "\"" (str/replace s "\"" "\\\"") "\"")
    s))

(defn- csv
  "Split a comma-separated flag value into a trimmed, non-empty seq."
  [s]
  (when s (->> (str/split s #",") (map str/trim) (remove str/blank?) seq)))

(defn- flow-list [k vs] (str k ": [" (str/join ", " vs) "]\n"))

(defn- source-block
  "`source` is dcterms:source / prov:wasDerivedFrom and is genuinely
  many-valued. Emit a scalar for one value and a block list for several, so
  the common case stays readable."
  [vs]
  (if (= 1 (count vs))
    (str "source: " (first vs) "\n")
    (apply str "source:\n" (map #(str "  - " % "\n") vs))))

(defn frontmatter [{:keys [type title description tags today
                           ai-assisted session tools agents-skills source]}]
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
       ;; A scaffold produced from flags alone is AI-generated; one a human
       ;; specified and directed is L1. See the ladder in CLAUDE.md.
       (let [maturity (if ai-assisted (canonical-maturity "L0") (canonical-maturity "L1"))
             note     (if ai-assisted
                        "AI-generated; not yet human-reviewed"
                        "Human-directed scaffold; not yet individually reviewed")]
         (str "review_maturity: " maturity "\n"
              "review_note: " note "\n"))
       "---\n\n"
       "# " title "\n"))

(defn- assert-valid-frontmatter!
  "Guard: the file we are about to write must open and close its frontmatter
  block, or tools/validate_metadata.clj rejects it on sight. Cheap to check
  here, confusing to debug later."
  [text path]
  (when-not (and (str/starts-with? text "---\n")
                 (str/index-of (subs text 4) "\n---"))
    (usage-and-exit!
     (str "refusing to write " path
          ": generated frontmatter is not delimited by a closing `---`."
          " This is a bug in tools/new_doc.bb, not in your arguments."))))

(defn run [args]
  (let [{:keys [type title path description tags force
                ai-assisted session tools agents-skills source] :as opts} (parse-args args)]
    (when-not type  (usage-and-exit! "--type is required"))
    (when-not title (usage-and-exit! "--title is required"))
    (when-not path  (usage-and-exit! "--path is required"))
    (let [docs-prefix (str (str/replace docs-dir #"/+$" "") "/")]
      (when-not (str/starts-with? path docs-prefix)
        (usage-and-exit! (str "--path must be under " docs-prefix))))
    ;; Same rule the validator applies, read from the same place, so the
    ;; scaffolder cannot emit a document that would immediately fail `bb
    ;; tools/validate_metadata.clj`. Flag names use dashes, frontmatter keys
    ;; use underscores.
    (when ai-assisted
      (doseq [k (sort (or (:provenance-required schema) #{}))]
        (let [flag (str "--" (str/replace (name k) "_" "-"))]
          (when (str/blank? (str (get opts (keyword (str/replace (name k) "_" "-")))))
            (usage-and-exit!
             (str "--ai-assisted was given, so " flag " is required too"
                  " (:provenance-required in " schema-file ")."
                  " If it genuinely was not captured, pass " flag " "
                  (pr-str (:provenance-unknown schema))))))))
    (when-not (contains? (:types schema) type)
      (println (str "warn: type \"" type "\" is not in the taxonomy ("
                     (str/join ", " (sort (:types schema))) ") — proceeding anyway")))
    (let [f (io/file path)]
      (when (and (.exists f) (not force))
        (usage-and-exit! (str path " already exists — pass --force to overwrite")))
      (io/make-parents f)
      (let [text (frontmatter {:type type
                            :title title
                            :description description
                            :tags (csv tags)
                            :today (str (java.time.LocalDate/now))
                            :ai-assisted ai-assisted
                            :session session
                            :tools (csv tools)
                            :agents-skills (csv agents-skills)
                            :source (csv source)})]
        (assert-valid-frontmatter! text path)
        (spit f text))
      (println (str "wrote " path)))))

(when (= *file* (System/getProperty "babashka.file"))
  (run *command-line-args*))
