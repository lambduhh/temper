(ns temper.okf.test-runner
  "Run the test suite: `bb test`, or `bb test temper.okf.golden-test` for a subset."
  (:require [clojure.test :as t]))

(def test-namespaces
  '[temper.okf.config-test
    temper.okf.schema-test
    temper.okf.load-test
    temper.okf.api-test
    temper.okf.examples-test
    temper.okf.vocab-test
    temper.okf.okf-v02-test
    temper.okf.golden-test
    temper.okf.review-test
    temper.okf.drift-test])

(defn -main [& args]
  (let [nss (if (seq args) (map symbol args) test-namespaces)]
    (apply require nss)
    (let [{:keys [fail error]} (apply t/run-tests nss)]
      (System/exit (if (zero? (+ fail error)) 0 1)))))
