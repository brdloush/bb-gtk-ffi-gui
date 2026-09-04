;; F3 in Babatype opens the GTK inspector -- the same window
;; GTK_DEBUG=interactive gives you, whose "Frames" page shows the live frame
;; rate. gtk_window_set_interactive_debugging needs neither that env var nor
;; the gtk-enable-inspector-keybinding setting, which is the whole point of
;; calling it directly.
;;
;; The inspector window is a second toplevel, so the check is: how many
;; toplevels exist, and which of them are visible. The key is delivered to the
;; real controller with g_signal_emit_by_name, the same trick input_test uses:
;; GTK calls must happen on the GTK thread, and this is the path the app
;; actually takes.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[babatype :as b]
         '[gtk.adw :as adw] '[gtk.core :as ui] '[gtk.dev :as dev] '[gtk.ffi :as g])
(defcfn emit "g_signal_emit_by_name" [:pointer :string :&] :void)
(defcfn list-toplevels "gtk_window_list_toplevels" [] :pointer)
(defcfn glist-length "g_list_length" [:pointer] :int)
(defcfn glist-nth-data "g_list_nth_data" [:pointer :int] :pointer)
(defcfn widget-get-visible "gtk_widget_get_visible" [:pointer] :int)

(def F3 0xffc0)

(def stop-tick (b/tick!))
(def th (future (ui/run (b/app) :title "inspector test" :width 900 :height 600
                        :window adw/window
                        :on-ready (fn [_ _] (ui/load-css! b/css))
                        :on-render (fn [_ t] (b/place-caret! t)))))
(Thread/sleep 2200)

(def ctrl (:controller (last @ui/controllers)))

(defn f3!
  "F3 as the app really receives it."
  []
  (dev/on-gtk-thread!
   (fn []
     (with-open [arena (ffi/confined-arena)]
       (let [ret (ffi/alloc arena :int)]
         (emit ctrl "key-pressed" (int F3) (int 0) (int 0) ret)
         (g/<-gbool (ffi/read ret :int))))))
  (Thread/sleep 900))

(defn toplevels
  "Visibility of every toplevel GTK knows about, ours included."
  []
  (dev/on-gtk-thread!
   (fn []
     (let [l (list-toplevels)]
       (mapv #(g/<-gbool (widget-get-visible (glist-nth-data l %)))
             (range (glist-length l)))))))

;; --- 1. only our own window to start with -------------------------------
(println "1) toplevels before:" (toplevels))
(assert (= [true] (toplevels)) "the inspector must not be open until asked for")

;; --- 2. F3 opens it, and is not typed into the passage ------------------
(def before-input (:input (:test @b/state)))
(f3!)
(println "2) toplevels after F3:" (toplevels))
(assert (= 2 (count (toplevels))) "the inspector should be a second toplevel")
(assert (every? true? (toplevels)) "and it should be visible")
(assert (= before-input (:input (:test @b/state))) "F3 must not type anything")

;; --- 3. F3 again hides it, without destroying it ------------------------
;; Keeping the window alive is what lets its frame-rate history survive a
;; toggle.
(f3!)
(println "3) toplevels after F3 again:" (toplevels))
(assert (= 2 (count (toplevels))) "hiding the inspector should not destroy it")
(assert (= 1 (count (filter true? (toplevels)))) "only our window stays visible")

;; --- 4. and back again --------------------------------------------------
(f3!)
(println "4) toplevels after a third F3:" (toplevels))
(assert (every? true? (toplevels)) "the toggle has to work in both directions")

(f3!)
(ui/close!)
(stop-tick)
(assert (nil? (deref th 3000 :TIMEOUT)))
(println "ALL OK")
(System/exit 0)
