(ns temper.okf.cli
  "Shared plumbing for each tool's -main: flag parsing, and turning a result map
  into output and an exit code. Nothing here runs at load time.")

(defn parse-flags
  "Parse `args` into an options map.

  `value` maps a flag to the key that takes the next argument as its value;
  `bool` maps a flag to a key set to true. Any other argument is ignored, as the
  original scripts ignored arguments they did not recognise.

      (parse-flags [\"--check\" \"--schema\" \"cfg/okf.edn\"]
                   {:value {\"--schema\" :schema} :bool {\"--check\" :check}})
      ;=> {:check true, :schema \"cfg/okf.edn\"}"
  [args {:keys [value bool]}]
  (loop [args (seq args) opts {}]
    (if-not args
      opts
      (let [[a & more] args]
        (cond
          (contains? value a) (recur (next more) (assoc opts (value a) (first more)))
          (contains? bool a)  (recur more (assoc opts (bool a) true))
          :else               (recur more opts))))))

(def path-flags
  "The flags every tool accepts in addition to its own."
  {"--docs-dir" :docs-dir "--schema" :schema})

(defn exit!
  "Print `out` to stdout and the result's :config-errors to stderr, one per
  line. Exit the process only when :exit is non-zero: exiting on success
  would end a bb task that chains several tools (:depends [validate links])
  after the first one, and report success for checks that never ran."
  [{:keys [exit config-errors]} out]
  (when (seq out)
    (print out)
    (flush))
  (when (seq config-errors)
    (binding [*out* *err*]
      (doseq [line config-errors] (println line))
      (flush)))
  (when-not (zero? exit)
    (System/exit exit)))
