(ns temper.okf.vocab-test
  "vocab/temper.ttl defines the temper: terms that examples/context.jsonld
  maps frontmatter keys to. The two must agree: a term used in the context but
  not defined would lift to an IRI nobody documents, and a term defined but
  unused is a stale promise. There is no RDF parser under babashka, so this
  reads the Turtle subjects with a pattern; the vocabulary file keeps one
  `temper:<term>` subject per definition, at the start of a line."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [temper.okf.golden-support :as g]))

(def ^:private ns-iri "https://w3id.org/temper#")

(defn- defined-terms []
  (->> (slurp (str (fs/path g/root "vocab" "temper.ttl")))
       (re-seq #"(?m)^temper:(\w+)\s*$")
       (map second)
       set))

(defn- used-terms []
  (let [ctx (-> (str (fs/path g/root "examples" "context.jsonld")) slurp json/parse-string (get "@context"))]
    (is (= ns-iri (get ctx "temper")) "the context's temper prefix is the vocabulary namespace")
    ;; Walk scoped contexts too: generated, verified, sources and usage_window
    ;; map their nested keys in an @context of their own.
    (letfn [(iris [ctx]
              (mapcat (fn [v]
                        (cond (string? v) [v]
                              (map? v) (cons (get v "@id") (some-> (get v "@context") iris))
                              :else nil))
                      (vals ctx)))]
      (->> (iris ctx)
           (keep #(when (string? %) (second (re-matches #"temper:(\w+)" %))))
           set))))

(deftest context-and-vocabulary-agree
  (let [defined (defined-terms) used (used-terms)]
    (is (seq defined) "the vocabulary defines terms")
    (testing "every temper: term the context uses is defined"
      (is (= #{} (set (remove defined used)))))
    (testing "every defined term is used by the context"
      (is (= #{} (set (remove used defined)))))
    (testing "the vocabulary declares its namespace"
      (is (str/includes? (slurp (str (fs/path g/root "vocab" "temper.ttl")))
                         (str "@prefix temper: <" ns-iri "> ."))))))
