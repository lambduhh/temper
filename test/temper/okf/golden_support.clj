(ns temper.okf.golden-support
  "Harness for the golden tests: run an original script and the matching
  okf.* -main against identical copies of a bundle, and capture what each did.

  Both runs of a case use the same directory path, one after the other, with
  the directory rebuilt from the fixture in between -- so absolute paths that
  reach the output (canonical repo roots, slurp error messages) are identical
  by construction rather than by normalisation. Cases run in parallel with each
  other, each in its own directory."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def root
  "This repository's root, from this file's location rather than the cwd."
  (-> *file* fs/parent fs/parent fs/parent fs/parent fs/canonicalize str)) ; test/temper/okf/<file>

(def src-dir (str (fs/path root "src")))
(def fixtures-dir (str (fs/path root "test" "fixtures")))
(def originals-dir (str (fs/path fixtures-dir "original-scripts")))

(def bb
  "The babashka executable used for every child process. OKF_TEST_BB overrides."
  (or (not-empty (System/getenv "OKF_TEST_BB")) "bb"))

(def real-bundle
  "A working tree of a real OKF bundle. Real-bundle cases are skipped (with a
  notice) when it is absent."
  (or (not-empty (System/getenv "OKF_GOLDEN_REAL_BUNDLE")) "/tmp/okf-merge"))

(defn real-bundle-available? []
  (fs/exists? (fs/path real-bundle "tools" "validate_metadata.clj")))

(def tools
  {:validate {:script "validate_metadata.clj" :ns "temper.okf.validate"}
   :links    {:script "check_links.bb"        :ns "temper.okf.links"}
   :maturity {:script "gen_maturity_table.bb" :ns "temper.okf.maturity"}
   :new-doc  {:script "new_doc.bb"            :ns "temper.okf.new-doc"}})

(def ^:private scrubbed-env
  "The parent environment minus anything that would change what a child run
  resolves, so a developer's own OKF_* settings cannot leak into a case."
  ["OKF_DOCS_DIR" "OKF_SCHEMA" "OKF_MATURITY_TARGETS"
   "BABASHKA_CLASSPATH" "BABASHKA_PRELOADS"])

(defn- child-env [extra]
  (merge (apply dissoc (into {} (System/getenv)) scrubbed-env) extra))

(defn- sh [dir env cmd]
  (let [{:keys [out err exit]} @(p/process cmd {:dir (str dir) :env (child-env env)
                                                :out :string :err :string})]
    {:out out :err err :exit exit}))

(defn- sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (->> (.digest md (fs/read-all-bytes f))
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str))))

(defn tree
  "Every file and directory under `dir`: relative path -> content hash, or
  :dir. Compared between runs, so a tool that writes, or fails to write,
  something different is caught even when its output agrees."
  [dir]
  (into (sorted-map)
        (for [f (rest (file-seq (io/file (str dir))))]
          [(str (fs/relativize dir f)) (if (.isDirectory f) :dir (sha256 f))])))

(defn- copy-real-bundle!
  "Copy the parts of the real bundle the tools read -- not .git -- into `dir`."
  [dir]
  (fs/create-dirs dir)
  (doseq [entry ["docs" "README.md" "CLAUDE.md"]
          :let [src (fs/path real-bundle entry)]
          :when (fs/exists? src)]
    (if (fs/directory? src)
      (fs/copy-tree src (fs/path dir entry))
      (fs/copy src (fs/path dir entry)))))

(defn- setup!
  "Rebuild `dir` for a case: copy the bundle, then apply :write, :schema-edit
  and :remove, in that order."
  [dir {:keys [bundle write schema-path schema-edit remove]}]
  (fs/delete-tree dir)
  (if (= bundle :real-copy)
    (copy-real-bundle! dir)
    (fs/copy-tree (fs/path fixtures-dir bundle) dir))
  (doseq [[path content] write
          :let [f (fs/file dir path)]]
    (io/make-parents f)
    (spit f content))
  (when schema-edit
    (let [f (fs/file dir (or schema-path "docs/metadata-schema.edn"))]
      (spit f (pr-str (schema-edit (edn/read-string (slurp f)))))))
  (doseq [path remove]
    (fs/delete-tree (fs/path dir path))))

(defn- original-cmd [{:keys [tool bundle args]}]
  (let [script (:script (tools tool))
        ;; Always the vendored oracle (which carries upstream fix bc7dba5), so
        ;; real-bundle and fixture cases are judged against the same script.
        dir    originals-dir]
    (into [bb (str (fs/path dir script))] args)))

(defn- library-cmd [{:keys [tool args lib-args] :as kase}]
  (into [bb "--classpath" src-dir "-m" (:ns (tools tool))]
        (if (contains? kase :lib-args) lib-args args)))

(defn- after-run
  "What a run left behind: the file tree, plus the text of any files the case
  names in :capture (for cases that assert on content, not just equality)."
  [dir result {:keys [capture]}]
  (assoc result
         :tree  (tree dir)
         :files (into {} (for [path capture
                               :let [f (fs/file dir path)]]
                           [path (when (fs/exists? f) (slurp f))]))))

(defn run-case
  "Run one case both ways. Returns {:case .. :original {..} :library {..}},
  each side {:out :err :exit :tree :files}. `:bundle :real` runs read-only in
  the real bundle itself, as the original scripts are run there; every other
  bundle is copied into `work-dir`."
  [work-dir {:keys [id bundle env lib-env] :as kase}]
  (let [lib-env (if (contains? kase :lib-env) lib-env env)]
    (if (= bundle :real)
      {:case     kase
       :original (sh real-bundle env (original-cmd kase))
       :library  (sh real-bundle lib-env (library-cmd kase))}
      (let [dir (str (fs/path work-dir (str/replace id #"[^A-Za-z0-9_.-]" "_")))
            orig (do (setup! dir kase)
                     (after-run dir (sh dir env (original-cmd kase)) kase))
            lib  (do (setup! dir kase)
                     (after-run dir (sh dir lib-env (library-cmd kase)) kase))]
        {:case kase :original orig :library lib}))))

(defn run-cases
  "Run `cases` in parallel under a fresh temp directory, deleted afterwards
  unless OKF_GOLDEN_KEEP is set."
  [cases]
  (let [work (str (fs/create-temp-dir {:prefix "okf-golden-"}))]
    (try
      (doall (pmap #(run-case work %) cases))
      (finally
        (if (System/getenv "OKF_GOLDEN_KEEP")
          (println "golden work dir kept at" work)
          (fs/delete-tree work))))))
