(ns temper.okf.api-test
  "The library called from Clojure: `run` returns data, prints nothing and
  never exits. The schemas' path-valued keys are relative to the working
  directory, so these tests build schemas with absolute paths and run against
  absolute docs dirs -- independent of where the suite is run from."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [temper.okf.golden-support :as g]
            [temper.okf.links :as links]
            [temper.okf.maturity :as maturity]
            [temper.okf.new-doc :as new-doc]
            [temper.okf.validate :as validate]))

(def ^:dynamic *tmp* nil)

(use-fixtures :each
  (fn [t]
    (let [dir (fs/canonicalize (fs/create-temp-dir {:prefix "okf-api-"}))]
      (try (binding [*tmp* (str dir)] (t))
           (finally (fs/delete-tree dir))))))

(defn- bundle!
  "Copy fixture `name` into the temp dir and rewrite its schema's path-valued
  keys to absolute paths. Returns {:docs-dir :schema}."
  [name & {:keys [edit] :or {edit identity}}]
  (let [dir (str (fs/path *tmp* (str name "-" (gensym))))
        _ (fs/copy-tree (fs/path g/fixtures-dir name) dir)
        schema-file (str dir "/docs/metadata-schema.edn")
        abs #(str dir "/" %)
        schema (-> (edn/read-string (slurp schema-file))
                   (update :bundle-root abs)
                   (update :context-file abs)
                   (update-in [:link-policy :glossary] abs)
                   (update-in [:link-policy :verbatim-dirs] #(set (map abs %)))
                   (update-in [:link-policy :untracked-dirs] #(set (map abs %)))
                   edit)]
    (spit schema-file (pr-str schema))
    {:docs-dir (str dir "/docs") :schema schema-file :dir dir}))

(defn- quietly
  "Call f, asserting it printed nothing to stdout or stderr."
  [f]
  (let [err (java.io.StringWriter.)
        result (atom nil)
        out (binding [*err* err] (with-out-str (reset! result (f))))]
    (is (= "" out) "run printed to stdout")
    (is (= "" (str err)) "run printed to stderr")
    @result))

(deftest validate-run
  (testing "a clean bundle"
    (let [{:keys [docs-dir schema]} (bundle! "clean")
          r (quietly #(validate/run {:docs-dir docs-dir :schema schema}))]
      (is (= {:exit 0 :documents 4 :errors 0 :warnings 0 :context-gaps []}
             (select-keys r [:exit :documents :errors :warnings :context-gaps])))
      (is (= (str docs-dir "/index.md") (:path (last (sort-by :path (:results r))))))
      (is (str/ends-with? (validate/report r) "0 context gaps\nOK\n"))))
  (testing "the full fixture: same counts the CLI summary prints"
    (let [{:keys [docs-dir schema]} (bundle! "full")
          r (quietly #(validate/run {:docs-dir docs-dir :schema schema}))
          by-name (into {} (map (juxt #(fs/file-name (:path %)) identity)) (:results r))]
      (is (= {:exit 1 :documents 33 :errors 16 :warnings 10}
             (select-keys r [:exit :documents :errors :warnings])))
      (is (= 2 (count (:context-gaps r))))
      (is (= 3 (count (:errors (by-name "bad-dates.md")))))
      (is (= 2 (count (:warnings (by-name "provenance-unrecorded.md")))))))
  (testing "date values are checked against the @type the JSON-LD context declares"
    (let [{:keys [docs-dir schema dir]} (bundle! "clean")
          ctx-file (str docs-dir "/context.jsonld")
          doc! (fn [n fm] (spit (str docs-dir "/" n) (str "---\ntype: Guide\n" fm "---\n\n# x\n")))
          warnings (fn [n] (->> (quietly #(validate/run {:docs-dir docs-dir :schema schema}))
                                :results (filter #(= (str docs-dir "/" n) (:path %)))
                                first :warnings))]
      (doc! "ts.md" "review_maturity: L2 (Human-reviewed)\nreviewed_at: 2026-11-11T23:41:05Z\n")
      (doc! "mod.md" "modified: 2026-10-01T10:00:00Z\n")
      (doc! "ok.md" "created: 2026-10-01\n")
      (is (= 1 (count (warnings "ts.md"))))
      (is (str/includes? (first (warnings "ts.md")) "change its @type to xsd:dateTime"))
      (is (str/includes? (first (warnings "mod.md")) "write it as a date, or change its @type"))
      (is (empty? (warnings "ok.md")))
      (testing "with reviewed_at retyped to xsd:dateTime, a date is the mismatch"
        (spit ctx-file (str/replace (slurp ctx-file)
                                    #"(\"reviewed_at\": \{[^}]*)xsd:date\""
                                    "$1xsd:dateTime\""))
        (doc! "d.md" "review_maturity: L2 (Human-reviewed)\nreviewed_at: 2026-09-01\n")
        (is (empty? (warnings "ts.md")))
        (is (str/includes? (first (warnings "d.md")) "don't hand-edit reviewed_at"))))
    (testing "prefixes are expanded from the context itself"
      (is (= {:a :date :b :datetime}
             (validate/context-date-types
              {"xsd" "http://www.w3.org/2001/XMLSchema#"
               "a" {"@type" "xsd:date"}
               "b" {"@type" "http://www.w3.org/2001/XMLSchema#dateTime"}
               "c" {"@type" "@id"}
               "d" "dcterms:x"})))
      (is (= {} (validate/context-date-types {"a" {"@type" "xsd:date"}}))
          "without an xsd prefix, xsd:date is an absolute IRI, not the XSD type")))
  (testing "unreadable configuration is data, not an exit"
    (is (= {:exit 2 :config-errors ["validate_metadata: docs directory /nonexistent is missing or unreadable."]}
           (quietly #(validate/run {:docs-dir "/nonexistent"}))))
    (let [{:keys [docs-dir schema]} (bundle! "clean" :edit #(dissoc % :provenance-unknown))]
      (is (= 2 (:exit (quietly #(validate/run {:docs-dir docs-dir :schema schema})))))
      (is (= "" (validate/report {:exit 2 :config-errors ["x"]}))))))

(deftest links-run
  (let [{:keys [docs-dir schema]} (bundle! "clean")]
    (is (= {:exit 0 :errors 0 :orphans ["Lonely term"] :glossary true}
           (select-keys (quietly #(links/run {:docs-dir docs-dir :schema schema :glossary true}))
                        [:exit :errors :orphans :glossary])))
    (is (nil? (:orphans (quietly #(links/run {:docs-dir docs-dir :schema schema}))))))
  (let [{:keys [docs-dir schema]} (bundle! "full")
        r (quietly #(links/run {:docs-dir docs-dir :schema schema :glossary true}))
        by-name (into {} (map (juxt #(fs/file-name (:path %)) identity)) (:results r))]
    (is (= 1 (:exit r)))
    (is (= ["Term B" "Term C (extra)"] (:orphans r)))
    (is (= 2 (count (:warnings (by-name "links.md")))) "both untracked notes/ links warn")
    (is (= 2 (count (:warnings (by-name "verbatim.md")))) "verbatim anchors warn")))

(deftest link-helpers
  (is (= "sdb" (links/slug "S&DB")))
  (is (= "term-c-extra" (links/slug " Term C (extra) ")))
  (is (= "a  b" (links/strip-code "a `[x](y.md)` b")))
  (testing "the marker window follows the marker's length"
    (let [re (links/link-re "(VPN required)")]
      (is (= " (VPN required)" (nth (re-find re "[a](https://x/) (VPN required)") 2))))))

(deftest maturity-run
  (let [{:keys [dir docs-dir schema]} (bundle! "full")
        readme (str dir "/README.md")
        opts {:docs-dir docs-dir :schema schema :targets [readme (str dir "/CLAUDE.md")]}
        check (quietly #(maturity/run (assoc opts :check true)))]
    (is (= {:exit 1 :stale 1 :with-markers 1 :levels 5}
           (select-keys check [:exit :stale :with-markers :levels])))
    (is (str/includes? (slurp readme) "Old label") "--check does not write")
    (let [write (quietly #(maturity/run opts))]
      (is (= 0 (:exit write)))
      (is (= (:after (first (:files write))) (slurp readme)) "the rewritten file is what :after promised"))
    (is (= 0 (:exit (quietly #(maturity/run (assoc opts :check true))))) "and is now current")))

(deftest maturity-render
  (let [levels [{:level "L0" :label "A" :meaning "m0"} {:level "L1" :label "B" :meaning "m1"}]]
    (is (= "**L0** A · **L1** B" (maturity/render-inline levels)))
    (is (= "| Level | Label | Meaning |\n|---|---|---|\n| **L0** | A | m0 |\n| **L1** | B | m1 |"
           (maturity/render-table levels)))
    (is (= "x\n<!-- review-maturity:inline:begin -->\n**L0** A · **L1** B\n<!-- review-maturity:inline:end -->"
           (maturity/transform levels "x\n<!-- review-maturity:inline:begin -->\nold\n<!-- review-maturity:inline:end -->")))
    (is (= "no markers" (maturity/transform levels "no markers")))))

(deftest new-doc-round-trips-through-validate
  ;; The scaffolder's docstring promises it cannot emit a document the
  ;; validator would reject. Hold it to that from the API.
  (let [{:keys [docs-dir schema]} (bundle! "clean")
        path (str docs-dir "/new/scaffold.md")
        r (quietly #(new-doc/run {:docs-dir docs-dir :schema schema :path path
                                  :type "Research" :title "Scaffold: from the API"
                                  :tags ["api" " test "] :source ["https://a.example" "https://b.example"]
                                  :ai-assisted "Claude via Claude Code" :session "s1" :tools ["babashka"]
                                  :today "2026-01-02"}))]
    (is (= 0 (:exit r)))
    (is (= (slurp path) (:text r)))
    (is (str/includes? (:text r) "created: 2026-01-02\nmodified: 2026-01-02\n"))
    (is (str/includes? (:text r) "tags: [api, test]\n"))
    (is (str/includes? (:text r) "source:\n  - https://a.example\n  - https://b.example\n"))
    (is (str/includes? (:text r) "review_maturity: L0 (AI-generated)\n"))
    (let [v (quietly #(validate/run {:docs-dir docs-dir :schema schema}))]
      (is (= 0 (:exit v)))
      (is (= {:path path :errors [] :warnings []}
             (first (filter #(= path (:path %)) (:results v))))))))

(deftest new-doc-usage-errors
  (let [{:keys [docs-dir schema]} (bundle! "clean")
        base {:docs-dir docs-dir :schema schema :type "Guide" :title "T"}]
    (is (= {:exit 1 :error "--path is required" :warnings [] :docs-dir docs-dir}
           (quietly #(new-doc/run base))))
    (testing "an empty collection is as blank as an empty string"
      (is (str/includes? (:error (quietly #(new-doc/run (assoc base :path (str docs-dir "/x.md")
                                                               :ai-assisted "C" :session "s" :tools []))))
                         "--tools is required")))
    (testing "warnings collected before the run ended are kept"
      (is (= 1 (count (:warnings (quietly #(new-doc/run (assoc base :type "Tutorial"
                                                               :path (str docs-dir "/a.md")))))))))
    (testing ":force true overwrites from the API"
      (is (= 1 (:exit (quietly #(new-doc/run (assoc base :path (str docs-dir "/a.md")))))))
      (is (= 0 (:exit (quietly #(new-doc/run (assoc base :path (str docs-dir "/a.md") :force true)))))))
    (is (= {:exit 2} (select-keys (quietly #(new-doc/run (assoc base :schema "/nonexistent.edn"))) [:exit])))))
