(ns temper.okf.links
  "Validate links across a docs bundle against :link-policy in its schema.

  Paths resolve from options, then OKF_DOCS_DIR / OKF_SCHEMA, then the original
  layout (docs/ and docs/metadata-schema.edn) -- see temper.okf.config.

  Three classes of defect, all mechanically detectable, all previously caught
  only by hand:

    ERROR  a relative link that does not resolve to a file on disk
    ERROR  an anchor that does not match any heading in its target file
    ERROR  an internal URL without an access marker

  Configuration comes from :link-policy and is validated before any file is
  read. A key that is ABSENT is a configuration error and exits 2 -- silently
  defaulting to \"check nothing\" turns a rename or typo into a green run that
  verified nothing, which is worse than a crash. A key that is PRESENT BUT
  EMPTY is an explicit opt-out and is honoured.

  Relative links into :untracked-dirs -- gitignored planning and handoff
  notes -- are reported as warnings, and identically whether or not the target
  exists locally. A gitignored directory is present on the author's machine and
  absent in CI, so an existence check there is more permissive locally than on
  the pull request; reporting the link itself removes the asymmetry.

  Documents under :verbatim-dirs are mirrors of material authored elsewhere.
  Their anchors came from the original export and use that tool's slug format,
  not GitHub's, so anchor findings there are warnings -- we cannot fix them
  without editing a verbatim copy. A link that resolves to no file is still an
  error everywhere, because that is a real break regardless of authorship.

  The policy's path-valued keys (:verbatim-dirs, :untracked-dirs, :glossary)
  are relative to the working directory (the repo root), not the docs dir.

  From a project that depends on this library (see README.md):

      bb -m temper.okf.links              # report
      bb -m temper.okf.links --quiet      # only files with findings
      bb -m temper.okf.links --glossary   # also sweep for glossary entries
                                   #   nothing links to

  The glossary sweep is opt-in: it answers \"is the glossary wired up?\", which
  is worth asking when the docs change shape, not on every run. It reports
  warnings only and never affects the exit code.

  Exit codes: 0 clean · 1 findings · 2 bad configuration. Warnings do not fail
  the run, matching temper.okf.validate."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [temper.okf.cli :as cli]
            [temper.okf.config :as config]))

(def ^:private tool
  "Prefix for configuration errors on stderr: this namespace. The original
  script's name, `check_links`, named a file only its own repo had."
  "temper.okf.links")

