(ns babaengine
  "The typing test as a pure state machine.

   The target is one flat string, spaces included, and **space is just another
   character**. Typing a space where a letter is expected is a mistyped
   character; typing a letter where the space between two words is expected is
   equally a mistake. Nothing about the space bar is special.

   That falls out nicely: a wrong keystroke consumes one position, so a
   substitution stays aligned with the target and the rest of the passage keeps
   being scored normally. Only inserting or dropping a character puts you out of
   step, and backspace fixes that.

   Every number is *derived* from the input and the keystroke log rather than
   accumulated, so the live counter and the results screen cannot disagree, and
   the whole thing tests without a window."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; state
;; ---------------------------------------------------------------------------

(defn wrap-words
  "Greedily packs `words` into lines of at most `cols` characters, counting the
   single space that joins words *within* a line. Returns a vector of vectors.

   We wrap here rather than letting Pango do it. Pango re-flows whenever the
   text changes, which slides the passage sideways under the caret, and it
   breaks `keep-indexed` in half at the hyphen."
  [words cols]
  (loop [ws (seq words), line [], len 0, lines []]
    (if-let [w (first ws)]
      (let [need (+ (count w) (if (seq line) 1 0))]
        (if (and (seq line) (> (+ len need) cols))
          (recur ws [] 0 (conj lines line))
          (recur (rest ws) (conj line w) (+ len need) lines)))
      (cond-> lines (seq line) (conj line)))))

(defn new-test
  "A fresh test. `mode` is {:kind :time|:words :limit n}, `cols` the width the
   passage will be drawn at.

   The target is simply the words joined by single spaces -- **including** at a
   line break, where the space belongs to the line it ends. The line spans and
   the word spans are worked out once, here, rather than re-derived by every
   function that needs them."
  ([words mode] (new-test words mode 52))
  ([words mode cols]
   (let [words      (vec words)
         target     (str/join " " words)
         n          (count words)
         ;; where each word sits: one space between every pair
         word-spans (:spans (reduce (fn [{:keys [at spans]} w]
                                      {:at (+ at (count w) 1)
                                       :spans (conj spans {:start at
                                                           :end (+ at (count w))})})
                                    {:at 0 :spans []} words))
         ;; each line runs from its first word to its last, and swallows the
         ;; space that follows, so the caret has somewhere visible to sit at a
         ;; line break
         line-spans (:spans
                     (reduce (fn [{:keys [i spans]} line-words]
                              (let [a i
                                    b (+ i (dec (count line-words)))
                                    start (:start (nth word-spans a))
                                    end   (:end (nth word-spans b))
                                    end   (if (< b (dec n)) (inc end) end)]
                                {:i (inc b)
                                 :spans (conj spans {:start start :end end})}))
                            {:i 0 :spans []}
                            (wrap-words words cols)))]
     {:words      words
      :cols       cols
      :lines      (mapv #(subs target (:start %) (:end %)) line-spans)
      :line-spans line-spans
      :word-spans word-spans
      :target     target
      :mode       mode
      :input      ""
      :keys       []
      :started    nil
      :ended      nil})))

(defn started?  [s] (some? (:started s)))
(defn finished? [s] (some? (:ended s)))
(defn cursor    [s] (count (:input s)))

(defn expected
  "The character the cursor sits on, or nil at the end of the passage."
  [{:keys [target] :as s}]
  (get target (cursor s)))

(defn elapsed-ms
  "Milliseconds of typing so far. Zero before the first keystroke, and frozen
   once the test ends."
  [{:keys [started ended]} now]
  (if started (- (or ended now) started) 0))

(defn remaining-ms
  "For a timed test. nil for a word-count test."
  [{:keys [mode] :as s} now]
  (when (= :time (:kind mode))
    (max 0 (- (* 1000 (:limit mode)) (elapsed-ms s now)))))

;; ---------------------------------------------------------------------------
;; the end conditions
;; ---------------------------------------------------------------------------

(defn- complete?
  [{:keys [mode target input] :as s} now]
  (or
   ;; reaching the end of the passage always ends it
   (and (started? s) (>= (count input) (count target)))
   (case (:kind mode)
     :time (and (started? s) (zero? (remaining-ms s now)))
     false)))

(defn tick
  "Ends a timed test when its clock runs out, so a test left alone still
   finishes. Called from the UI's timer."
  [s now]
  (if (and (not (finished? s)) (complete? s now))
    (assoc s :ended now)
    s))

;; ---------------------------------------------------------------------------
;; keys
;; ---------------------------------------------------------------------------

(defn- type-char
  "Any printable character, space included. Compared against the target at the
   cursor, then appended -- so a mistake costs one position and no more.

   Nothing caps the length: a wrong character consumes a target position rather
   than being appended alongside one, so the input can never grow past the
   target. Reaching the end ends the test."
  [s ch now]
  (-> s
      (update :keys conj {:at now :correct? (= ch (expected s))})
      (update :input str ch)))

(defn- backspace [s]
  (cond-> s
    (seq (:input s)) (update :input subs 0 (dec (count (:input s))))))

(defn apply-key
  "The only way state changes. `k` is the map from `:on-key`: {:key .. :char ..}.
   Space arrives as an ordinary `:char`, which is the whole point."
  [s {:keys [key char]} now]
  (if (finished? s)
    s
    (let [s (if (and (not (started? s)) char) (assoc s :started now) s)]
      (-> (cond
            char                (type-char s char now)
            (= "BackSpace" key) (backspace s)
            :else               s)
          (tick now)))))

;; ---------------------------------------------------------------------------
;; derived numbers
;; ---------------------------------------------------------------------------

(defn- minutes [s now] (/ (max 1 (elapsed-ms s now)) 60000.0))

(defn correct-chars
  "Positions where what was typed matches the target."
  [{:keys [target input]}]
  (count (filter true? (map = target input))))

(defn typed-chars [{:keys [input]}] (count input))

(defn wpm
  "Net words per minute: correctly typed characters over five, per minute. The
   standard definition, and the one that fits a flat target."
  [s now]
  (/ (/ (correct-chars s) 5.0) (minutes s now)))

(defn raw-wpm [s now] (/ (/ (typed-chars s) 5.0) (minutes s now)))

(defn accuracy
  "Correct keystrokes over all keystrokes. 1.0 before anything is typed."
  [{:keys [keys]}]
  (if (empty? keys)
    1.0
    (/ (count (filter :correct? keys)) (double (count keys)))))

(defn words-passed
  "How many words the cursor has gone past -- progress, not correctness. This is
   what the `12 / 25` counter means by words; a typo does not stop you having
   got through the word."
  [{:keys [word-spans] :as s}]
  (let [cur (cursor s)]
    (count (filter #(>= cur (:end %)) word-spans))))

(defn words-correct
  "How many whole words were typed exactly right, wherever they fall."
  [{:keys [word-spans target input]}]
  (let [cur (count input)]
    (count (filter (fn [{:keys [start end]}]
                     (and (>= cur end)
                          (= (subs target start end) (subs input start end))))
                   word-spans))))

(defn errors
  "How many keystrokes were wrong. Backspacing does not undo one: the mistake
   was still made, which is the point of counting them."
  [{:keys [keys]}]
  (count (remove :correct? keys)))

(defn errors-per-second
  "Errors in each elapsed second, aligned with the wpm series. Per interval,
   not cumulative -- these mark *when* the mistakes happened."
  [{:keys [keys started] :as s} now]
  (when started
    (let [secs (max 1 (int (Math/ceil (/ (elapsed-ms s now) 1000.0))))]
      (vec (for [sec (range 1 (inc secs))
                 :let [from (+ started (* 1000 (dec sec)))
                       to   (+ started (* 1000 sec))]]
             (count (filter #(and (not (:correct? %))
                                  (> (:at %) from)
                                  (<= (:at %) to))
                            keys)))))))

(defn per-second-wpm
  "One cumulative wpm reading per elapsed second, for the results chart."
  [{:keys [keys started] :as s} now]
  (when started
    (let [secs (max 1 (int (Math/ceil (/ (elapsed-ms s now) 1000.0))))]
      (vec (for [sec (range 1 (inc secs))
                 :let [cutoff (+ started (* 1000 sec))
                       n (count (filter #(and (:correct? %) (<= (:at %) cutoff)) keys))]]
             (/ (/ n 5.0) (/ sec 60.0)))))))

(defn consistency
  "1 minus the coefficient of variation of the per-second series. A steady
   typist scores near 1."
  [s now]
  (let [xs (remove zero? (or (per-second-wpm s now) []))]
    (if (< (count xs) 2)
      1.0
      (let [mean (/ (reduce + xs) (count xs))
            var  (/ (reduce + (map #(let [d (- % mean)] (* d d)) xs)) (count xs))]
        (if (zero? mean)
          1.0
          (max 0.0 (- 1.0 (/ (Math/sqrt var) mean))))))))

(defn character-breakdown
  "correct / incorrect, over what was actually attempted.

   There is deliberately no \"extra\" or \"missed\": the input can never outgrow
   the target, and characters past the cursor were never reached rather than
   skipped -- counting the unread remainder of a timed passage as mistakes would
   be nonsense."
  [{:keys [target input]}]
  (let [pairs (map = target input)]
    {:correct   (count (filter true? pairs))
     :incorrect (count (filter false? pairs))
     :attempted (count input)}))

(defn summary
  "Everything the results screen shows."
  [s now]
  {:wpm         (wpm s now)
   :raw-wpm     (raw-wpm s now)
   :accuracy    (accuracy s)
   :consistency (consistency s now)
   :seconds     (/ (elapsed-ms s now) 1000.0)
   :words       (words-passed s)
   :chars       (character-breakdown s)
   :errors      (errors s)
   :series      (per-second-wpm s now)
   :error-series (errors-per-second s now)})

;; ---------------------------------------------------------------------------
;; what the UI draws
;; ---------------------------------------------------------------------------

(defn visible-lines
  "The `n` lines worth drawing, each as {:target :input :cursor}, with :cursor
   nil on the lines the caret is not on.

   Positioned so the caret sits on the second line whenever there is a line
   above it: the block then scrolls a whole line at a time, rather than sliding
   sideways word by word."
  [{:keys [target input line-spans] :as s} n]
  (let [cur   (cursor s)
        ci    (or (first (keep-indexed (fn [i {:keys [start end]}]
                                        (when (and (>= cur start) (< cur end)) i))
                                      line-spans))
                  (dec (count line-spans)))
        from  (max 0 (min (dec ci) (- (count line-spans) n)))
        shown (subvec line-spans from (min (count line-spans) (+ from n)))]
    (mapv (fn [{:keys [start end]}]
            {:target (subs target start end)
             :input  (if (> (count input) start)
                       (subs input start (min end (count input)))
                       "")
             ;; half-open, or a caret sitting exactly on a line break would be
             ;; drawn twice: once at the end of one line and once at the start
             ;; of the next. The very end of the passage is the exception.
             :cursor (when (or (and (>= cur start) (< cur end))
                               (and (= cur (count target)) (= end cur)))
                       (- cur start))})
          shown)))
