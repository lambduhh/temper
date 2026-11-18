(ns temper.okf.okf-v02-test
  "temper.okf.validate/validate-okf-v02: the shape of the OKF v0.2 families
  (§5), checked only when a key is present."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [temper.okf.validate :as validate]))

(defn- errors [fm] (validate/validate-okf-v02 fm (yaml/parse-string fm)))
(defn- one-error-like [fm s]
  (let [es (errors fm)]
    (is (= 1 (count es)) (pr-str es))
    (is (some #(str/includes? % s) es) (pr-str es))))

(def ^:private good
  (str "type: Guide\n"
       "status: stable\n"
       "generated: { by: claude-code/claude-opus-5-5, at: 2026-06-20T22:53:05Z }\n"
       "verified: { by: \"human:lambduhh\", at: 2026-06-25T09:00:00Z }\n"
       "stale_after: 2026-12-31T00:00:00Z\n"
       "sources:\n"
       "  - id: spec\n"
       "    resource: https://example.org/spec\n"
       "    author: team:docs\n"
       "    usage_count: 5000\n"
       "    last_modified: 2026-05-30T00:00:00+02:00\n"
       "usage_window: { from: 2026-06-01T00:00:00Z, to: 2026-06-30T00:00:00Z }\n"))

(deftest well-formed-and-absent
  (is (= [] (errors good)) "every family, well formed")
  (is (= [] (errors "type: Guide\n")) "no family at all: nothing to check")
  (is (= [] (errors (str "type: Guide\nverified:\n"
                         "  - { by: \"human:a\", at: 2026-06-25T09:00:00Z }\n"
                         "  - { by: process:nightly, at: 2026-06-26T02:00:00.5-03:00 }\n")))
      "verified as a list"))

(deftest generated
  (one-error-like "generated: claude\n" "must be a mapping")
  (one-error-like "generated: { at: 2026-06-20T22:53:05Z }\n" "generated.by is required")
  (one-error-like "generated: { by: Claude Opus, at: 2026-06-20T22:53:05Z }\n" "is not an OKF actor")
  (testing "timestamps need a time and an offset; the parser alone can't tell"
    (one-error-like "generated: { by: \"human:a\", at: 2026-06-20 }\n" "`at: 2026-06-20`")
    (one-error-like "generated: { by: \"human:a\", at: 2026-06-20T22:53:05 }\n" "UTC offset")
    (one-error-like "generated:\n  by: \"human:a\"\n  at: '2026-06-20'\n" "`at: 2026-06-20`")))

(deftest verified
  (one-error-like "verified: yes\n" "mapping or a list")
  (one-error-like "verified: { by: \"human:a\" }\n" "verified.at is required")
  (one-error-like "verified: [{ by: \"human:a\", at: 2026-06-25T09:00:00Z }, { at: 2026-06-25T09:00:00Z }]\n"
                  "verified.by is required"))

(deftest sources-window-status-stale
  (one-error-like "sources: https://example.org\n" "list of mappings")
  (one-error-like "sources:\n  - title: T\n" "needs a `resource`")
  (one-error-like "sources:\n  - resource: r\n    usage_count: -1\n" "non-negative integer")
  (one-error-like "sources:\n  - resource: r\n    last_modified: 2026-05-30\n" "last_modified")
  (one-error-like "usage_window: { from: 2026-06-01T00:00:00Z }\n" "{ from, to }")
  (one-error-like "usage_window: { from: 2026-06-01T00:00:00Z, to: 2026-06-30 }\n" "`to: 2026-06-30`")
  (one-error-like "status: published\n" "draft, stable, deprecated")
  (one-error-like "stale_after: 2026-09-23\n" "stale_after"))
