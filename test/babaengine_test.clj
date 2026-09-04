;; The typing engine. Pure, so all of this runs with no window and no timing
;; flakiness -- every timestamp is passed in.
;;
;; The model under test: the target is one flat string and **space is just
;; another character**. Typing a space where a letter belongs is a mistyped
;; character; typing a letter where the gap between two words belongs is
;; equally a mistake.
(require '[babaengine :as e])

(defn- k [c] {:key (if (= c \space) "space" (str c)) :char c})
(defn type-text
  "Types `text` one character per 100ms, starting at t0."
  ([s text] (type-text s text 0))
  ([s text t0]
   (reduce (fn [s [i c]] (e/apply-key s (k c) (+ t0 (* i 100))))
           s (map-indexed vector text))))

(def words ["defn" "println" "map"])
(defn fresh [] (e/new-test words {:kind :words :limit 3} 40))

;; --- 1. the target is flat, spaces included ----------------------------
(println "1) target:" (pr-str (:target (fresh))))
(assert (= "defn println map" (:target (fresh))))
(assert (= 0 (e/cursor (fresh))))
(assert (= \d (e/expected (fresh))))

;; --- 2. space mid-word is an ordinary mistyped character ---------------
;; "defn", cursor over the f, press space
(def mid (-> (fresh) (type-text "de") (e/apply-key (k \space) 300)))
(println "2) typed 'de' then space:" (pr-str (:input mid))
         "| that keystroke correct?" (:correct? (last (:keys mid)))
         "| cursor now over" (pr-str (str (e/expected mid))))
(assert (false? (:correct? (last (:keys mid)))) "space mid-word must count as a mistake")
(assert (= "de " (:input mid)) "and must consume exactly one position")
(assert (= \n (e/expected mid)) "so the cursor is still inside defn")
;; it is worth exactly as much as any other wrong key
(def mid-x (-> (fresh) (type-text "de") (e/apply-key (k \x) 300)))
(assert (= (:correct? (last (:keys mid)))
           (:correct? (last (:keys mid-x)))))
(assert (= (e/expected mid) (e/expected mid-x)))
(assert (= (e/correct-chars mid) (e/correct-chars mid-x)))

;; --- 3. on the gap between words, only space is right -----------------
(def gap (type-text (fresh) "defn"))
(println "3) after 'defn' the cursor expects" (pr-str (str (e/expected gap))))
(assert (= \space (e/expected gap)))
(assert (false? (:correct? (last (:keys (e/apply-key gap (k \x) 500)))))
        "a letter where the gap belongs is a mistake")
(assert (true? (:correct? (last (:keys (e/apply-key gap (k \space) 500)))))
        "and the space is correct")

;; --- 4. a substitution stays aligned ----------------------------------
;; one wrong character costs one position, so everything after it still scores
(def sub (type-text (fresh) "de n println map"))
(println "4) typed" (pr-str (:input sub)) "-> correct chars"
         (e/correct-chars sub) "of" (count (:target sub))
         "| accuracy" (format "%.0f%%" (* 100 (e/accuracy sub))))
(assert (= 15 (e/correct-chars sub)) "only the one wrong position should be lost")
(assert (= 16 (count (:target sub))))
(assert (= {:correct 15 :incorrect 1 :attempted 16} (e/character-breakdown sub)))

;; --- 5. the clock starts on the first keystroke and then freezes -------
(def s0 (fresh))
(assert (not (e/started? s0)))
(assert (zero? (e/elapsed-ms s0 99999)) "an untouched test must not accrue time")
(assert (not (e/started? (e/apply-key s0 {:key "Shift_L"} 500))) "a modifier must not start it")
(assert (= 1000 (:started (e/apply-key s0 (k \d) 1000))))

(def perfect (type-text (fresh) "defn println map"))
(println "5) finished on reaching the end:" (e/finished? perfect)
         "| elapsed frozen at" (e/elapsed-ms perfect 999999) "ms")
(assert (e/finished? perfect))
(assert (= 1500 (e/elapsed-ms perfect 999999)) "the clock must freeze when it ends")
(assert (= perfect (e/apply-key perfect (k \x) 999999)) "a finished test must be inert")
(assert (= 1.0 (e/accuracy perfect)))
(assert (= 3 (e/words-passed perfect)))

;; --- 6. backspace ------------------------------------------------------
(def bs (-> (fresh) (type-text "defn") (e/apply-key {:key "BackSpace"} 500)))
(println "6) backspace:" (pr-str (:input bs)))
(assert (= "def" (:input bs)))
;; there is no word boundary to get stuck on any more -- it walks straight back
(def bs2 (reduce (fn [s _] (e/apply-key s {:key "BackSpace"} 900))
                 (type-text (fresh) "defn p") (range 3)))
(assert (= "def" (:input bs2)) "backspace should cross the space without ceremony")
(assert (= (fresh) (e/apply-key (fresh) {:key "BackSpace"} 10))
        "backspace on an empty test should change nothing at all")

;; --- 7. the input can never outgrow the target ------------------------
;; This is why the old "cap the extra characters" rule is gone. A wrong key
;; consumes a target position rather than being appended beside one, so there
;; is nothing to overrun: reaching the end simply ends the test.
(def over (type-text (fresh) (str "defn println map" (apply str (repeat 12 "x")))))
(println "7) mashed 12 keys past the end; input length"
         (count (:input over)) "of target" (count (:target over)))
(assert (= (count (:target over)) (count (:input over)))
        "input must never grow past the target")
(assert (= {:correct 16 :incorrect 0 :attempted 16} (e/character-breakdown over))
        "and no phantom extras should appear in the breakdown")
;; the same holds mid-passage, which is what used to shove the text sideways
(def mash-mid (type-text (fresh) (str "de" (apply str (repeat 8 "x")))))
(println "    mashing mid-word:" (pr-str (:input mash-mid)))
(assert (= 10 (count (:input mash-mid))) "each wrong key takes exactly one position")
(assert (= 10 (:attempted (e/character-breakdown mash-mid))))

;; --- 8. the numbers ---------------------------------------------------
;; "defn println map" is 16 characters typed in 1.5s, all correct.
;; 16/5 = 3.2 words in 0.025 min -> 128 wpm
(def m (e/summary perfect 1500))
(println "8) summary:" (-> m (update :wpm float) (update :raw-wpm float)
                           (update :accuracy float) (update :consistency float)))
(assert (< 127.9 (:wpm m) 128.1) (str "wpm was " (:wpm m)))
(assert (= (:wpm m) (:wpm (e/summary perfect 999999))) "a finished test must not keep accruing")
(assert (= 1.0 (:accuracy m)))
(assert (= {:correct 16 :incorrect 0 :attempted 16} (:chars m)))
;; cpm is the same reading without the five-characters-per-word divisor
(println "   cpm" (format "%.1f" (:cpm m)) "raw cpm" (format "%.1f" (:raw-cpm m)))
(assert (< 639.9 (:cpm m) 640.1) (str "cpm was " (:cpm m)))
(assert (= (:cpm m) (* 5 (:wpm m))))
(assert (= (:raw-cpm m) (* 5 (:raw-wpm m))))
;; raw counts everything typed, net only what was right
(println "   one wrong char: wpm" (format "%.1f" (e/wpm sub 1600))
         "raw" (format "%.1f" (e/raw-wpm sub 1600)))
(assert (< (e/wpm sub 1600) (e/raw-wpm sub 1600)))

;; before anything is typed the numbers are defined, not NaN
(def z (e/summary (fresh) 5000))
(println "   untouched:" (select-keys z [:wpm :accuracy :consistency :series :words]))
(assert (zero? (:wpm z)))
(assert (= 1.0 (:accuracy z)))
(assert (= 1.0 (:consistency z)))
(assert (nil? (:series z)))
(assert (not (Double/isNaN (:wpm z))))

;; --- 9. words passed is progress; words correct is correctness ---------
(def probes ["" "def" "defn" "defn " "defn println" "defn println map" "dxfn println"])
(println "9) words passed :" (mapv #(e/words-passed (type-text (fresh) %)) probes))
(println "   words correct:" (mapv #(e/words-correct (type-text (fresh) %)) probes))
(assert (= [0 0 1 1 2 3 2] (mapv #(e/words-passed (type-text (fresh) %)) probes))
        "progress must not care whether the word was right")
(assert (= [0 0 1 1 2 3 1] (mapv #(e/words-correct (type-text (fresh) %)) probes))
        "and a typo in the first word must not hide a correct second one")

;; --- 10. a timed test ends on the clock alone -------------------------
(def timed (e/new-test words {:kind :time :limit 30} 40))
(def t1 (type-text timed "defn"))
(println "10) remaining after 0.3s:" (e/remaining-ms t1 300) "ms")
(assert (= 29700 (e/remaining-ms t1 300)))
(assert (not (e/finished? t1)))
(assert (e/finished? (e/tick t1 30000)))
(assert (= 30000 (e/remaining-ms timed 99999)) "unstarted means the full time is left")
(assert (not (e/finished? (e/tick timed 99999))) "an untouched test cannot time out")

;; --- 10b. errors are counted, and located in time --------------------
(def slips (type-text (fresh) "dxfn prxntln map"))
(println "10b) errors:" (e/errors slips) "| accuracy" (format "%.0f%%" (* 100 (e/accuracy slips))))
(assert (= 2 (e/errors slips)))
;; every keystroke is either right or an error, with nothing in between
(assert (= (count (:keys slips))
           (+ (e/errors slips) (count (filter :correct? (:keys slips))))))
;; backspacing does not un-make a mistake
(def repaired (-> (fresh) (type-text "dx")
                  (e/apply-key {:key "BackSpace"} 500)
                  (type-text "efn" 600)))
(println "     after correcting a typo, errors still:" (e/errors repaired))
(assert (= 1 (e/errors repaired)) "a corrected mistake was still a mistake")
(assert (= "defn" (:input repaired)) "but the text should be right again")

;; located in the right second, per interval rather than cumulative
(def spread (reduce (fn [t [i c]]
                      (e/apply-key t {:key (str c) :char c} (+ 1000 (* i 250))))
                    (e/new-test ["map" "reduce" "filter" "keep"] {:kind :time :limit 5} 40)
                    (map-indexed vector "map redxce filxer keep")))
(println "     errors per second:" (e/errors-per-second spread 6500))
(assert (= [0 1 0 1 0] (e/errors-per-second spread 6500)))
(assert (= (count (e/per-second-wpm spread 6500))
           (count (e/errors-per-second spread 6500)))
        "the two series must line up, or the chart marks the wrong second")
(assert (= (e/errors spread) (reduce + (e/errors-per-second spread 6500)))
        "the per-second errors must add up to the total")
;; nil before anything is typed, like the wpm series
(assert (nil? (e/errors-per-second (fresh) 5000)))
(assert (zero? (e/errors (fresh))))

;; --- 11. we wrap the passage ourselves -------------------------------
;; Pango is not allowed to wrap: it re-flows as the text changes, which slides
;; the passage sideways under the caret, and it breaks `keep-indexed` in half.
;; The target itself is unaffected -- just the words joined by spaces.
(def long-words ["some" "comment" "reduce" "filter" "partition-by" "keep-indexed"
                 "assoc-in" "update-in" "swap!" "deref" "mapcat" "interleave"])
(def lt (e/new-test long-words {:kind :time :limit 60} 40))
(def spans (:line-spans lt))
(println "11) lines at 40 cols:")
(doseq [l (:lines lt)] (println (format "     %2d %s" (count l) (pr-str l))))

;; the lines run together with nothing between them
(assert (= (:target lt) (apply str (:lines lt))) "the lines must reassemble")
(assert (= (:target lt) (str/join " " long-words)) "the target is just the words")
(assert (= (:target lt)
           (apply str (map #(subs (:target lt) (:start %) (:end %)) spans))))
;; no line exceeds the budget, none starts or ends on a space, no word is split
(doseq [[i l] (map-indexed vector (:lines lt))]
  (assert (<= (count (str/trimr l)) 40) (str "line too long: " (pr-str l)))
  (assert (not (str/starts-with? l " ")) (str "leading space: " (pr-str l)))
  ;; every line but the last ends with the space it swallowed
  (assert (= (< i (dec (count (:lines lt)))) (str/ends-with? l " "))
          (str "wrong trailing space: " (pr-str l)))
  (assert (every? (set long-words) (str/split (str/trim l) #" "))
          (str "word split: " (pr-str l))))

;; the line-end space is part of the target and is typed like any other
;; character. It belongs to the line it ends, so the caret has somewhere
;; visible to sit at the break rather than vanishing off the right edge.
(def l1-end (:end (first spans)))
(def before-break (type-text lt (subs (:target lt) 0 (dec l1-end))))
(println "     expected at the end of line 1:" (pr-str (str (e/expected before-break))))
(assert (= \space (e/expected before-break))
        "the space between the last word of a line and the next is still typed")
(assert (= [(dec l1-end) nil nil] (mapv :cursor (e/visible-lines before-break 3)))
        "and the caret sits at the end of line 1 while it is typed")
(def at-break (type-text lt (subs (:target lt) 0 l1-end)))
(assert (= [nil 0 nil] (mapv :cursor (e/visible-lines at-break 3)))
        "once typed, the caret moves to the start of the next line")
(assert (= (first (second (:lines lt))) (e/expected at-break)))

;; a word longer than the budget still gets a line of its own
(assert (= 1 (count (e/wrap-words ["set-agent-send-off-executor!"] 10))))
(assert (= [] (e/wrap-words [] 40)))

;; --- 12. the block scrolls a line at a time --------------------------
(defn at [n] (type-text (e/new-test long-words {:kind :time :limit 60} 40)
                        (subs (:target lt) 0 n)))
(defn carets [n] (mapv :cursor (e/visible-lines (at n) 3)))
(def line1-end (:end (first spans)))
(println "12) carets by line, typing across the first break:")
(doseq [n [0 (dec line1-end) line1-end (inc line1-end)]]
  (println (format "     cursor %3d -> %s" n (carets n))))
;; the caret is on exactly one line, never two
(doseq [n (range 0 (min 90 (count (:target lt))))]
  (assert (= 1 (count (remove nil? (carets n))))
          (str "caret drawn " (count (remove nil? (carets n))) " times at " n)))
;; finishing a line moves the caret to the start of the next one, and the line
;; above stays on screen -- no sideways slide
(assert (= [(dec (- line1-end 0)) nil nil] (carets (dec line1-end))))
(assert (= [nil 0 nil] (carets line1-end))
        "the caret should land on the first character of the next line")
(def before (mapv :target (e/visible-lines (at (dec line1-end)) 3)))
(def after  (mapv :target (e/visible-lines (at line1-end) 3)))
(println "     lines unchanged across the break:" (= before after))
(assert (= before after) "the visible lines must not move when the caret crosses")

;; with only as many lines as fit, there is nothing to scroll
(assert (= 3 (count spans)))
(assert (= (mapv :target (e/visible-lines (at 0) 3))
           (mapv :target (e/visible-lines (at 90) 3)))
        "a passage that fits should never scroll")

;; a longer passage does scroll, and by a whole line
(def many (e/new-test (vec (repeatedly 30 #(rand-nth long-words)))
                      {:kind :time :limit 60} 40))
(def mspans (:line-spans many))
(assert (> (count mspans) 4) "need more lines than fit to test scrolling")
(defn mat [n] (type-text many (subs (:target many) 0 n)))
(def first-line-of (fn [n] (:target (first (e/visible-lines (mat n) 3)))))
(def l0 (subs (:target many) (:start (nth mspans 0)) (:end (nth mspans 0))))
(def l1 (subs (:target many) (:start (nth mspans 1)) (:end (nth mspans 1))))
(def l2 (subs (:target many) (:start (nth mspans 2)) (:end (nth mspans 2))))
(println "     top line while on line 1:" (= l0 (first-line-of 5)))
(println "     top line while on line 3:" (= l1 (first-line-of (:start (nth mspans 2)))))
(println "     top line while on line 4:" (= l2 (first-line-of (:start (nth mspans 3)))))
(assert (= l0 (first-line-of 5)))
(assert (= l0 (first-line-of (:start (nth mspans 1)))) "line 2 still shows line 1 on top")
(assert (= l1 (first-line-of (:start (nth mspans 2)))) "line 3 scrolls by exactly one")
(assert (= l2 (first-line-of (:start (nth mspans 3)))) "and again by exactly one")
;; the input slice must line up with the line it belongs to
(doseq [{:keys [target input cursor]} (e/visible-lines (mat 120) 3)]
  (assert (<= (count input) (count target)))
  (when cursor (assert (= cursor (count input)))))
(println "ALL OK")
