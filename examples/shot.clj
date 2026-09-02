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
            [babaengine :as e]
            [babatype]
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
   "babatype" {:path "docs/babatype.png"
               :title "Babatype"
               :size [1100 700]
               :settle 900
               ;; type a realistic run so the shot shows colour, caret and
               ;; errors rather than an untouched passage
               :start (fn []
                        (let [t0 (- (System/currentTimeMillis) 9000)
                              words (:words (:test @babatype/state))
                              text (str (clojure.string/join " " (take 7 words)) " ")
                              typed (str (subs text 0 (- (count text) 6)) "xz")]
                          (swap! babatype/state update :test
                                 (fn [t]
                                   (reduce (fn [t [i c]]
                                             ;; space carries a :char like any
                                             ;; other printable key
                                             (e/apply-key t
                                                          {:key (if (= c \space) "space" (str c))
                                                           :char c}
                                                          (+ t0 (* i 120))))
                                           t (map-indexed vector typed))))
                          (swap! babatype/state assoc :best 92))
                        (fn []))
               :app   (fn [] (babatype/app))
               :css   (fn [] babatype/css)
               :overlay! (fn [_] nil)}
   "babatype-results"
             {:path "docs/babatype-results.png"
              :title "Babatype"
              :size [1100 700]
              :settle 900
              ;; a finished 30s run at a believable ~90 wpm, with the odd slip,
              ;; so the chart and the character breakdown have real numbers.
              ;; 90 wpm is 7.5 characters a second, so 133ms a keystroke.
              :start (fn []
                       (let [ms 133
                             t0 (- (System/currentTimeMillis) 30200)
                             words (:words (:test @babatype/state))
                             ;; Fumble every seventh word by *substituting* a
                             ;; letter, not inserting one. A substitution costs
                             ;; one position and the rest stays aligned, which
                             ;; is what a real typist mostly does; an insertion
                             ;; would put everything after it out of step until
                             ;; backspaced.
                             text (->> (take 70 words)
                                       (map-indexed
                                        (fn [i w]
                                          (if (and (zero? (mod (inc i) 7))
                                                   (> (count w) 2))
                                            (str (subs w 0 1) "x" (subs w 2))
                                            w)))
                                       (clojure.string/join " "))
                             keep-n (int (/ 30000 ms))]
                         (swap! babatype/state update :test
                                (fn [t]
                                  (-> (reduce (fn [t [i c]]
                                                (e/apply-key
                                                 t
                                                 ;; space carries a :char like any
                                                 ;; other printable key
                                                 {:key (if (= c \space) "space" (str c))
                                                  :char c}
                                                 (+ t0 (* i ms))))
                                              t
                                              (map-indexed vector (take keep-n text)))
                                      (e/tick (System/currentTimeMillis)))))
                         (swap! babatype/state assoc :best 92))
                       (fn []))
              :app   (fn [] (babatype/app))
              :css   (fn [] babatype/css)
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
