(ns temper.okf.golden-test
  "Golden tests: every case runs the original script and the
  library's -main against identical bundles and requires identical stdout,
  stderr, exit code and resulting file tree.

  Each case also pins its expected exit code (and, where useful, a substring of
  the output), so a case cannot pass by both sides failing the same wrong way
  -- for instance a fixture typo that makes both report an unreadable schema.

  Cases listed in `intentional-differences` are the exceptions: there the
  library deliberately departs from the original, and the test pins both
  behaviours separately. Each carries its reason; README.md lists them too."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [temper.okf.golden-support :as g]))

;; ---------------------------------------------------------------------------
;; Schema edits shared by several cases

(defn- dissoc-policy [& ks] #(apply update % :link-policy dissoc ks))
(defn- assoc-policy [k v] #(assoc-in % [:link-policy k] v))

(def ^:private original-commands
  "What the fixture schemas set :commands to: the original scripts' own
  commands, so cases that print the usage or FAILED line compare
  byte-for-byte. The difference/*-command cases pin what happens without it."
  {:new-doc "bb tools/new_doc.bb" :maturity "bb tools/gen_maturity_table.bb"})

(def ^:private full-env-relocated
  {"OKF_DOCS_DIR" "knowledge" "OKF_SCHEMA" "cfg/okf.edn"})

;; ---------------------------------------------------------------------------
;; validate

(def validate-cases
  [{:id "validate/full" :bundle "full" :exit 1 :expect "16 with errors"}
   {:id "validate/clean" :bundle "clean" :exit 0}
   {:id "validate/docs-dir-dot-slash" :bundle "clean" :env {"OKF_DOCS_DIR" "./docs"} :exit 0}
   {:id "validate/docs-dir-trailing-slash" :bundle "clean" :env {"OKF_DOCS_DIR" "docs/"} :exit 0}
   {:id "validate/relocated-env" :bundle "relocated" :env full-env-relocated :exit 0
    :expect "knowledge/a.md"}
   {:id "validate/relocated-flags" :bundle "relocated" :env full-env-relocated
    :lib-env {} :lib-args ["--docs-dir" "knowledge" "--schema" "cfg/okf.edn"] :exit 0}
   {:id "validate/flags-override-env" :bundle "relocated" :env full-env-relocated
    :lib-env {"OKF_DOCS_DIR" "nope" "OKF_SCHEMA" "nope.edn"}
    :lib-args ["--docs-dir" "knowledge" "--schema" "cfg/okf.edn"] :exit 0}
   {:id "validate/relocated-default-schema" :bundle "relocated" :env {"OKF_DOCS_DIR" "knowledge"}
    :exit 2 :expect "cannot read schema at knowledge/metadata-schema.edn"}
   {:id "validate/missing-docs-dir" :bundle "clean" :env {"OKF_DOCS_DIR" "nope"}
    :exit 2 :expect "docs directory nope is missing or unreadable"}
   {:id "validate/missing-docs-dir-flag" :bundle "clean" :env {"OKF_DOCS_DIR" "nope"}
    :lib-env {} :lib-args ["--docs-dir" "nope"] :exit 2}
   {:id "validate/schema-missing" :bundle "clean" :env {"OKF_SCHEMA" "nope.edn"}
    :exit 2 :expect "No such file"}
   {:id "validate/schema-malformed" :bundle "clean"
    :write {"docs/metadata-schema.edn" "{:types #{\"A\"} :oops"} :exit 2 :expect "cannot read schema"}
   {:id "validate/schema-empty" :bundle "clean" :write {"docs/metadata-schema.edn" ""}
    :exit 2 :expect "is missing :review-staleness"}
   {:id "validate/no-review-staleness" :bundle "clean" :schema-edit #(dissoc % :review-staleness)
    :exit 2 :expect "is missing :review-staleness"}
   {:id "validate/no-provenance-required" :bundle "clean" :schema-edit #(dissoc % :provenance-required)
    :exit 2 :expect "is missing :provenance-required"}
   {:id "validate/no-provenance-unknown" :bundle "clean" :schema-edit #(dissoc % :provenance-unknown)
    :exit 2 :expect "is missing :provenance-unknown"}
   {:id "validate/no-provenance-either" :bundle "clean"
    :schema-edit #(dissoc % :provenance-required :provenance-unknown)
    :exit 2 :expect "is missing :provenance-required"}
   {:id "validate/no-context-file" :bundle "clean" :schema-edit #(dissoc % :context-file)
    :exit 2 :expect "is missing :context-file"}
   {:id "validate/context-file-blank" :bundle "full" :schema-edit #(assoc % :context-file "")
    :exit 1 :expect "0 context gaps"}
   {:id "validate/context-file-whitespace" :bundle "full" :schema-edit #(assoc % :context-file "   ")
    :exit 1 :expect "0 context gaps"}
   {:id "validate/context-file-missing" :bundle "clean"
    :schema-edit #(assoc % :context-file "docs/nope.jsonld") :exit 2 :expect "missing or unreadable"}
   {:id "validate/context-file-is-dir" :bundle "clean" :schema-edit #(assoc % :context-file "docs")
    :exit 2 :expect "points at docs,"}
   {:id "validate/staleness-from-L3" :bundle "full" :schema-edit #(assoc % :review-staleness {:from "L3"})
    :exit 1}
   {:id "validate/staleness-from-unknown-level" :bundle "full"
    :schema-edit #(assoc % :review-staleness {:from "L9"}) :exit 1}
   {:id "validate/provenance-required-empty" :bundle "full"
    :schema-edit #(assoc % :provenance-required #{}) :exit 1}
   {:id "validate/provenance-unknown-custom" :bundle "full"
    :schema-edit #(assoc % :provenance-unknown "n/a") :exit 1}
   {:id "validate/no-bundle-root-file" :bundle "clean" :remove ["docs/index.md"] :exit 0
    :expect "3 documents checked"}
   {:id "validate/index-no-frontmatter" :bundle "clean" :write {"docs/index.md" "# Index\n"}
    :exit 1 :expect "must declare okf_version"}
   {:id "validate/okf-version-mismatch" :bundle "clean" :schema-edit #(assoc % :okf-version "0.2")
    :exit 1 :expect "okf_version must be \"0.2\""}
   {:id "validate/no-types-key" :bundle "clean" :schema-edit #(dissoc % :types) :exit 0
    :expect "is not in the taxonomy ()"}
   {:id "validate/no-date-keys" :bundle "full" :schema-edit #(dissoc % :date-keys) :exit 1}
   ;; With :known-keys absent every key warns as unknown, and the JSON-LD check
   ;; expects nothing -- a silently weakened rule; see README "Open questions".
   {:id "validate/no-known-keys" :bundle "clean" :schema-edit #(dissoc % :known-keys) :exit 0
    :expect "unknown key `type`"}
   {:id "validate/unknown-args-ignored" :bundle "clean" :args ["--verbose" "extra"] :exit 0}
   {:id "validate/real" :bundle :real :exit 0}
   {:id "validate/real-env" :bundle :real
    :env {"OKF_DOCS_DIR" "docs" "OKF_SCHEMA" "docs/metadata-schema.edn"} :exit 0}
   {:id "validate/real-flags" :bundle :real :lib-args ["--docs-dir" "docs" "--schema" "docs/metadata-schema.edn"]
    :exit 0}])

;; ---------------------------------------------------------------------------
;; links

(def links-cases
  [{:id "links/full" :bundle "full" :exit 1 :expect "link does not resolve: missing.md"}
   {:id "links/full-quiet" :bundle "full" :args ["--quiet"] :exit 1}
   {:id "links/full-glossary" :bundle "full" :args ["--glossary"] :exit 1
    :expect "2 glossary entries are never linked"}
   {:id "links/full-glossary-quiet" :bundle "full" :args ["--glossary" "--quiet"] :exit 1}
   {:id "links/clean" :bundle "clean" :exit 0}
   ;; Two links close together on one line: both must be checked (upstream fix bc7dba5).
   {:id "links/adjacent-links" :bundle "clean" :exit 1 :expect "nope2.md"
    :write {"docs/adj.md" "---\ntype: Reference\n---\n\n# Adj\n\n[a](a.md) [b](nope2.md)\n"}}
   {:id "links/clean-quiet" :bundle "clean" :args ["--quiet"] :exit 0}
   {:id "links/clean-glossary" :bundle "clean" :args ["--glossary"] :exit 0
    :expect "1 glossary entry is never linked"}
   {:id "links/relocated-env" :bundle "relocated" :env full-env-relocated :args ["--glossary"] :exit 1
    :expect "without a `(VPN required)` marker"}
   {:id "links/relocated-flags" :bundle "relocated" :env full-env-relocated :args ["--glossary"]
    :lib-env {} :lib-args ["--glossary" "--docs-dir" "knowledge" "--schema" "cfg/okf.edn"] :exit 1}
   {:id "links/missing-docs-dir" :bundle "clean" :env {"OKF_DOCS_DIR" "nope"} :exit 2}
   {:id "links/schema-missing" :bundle "clean" :env {"OKF_SCHEMA" "nope.edn"} :exit 2}
   {:id "links/schema-malformed" :bundle "clean" :write {"docs/metadata-schema.edn" "{:link-policy"}
    :exit 2}
   {:id "links/schema-empty" :bundle "clean" :write {"docs/metadata-schema.edn" ""} :exit 2
    :expect "absent entirely"}
   {:id "links/policy-absent" :bundle "clean" :schema-edit #(dissoc % :link-policy) :exit 2
    :expect "absent entirely"}
   {:id "links/policy-missing-keys" :bundle "clean"
    :schema-edit (dissoc-policy :internal-hosts :untracked-dirs) :exit 2 :expect "missing key :internal-hosts"}
   {:id "links/policy-blank-marker" :bundle "clean" :schema-edit (assoc-policy :internal-marker "  ")
    :exit 2 :expect "is blank"}
   {:id "links/policy-blank-and-missing" :bundle "clean"
    :schema-edit (comp (assoc-policy :internal-marker "") (dissoc-policy :verbatim-dirs)) :exit 2}
   {:id "links/policy-empty-opt-outs" :bundle "full"
    :schema-edit (comp (assoc-policy :internal-hosts #{}) (assoc-policy :verbatim-dirs #{})
                       (assoc-policy :untracked-dirs #{}))
    :exit 1 :expect "link does not resolve: ../notes/nope.md"}
   {:id "links/longer-marker" :bundle "full" :schema-edit (assoc-policy :internal-marker "(VPN required)")
    :exit 1 :expect "without a `(VPN required)` marker"}
   {:id "links/no-glossary-key" :bundle "full" :args ["--glossary"] :schema-edit (dissoc-policy :glossary)
    :exit 1 :expect "0 unlinked glossary entries"}
   {:id "links/glossary-file-missing" :bundle "full" :args ["--glossary"] :remove ["docs/glossary.md"]
    :exit 1 :expect "0 unlinked glossary entries"}
   {:id "links/unknown-args-ignored" :bundle "full" :args ["--verbose"] :exit 1}
   {:id "links/real" :bundle :real :exit 0}
   {:id "links/real-quiet" :bundle :real :args ["--quiet"] :exit 0}
   {:id "links/real-glossary" :bundle :real :args ["--glossary"] :exit 0}
   {:id "links/real-flags" :bundle :real :args ["--glossary"]
    :lib-args ["--glossary" "--docs-dir" "docs" "--schema" "docs/metadata-schema.edn"] :exit 0}])

;; ---------------------------------------------------------------------------
;; maturity

(def ^:private relocated-targets
  (assoc full-env-relocated "OKF_MATURITY_TARGETS" "README.md,CONTRIBUTING.md"))

(def maturity-cases
  [{:id "maturity/full-check" :bundle "full" :args ["--check"] :exit 1 :expect "STALE  README.md"}
   {:id "maturity/full-write" :bundle "full" :exit 0 :expect "wrote  README.md"}
   {:id "maturity/clean-check" :bundle "clean" :args ["--check"] :exit 0}
   {:id "maturity/clean-write" :bundle "clean" :exit 0 :expect "0 rewritten"}
   {:id "maturity/targets-env" :bundle "relocated" :env relocated-targets :args ["--check"] :exit 1
    :expect "STALE  CONTRIBUTING.md"}
   {:id "maturity/targets-env-write" :bundle "relocated" :env relocated-targets :exit 0}
   {:id "maturity/targets-flags" :bundle "relocated" :env relocated-targets :args ["--check"]
    :lib-env {} :lib-args ["--check" "--targets" "README.md,CONTRIBUTING.md"
                           "--docs-dir" "knowledge" "--schema" "cfg/okf.edn"] :exit 1}
   {:id "maturity/targets-blank-entries" :bundle "relocated"
    :env (assoc full-env-relocated "OKF_MATURITY_TARGETS" " README.md , ,CONTRIBUTING.md ")
    :args ["--check"] :exit 1}
   {:id "maturity/targets-only-blanks" :bundle "relocated"
    :env (assoc full-env-relocated "OKF_MATURITY_TARGETS" " , ") :args ["--check"] :exit 0
    :expect "0 file(s) with markers"}
   {:id "maturity/targets-missing" :bundle "relocated"
    :env (assoc full-env-relocated "OKF_MATURITY_TARGETS" "README.md,NOPE.md,ALSO.md")
    :args ["--check"] :exit 2 :expect "NOPE.md, ALSO.md"}
   {:id "maturity/targets-directory" :bundle "relocated"
    :env (assoc full-env-relocated "OKF_MATURITY_TARGETS" "knowledge") :exit 2}
   {:id "maturity/schema-missing" :bundle "clean" :env {"OKF_SCHEMA" "nope.edn"} :exit 2}
   {:id "maturity/schema-and-targets-missing" :bundle "clean"
    :env {"OKF_SCHEMA" "nope.edn" "OKF_MATURITY_TARGETS" "NOPE.md"} :exit 2 :expect "cannot read schema"}
   {:id "maturity/schema-malformed" :bundle "clean" :write {"docs/metadata-schema.edn" "{"} :exit 2}
   {:id "maturity/no-review-maturity-check" :bundle "clean" :schema-edit #(dissoc % :review-maturity)
    :args ["--check"] :exit 1 :expect "0 level(s)"}
   {:id "maturity/no-review-maturity-write" :bundle "clean" :schema-edit #(dissoc % :review-maturity)
    :exit 0}
   {:id "maturity/relabelled-write" :bundle "full"
    :schema-edit #(assoc-in % [:review-maturity 0 :label] "AI-drafted") :exit 0}
   {:id "maturity/default-targets-absent" :bundle "full" :remove ["README.md" "CLAUDE.md"]
    :args ["--check"] :exit 0}
   {:id "maturity/docs-dir-default-targets" :bundle "relocated" :env full-env-relocated
    :write {"knowledge/README.md" "<!-- review-maturity:inline:begin -->\nx\n<!-- review-maturity:inline:end -->\n"}
    :args ["--check"] :exit 1 :expect "STALE  knowledge/README.md"}
   ;; Pinned rather than endorsed: the marker regex needs a line between the
   ;; begin and end markers, so a freshly pasted adjacent pair is reported as
   ;; "no markers" and never filled. See README "Carried-over behaviour".
   {:id "maturity/adjacent-marker-pair-skipped" :bundle "clean"
    :write {"CLAUDE.md" "<!-- review-maturity:table:begin -->\n<!-- review-maturity:table:end -->\n"}
    :exit 0 :expect "skip   CLAUDE.md (no markers)"}
   {:id "maturity/real-check" :bundle :real :args ["--check"] :exit 0}
   {:id "maturity/real-flags" :bundle :real :args ["--check"]
    :lib-args ["--check" "--docs-dir" "docs" "--schema" "docs/metadata-schema.edn"] :exit 0}
   {:id "maturity/real-copy-relabelled-write" :bundle :real-copy
    :schema-edit #(assoc-in % [:review-maturity 2 :meaning] "Reworded for the golden test.")
    :exit 0 :expect "2 rewritten"}])

;; ---------------------------------------------------------------------------
;; new-doc

(def ^:private ai-flags
  ["--ai-assisted" "Claude Opus 5 via Claude Code" "--session" "c625e009" "--tools" "babashka,Glean"])

(def new-doc-cases
  [{:id "new-doc/basic" :bundle "clean"
    :args ["--type" "Research" "--title" "My Doc" "--path" "docs/x/my-doc.md"] :exit 0
    :expect "wrote docs/x/my-doc.md"}
   {:id "new-doc/all-flags" :bundle "clean"
    :args ["--type" "Guide" "--title" "Colon: title" "--path" "docs/g.md"
           "--description" "Summary: with colon" "--tags" "a, b,,c" "--source" "https://example.org/one"]
    :exit 0}
   {:id "new-doc/multi-source" :bundle "clean"
    :args ["--type" "Guide" "--title" "T" "--path" "docs/m.md" "--source" "https://a.example, https://b.example"]
    :exit 0}
   {:id "new-doc/ai-assisted" :bundle "clean"
    :args (into ["--type" "Research" "--title" "AI" "--path" "docs/ai.md"
                 "--agents-skills" "https://x.example/skill.md,https://y.example/agent.md"] ai-flags)
    :exit 0}
   {:id "new-doc/ai-assisted-no-agents-skills" :bundle "clean"
    :args (into ["--type" "Research" "--title" "AI" "--path" "docs/ai.md"] ai-flags) :exit 0}
   {:id "new-doc/ai-missing-session" :bundle "clean"
    :args ["--type" "Research" "--title" "AI" "--path" "docs/ai.md" "--ai-assisted" "Claude"]
    :exit 1 :expect "so --session is required too"}
   {:id "new-doc/ai-missing-tools" :bundle "clean"
    :args ["--type" "Research" "--title" "AI" "--path" "docs/ai.md" "--ai-assisted" "Claude"
           "--session" "abc"] :exit 1 :expect "so --tools is required too"}
   {:id "new-doc/ai-blank-session" :bundle "clean"
    :args ["--type" "Research" "--title" "AI" "--path" "docs/ai.md" "--ai-assisted" "Claude"
           "--session" "  " "--tools" "bb"] :exit 1}
   {:id "new-doc/ai-unrecorded" :bundle "clean"
    :args ["--type" "Research" "--title" "AI" "--path" "docs/ai.md" "--ai-assisted" "Claude"
           "--session" "unrecorded" "--tools" "unrecorded"] :exit 0}
   {:id "new-doc/no-args" :bundle "clean" :args [] :exit 1 :expect "--type is required"}
   {:id "new-doc/missing-title" :bundle "clean" :args ["--type" "Guide" "--path" "docs/a2.md"]
    :exit 1 :expect "--title is required"}
   {:id "new-doc/missing-path" :bundle "clean" :args ["--type" "Guide" "--title" "T"]
    :exit 1 :expect "--path is required"}
   {:id "new-doc/path-outside" :bundle "clean" :args ["--type" "Guide" "--title" "T" "--path" "other/x.md"]
    :exit 1 :expect "--path must be under docs/"}
   {:id "new-doc/path-prefix-lookalike" :bundle "clean"
    :args ["--type" "Guide" "--title" "T" "--path" "docsx/x.md"] :exit 1}
   ;; Current behaviour, pinned rather than endorsed: the --path check is a
   ;; string-prefix test, so `..` segments pass it. See README "Security notes".
   {:id "new-doc/path-dot-dot" :bundle "clean"
    :args ["--type" "Guide" "--title" "T" "--path" "docs/../escaped.md"] :exit 0
    :expect "wrote docs/../escaped.md"}
   {:id "new-doc/unknown-type" :bundle "clean" :args ["--type" "Tutorial" "--title" "T" "--path" "docs/t.md"]
    :exit 0 :expect "warn: type \"Tutorial\""}
   {:id "new-doc/unknown-type-exists" :bundle "clean"
    :args ["--type" "Tutorial" "--title" "T" "--path" "docs/a.md"] :exit 1 :expect "already exists"}
   {:id "new-doc/exists" :bundle "clean" :args ["--type" "Guide" "--title" "T" "--path" "docs/a.md"]
    :exit 1 :expect "pass --force to overwrite"}
   {:id "new-doc/force-with-value" :bundle "clean"
    :args ["--type" "Guide" "--title" "T" "--path" "docs/a.md" "--force" "yes"] :exit 0}
   ;; --force followed by a non-flag value still consumes it, as before.
   {:id "new-doc/force-with-value-mid" :bundle "clean"
    :args ["--force" "yes" "--type" "Guide" "--title" "T" "--path" "docs/a.md"] :exit 0}
   {:id "new-doc/stray-positional" :bundle "clean"
    :args ["stray" "--type" "Guide" "--title" "T" "--path" "docs/s.md"] :exit 1 :expect "--type is required"}
   {:id "new-doc/schema-missing-no-args" :bundle "clean" :env {"OKF_SCHEMA" "nope.edn"} :args []
    :exit 2 :expect "new_doc: cannot read schema"}
   {:id "new-doc/schema-missing" :bundle "clean" :env {"OKF_SCHEMA" "nope.edn"}
    :args ["--type" "Guide" "--title" "T" "--path" "docs/n.md"] :exit 2}
   {:id "new-doc/relocated-env" :bundle "relocated" :env full-env-relocated
    :args ["--type" "Guide" "--title" "T" "--path" "knowledge/n.md"] :exit 0}
   {:id "new-doc/relocated-wrong-prefix" :bundle "relocated" :env full-env-relocated
    :args ["--type" "Guide" "--title" "T" "--path" "docs/n.md"] :exit 1 :expect "<knowledge/...md>"}
   {:id "new-doc/relocated-flags" :bundle "relocated" :env full-env-relocated
    :args ["--type" "Guide" "--title" "T" "--path" "knowledge/n.md"]
    :lib-env {} :lib-args ["--type" "Guide" "--title" "T" "--path" "knowledge/n.md"
                           "--docs-dir" "knowledge" "--schema" "cfg/okf.edn"] :exit 0}
   {:id "new-doc/docs-dir-trailing-slash" :bundle "clean" :env {"OKF_DOCS_DIR" "docs/"}
    :args ["--type" "Guide" "--title" "T" "--path" "other.md"] :exit 1 :expect "<docs//...md>"}
   {:id "new-doc/title-quotes" :bundle "clean"
    :args ["--type" "Guide" "--title" "He said: \"hi\"" "--path" "docs/q.md"] :exit 0}
   {:id "new-doc/title-hash" :bundle "clean" :args ["--type" "Guide" "--title" "C# notes" "--path" "docs/h.md"]
    :exit 0}
   {:id "new-doc/description-empty" :bundle "clean"
    :args ["--type" "Guide" "--title" "T" "--path" "docs/d.md" "--description" ""] :exit 0}
   {:id "new-doc/provenance-required-empty" :bundle "clean"
    :schema-edit #(assoc % :provenance-required #{})
    :args ["--type" "Guide" "--title" "T" "--path" "docs/p.md" "--ai-assisted" "Claude"] :exit 0}
   {:id "new-doc/provenance-required-absent" :bundle "clean"
    :schema-edit #(dissoc % :provenance-required)
    :args ["--type" "Guide" "--title" "T" "--path" "docs/p.md" "--ai-assisted" "Claude"] :exit 0}
   {:id "new-doc/provenance-required-custom-key" :bundle "clean"
    :schema-edit #(assoc % :provenance-required #{:session :model})
    :args ["--type" "Guide" "--title" "T" "--path" "docs/p.md" "--ai-assisted" "Claude"
           "--session" "s" "--model" "m"] :exit 0}
   {:id "new-doc/provenance-required-custom-key-missing" :bundle "clean"
    :schema-edit #(assoc % :provenance-required #{:session :model})
    :args ["--type" "Guide" "--title" "T" "--path" "docs/p.md" "--ai-assisted" "Claude" "--session" "s"]
    :exit 1 :expect "so --model is required too"}
   {:id "new-doc/today-flag-inert" :bundle "clean"
    :args ["--type" "Guide" "--title" "T" "--path" "docs/t.md" "--today" "2001-01-01"] :exit 0}
   {:id "new-doc/real-copy" :bundle :real-copy
    :args (into ["--type" "Research" "--title" "Golden" "--path" "docs/roadshow/golden.md"] ai-flags)
    :exit 0}
   ;; Exit 1 prints the usage line; the real bundle's schema has no :commands.
   {:id "new-doc/real-copy-exists" :bundle :real-copy
    :schema-edit #(assoc % :commands original-commands)
    :args ["--type" "Reference" "--title" "G" "--path" "docs/glossary.md"] :exit 1}])

;; ---------------------------------------------------------------------------
;; Intentional differences: the library departs from the original on purpose.
;; Each pins what the original does and what the library does instead.

(def intentional-differences
  [;; The originals printed while results were still lazy, so an exception
   ;; part-way through left some PASS/FAIL lines on stdout before the stack
   ;; trace. `run` computes the whole result before `report` renders it, so
   ;; the library prints nothing to stdout in that case. Exit code (1, from
   ;; babashka's uncaught-exception handler) and exception type agree.
   {:id "difference/validate-no-context-exempt" :tool :validate :bundle "clean" :args []
    :schema-edit #(dissoc % :context-exempt)
    :expect-original {:exit 1 :out-includes "PASS docs/a.md"}
    :expect-library  {:exit 1 :out ""}}
   {:id "difference/links-malformed-percent-escape" :tool :links :bundle "clean" :args []
    :write {"docs/glossary.md" "---\ntype: Reference\n---\n\n# G\n\n[bad escape](a%zz.md)\n"}
    :expect-original {:exit 1 :out-includes "PASS docs/README.md"}
    :expect-library  {:exit 1 :out ""}}
   ;; A level listed twice: new_doc.bb took the first label, the validator the
   ;; last, so the scaffold could fail validation. The library uses one lookup
   ;; (temper.okf.schema/canonical-maturity, last wins) in both.
   {:id "difference/new-doc-duplicate-level-label" :tool :new-doc :bundle "clean"
    :schema-edit #(update % :review-maturity conj {:level "L1" :label "Directed" :meaning "dup"})
    :args ["--type" "Guide" "--title" "T" "--path" "docs/dup.md"]
    :capture ["docs/dup.md"]
    :expect-original {:exit 0 :file-includes {"docs/dup.md" "review_maturity: L1 (Human-directed)\n"}}
    :expect-library  {:exit 0 :file-includes {"docs/dup.md" "review_maturity: L1 (Directed)\n"}}}
   ;; The usage line shows `[--force]`, but the original parser gave every flag
   ;; the next argument: a trailing --force got nil and refused to overwrite,
   ;; and a leading one swallowed the flag after it. The library lets --force
   ;; stand alone (see temper.okf.new-doc/optional-value-flags).
   {:id "difference/new-doc-force-trailing" :tool :new-doc :bundle "clean"
    :args ["--type" "Guide" "--title" "T" "--path" "docs/a.md" "--force"]
    :capture ["docs/a.md"]
    :expect-original {:exit 1 :out-includes "error: docs/a.md already exists"
                      :file-includes {"docs/a.md" "title: A clean document"}}
    :expect-library  {:exit 0 :out "wrote docs/a.md\n"
                      :file-includes {"docs/a.md" "title: T\n"}}}
   {:id "difference/new-doc-force-before-flag" :tool :new-doc :bundle "clean"
    :args ["--force" "--type" "Guide" "--title" "T" "--path" "docs/a.md"]
    :capture ["docs/a.md"]
    :expect-original {:exit 1 :out-includes "error: --type is required"}
    :expect-library  {:exit 0 :out "wrote docs/a.md\n"
                      :file-includes {"docs/a.md" "type: Guide\n"}}}
   ;; The originals named their own scripts (`bb tools/new_doc.bb`), which exist
   ;; only in the repo they came from. Without :commands the library names the
   ;; form that works wherever temper is on the classpath; with it, the
   ;; consumer's own command. See temper.okf.schema/command.
   {:id "difference/new-doc-default-command" :tool :new-doc :bundle "clean" :args []
    :schema-edit #(dissoc % :commands)
    :expect-original {:exit 1 :out-includes "usage: bb tools/new_doc.bb --type <Type>"}
    :expect-library  {:exit 1 :out-includes "usage: bb -m temper.okf.new-doc --type <Type>"}}
   {:id "difference/new-doc-custom-command" :tool :new-doc :bundle "clean" :args []
    :schema-edit #(assoc-in % [:commands :new-doc] "bb new-doc")
    :expect-original {:exit 1 :out-includes "usage: bb tools/new_doc.bb --type <Type>"}
    :expect-library  {:exit 1 :out-includes "usage: bb new-doc --type <Type>"}}
   {:id "difference/maturity-default-command" :tool :maturity :bundle "full" :args ["--check"]
    :schema-edit #(dissoc % :commands)
    :expect-original {:exit 1 :out-includes "FAILED — run `bb tools/gen_maturity_table.bb` and commit"}
    :expect-library  {:exit 1 :out-includes "FAILED — run `bb -m temper.okf.maturity` and commit"}}
   {:id "difference/maturity-custom-command" :tool :maturity :bundle "full" :args ["--check"]
    :schema-edit #(assoc-in % [:commands :maturity] "bb maturity")
    :expect-original {:exit 1 :out-includes "FAILED — run `bb tools/gen_maturity_table.bb` and commit"}
    :expect-library  {:exit 1 :out-includes "FAILED — run `bb maturity` and commit"}}
   ;; The originals said "see CLAUDE.md", a file in their own repo. The library
   ;; points at the schema's :spec-doc, or else at the schema file, which
   ;; exists once the run gets this far. One case per message, all from the
   ;; full fixture. See temper.okf.schema/spec-doc.
   {:id "difference/validate-no-frontmatter-spec-doc" :tool :validate :bundle "full" :args []
    :schema-edit #(dissoc % :spec-doc)
    :expect-original {:exit 1 :out-includes "with at least `type:` (see CLAUDE.md)"}
    :expect-library  {:exit 1 :out-includes "with at least `type:` (see docs/metadata-schema.edn)"}}
   {:id "difference/validate-maturity-ladder-spec-doc" :tool :validate :bundle "full" :args []
    :schema-edit #(dissoc % :spec-doc)
    :expect-original {:exit 1 :out-includes "(L0 AI-generated → L4 human-endorsed; see CLAUDE.md)"}
    :expect-library  {:exit 1 :out-includes "(L0 AI-generated → L4 human-endorsed; see docs/metadata-schema.edn)"}}
   {:id "difference/validate-reviewed-at-spec-doc" :tool :validate :bundle "full" :args []
    :schema-edit #(dissoc % :spec-doc)
    :expect-original {:exit 1 :out-includes "(e.g. `reviewed_at: 2026-09-09`). See CLAUDE.md."}
    :expect-library  {:exit 1 :out-includes "(e.g. `reviewed_at: 2026-09-09`). See docs/metadata-schema.edn."}}
   {:id "difference/validate-provenance-spec-doc" :tool :validate :bundle "full" :args []
    :schema-edit #(dissoc % :spec-doc)
    :expect-original {:exit 1 :out-includes "set it to \"unrecorded\". See CLAUDE.md."}
    :expect-library  {:exit 1 :out-includes "set it to \"unrecorded\". See docs/metadata-schema.edn."}}
   {:id "difference/validate-unknown-key-spec-doc" :tool :validate :bundle "full" :args []
    :schema-edit #(dissoc % :spec-doc)
    :expect-original {:exit 1 :out-includes "unknown key `autor` — check the spelling against the field list in CLAUDE.md, or add it to :known-keys in docs/metadata-schema.edn"}
    :expect-library  {:exit 1 :out-includes "unknown key `autor` — check the spelling, or add it to :known-keys in docs/metadata-schema.edn"}}
   ;; With :spec-doc set, the pointer names it; the unknown-key warning keeps
   ;; the original's wording with the consumer's file in it.
   {:id "difference/validate-custom-spec-doc" :tool :validate :bundle "full" :args []
    :schema-edit #(assoc % :spec-doc "CONTRIBUTING.md")
    :expect-original {:exit 1 :out-includes "(L0 AI-generated → L4 human-endorsed; see CLAUDE.md)"}
    :expect-library  {:exit 1 :out-includes "the field list in CONTRIBUTING.md, or add it to :known-keys"}}
   ;; Configuration errors were prefixed with the original script's name,
   ;; naming a file only the original repo had. The library uses its
   ;; namespace. Every exit-2 case above compares stderr after
   ;; g/renamed-prefix; these pin the raw prefix on each side, one per tool.
   {:id "difference/validate-stderr-prefix" :tool :validate :bundle "clean" :args []
    :env {"OKF_DOCS_DIR" "nope"}
    :expect-original {:exit 2 :out "" :err-starts-with "validate_metadata: docs directory nope"}
    :expect-library  {:exit 2 :out "" :err-starts-with "temper.okf.validate: docs directory nope"}}
   {:id "difference/links-stderr-prefix" :tool :links :bundle "clean" :args []
    :schema-edit #(dissoc % :link-policy)
    :expect-original {:exit 2 :out "" :err-starts-with "check_links: cannot run -- :link-policy"}
    :expect-library  {:exit 2 :out "" :err-starts-with "temper.okf.links: cannot run -- :link-policy"}}
   {:id "difference/maturity-stderr-prefix" :tool :maturity :bundle "clean" :args []
    :env {"OKF_MATURITY_TARGETS" "NOPE.md"}
    :expect-original {:exit 2 :out "" :err-starts-with "gen_maturity_table: target file(s) missing"}
    :expect-library  {:exit 2 :out "" :err-starts-with "temper.okf.maturity: target file(s) missing"}}
   {:id "difference/new-doc-stderr-prefix" :tool :new-doc :bundle "clean" :args []
    :env {"OKF_SCHEMA" "nope.edn"}
    :expect-original {:exit 2 :out "" :err-starts-with "new_doc: cannot read schema at nope.edn"}
    :expect-library  {:exit 2 :out "" :err-starts-with "temper.okf.new-doc: cannot read schema at nope.edn"}}])

;; ---------------------------------------------------------------------------

(defn- with-tool [tool cases] (map #(assoc % :tool tool :args (vec (:args % []))) cases))

(def all-cases
  (concat (with-tool :validate validate-cases)
          (with-tool :links links-cases)
          (with-tool :maturity maturity-cases)
          (with-tool :new-doc new-doc-cases)))

(defn- runnable [cases]
  (if (g/real-bundle-available?)
    cases
    (do (println "golden: real bundle not found at" g/real-bundle
                 "-- skipping its cases (set OKF_GOLDEN_REAL_BUNDLE)")
        (remove #(#{:real :real-copy} (:bundle %)) cases))))

(defn- output [{:keys [out err]}] (str out err))

(deftest case-ids-are-unique
  (let [ids (map :id (concat all-cases intentional-differences))]
    (is (= (count ids) (count (set ids))))))

(deftest library-matches-original
  (doseq [{:keys [case original library]} (g/run-cases (runnable all-cases))
          :let [{:keys [id exit expect]} case]]
    (testing id
      (is (= exit (:exit original)) "original exit code is the one the case expects")
      (when expect
        (is (str/includes? (output original) expect) "original output contains the expected text"))
      (is (= (:out original) (:out library)) "stdout")
      (is (= (g/renamed-prefix (:tool case) (:err original)) (:err library))
          "stderr, after the one rename in g/renamed-prefix")
      (is (= (:exit original) (:exit library)) "exit code")
      (is (= (:tree original) (:tree library)) "resulting files"))))

(deftest intentional-differences-hold
  (doseq [{:keys [case original library]} (g/run-cases (runnable intentional-differences))
          :let [{:keys [id expect-original expect-library]} case]]
    (testing id
      (doseq [[side result expected] [["original" original expect-original]
                                      ["library" library expect-library]]]
        (is (= (:exit expected) (:exit result)) (str side " exit code"))
        (when-let [s (:out expected)]
          (is (= s (:out result)) (str side " stdout")))
        (when-let [s (:out-includes expected)]
          (is (str/includes? (:out result) s) (str side " stdout contains")))
        (when-let [s (:err-starts-with expected)]
          (is (str/starts-with? (:err result) s) (str side " stderr starts with")))
        (doseq [[path s] (:file-includes expected)]
          (is (str/includes? (str (get-in result [:files path])) s)
              (str side " " path " contains " (pr-str s))))))))

