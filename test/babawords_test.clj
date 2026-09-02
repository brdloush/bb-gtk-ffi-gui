;; The word list. Reads the live clojure.core, so it also acts as a check that
;; this babashka still has the shape the app assumes.
(require '[babawords :as w] '[clojure.string :as str])

(def names (w/all-names))
(println "1) clojure.core publics, minus plumbing:" (count names))
(assert (> (count names) 400) "suspiciously few names -- did ns-publics change?")
(assert (every? string? names))
(assert (= names (sort names)) "should be sorted, so pools are stable")
(assert (apply distinct? names))

;; repl history vars and -impl internals are vocabulary nobody wants
(println "   filtered out:" (pr-str (filter #(re-matches #"\*[0-9e]" %) names))
         (pr-str (filter #(str/ends-with? % "-impl") names)))
(assert (empty? (filter #(re-matches #"\*[0-9e]" %) names)) "*1 *2 *e should be gone")
(assert (empty? (filter #(str/ends-with? % "-impl") names)) "-impl should be gone")
;; but the real vocabulary is still there
(assert (every? (set names) ["map" "reduce" "filter" "swap!" "some?" "->>" "assoc-in"]))

;; --- 2. the three sources get harder ------------------------------------
(def st (w/stats))
(println "2) pool sizes:" st)
(assert (< (:core st) (:symbols st) (:everything st)) "sources should nest")

(def core-pool (w/word-pool names :core))
(println "   :core sample:" (str/join " " (take 8 core-pool)))
(assert (every? w/plain-name? core-pool) ":core must be plain names only")
(assert (every? #(<= 2 (count %) 12) core-pool) ":core must stay typeable")

(def sym-pool (w/word-pool names :symbols))
(assert (some #(not (w/plain-name? %)) sym-pool) ":symbols should include punctuation")
(assert (contains? (set sym-pool) "->>") "->> is the whole point of :symbols")
(assert (every? #(<= (count %) 12) sym-pool))

(def all-pool (w/word-pool names :everything))
(assert (some #(> (count %) 12) all-pool) ":everything should include the monsters")
(println "   longest in :everything:" (last (sort-by count all-pool)))

;; an unknown source falls back rather than returning nothing
(assert (= core-pool (w/word-pool names :nonsense)))

;; --- 3. sampling ---------------------------------------------------------
(def ws (w/words :core 40 (java.util.Random. 42)))
(println "3) sampled 40:" (str/join " " (take 6 ws)) "...")
(assert (= 40 (count ws)))
(assert (every? (set core-pool) ws) "sampled a word that is not in the pool")
;; deterministic for a given seed, so a test can rely on it
(assert (= ws (w/words :core 40 (java.util.Random. 42))))
(assert (not= ws (w/words :core 40 (java.util.Random. 43))))
;; never the same word twice running -- it reads badly and skews the test
(assert (not-any? (fn [[a b]] (= a b)) (partition 2 1 ws)) "repeated a word back to back")

;; a one-word pool must not spin forever looking for something different
(println "4) single-word pool:" (w/sample ["map"] 3 (java.util.Random. 1)))
(assert (= ["map" "map" "map"] (w/sample ["map"] 3 (java.util.Random. 1))))
(assert (nil? (w/sample [] 5 (java.util.Random. 1))) "empty pool gives nil, not a hang")
(assert (= [] (w/sample ["map"] 0 (java.util.Random. 1))))
(println "ALL OK")
