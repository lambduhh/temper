(ns temper.okf.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [temper.okf.cli :as cli]
            [temper.okf.config :as config]
            [temper.okf.maturity :as maturity]))

(deftest resolve-paths-precedence
  (testing "defaults: the layout the original scripts assumed"
    (is (= {:docs-dir "docs" :schema-file "docs/metadata-schema.edn"}
           (config/resolve-paths {} {}))))
  (testing "env vars"
    (is (= {:docs-dir "knowledge" :schema-file "knowledge/metadata-schema.edn"}
           (config/resolve-paths {} {"OKF_DOCS_DIR" "knowledge"})))
    (is (= {:docs-dir "docs" :schema-file "cfg/okf.edn"}
           (config/resolve-paths {} {"OKF_SCHEMA" "cfg/okf.edn"}))))
  (testing "options win over env vars"
    (is (= {:docs-dir "a" :schema-file "b.edn"}
           (config/resolve-paths {:docs-dir "a" :schema "b.edn"}
                                 {"OKF_DOCS_DIR" "x" "OKF_SCHEMA" "y.edn"}))))
  (testing "the default schema follows whichever docs dir won"
    (is (= "a/metadata-schema.edn"
           (:schema-file (config/resolve-paths {:docs-dir "a"} {"OKF_DOCS_DIR" "x"})))))
  (testing "an empty string is unset at every level, as `not-empty` made it in the originals"
    (is (= {:docs-dir "x" :schema-file "x/metadata-schema.edn"}
           (config/resolve-paths {:docs-dir "" :schema ""} {"OKF_DOCS_DIR" "x" "OKF_SCHEMA" ""})))))

(deftest config-errors-become-exit-2
  (is (= {:exit 2 :config-errors ["a" "b"]}
         (config/catch-config-errors #(config/config-error! "a" "b"))))
  (is (= {:exit 0} (config/catch-config-errors (constantly {:exit 0}))))
  (testing "other ex-info is not swallowed"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                          (config/catch-config-errors #(throw (ex-info "boom" {})))))))

(deftest read-schema-failures-are-config-errors
  (let [{:keys [exit config-errors]}
        (config/catch-config-errors #(config/read-schema! "tool" "/nonexistent/okf.edn"))]
    (is (= 2 exit))
    (is (re-matches #"tool: cannot read schema at /nonexistent/okf.edn -- .*" (first config-errors)))))

(deftest md-files-are-sorted-relpaths
  (is (= ["test/fixtures/clean/docs/README.md" "test/fixtures/clean/docs/a.md"
          "test/fixtures/clean/docs/glossary.md" "test/fixtures/clean/docs/index.md"]
         (config/md-files "./test/fixtures/clean/docs"))))

(deftest parse-flags
  (let [spec {:value {"--schema" :schema} :bool {"--check" :check}}]
    (is (= {:check true :schema "s.edn"} (cli/parse-flags ["--check" "--schema" "s.edn"] spec)))
    (is (= {} (cli/parse-flags ["stray" "--unknown"] spec)) "unrecognised arguments are ignored")
    (is (= {:schema nil} (cli/parse-flags ["--schema"] spec)) "a trailing value flag gets nil")))

(deftest maturity-targets-precedence
  (is (= ["README.md" "CLAUDE.md" "docs/README.md"] (maturity/resolve-targets {} "docs" {})))
  (is (= ["deps.edn"] (maturity/resolve-targets {} "docs" {"OKF_MATURITY_TARGETS" " deps.edn , "})))
  (is (= ["bb.edn"] (maturity/resolve-targets {:targets "bb.edn"} "docs"
                                              {"OKF_MATURITY_TARGETS" "deps.edn"}))
      "the option wins over the env var")
  (is (= ["bb.edn" "deps.edn"] (maturity/resolve-targets {:targets [" bb.edn" "" "deps.edn"]} "docs" {}))
      "a collection is trimmed and filtered like the comma-separated form")
  (is (= {:exit 2 :config-errors ["gen_maturity_table: target file(s) missing or unreadable: NOPE.md"]}
         (config/catch-config-errors #(maturity/resolve-targets {:targets "deps.edn,NOPE.md"} "docs" {})))))
