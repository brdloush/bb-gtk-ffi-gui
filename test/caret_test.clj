;; The caret is a thin bar floated over the passage and positioned from the
;; laid-out text, so it can sit *between* characters. This checks it lands
;; exactly where Pango says the next character starts.
;;
;; The assertion is against whatever the cursor actually is, not a fixed index,
;; because a window that takes focus will happily receive stray keystrokes from
;; whoever is at the keyboard.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[babaengine :as e] '[babatype :as b]
         '[gtk.adw :as adw] '[gtk.core :as ui] '[gtk.dev :as dev] '[gtk.ffi :as g])
(defcfn m-start "gtk_widget_get_margin_start" [:pointer] :int)
(defcfn m-top "gtk_widget_get_margin_top" [:pointer] :int)
(defcfn c-width "gtk_widget_get_width" [:pointer] :int)
(defcfn c-height "gtk_widget_get_height" [:pointer] :int)
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)

(def find-node @#'b/find-node)
(def caret-index @#'b/caret-index)

(def first-frame (atom nil))
(def stop-tick (b/tick!))
(def th (future
          (ui/run (b/app) :title "caret test" :width 1100 :height 700
                  :window adw/window
                  :on-ready (fn [_ t]
                              (ui/load-css! b/css)
                              (reset! first-frame
                                      (let [c (:widget (find-node t "caret"))]
                                        [(m-start c) (m-top c)])))
                  :on-render (fn [_ t] (b/place-caret! t)))))
(Thread/sleep 2200)

(defn pango-pos
  "Where Pango says the character at the cursor starts, in the label's widget
   coordinates."
  []
  (dev/on-gtk-thread!
   (fn []
     (let [tree (:tree @ui/current)
           lbl  (:widget (find-node tree "passage"))
           idx  (caret-index (e/visible-lines (:test @b/state) b/visible-line-count))]
       (with-open [a (ffi/confined-arena)]
         (let [ox (ffi/alloc a :int) oy (ffi/alloc a :int) rect (ffi/alloc a 16)]
           (g/label-get-layout-offsets lbl ox oy)
           (g/pango-index-to-pos (g/label-get-layout lbl) (int idx) rect)
           {:x (+ (ffi/read ox :int) (quot (ffi/read rect :int 0) g/PANGO-SCALE))
            :y (+ (ffi/read oy :int) (quot (ffi/read rect :int 4) g/PANGO-SCALE))
            :h (quot (ffi/read rect :int 12) g/PANGO-SCALE)
            :index idx}))))))

(defn caret-at []
  (dev/on-gtk-thread!
   (fn [] (let [c (:widget (find-node (:tree @ui/current) "caret"))]
            {:x (m-start c) :y (m-top c) :w (c-width c) :h (c-height c)
             :type (type-name c)}))))

;; --- 1. it is a thin bar, not a block over a character -----------------
(def c (caret-at))
(println "1) caret:" c)
(assert (= b/caret-width (:w c)) "the caret should be a few pixels wide")
(assert (< (:w c) 6) "and definitely not a whole character cell")
(assert (> (:h c) 20) "but as tall as the text")

;; --- 2. the first frame cannot know, and that is why the loop ticks ----
;; Nothing measured from a laid-out label is available during the first render:
;; the widget has no size yet. notify::width and a frame-clock callback both
;; still report zero, so a later render is what places it.
(println "2) caret on the first frame:" @first-frame "(before allocation)")
(assert (= [0 0] @first-frame))

;; --- 3. it sits exactly where Pango says, from the very first screen ---
(def p (pango-pos))
(println "3) cursor index" (:index p) "-> pango" (select-keys p [:x :y])
         "| caret" (select-keys (caret-at) [:x :y]))
(assert (= [(:x p) (:y p)] [(:x (caret-at)) (:y (caret-at))])
        "the caret is not where the next character starts")
(assert (pos? (:y p)) "the text is inset from the top, so y must not be zero")
(assert (= (:h p) (:h (caret-at))) "the caret should match the character height")

;; --- 4. and it follows the cursor -------------------------------------
(def before (caret-at))
(swap! b/state update :test
       (fn [t] (reduce (fn [t ch] (e/apply-key t {:key (str ch) :char ch}
                                               (System/currentTimeMillis)))
                       t (take 4 (:target t)))))
(ui/refresh!)
(Thread/sleep 300)
(def after (caret-at))
(def p2 (pango-pos))
(println "4) after typing, caret moved" (:x before) "->" (:x after)
         "| pango says" (:x p2))
(assert (> (:x after) (:x before)) "the caret should have moved right")
(assert (= [(:x p2) (:y p2)] [(:x after) (:y after)]))
;; a monospace font means one character is one fixed step
(assert (zero? (mod (- (:x after) (:x before)) 1)) "position should be whole pixels")

(ui/close!)
(stop-tick)
(assert (nil? (deref th 3000 :TIMEOUT)))
(println "ALL OK")
(System/exit 0)
