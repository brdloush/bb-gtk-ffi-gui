;; The passage markup. Pure string building, so no window needed.
(require '[babaengine :as e] '[babatype :as b] '[clojure.string :as str])

(defn- markup-for [words text]
  (let [t (reduce (fn [t [i c]]
                    (e/apply-key t {:key (if (= c \space) "space" (str c)) :char c}
                                 (+ 1000 (* i 50))))
                  (e/new-test words {:kind :words :limit (count words)} b/cols)
                  (map-indexed vector text))]
    (b/passage-markup (e/visible-lines t b/visible-line-count))))

;; --- 1. ligatures are off ----------------------------------------------
;; A font like JetBrains Mono renders -> as one arrow glyph, which puts the
;; caret and the per-character colours in the wrong place. Only the Pango
;; attribute turns it off: per-character spans do not, and GTK's CSS
;; font-feature-settings silently does nothing.
(def m (markup-for ["->" "some?" "<="] "->"))
(println "1) markup opens with:" (subs m 0 52))
(assert (str/starts-with? m "<span font_features='"))
(doseq [f ["liga=0" "calt=0" "dlig=0" "clig=0"]]
  (assert (str/includes? m f) (str "missing " f)))
(assert (str/ends-with? m "</span>"))

;; --- 2. every character is coloured by what was typed ------------------
(def typed (markup-for ["map" "reduce"] "mxp"))
(println "2) typed 'mxp' over 'map reduce'")
;; the correct m
(assert (str/includes? typed "<span foreground='#eceff2'>m</span>"))
;; the wrong x shows the character that *should* have been typed, in red
(assert (str/includes? typed "underline='single'"))
(assert (str/includes? typed ">a</span>") "a mistake should show the target letter")
(assert (not (str/includes? typed ">x<")) "not the letter that was typed")
;; and the untyped remainder is dim
(assert (str/includes? typed "<span foreground='#5f6368'>r</span>"))

;; --- 3. a mistyped space shows what was typed instead ------------------
;; a red space would be invisible, and monospace means the swap costs no width
(def spaced (markup-for ["map" "reduce"] "mapx"))
(println "3) typed a letter where the gap belongs")
(assert (str/includes? spaced "underline='single'"))
(assert (str/includes? spaced ">x</span>") "should show the intruding character")

;; --- 4. the markup contains no caret at all --------------------------
;; The caret is a thin bar floated over the label and positioned from the
;; laid-out text, so it can sit *between* characters. A background span could
;; only ever cover a whole character cell. See test/caret_test.clj.
(def caret (markup-for ["map" "reduce"] "ma"))
(println "4) no caret in the markup:" (not (str/includes? caret "background=")))
(assert (not (str/includes? caret "background="))
        "the caret must not be a background span any more")
;; and the character under the cursor is coloured like any other untyped one
(assert (str/includes? caret "<span foreground='#5f6368'>p</span>")
        "the character at the cursor should just be dim")

;; --- 5. markup characters in the words are escaped --------------------
;; nothing in clojure.core contains & or <, but the passage must not be able to
;; inject markup even so
(def esc (markup-for ["a<b" "c&d"] "a<b"))
(println "5) escaping:" (str/includes? esc "&lt;") (str/includes? esc "&amp;"))
(assert (str/includes? esc "&lt;"))
(assert (str/includes? esc "&amp;"))
(assert (not (re-find #"<span[^>]*>[^<&]*<b" esc)) "raw < leaked into the markup")

;; --- 6. lines are joined with real newlines --------------------------
(def many (markup-for (vec (repeat 40 "reduce")) ""))
(println "6) newlines in the markup:" (count (re-seq #"\n" many)))
(assert (= (dec b/visible-line-count) (count (re-seq #"\n" many)))
        "should be one newline between each visible line, and no more")
;; --- 7. the keys that are not typing --------------------------------
;; F11 and F5 toggle fullscreen, Tab restarts, Escape leaves. Everything else
;; that produces a character is typing, and ctrl combinations fall through.
(println "7) non-typing keys are claimed:"
         (mapv #(boolean (b/on-key {:key %})) ["Tab" "F11" "F5"]))
(assert (every? #(boolean (b/on-key {:key %})) ["Tab" "F11" "F5"])
        "these must be consumed, or GTK gets them too")
(assert (false? (boolean (b/on-key {:key "w" :char \w :ctrl? true})))
        "ctrl+w must fall through rather than typing a w")
(assert (false? (boolean (b/on-key {:key "Shift_L"})))
        "a bare modifier is not typing")
(assert (true? (boolean (b/on-key {:key "a" :char \a})))
        "an ordinary character is typing")
(assert (true? (boolean (b/on-key {:key "space" :char \space})))
        "and so is space")

(println "ALL OK")
