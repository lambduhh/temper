(ns temper.okf.examples-test
  "examples/metadata-schema.edn and examples/context.jsonld are what a new
  consumer copies first. A bundle built from them alone, plus one scaffolded
  document, must pass every tool."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [temper.okf.golden-support :as g]))

(defn- run-tool [dir ns & args]
  @(p/process (into [g/bb "--classpath" g/src-dir "-m" ns] args)
              {:dir (str dir) :out :string :err :string
               :env (apply dissoc (into {} (System/getenv))
                           ["OKF_DOCS_DIR" "OKF_SCHEMA" "OKF_MATURITY_TARGETS"])}))

(deftest example-schema-passes-every-tool
  (let [dir (fs/create-temp-dir {:prefix "okf-example-"})]
    (try
      (fs/create-dirs (fs/path dir "docs"))
      (fs/copy (fs/path g/root "examples" "metadata-schema.edn") (fs/path dir "docs" "metadata-schema.edn"))
      (fs/copy (fs/path g/root "examples" "context.jsonld") (fs/path dir "docs" "context.jsonld"))
      (spit (str (fs/path dir "docs" "index.md")) "---\nokf_version: \"0.1\"\n---\n\n# Index\n")
      (spit (str (fs/path dir "README.md"))
            ;; A line between the markers is required: see the adjacent-pair case
            ;; in golden_test.clj and README "Carried-over behaviour".
            "<!-- review-maturity:table:begin -->\n\n<!-- review-maturity:table:end -->\n")
      (let [scaffold (run-tool dir "temper.okf.new-doc" "--type" "Guide" "--title" "First doc"
                               "--path" "docs/first.md" "--ai-assisted" "Claude via Claude Code"
                               "--session" "s1" "--tools" "babashka")]
        (is (= 0 (:exit scaffold)) (:out scaffold)))
      (testing "validate"
        (let [{:keys [exit out]} (run-tool dir "temper.okf.validate")]
          (is (= 0 exit) out)
          (is (str/includes? out "2 documents checked · 0 with errors · 0 warnings · 0 context gaps"))))
      (testing "links"
        (is (= 0 (:exit (run-tool dir "temper.okf.links" "--glossary")))))
      (testing "maturity: write fills the empty marker pair, then --check is clean"
        (is (= 0 (:exit (run-tool dir "temper.okf.maturity"))))
        (is (str/includes? (slurp (str (fs/path dir "README.md"))) "| **L4** | Human-endorsed |"))
        (is (= 0 (:exit (run-tool dir "temper.okf.maturity" "--check")))))
      (finally (fs/delete-tree dir)))))
