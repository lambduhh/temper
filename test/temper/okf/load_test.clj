(ns temper.okf.load-test
  "Requiring the library must have no side effects. The original scripts read
  env vars and the schema, printed, and called System/exit at load time; a
  library that did that could end the process of whoever required it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.test :refer [deftest is]]
            [temper.okf.golden-support :as g]))

(deftest requiring-every-namespace-is-inert
  ;; A hostile environment: every path the tools could resolve points at
  ;; nothing, and the cwd has no docs/ or schema. Loading must still succeed
  ;; silently and leave the directory untouched.
  (let [dir (fs/create-temp-dir {:prefix "okf-load-"})]
    (try
      (let [env (merge (into {} (System/getenv))
                       {"OKF_DOCS_DIR" "/nonexistent/docs"
                        "OKF_SCHEMA" "/nonexistent/schema.edn"
                        "OKF_MATURITY_TARGETS" "/nonexistent/README.md"})
            {:keys [out err exit]}
            @(p/process [g/bb "--classpath" g/src-dir "-e"
                         (str "(require 'temper.okf.config 'temper.okf.cli 'temper.okf.schema 'temper.okf.validate"
                              " 'temper.okf.links 'temper.okf.maturity 'temper.okf.new-doc 'temper.okf.review)"
                              " (print :loaded)")]
                        {:dir (str dir) :env env :out :string :err :string})]
        (is (= ":loaded" out))
        (is (= "" err))
        (is (= 0 exit))
        (is (empty? (fs/list-dir dir))))
      (finally (fs/delete-tree dir)))))
