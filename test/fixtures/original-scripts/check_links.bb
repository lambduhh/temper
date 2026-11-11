#!/usr/bin/env bb
(ns tools.check-links
  "Validate links across a docs bundle against :link-policy in its schema.

  Paths default to this repo's layout (docs/ and docs/metadata-schema.edn) and
  are overridable via OKF_DOCS_DIR / OKF_SCHEMA, so this file can be copied
  into another repo unedited.

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

  Run from the repo root:

      bb tools/check_links.bb              # report
      bb tools/check_links.bb --quiet      # only files with findings
      bb tools/check_links.bb --glossary   # also sweep for glossary entries
                                           #   nothing links to

  The glossary sweep is opt-in: it answers \"is the glossary wired up?\", which
  is worth asking when the docs change shape, not on every run. It reports
  warnings only and never affects the exit code.

  Exit codes: 0 clean · 1 findings · 2 bad configuration. Warnings do not fail
  the run, matching tools/validate_metadata.clj."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(def docs-dir    (or (not-empty (System/getenv "OKF_DOCS_DIR")) "docs"))
(let [dir (io/file docs-dir)]
  (when-not (and (.isDirectory dir) (.canRead dir))
    (binding [*out* *err*]
      (println (str "check_links: docs directory " docs-dir " is missing or unreadable.")))
    (System/exit 2)))
(def schema-file (or (not-empty (System/getenv "OKF_SCHEMA"))
                     (str docs-dir "/metadata-schema.edn")))

(defn- read-schema!
  "Making the path configurable adds a way to point at nothing; a typo'd
  OKF_SCHEMA must not surface as a slurp stack trace. Same doctrine as
  validate-policy! below: unreadable configuration exits 2."
  [path]
  (try
    (edn/read-string (slurp path))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "check_links: cannot read schema at " path
                      " -- " (.getMessage e))))
      (System/exit 2))))

(def schema (read-schema! schema-file))
(def policy (:link-policy schema))

(defn- config-error! [msgs]
  (binding [*out* *err*]
    (println (str "check_links: cannot run -- :link-policy in " schema-file " is not usable."))
    (doseq [m msgs] (println "   " m))
    (println "  Refusing to report a clean run against configuration this tool cannot read."))
  (System/exit 2))

(defn- validate-policy!
  "Fail loudly on configuration this tool cannot act on.

  This exists because of a real incident: a schema key was renamed, the reader
  still asked for the old name, `(:vpn-hosts policy #{})` returned the empty
  set, and the tool reported `0 errors` while checking no URLs at all. A
  default that means do-nothing is indistinguishable from a passing run.

  So: absent key = error. Present-but-empty = a deliberate opt-out, allowed."
  []
  (let [missing (fn [k] (when-not (contains? policy k)
                          (str "missing key " k)))
        blankm  (when (and (contains? policy :internal-marker)
                           (str/blank? (str (:internal-marker policy))))
                  "`:internal-marker` is blank -- give it a marker string, e.g. \"(internal)\"")
        msgs    (cond-> (keep missing [:internal-marker :internal-hosts :verbatim-dirs
                                      :untracked-dirs])
                  (nil? policy) (concat ["`:link-policy` is absent entirely"])
                  blankm        (concat [blankm]))]
    (when (seq msgs) (config-error! msgs))))

(validate-policy!)

(def internal-hosts  (:internal-hosts policy))
(def internal-marker (:internal-marker policy))
(def verbatim-dirs   (:verbatim-dirs policy))
(def untracked-dirs  (:untracked-dirs policy))
(def glossary-path   (:glossary policy))

(defn md-files []
  (->> (file-seq (io/file docs-dir))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".md"))
       (map #(str/replace (.getPath %) #"^\./" ""))
       sort))

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

(defn headings [path]
  (when (.exists (io/file path))
    (->> (str/split-lines (slurp path))
         (keep #(second (re-matches #"#{1,6}\s+(.+?)\s*$" %)))
         (map slug)
         set)))

(def headings* (memoize headings))

(defn url-decode [s]
  (java.net.URLDecoder/decode s "UTF-8"))

(defn verbatim? [path]
  (some #(str/starts-with? path %) verbatim-dirs))

(def repo-root (.getCanonicalPath (io/file ".")))

(defn untracked-target
  "The :untracked-dirs prefix a resolved link falls under, or nil. Compares
  repo-root-relative paths so the answer does not depend on where the repo is
  checked out."
  [^java.io.File resolved]
  (let [abs (.getCanonicalPath resolved)
        rel (if (str/starts-with? abs (str repo-root "/"))
              (subs abs (inc (count repo-root)))
              abs)]
    (first (filter #(str/starts-with? rel %) untracked-dirs))))

;; [text](target) plus whatever immediately follows, so an access marker after
;; the closing paren is visible to the check. The trailing window is derived
;; from the marker length -- hard-coding it silently stops detecting a marker
;; the moment someone configures a longer one.
;;
;; The window is captured inside a lookahead so it is read but not consumed.
;; As a plain group it swallowed the start of any link that followed within
;; the window on the same line -- `[a](x.md) [b](y.md)` -- and re-seq resumed
;; after it, so the second link was never checked at all.
(def link-re
  (re-pattern (str "\\[[^\\]]*\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)"
                   "(?=([^\\n]{0," (+ 2 (count internal-marker)) "}))")))

(defn check-file [path]
  (let [content (strip-code (slurp path))
        dir     (or (.getParent (io/file path)) ".")
        errs    (atom []) warns (atom [])
        ;; Anchors inside a verbatim mirror are not ours to correct.
        anchor! (if (verbatim? path)
                  #(swap! warns conj %)
                  #(swap! errs conj %))]
    (doseq [[_ target trailing] (re-seq link-re content)]
      (cond
        ;; external
        (re-matches #"(?i)(https?|mailto):.*" target)
        (let [host (second (re-find #"(?i)https?://([^/]+)" target))]
          (when (and host (contains? internal-hosts host) (not (verbatim? path))
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
              untracked   (untracked-target resolved)]
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
  []
  (when (and glossary-path (.exists (io/file glossary-path)))
    (let [base (.getName (io/file glossary-path))
          refd (->> (md-files)
                    (mapcat #(re-seq (re-pattern (str base "#([a-z0-9-]+)")) (slurp %)))
                    (map second) set)]
      (->> (str/split-lines (slurp glossary-path))
           (keep #(second (re-matches #"###\s+(.+?)\s*$" %)))
           (remove #(contains? refd (slug %)))))))

(defn run [args]
  (let [quiet?     (some #{"--quiet"} args)
        glossary?  (some #{"--glossary"} args)
        results    (map check-file (md-files))
        orphans    (when glossary? (orphan-glossary-entries))]
    (doseq [{:keys [path errors warnings]} results]
      (when (or (seq errors) (seq warnings) (not quiet?))
        (println (if (seq errors) "FAIL" "PASS") path))
      (doseq [e errors] (println "    ERROR:" e))
      (doseq [w warnings] (println "    warn: " w)))
    (when (seq orphans)
      (println)
      (println (str "warn: " (count orphans) " glossary entr"
                    (if (= 1 (count orphans)) "y is" "ies are")
                    " never linked from any document:"))
      (doseq [o orphans] (println "    -" o)))
    (let [n-err (count (filter #(seq (:errors %)) results))]
      (println)
      (printf "%d documents checked · %d with errors%s\n"
              (count results) n-err
              (if glossary?
                (format " · %d unlinked glossary entries" (count orphans))
                ""))
      (if (pos? n-err) (do (println "FAILED") 1) (do (println "OK") 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (run *command-line-args*)))
