(ns babatype
  "Babatype: a typing test whose words are the names of clojure.core's public
   vars, read out of the running interpreter at boot.

   The passage is a single GtkLabel. Per-character colour, the caret and the
   error underlines are all Pango markup, rebuilt on each keystroke -- about
   0.17 ms for a full passage, which at any human typing speed is free. The
   alternative, a widget per character, would churn hundreds of widgets a
   second.

   All the logic lives in babaengine, pure and tested without a window.

   Run it with `bb babatype`."
  (:require [babaengine :as e]
            [babatype-css]
            [babawords :as w]
            [clojure.string :as str]
            [gtk.adw :as adw]
            [gtk.core :as ui]
            [gtk.dev :as dev]
            [babashka.ffi :as ffi]
            [gtk.ffi :as g]
            [gtk.ratom :as r]))

(def css babatype-css/css)

;; ---------------------------------------------------------------------------
;; state
;; ---------------------------------------------------------------------------

(def cols
  "Characters per line. We wrap the passage ourselves rather than letting Pango
   do it, so the layout never moves as you type -- see babaengine/wrap-words.
   The font is monospace, so a character count is a width."
  52)

(def visible-line-count 3)

(def caret-width
  "A thin bar, as in the reference, rather than a block covering a character."
  3)


(def default-mode {:kind :time :limit 30})
(def default-source :core)

(defn- word-count-for
  "How many words to lay out. A timed test needs enough that a fast typist
   cannot run out; a word test needs exactly its limit."
  [{:keys [kind limit]}]
  (if (= :words kind) limit (max 60 (* 4 limit))))

(defn fresh-test [mode source]
  ;; cols goes in here, not into the renderer: the line breaks decide the target
  ;; itself, because a line's last word is not followed by a space
  (e/new-test (w/words source (word-count-for mode)) mode cols))

(defonce state
  (r/atom {:mode default-mode
           :source default-source
           :test (fresh-test default-mode default-source)
           :best 0}))

(defn restart!
  ([] (restart! (:mode @state) (:source @state)))
  ([mode source]
   (swap! state assoc :mode mode :source source
          :test (fresh-test mode source))))

(defn- remember-best! [s]
  (let [w (e/wpm (:test s) (System/currentTimeMillis))]
    (update s :best max (int w))))

;; ---------------------------------------------------------------------------
;; keys
;; ---------------------------------------------------------------------------

(defn- toggle-fullscreen! []
  (when-let [win (:window @ui/current)]
    (if (g/<-gbool (g/window-is-fullscreen win))
      (g/window-unfullscreen win)
      (g/window-fullscreen win))))

(defonce ^:private inspector? (volatile! false))

(defn- toggle-inspector!
  "Opens the GTK inspector -- the window GTK_DEBUG=interactive would give you.
   Its \"Frames\" page draws the live frame rate and frame times, which is a real
   measurement of what the renderer is doing rather than a number this app
   guesses about itself.

   The inspector keeps its own frame clock running while it is open, so the
   idle cost of the app is not what it normally is while you are watching."
  []
  (g/window-set-interactive-debugging (if (vswap! inspector? not) 1 0)))

(defn on-key
  "Everything is typing except Tab, which restarts, F11 which toggles
   fullscreen, F3 which opens the GTK inspector, and Escape, which leaves.
   Returns truthy for keys we consumed so the rest fall through to GTK."
  [{:keys [key char ctrl?] :as k}]
  (cond
    (= "Tab" key)    (do (restart!) true)
    (or (= "F11" key) (= "F5" key)) (do (toggle-fullscreen!) true)
    (= "F3" key)     (do (toggle-inspector!) true)
    (= "Escape" key) (do (ui/close!) true)
    ;; let ctrl combinations through: ctrl+w should not type a w
    ctrl?            false
    ;; space arrives as an ordinary :char -- nothing here treats it specially
    (or char (= "BackSpace" key))
    (do (swap! state
               (fn [s]
                 (let [t (e/apply-key (:test s) k (System/currentTimeMillis))
                       s (assoc s :test t)]
                   (if (e/finished? t) (remember-best! s) s))))
        true)
    :else false))

;; ---------------------------------------------------------------------------
;; the passage
;; ---------------------------------------------------------------------------

