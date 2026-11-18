(ns temper.okf.config
  "Where a docs bundle and its schema live, how the schema is read, and how a
  configuration failure ends a run.

  Every tool resolves its paths the same way -- an options map first, then the
  environment, then these defaults:

      option     env var        default
      :docs-dir  OKF_DOCS_DIR   \"docs\"
      :schema    OKF_SCHEMA     <docs-dir>/metadata-schema.edn

  An empty string counts as unset at every level, as it did in the original
  scripts (`(not-empty (System/getenv ...))`).

  Nothing in this namespace, or in any okf.* namespace, reads the environment,
  touches the filesystem, prints or exits when it is loaded. The original
  scripts did all of that at load time, which was fine for a script and is
  wrong for a library: requiring a tool from a REPL, a test or another program
  must not be able to end the process. All of it now happens inside functions
  called from each tool's `run`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-docs-dir "docs")

(defn- setting
  "First non-empty value of option `k`, then env var `env-var`, or nil."
  [opts env k env-var]
  (or (some-> (get opts k) str not-empty)
      (some-> (get env env-var) str not-empty)))

(defn resolve-paths
  "Resolve `{:docs-dir .. :schema-file ..}` from `opts` (:docs-dir, :schema),
  then `env` (OKF_DOCS_DIR, OKF_SCHEMA), then the defaults. `env` defaults to
  the process environment; pass a map to resolve in isolation."
  ([opts] (resolve-paths opts (System/getenv)))
  ([opts env]
   (let [docs-dir (or (setting opts env :docs-dir "OKF_DOCS_DIR") default-docs-dir)]
     {:docs-dir    docs-dir
      :schema-file (or (setting opts env :schema "OKF_SCHEMA")
                       (str docs-dir "/metadata-schema.edn"))})))

(defn config-error!
  "End the current run as unreadable configuration: the tool's `run` returns
  `{:exit 2 :config-errors lines}` and its -main prints `lines` to stderr.

  Thrown rather than returned so the checks can read top to bottom in the order
  the original scripts ran them, and the first failing check is the one
  reported -- exactly where those scripts called (System/exit 2)."
  [& lines]
  (throw (ex-info (str/join "\n" lines) {::config-error (vec lines)})))

(defn catch-config-errors
  "Call `f`. If it ends via config-error!, return
  `{:exit 2 :config-errors [line ...]}` instead; rethrow anything else."
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (if-let [lines (::config-error (ex-data e))]
        {:exit 2 :config-errors lines}
        (throw e)))))

(defn require-docs-dir!
  "The docs directory must exist and be readable; otherwise exit 2 with a
  message prefixed by `tool` (the calling tool's namespace)."
  [tool docs-dir]
  (let [dir (io/file docs-dir)]
    (when-not (and (.isDirectory dir) (.canRead dir))
      (config-error! (str tool ": docs directory " docs-dir " is missing or unreadable.")))))

(defn read-schema!
  "Read the EDN schema at `path`.

  Making the path configurable adds a way to point at nothing; a typo'd
  OKF_SCHEMA must not surface as a slurp stack trace. Same doctrine as the
  missing-key checks in each tool: unreadable configuration exits 2."
  [tool path]
  (try
    (edn/read-string (slurp path))
    (catch Exception e
      (config-error! (str tool ": cannot read schema at " path
                          " -- " (.getMessage e))))))

(defn relpath
  "A path as the tools print it: with one leading `./` removed."
  [path]
  (str/replace (str path) #"^\./" ""))

(defn md-files
  "Every `*.md` file under `docs-dir`, recursively, as relpath strings in sorted
  order."
  [docs-dir]
  (->> (file-seq (io/file docs-dir))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".md"))
       (map #(relpath (.getPath ^java.io.File %)))
       sort))
