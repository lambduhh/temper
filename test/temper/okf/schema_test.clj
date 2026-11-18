(ns temper.okf.schema-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [temper.okf.schema :as schema]))

(def fixture-schema
  (edn/read-string (slurp "test/fixtures/full/docs/metadata-schema.edn")))

(deftest parse-maturity
  (is (= ["L2" nil] (schema/parse-maturity "L2")))
  (is (= ["L2" "Human-reviewed"] (schema/parse-maturity "L2 (Human-reviewed)")))
  (is (= ["L2" "Human-reviewed"] (schema/parse-maturity "  L2( Human-reviewed )  ")))
  (is (= ["L2" nil] (schema/parse-maturity "L2 ()")) "an empty label counts as no label")
  (is (= ["L10" nil] (schema/parse-maturity "L10")) "shape only; legality is checked elsewhere")
  (is (nil? (schema/parse-maturity "high")))
  (is (nil? (schema/parse-maturity 2)))
  (is (nil? (schema/parse-maturity nil))))

(deftest canonical-maturity
  (is (= "L0 (AI-generated)" (schema/canonical-maturity fixture-schema "L0")))
  (is (= "L4 (Human-endorsed)" (schema/canonical-maturity fixture-schema "L4")))
  (testing "a duplicated level resolves to its later entry, in both tools"
    (is (= "L0 (second)"
           (schema/canonical-maturity {:review-maturity [{:level "L0" :label "first"}
                                                         {:level "L0" :label "second"}]}
                                      "L0")))))

(deftest maturity-views
  (is (= #{"L0" "L1" "L2" "L3" "L4"} (schema/maturity-values fixture-schema)))
  (is (= "L0/L1/L2/L3/L4" (schema/maturity-hint fixture-schema)))
  (is (= "Data Model, Decision Log, Diagram, Errata, Guide, Meeting Notes, RFC, Reference, Research, Review, Vision"
         (schema/types-hint fixture-schema))))

(deftest reviewed-at-levels
  (is (= #{"L2" "L3" "L4"} (schema/reviewed-at-levels fixture-schema)))
  (is (= #{"L4"} (schema/reviewed-at-levels (assoc fixture-schema :review-staleness {:from "L4"}))))
  (testing "a :from that names no level requires reviewed_at nowhere"
    (is (= #{} (schema/reviewed-at-levels (assoc fixture-schema :review-staleness {:from "L9"}))))))

(deftest command
  (is (= "bb -m temper.okf.new-doc" (schema/command {} :new-doc)))
  (is (= "bb -m temper.okf.maturity" (schema/command nil :maturity)))
  (is (= "bb new-doc" (schema/command {:commands {:new-doc "bb new-doc"}} :new-doc)))
  (testing "a blank or absent entry falls back, per tool"
    (is (= "bb -m temper.okf.new-doc" (schema/command {:commands {:new-doc "  "}} :new-doc)))
    (is (= "bb -m temper.okf.maturity" (schema/command {:commands {:new-doc "bb new-doc"}} :maturity)))))

(deftest spec-doc
  (is (= "CONTRIBUTING.md" (schema/spec-doc {:spec-doc "CONTRIBUTING.md"})))
  (is (nil? (schema/spec-doc {})))
  (is (nil? (schema/spec-doc {:spec-doc " "})) "blank counts as unset"))