(def ^:private pango-escapes
  {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&apos;"})

(defn- esc [ch] (or (pango-escapes ch) (str ch)))

(defn- line-markup
  "One line, coloured character by character against what was typed.

   A mistyped position shows the character you *should* have typed, in red --
   except where that character is a space, when it shows what you actually typed
   instead. A red space is invisible, and monospace means the substitution costs
   no width."
  [{:keys [target input]}]
  (let [n (max (count target) (count input))]
    (str
     (apply str
            (for [i (range n)
                  :let [t (get target i)
                        u (get input i)]]
              ;; No caret here: it is a thin bar floated over the label and
              ;; positioned from the laid-out text, so it can sit *between*
              ;; characters the way the reference does. A background span would
              ;; have to cover a whole character cell.
              (cond
                (nil? t) (str "<span foreground='#7d3b43'>" (esc u) "</span>")
                (nil? u) (str "<span foreground='#5f6368'>" (esc t) "</span>")
                (= t u)  (str "<span foreground='#eceff2'>" (esc t) "</span>")
                :else    (str "<span foreground='#ca4754' underline='single'"
                              " underline_color='#ca4754'>"
                              (esc (if (= t \space) u t)) "</span>")))))))

(def no-ligatures
  "Turns off the programming ligatures a font like JetBrains Mono applies, so
   `->` stays two characters instead of becoming one arrow glyph.

   This matters more than it looks. A ligature renders two characters as a
   single glyph, so per-character colouring and the caret land in the wrong
   place, and `<=` becomes genuinely hard to aim at.

   It has to be the Pango attribute. Neither wrapping each character in its own
   `<span>` nor GTK's CSS `font-feature-settings` has any effect -- both were
   tried, and both silently kept the ligatures."
  "liga=0,calt=0,dlig=0,clig=0")

(defn passage-markup
  "The visible lines, joined with real newlines. Pango is given no chance to
   re-wrap: the label has wrap turned off and the breaks are already decided."
  [lines]
  (str "<span font_features='" no-ligatures "'>"
       (str/join "\n" (map line-markup lines))
       "</span>"))

;; ---------------------------------------------------------------------------
;; view
;; ---------------------------------------------------------------------------

(defn- find-node
  "First node in the tree whose props carry `cls` in :class."
  [node cls]
  (when node
    (or (when (some #{cls} (let [c (:class (:props node))]
                             (if (string? c) [c] c)))
          node)
        (some #(find-node % cls) (:children node)))))

(defn- caret-index
  "Where the cursor sits in the label's *plain* text: the visible lines joined
   by newlines, so one extra character per line above it."
  [lines]
  (loop [ls lines, at 0]
    (if-let [{:keys [target cursor]} (first ls)]
      (if cursor
        (+ at cursor)
        (recur (rest ls) (+ at (count target) 1)))
      at)))

(def caret-slide-ms
  "How long the caret takes to slide to the next character. Long enough to be
   seen, short enough that a fast typist never sees it lag behind."
  70)

(def caret-jump
  "A move wider than this is not a step to the next character -- it is a line
   wrap, a restart, or a whole word deleted. Sliding across the screen for
   those looks wrong, so they snap."
  120)

(defonce ^:private caret-anim
  ;; x and h are animated, y is not: y only changes on a line wrap, which snaps
  ;; anyway. x0/h0 are where the current move started, tx/th where it ends.
  (atom {:widget nil :live? false :settled? true :y 0
         :x0 0.0 :h0 0.0 :tx 0.0 :th 0.0 :t0 0}))

;; The animator waits on this rather than polling, so an idle app stays idle.
(defonce ^:private caret-wake (Object.))

;; True only while caret-tick! is running. Without it nobody would ever draw
;; the frames after the first, so a lone place-caret! -- the screenshot tool
;; does exactly that -- snaps instead of freezing the bar mid-slide.
(defonce ^:private caret-driven? (volatile! false))

(defn- caret-target
  "Where the caret belongs right now: [x y height] in pixels, asked of the
   label's Pango layout. Monospace would let us multiply, but asking is exact
   and survives a font change. Nil before the label has been laid out."
  [tree]
  (when-let [lbl (find-node tree "passage")]
    (let [idx (caret-index (e/visible-lines (:test @state) visible-line-count))
          layout (g/label-get-layout (:widget lbl))]
      (with-open [arena (ffi/confined-arena)]
        (let [ox   (ffi/alloc arena :int)
              oy   (ffi/alloc arena :int)
              rect (ffi/alloc arena 16)]     ; PangoRectangle: 4 ints
          (g/label-get-layout-offsets (:widget lbl) ox oy)
          (g/pango-index-to-pos layout (int idx) rect)
          [(+ (ffi/read ox :int)
              (quot (ffi/read rect :int 0) g/PANGO-SCALE))
           (+ (ffi/read oy :int)
              (quot (ffi/read rect :int 4) g/PANGO-SCALE))
           (quot (ffi/read rect :int 12) g/PANGO-SCALE)])))))

(defn- caret-draw!
  "Moves the bar. Two margins and a size request -- no re-render, so this is
   cheap enough to do every frame. Main-loop thread only."
  [widget x y h]
  (g/widget-set-margin-start widget (max 0 (int x)))
  (g/widget-set-margin-top widget (max 0 (int y)))
  (g/widget-set-size-request widget caret-width (max 0 (int h))))

(defn- lerp [a b f] (+ a (* (- b a) f)))

(defn- caret-frame
  "[x h] at time `now`: linear from where the move started to where it ends."
  [{:keys [x0 h0 tx th t0]} now]
  (let [f (min 1.0 (/ (double (- now t0)) caret-slide-ms))]
    [(lerp x0 tx f) (lerp h0 th f)]))

(defn- caret-retarget
  "Points the animation at a new spot, starting from wherever the bar is drawn
   at this instant so a move interrupted mid-flight carries on smoothly.
   Unchanged targets are left alone: on-render fires far more often than the
   caret moves, and restarting the clock each time would freeze it in place."
  [{:keys [live? tx th y] :as a} widget [nx ny nh] now]
  (if (and live? (== nx tx) (== ny y) (== nh th))
    (assoc a :widget widget)
    (let [[x h] (if live? (caret-frame a now) [nx nh])
          snap? (or (not @caret-driven?)
                    (not live?)
                    (not= ny y)
                    (> (Math/abs (- nx x)) caret-jump))]
      (assoc a :widget widget :live? true :settled? false :y ny
             :x0 (if snap? (double nx) x)
             :h0 (if snap? (double nh) h)
             :tx (double nx) :th (double nh) :t0 now))))

(defn place-caret!
  "Aims the caret at the character about to be typed and draws the first frame
   of the move. caret-tick! draws the rest."
  [tree]
  (when-let [car (find-node tree "caret")]
    (when-let [target (caret-target tree)]
      (let [now (System/currentTimeMillis)
            before @caret-anim
            a (swap! caret-anim caret-retarget (:widget car) target now)
            [x h] (caret-frame a now)]
        (caret-draw! (:widget car) x (:y a) h)
        (when (not= (:t0 before) (:t0 a))
          (locking caret-wake (.notifyAll caret-wake)))))))

(defn caret-tick!
  "Draws the caret at ~60 Hz while it is in flight, and nothing at all once it
   has arrived: the loop waits to be woken by place-caret!. Only the caret's
   own margins are touched -- a full re-render every frame to move a three
   pixel bar would be silly -- and the widget calls go through later! because
   this is not the main-loop thread. Returns a fn that stops it."
  []
  (let [running? (volatile! true)]
    (vreset! caret-driven? true)
    (future
      (while @running?
        (let [{:keys [widget live? settled? t0] :as a} @caret-anim
              now (System/currentTimeMillis)
              flying? (and widget live? (not settled?))]
          (cond
            (not flying?)
            (locking caret-wake (.wait caret-wake 1000))

            (< (- now t0) caret-slide-ms)
            (let [[x h] (caret-frame a now)]
              (dev/later! #(caret-draw! widget x (:y a) h))
              (Thread/sleep 16))

            ;; the sleep always overshoots the deadline a little, so the last
            ;; frame is drawn from the clamped fraction: exactly the target.
            :else
            (let [[x h] (caret-frame a now)]
              (dev/later! #(caret-draw! widget x (:y a) h))
              (swap! caret-anim
                     #(if (= (:t0 %) t0) (assoc % :settled? true) %)))))))
    #(do (vreset! running? false)
         (vreset! caret-driven? false)
         (locking caret-wake (.notifyAll caret-wake)))))

(defn- mode-button [label on? f]
  ;; :focusable false so a button can never hold focus and swallow the space
  ;; bar. The capture-phase controller already prevents that, but a focus ring
  ;; sitting on "core" while you type also just looks wrong.
  [:button {:label label :class (if on? ["on"] []) :on-click f
            :focusable false}])

(defn mode-bar [{:keys [mode source]}]
  [:hbox {:spacing 18 :halign :center}
   [:hbox {:class "modebar" :spacing 0}
    (for [[label s] [["core" :core] ["symbols" :symbols] ["everything" :everything]]]
      (mode-button label (= source s) #(restart! mode s)))]
   [:hbox {:class "modebar" :spacing 0}
    (for [[label kind] [["time" :time] ["words" :words]]]
      (mode-button label (= kind (:kind mode))
                   #(restart! {:kind kind :limit (if (= kind :time) 30 25)} source)))]
   [:hbox {:class "modebar" :spacing 0}
    (for [n (if (= :time (:kind mode)) [15 30 60 120] [10 25 50 100])]
      (mode-button (str n) (= n (:limit mode))
                   #(restart! (assoc mode :limit n) source)))]])

(defn- live-line [{:keys [test mode]} now]
  (let [left (e/remaining-ms test now)]
    [:hbox {:spacing 22 :halign :center}
     [:label {:class "live"
              :label (if left
                       (format "%ds" (int (Math/ceil (/ left 1000.0))))
                       (str (e/words-passed test) " / " (:limit mode)))}]
     [:label {:class "live" :label (format "%d wpm" (int (e/wpm test now)))}]
     [:label {:class "live" :label (format "%d%%" (int (* 100 (e/accuracy test))))}]]))

(def logo-file
  "The mark, looked up relative to the working directory. The bb tasks set
   Path= to the project root, so this resolves; if it does not, :picture leaves
   a blank space rather than failing."
  "icons/cz.brdloush.Babatype.svg")

(defn header []
  [:hbox {:spacing 10 :halign :start :valign :start}
   ;; :icon, not :picture: pixel-size is an actual size, where a picture's size
   ;; request is only a minimum and a 512px texture would ask for 512px
   [:icon {:file logo-file :size 38 :valign :center}]
   [:vbox {:spacing 0 :valign :center}
    [:label {:class "wordmark" :xalign 0
             :markup (str "ba<span foreground='#dd1111'>·</span>"
                          "ba<span foreground='#dd1111'>·</span>type")}]
    [:label {:class "tagline" :xalign 0 :label "clojure.core, one key at a time"}]]])

(defn typing-view [{:keys [test] :as s} now]
  [:vbox {:spacing 26 :valign :center}
   (mode-bar s)
   (live-line s now)
   [:clamp {:max 1100}
    [:overlay {}
     [:label {:class "passage" :wrap false :xalign 0
              :markup (passage-markup
                       (e/visible-lines test visible-line-count))
              ;; the first render happens before anything is allocated, so the
              ;; text has no geometry yet and the caret would land at the top
              ;; left. This fires when the label is finally given a width.
              :on-resize #(place-caret! (:tree @ui/current))}]
     ;; floated on top, moved into place by place-caret! after each render
     [:bin {:slot :over :class "caret"
            :halign :start :valign :start
            :width caret-width :height 40}]]]
   [:label {:class "hint" :halign :center
            :markup (str "<span foreground='#b9bdc4'><b>tab</b></span> restart"
                         "   ·   <span foreground='#b9bdc4'><b>f11</b></span> window"
                         "   ·   <span foreground='#b9bdc4'><b>esc</b></span> quit")}]])

;; ---------------------------------------------------------------------------
;; results
;; ---------------------------------------------------------------------------

(defn- stat [label value]
  [:vbox {:spacing 2 :halign :center}
   [:label {:class "statlabel" :label (str/upper-case label)}]
   [:label {:class "stat" :label value}]])

(defn- chart
  "wpm per second, as boxes, with a red cross over any second that contained a
   mistake -- the same idea as the reference's error axis. No drawing api
   needed: a bar's height is just a size request."
  [series errs]
  (let [top (max 1.0 (apply max (or (seq series) [1.0])))]
    [:vbox {:spacing 6}
     [:hbox {:spacing 3 :halign :center :valign :end}
      (map-indexed
       (fn [idx v]
         (let [e (get errs idx 0)]
           [:vbox {:spacing 3 :valign :end}
            ;; always present, so the bars stay on one baseline whether or not
            ;; this second had a mistake
            [:label {:class (if (pos? e) ["errmark"] ["errmark" "quiet"])
                     :halign :center
                     :tooltip (when (pos? e)
                                (str e " error" (when (> e 1) "s")
                                     " at " (inc idx) "s"))
                     :label (if (pos? e) "\u00d7" " ")}]
            [:bin {:class (if (= idx (dec (count series))) ["bar"] ["bar" "dim"])
                   :height (max 3 (int (* 110 (/ v top))))
                   :valign :end}]]))
       series)]
     [:bin {:class "chartbase"}]
     [:label {:class "statlabel" :halign :center
              :label (format "WPM OVER TIME  ·  PEAK %d  ·  \u00d7 MISTAKE"
                             (int top))}]]))

(defn results-view [{:keys [test best source mode]} now]
  (let [{:keys [wpm raw-wpm cpm accuracy consistency seconds words chars
                errors series error-series]}
        (e/summary test now)]
    [:vbox {:spacing 22 :valign :center}
     [:hbox {:spacing 40 :halign :center}
      [:vbox {:spacing 0 :halign :center}
       [:label {:class "biglabel" :label "WPM"}]
       [:label {:class "big" :label (str (int wpm))}]]
      [:vbox {:spacing 0 :halign :center}
       [:label {:class "biglabel" :label "CPM"}]
       [:label {:class "big" :label (str (int cpm))}]]
      [:vbox {:spacing 0 :halign :center}
       [:label {:class "biglabel" :label "ACCURACY"}]
       [:label {:class "big" :label (str (int (* 100 accuracy)) "%")}]]]
     [:clamp {:max 900}
      [:vbox {:spacing 20 :class "card"}
       (chart series error-series)
       [:hbox {:spacing 34 :halign :center}
        (stat "raw" (str (int raw-wpm)))
        (stat "consistency" (str (int (* 100 consistency)) "%"))
        (stat "time" (format "%.1fs" seconds))
        (stat "words" (str words))
        (stat "chars" (format "%d/%d" (:correct chars) (:incorrect chars)))
        (stat "errors" (str errors))
        (stat "best" (str best))]]]
     [:label {:class "hint" :halign :center
              :markup (str "<span foreground='#8b8f96'>tab</span> again   ·   "
                           (name source) "   ·   " (name (:kind mode)) " "
                           (:limit mode)
                           "   ·   <span foreground='#8b8f96'>esc</span> quit")}]]))

(defn home [state]
  (let [s @state
        now (System/currentTimeMillis)]
    [:bin {:class "bg" :on-key on-key}
     [:vbox {:spacing 0 :margin 26}
      (header)
      [:bin {:vexpand true}
       (if (e/finished? (:test s))
         (results-view s now)
         (typing-view s now))]]]))

;; ---------------------------------------------------------------------------
;; run
;; ---------------------------------------------------------------------------

(defn tick!
  "Drives the countdown, ends a timed test that is left alone, and keeps the
   caret honest.

   The slow beat while idle is what places the caret correctly on the very
   first screen. Nothing measured from a laid-out label is available during the
   first render -- the widget has no size yet -- and neither `notify::width` nor
   a frame-clock callback fires late enough to help: both still report a height
   of zero. A render shortly *after* allocation does the job, so the loop keeps
   ticking slowly even before anyone types."
  []
  (let [running? (volatile! true)]
    (future
      (while @running?
        (let [t (:test @state)
              live? (and (e/started? t) (not (e/finished? t)))]
          (Thread/sleep (if live? 100 400))
          (when live?
            (swap! state update :test e/tick (System/currentTimeMillis)))
          (ui/refresh!))))
    #(vreset! running? false)))

(defn app [] (fn [] (home state)))

(defn -main [& _]
  (let [stop (tick!)
        stop-caret (caret-tick!)]
    (try
      (ui/run (app)
              :title "Babatype"
              :app-id "cz.brdloush.Babatype"
              :app-name "Babatype"
              :width 1100 :height 700
              :window adw/window
              ;; A typing test wants the whole screen: nothing else on it is
              ;; useful while you are typing. F11 or F5 goes back.
              :on-ready (fn [win _tree]
                          (ui/load-css! css)
                          (g/window-fullscreen win))
              :on-render (fn [_win tree] (place-caret! tree)))
      (finally (stop) (stop-caret)))))
