(ns temper.okf.frontmatter
  "Reading and editing a Markdown document's leading YAML frontmatter as text,
  so edits touch only the lines they mean to and keep key order and comments.
  Shared by temper.okf.review and temper.okf.drift."
  (:require [clojure.string :as str]))

(def ^:private open-re  #"\A---[ \t]*\r?\n")
(def ^:private close-re #"(?m)^---[ \t]*\r?$")

(defn split
  "[frontmatter-text rest] when `content` starts with a --- line and has a
  closing --- line (trailing spaces and CRLF tolerated; an empty block is
  fine). `rest` starts just after the closing line's newline. Nil otherwise."
  [content]
  (let [content (str content)]
    (when-let [open (re-find open-re content)]
      (let [after-open (subs content (count open))
            m (re-matcher close-re after-open)]
        (when (.find m)
          (let [fm   (subs after-open 0 (.start m))
                tail (subs after-open (.end m))
                tail (cond (str/starts-with? tail "\r\n") (subs tail 2)
                           (str/starts-with? tail "\n")   (subs tail 1)
                           :else tail)]
            [(str/replace fm #"\r?\n\z" "") tail]))))))

(defn body
  "Content without its frontmatter block, or the content itself if it has none."
  [content]
  (if-let [[_ rest] (split content)] rest (str content)))

(defn- unquote* [s]
  (let [s (str/trim s)]
    (if (and (>= (count s) 2)
             (or (and (str/starts-with? s "\"") (str/ends-with? s "\""))
                 (and (str/starts-with? s "'") (str/ends-with? s "'"))))
      (subs s 1 (dec (count s)))
      s)))

(defn value
  "The scalar value of `k` in the frontmatter of `content`, trimmed and
  unquoted, or nil. Only top-level `k: value` lines are read."
  [content k]
  (some->> (split content) first
           (re-find (re-pattern (str "(?m)^" (java.util.regex.Pattern/quote (name k)) ":[ \t]*(.*?)[ \t]*\r?$")))
           second unquote* not-empty))

(defn yaml-str
  "A double-quoted YAML scalar."
  [s]
  (str "\"" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn set-key
  "Frontmatter text with `k` set to the literal `v`. Replaces the key's line and
  any indented continuation lines under it (a block scalar such as `k: >-`), or
  appends the key when absent."
  [fm k v]
  (let [re (re-pattern (str "(?m)^" (java.util.regex.Pattern/quote (name k)) ":.*(?:\\r?\\n[ \\t]+.*)*"))
        line (str (name k) ": " v)]
    (if (re-find re fm)
      (str/replace-first fm re (str/re-quote-replacement line))
      (if (str/blank? fm) line (str (str/trimr fm) "\n" line)))))

(defn set-keys
  "Content with each [k v] in `kvs` set in its frontmatter. The body is left
  exactly as it was."
  [content kvs]
  (let [[fm rest] (split content)]
    (str "---\n" (reduce (fn [fm [k v]] (set-key fm k v)) fm kvs) "\n---\n" rest)))
