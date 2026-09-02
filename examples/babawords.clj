(ns babawords
  "The word list: the names of `clojure.core`'s public vars, read out of the
   running interpreter at boot.

   No data file and no download -- the app types the language it is written in.
   Reading `ns-publics` rather than shipping a list also means it cannot drift
   from what this babashka actually has."
  (:require [clojure.string :as str]))

(def plain-name?
  "A name made only of lowercase letters, digits and hyphens."
  (partial re-matches #"[a-z][a-z0-9-]*"))

(defn- internal?
  "Names that are plumbing rather than vocabulary: repl history vars and the
   -impl suffixes. Typing `*1` teaches nobody anything."
  [n]
  (or (re-matches #"\*[0-9e]" n)
      (str/ends-with? n "-impl")))

(defn all-names
  "Every public in a namespace, as strings. Defaults to clojure.core."
  ([] (all-names 'clojure.core))
  ([ns-sym]
   (->> (ns-publics ns-sym) keys (map str) (remove internal?) sort vec)))

(def sources
  "Difficulty comes for free from the shape of the names themselves.

     :core       plain names of a comfortable length -- the default
     :symbols    adds ->, ->>, some?, swap!, *ns* and friends
     :everything adds the monsters, up to 31 characters"
  {:core       {:label "core"       :max-len 12 :punctuation? false}
   :symbols    {:label "symbols"    :max-len 12 :punctuation? true}
   :everything {:label "everything" :max-len 99 :punctuation? true}})

(defn word-pool
  "The candidate words for a source. Pure given `names`."
  [names source]
  (let [{:keys [max-len punctuation?]} (get sources source (:core sources))]
    (->> names
         (filter #(<= 2 (count %) max-len))
         (filter #(or punctuation? (plain-name? %)))
         vec)))

(defn sample
  "n words drawn from the pool, never the same word twice in a row.

   Takes an explicit `rnd` so tests can be deterministic."
  [pool n rnd]
  (when (seq pool)
    (loop [acc [] prev nil]
      (if (= n (count acc))
        acc
        (let [w (nth pool (.nextInt rnd (count pool)))]
          (if (and prev (= w prev) (> (count pool) 1))
            (recur acc prev)
            (recur (conj acc w) w)))))))

(defn words
  "The public entry point: n words for a source, from the live clojure.core."
  ([source n] (words source n (java.util.Random.)))
  ([source n rnd] (sample (word-pool (all-names) source) n rnd)))

(defn stats
  "How many words each source offers, for the mode bar and for sanity."
  []
  (let [names (all-names)]
    (into {} (for [s (keys sources)]
               [s (count (word-pool names s))]))))
