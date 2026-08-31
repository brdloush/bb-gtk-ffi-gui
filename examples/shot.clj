(ns shot
  "Writes docs/monitor.png: opens the monitor, waits for two /proc samples so
   the CPU figures are real, then has the app render a picture of itself.

   The screenshot is taken through GSK from inside the process, because GNOME
   refuses D-Bus screenshots from unsandboxed callers. `dev/later!` hops the
   waiting worker back onto the GTK thread, which is the only thread allowed to
   touch widgets.

   `bb shot [path]`"
  (:require [gtk.adw :as adw]
            [gtk.core :as ui]
            [gtk.dev :as dev]
            [monitor]
            [gtk.ratom :as r]))

(defn -main [& [path]]
  (monitor/lean!)                       ; same renderer as `bb monitor`, so the
                                        ; numbers in the picture are the real ones
  (let [path (or path "docs/monitor.png")
        stop (monitor/start-polling! monitor/state)]
    (try
      (ui/run (monitor/app)
              :title "System Monitor"
              :width 760 :height 880
              :window adw/window
              :on-ready
              (fn [_win tree]
                (ui/load-css! monitor/css)
                (reset! monitor/overlay (:widget tree))
                (future
                  ;; Wait out startup before shooting. Two polls are enough for
                  ;; the CPU figure to exist, but the first few seconds include
                  ;; class loading and the first paint, so the number would be
                  ;; higher than what the app actually costs once settled.
                  (Thread/sleep 7000)
                  (dev/later!
                   (fn []
                     (println "wrote" (dev/screenshot! path))
                     (ui/close!))))))
      (finally (stop)))))