(defn- policy-error! [schema-file msgs]
  (apply config/config-error!
         (concat [(str tool ": cannot run -- :link-policy in " schema-file " is not usable.")]
                 (map #(str "    " %) msgs)
                 ["  Refusing to report a clean run against configuration this tool cannot read."])))

(defn validate-policy!
  "Fail loudly on configuration this tool cannot act on.

  This exists because of a real incident: a schema key was renamed, the reader
  still asked for the old name, `(:vpn-hosts policy #{})` returned the empty
  set, and the tool reported `0 errors` while checking no URLs at all. A
  default that means do-nothing is indistinguishable from a passing run.

  So: absent key = error. Present-but-empty = a deliberate opt-out, allowed."
  [schema-file policy]
  (let [missing (fn [k] (when-not (contains? policy k)
                          (str "missing key " k)))
        blankm  (when (and (contains? policy :internal-marker)
                           (str/blank? (str (:internal-marker policy))))
                  "`:internal-marker` is blank -- give it a marker string, e.g. \"(internal)\"")
        msgs    (cond-> (keep missing [:internal-marker :internal-hosts :verbatim-dirs
                                      :untracked-dirs])
                  (nil? policy) (concat ["`:link-policy` is absent entirely"])
                  blankm        (concat [blankm]))]
    (when (seq msgs) (policy-error! schema-file msgs))))

(defn strip-code
  "Remove fenced and inline code before scanning, so a link inside an example
  is not reported as a real one."
  [s]
  (-> s
      (str/replace #"(?s)```.*?```" "")
      (str/replace #"`[^`\n]*`" "")))

(defn slug
  "GitHub's heading-anchor algorithm: lowercase, drop punctuation other than
  hyphens, collapse whitespace to hyphens. `### S&DB` -> `sdb`."
  [heading]
  (-> heading str/trim str/lower-case
      (str/replace #"[^\w\s-]" "")
      (str/replace #"\s+" "-")))

(defn headings
  "The set of heading slugs in the file at `path`, or nil if it does not exist."
  [path]
  (when (.exists (io/file path))
    (->> (str/split-lines (slurp path))
         (keep #(second (re-matches #"#{1,6}\s+(.+?)\s*$" %)))
         (map slug)
         set)))

(defn url-decode [s]
  (java.net.URLDecoder/decode s "UTF-8"))

(defn link-re
  "[text](target) plus whatever immediately follows, so an access marker after
  the closing paren is visible to the check. The trailing window is derived
  from the marker length -- hard-coding it silently stops detecting a marker
  the moment someone configures a longer one.

  The window is captured inside a lookahead, so it is read but not consumed.
  As a plain group it swallowed the start of a link that followed within the
  window on the same line -- `[a](x.md) [b](y.md)` -- and re-seq resumed after
  it, so the second link was never checked."
  [internal-marker]
  (re-pattern (str "\\[[^\\]]*\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)"
                   "(?=([^\\n]{0," (+ 2 (count internal-marker)) "}))")))

(defn load-config
  "Resolve paths, read the schema and validate :link-policy, in the order the
  original script did at load time. Ends the run via temper.okf.config/config-error!
  (exit 2) on configuration this tool cannot read."
  [opts]
  (let [{:keys [docs-dir schema-file]} (config/resolve-paths opts)
        _      (config/require-docs-dir! tool docs-dir)
        schema (config/read-schema! tool schema-file)
        policy (:link-policy schema)]
    (validate-policy! schema-file policy)
    {:docs-dir        docs-dir
     :schema-file     schema-file
     :internal-hosts  (:internal-hosts policy)
     :internal-marker (:internal-marker policy)
     :verbatim-dirs   (:verbatim-dirs policy)
     :untracked-dirs  (:untracked-dirs policy)
     :glossary-path   (:glossary policy)
     :link-re         (link-re (:internal-marker policy))
     :repo-root       (.getCanonicalPath (io/file "."))
     ;; Per run, not per process: a long-lived caller must not see headings
     ;; cached from an earlier run against files that have since changed.
     :headings        (memoize headings)}))

(defn verbatim?
  "True if `path` falls under one of the policy's :verbatim-dirs prefixes."
  [cfg path]
  (some #(str/starts-with? path %) (:verbatim-dirs cfg)))

(defn untracked-target
  "The :untracked-dirs prefix a resolved link falls under, or nil. Compares
  repo-root-relative paths so the answer does not depend on where the repo is
  checked out."
  [cfg ^java.io.File resolved]
  (let [repo-root (:repo-root cfg)
        abs (.getCanonicalPath resolved)
        rel (if (str/starts-with? abs (str repo-root "/"))
              (subs abs (inc (count repo-root)))
              abs)]
    (first (filter #(str/starts-with? rel %) (:untracked-dirs cfg)))))

(defn check-file
  "Check every link in the Markdown file at `path`. Returns
  {:path path :errors [msg ...] :warnings [msg ...]}."
  [cfg path]
  (let [{:keys [internal-hosts internal-marker link-re]} cfg
        headings* (:headings cfg)
        content (strip-code (slurp path))
        dir     (or (.getParent (io/file path)) ".")
        errs    (atom []) warns (atom [])
        ;; Anchors inside a verbatim mirror are not ours to correct.
        anchor! (if (verbatim? cfg path)
                  #(swap! warns conj %)
                  #(swap! errs conj %))]
    (doseq [[_ target trailing] (re-seq link-re content)]
      (cond
        ;; external
        (re-matches #"(?i)(https?|mailto):.*" target)
        (let [host (second (re-find #"(?i)https?://([^/]+)" target))]
          (when (and host (contains? internal-hosts host) (not (verbatim? cfg path))
                     (not (str/includes? trailing internal-marker)))
            (swap! errs conj (str "internal URL without a `" internal-marker
                                  "` marker: " target))))

        ;; same-document anchor
        (str/starts-with? target "#")
        (let [a (subs target 1)]
          (when-not (contains? (headings* path) a)
            (anchor! (str "anchor #" a " matches no heading in this file"))))

        ;; relative path, optionally with an anchor
        :else
        (let [[fp anchor] (str/split target #"#" 2)
              resolved    (.getCanonicalFile (io/file dir (url-decode fp)))
              untracked   (untracked-target cfg resolved)]
          (cond
            untracked
            (swap! warns conj
                   (str "links into gitignored " untracked
                        " -- will not resolve for other readers: " target))

            (not (.exists resolved))
            (swap! errs conj (str "link does not resolve: " target))

            (and anchor (str/ends-with? fp ".md")
                 (not (contains? (headings* (str resolved)) anchor)))
            (anchor! (str "anchor #" anchor " matches no heading in " fp))))))
    {:path path :errors @errs :warnings @warns}))

(defn orphan-glossary-entries
  "Glossary headings that nothing in the bundle links to. Opt-in via --glossary:
  an entry may be legitimately reference-only, defining a term a reader meets in
  the source documents rather than in ours, so this is a question worth asking
  when the docs change shape -- not a finding worth repeating every run."
  [cfg]
  (let [glossary-path (:glossary-path cfg)]
    (when (and glossary-path (.exists (io/file glossary-path)))
      (let [base (.getName (io/file glossary-path))
            refd (->> (config/md-files (:docs-dir cfg))
                      (mapcat #(re-seq (re-pattern (str base "#([a-z0-9-]+)")) (slurp %)))
                      (map second) set)]
        (->> (str/split-lines (slurp glossary-path))
             (keep #(second (re-matches #"###\s+(.+?)\s*$" %)))
             (remove #(contains? refd (slug %))))))))

(defn run
  "Check links in every Markdown document under the docs directory. Prints
  nothing and never exits.

  opts: :docs-dir, :schema (see temper.okf.config/resolve-paths), and
    :quiet     true to omit PASS lines for files with no findings from the report
    :glossary  true to also sweep for unlinked glossary entries

  Returns, on a completed run:
    {:exit      0 or 1
     :results   [{:path :errors [..] :warnings [..]} ...]   sorted by :path
     :documents number of documents checked
     :errors    number of documents with at least one error
     :warnings  total number of warnings
     :orphans   [heading ...] or nil   unlinked glossary entries (only with :glossary)
     :quiet .. :glossary ..}
  or, on unreadable configuration, {:exit 2 :config-errors [line ...]}."
  ([] (run {}))
  ([opts]
   (config/catch-config-errors
    (fn []
      (let [cfg     (load-config opts)
            results (mapv #(check-file cfg %) (config/md-files (:docs-dir cfg)))
            orphans (when (:glossary opts) (seq (orphan-glossary-entries cfg)))
            n-err   (count (filter #(seq (:errors %)) results))]
        {:exit      (if (pos? n-err) 1 0)
         :results   results
         :documents (count results)
         :errors    n-err
         :warnings  (reduce + (map #(count (:warnings %)) results))
         :orphans   (some-> orphans vec)
         :quiet     (boolean (:quiet opts))
         :glossary  (boolean (:glossary opts))})))))

(defn report
  "The text the original script printed to stdout for `result`. Empty for a
  configuration error, whose lines go to stderr instead."
  [{:keys [exit results orphans quiet glossary documents] :as result}]
  (if (= 2 exit)
    ""
    (with-out-str
      (doseq [{:keys [path errors warnings]} results]
        (when (or (seq errors) (seq warnings) (not quiet))
          (println (if (seq errors) "FAIL" "PASS") path))
        (doseq [e errors] (println "    ERROR:" e))
        (doseq [w warnings] (println "    warn: " w)))
      (when (seq orphans)
        (println)
        (println (str "warn: " (count orphans) " glossary entr"
                      (if (= 1 (count orphans)) "y is" "ies are")
                      " never linked from any document:"))
        (doseq [o orphans] (println "    -" o)))
      (println)
      (printf "%d documents checked · %d with errors%s\n"
              documents (:errors result)
              (if glossary
                (format " · %d unlinked glossary entries" (count orphans))
                ""))
      (println (if (pos? (:errors result)) "FAILED" "OK")))))

(defn -main
  "CLI entry point. Flags: --quiet, --glossary, --docs-dir DIR, --schema FILE.
  Prints the report and exits 0 (clean), 1 (findings) or 2 (bad configuration)."
  [& args]
  (let [result (run (cli/parse-flags args {:value cli/path-flags
                                           :bool  {"--quiet" :quiet "--glossary" :glossary}}))]
    (cli/exit! result (report result))))
