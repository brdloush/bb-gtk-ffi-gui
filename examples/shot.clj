(ns shot
  "Writes docs/<app>.png: opens an example, waits for real data, then has the
   app render a picture of itself.

   The screenshot goes through GSK from inside the process, because GNOME
   refuses D-Bus screenshots from unsandboxed callers. `dev/later!` hops the
   waiting worker back onto the GTK thread, which is the only thread allowed to
   touch widgets.

   `bb shot [monitor|weather] [path]`"
  (:require [babashka.fs :as fs]
            [gtk.adw :as adw]
            [gtk.core :as ui]
            [gtk.dev :as dev]
            [deck]
            [monitor]
            [weather]))

(def targets
  {"monitor" {:path "docs/monitor.png"
              :title "System Monitor"
              :size [760 880]
              ;; startup includes class loading and the first paint, so the CPU
              ;; figure would read high; wait for it to settle
              :settle 7000
              :start (fn [] (monitor/start-polling! monitor/state))
              :app   (fn [] (monitor/app))
              :css   (fn [] monitor/css)
              :overlay! (fn [w] (reset! monitor/overlay w))}
   "deck"    {:path "docs/deck.png"
              :title "Deck"
              :size [900 620]
              :settle 1200
              ;; page 1 is the bullets slide: shows type, markup and the chrome
              :start (fn [] (deck/load-deck! "examples/talk.md")
                       (swap! deck/state assoc :index 1)
                       (fn []))
              :app   (fn [] (deck/app))
              :css   (fn [] deck/css)
              :overlay! (fn [_] nil)}
   "weather" {:path "docs/weather.png"
              :title "Weather"
              :size [560 820]
              ;; one HTTP round trip, then a render
              :settle 4000
              :start (fn [] (weather/start-polling!))
              :app   (fn [] (weather/app))
              :css   (fn [] weather/css)
              :overlay! (fn [w] (reset! weather/overlay w))}})

(defn -main [& [which path]]
  (let [name (or which "monitor")
        {:keys [size settle title] :as t}
        (or (get targets name)
            (throw (ex-info (str "unknown target " name) {:known (keys targets)})))
        out  (or path (:path t))
        stop ((:start t))]
    (fs/create-dirs (fs/parent out))
    (try
      (ui/run ((:app t))
              :title title
              :width (first size) :height (second size)
              :window adw/window
              :on-ready
              (fn [_win tree]
                (ui/load-css! ((:css t)))
                ((:overlay! t) (:widget tree))
                (future
                  (Thread/sleep settle)
                  (dev/later!
                   (fn []
                     (let [{:keys [width height scale]} (dev/screenshot! out)]
                       (println (format "wrote %s  %dx%d px (scale %d)"
                                        out width height scale)))
                     (ui/close!))))))
      (finally (stop)))))
