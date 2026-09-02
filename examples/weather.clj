(ns weather
  "A weather app, in babashka, over GTK4 + libadwaita.

   Open-Meteo, so there is no API key and nothing to sign up for. The whole UI
   is a pure function of one map; a worker refreshes it and the reconciler
   patches only what changed.

   Layout is fixed -- 24 hourly cells, 7 day rows, 5 detail rows, always. Rows
   are created once and patched forever, which is what lets this app skip both
   keyed reconciliation and widget-lifetime management.

   Run it with `bb weather`."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [gtk.adw :as adw]
            [gtk.core :as ui]
            [gtk.ffi :as g]
            [gtk.ratom :as r]
            [openmeteo :as om]
            [weather-css :as wcss]))

(def css
  "The stylesheet, re-exported so tooling does not need to know where it lives."
  wcss/css)

;; ---------------------------------------------------------------------------
;; config and cache
;; ---------------------------------------------------------------------------

(def ^:dynamic *config-path*
  "Where place, units and the cached response live. Dynamic so tests can point
   it at a temp file instead of the real one."
  (str (or (System/getenv "XDG_CONFIG_HOME")
           (str (System/getProperty "user.home") "/.config"))
       "/bb-weather.edn"))

(def default-config
  "No IP geolocation: that would mean handing our address to a third party.
   The city lives here, and the geocoding call sends only what you type."
  {:place {:name "Prague" :region "Prague" :country "Czechia"
           :lat 50.0755 :lon 14.4378}
   :units :metric})

(defn read-config []
  (merge default-config
         (try (when (fs/exists? *config-path*)
                (edn/read-string (slurp *config-path*)))
              (catch Exception _ nil))))

(defn write-config! [m]
  (try
    (fs/create-dirs (fs/parent *config-path*))
    (spit *config-path* (pr-str (select-keys m [:place :units :cache :fetched-at])))
    (catch Exception e
      (println "[weather] could not save config:" (ex-message e)))))

;; ---------------------------------------------------------------------------
;; state
;; ---------------------------------------------------------------------------

(defonce state
  (r/atom {:config (read-config)
           :vm nil
           :loading? true
           :error nil}))

(defn reset-state!
  "Reloads config from *config-path*. For tests, and for after a config edit."
  []
  (reset! state {:config (read-config) :vm nil :loading? true :error nil}))

(defonce overlay (atom nil))

(def refresh-ms (* 15 60 1000))

(defn- apply-response!
  "Store a fresh response, cache it, and rebuild the view model."
  [data]
  (let [now (System/currentTimeMillis)]
    (swap! state
           (fn [s]
             (let [cfg (assoc (:config s) :cache data :fetched-at now)]
               (write-config! cfg)
               (assoc s
                      :config cfg
                      :vm (om/view-model data (:place cfg)
                                         {:units (:units cfg) :fetched-at now})
                      :loading? false
                      :error nil))))))

(defn- show-cached!
  "Render whatever was on disk, so the window opens with real content instead
   of a spinner. Marked with its age by the banner."
  []
  (swap! state
         (fn [s]
           (let [{:keys [cache fetched-at place units]} (:config s)]
             (if cache
               (assoc s :vm (om/view-model cache place
                                           {:units units :fetched-at fetched-at}))
               s)))))

(defn refresh!
  "Fetch on this thread -- callers put it on a worker. Never throws."
  []
  (swap! state assoc :loading? true)
  (try
    (apply-response! (om/fetch! (:place (:config @state))))
    (catch Exception e
      (swap! state assoc :loading? false :error (ex-message e)))))

(defn start-polling! []
  (let [running? (volatile! true)]
    (future
      (while @running?
        (refresh!)
        (Thread/sleep refresh-ms)))
    #(vreset! running? false)))

(defn set-place! [place]
  (swap! state (fn [s] (-> s
                           (assoc-in [:config :place] place)
                           (assoc :vm nil :loading? true))))
  (write-config! (:config @state))
  (future (refresh!)))

(defn toggle-units! []
  (swap! state (fn [s]
                 (let [cfg (update-in (:config s) [:units]
                                      {:metric :imperial :imperial :metric})]
                   (write-config! cfg)
                   (assoc s :config cfg
                          :vm (when-let [c (:cache cfg)]
                                (om/view-model c (:place cfg)
                                               {:units (:units cfg)
                                                :fetched-at (:fetched-at cfg)}))))))
  (future (refresh!)))

;; ---------------------------------------------------------------------------
;; view
;; ---------------------------------------------------------------------------

(defn hour-cell [now? {:keys [time temp-label precip icon]}]
  [:vbox {:spacing 3 :halign :center
          :class (if now? ["hour" "now"] ["hour"])}
   [:label {:class "h-time" :halign :center :label (if now? "now" time)}]
   [:icon {:icon icon :size 20 :halign :center}]
   [:label {:class "h-temp" :halign :center :label temp-label}]
   [:label {:class "h-pop" :halign :center
            :label (if (and precip (pos? precip)) (str precip "%") " ")}]])

(defn hero [{:keys [place current today hourly sky-class]}]
  [:bin {:class ["hero" sky-class]}
   [:vbox {:spacing 2 :margin 18}
    [:label {:class "place" :halign :center
             :label (str (:name place)
                         (when (:country place) (str "  ·  " (:country place))))}]
    [:label {:class "temp" :halign :center :label (:temp current)}]
    [:hbox {:spacing 8 :halign :center}
     [:icon {:icon (:icon current) :size 22}]
     [:label {:class "condition" :label (:label current)}]]
    [:label {:class "sub" :halign :center
             :label (str "Feels like " (:feels current)
                         "   ·   H " (:hi today) "   L " (:lo today))}]
    [:scroll {:h :automatic :v :never :hexpand true :margin 8 :class "hourly"}
     (into [:hbox {:spacing 2 :halign :center}]
           (map-indexed (fn [i h] (hour-cell (zero? i) h)) hourly))]]])

(defn day-row [{:keys [day icon hi lo precip]}]
  ;; deliberately single-line: the icon already carries the condition, and two
  ;; lines x 7 days pushes the dashboard past a screen height
  [:row {:title day :class "dayrow"}
   [:icon {:slot :prefix :icon icon :size 20}]
   [:hbox {:slot :suffix :spacing 12 :valign :center}
    [:label {:class "pop" :label (if (and precip (pos? precip)) (str precip "%") "")}]
    [:label {:class "d-hi" :label hi}]
    [:label {:class ["d-lo" "dim-label"] :label lo}]]])

(defn detail-row [{:keys [title value icon]}]
  [:row {:title title}
   [:icon {:slot :prefix :icon icon :size 18}]
   [:label {:slot :suffix :class "numeric" :valign :center :label value}]])

(defn dashboard [{:keys [daily details place] :as vm}]
  [:vbox {:spacing 0}
   (hero vm)
   [:clamp {:max 620}
    [:vbox {:spacing 14 :margin 14}
     (into [:group {:title "7 days"}] (map day-row daily))
     (into [:group {:title "Details"
                    :description (str (:timezone place)
                                      (when (:elevation place)
                                        (format "  ·  %.0f m" (:elevation place))))}]
           (map detail-row details))]]])

(defn loading []
  [:status-page {:icon "weather-few-clouds-symbolic"
                 :title "Fetching the forecast"
                 :description "Open-Meteo, no account needed."}])

(defn failed [err]
  [:status-page {:icon "network-offline-symbolic"
                 :title "Could not reach Open-Meteo"
                 :description (str err)}])

(defn home [state]
  (let [{:keys [vm loading? error config]} @state
        stale (om/stale-label (:fetched-at config) (System/currentTimeMillis))]
    [:toast-overlay {}
     [:toolbar-view {}
      [:header-bar {:slot :top}
       [:window-title {:title "Weather"
                       :subtitle (or (:name (:place config)) "")}]
       [:entry {:slot :start
                :value ""
                :placeholder "Search a city"
                :tooltip "Search for a city, then press Enter"
                :on-activate
                (fn [q]
                  (future
                    (if-let [hit (first (om/search-places! q))]
                      (do (set-place! hit)
                          (when-let [o @overlay]
                            (adw/toast! o (str (:name hit) ", " (:country hit)))))
                      (when-let [o @overlay]
                        (adw/toast! o (str "No place called \"" q "\""))))))}]
       [:icon-button {:slot :end
                      :icon "view-refresh-symbolic"
                      :tooltip "Refresh now"
                      :on-click #(future (refresh!))}]
       [:button {:slot :end
                 :class "flat"
                 :label (if (= :imperial (:units config)) "°F" "°C")
                 :tooltip "Switch units"
                 :on-click #(toggle-units!)}]]
      [:vbox {:spacing 0}
       (when (or stale error)
         [:banner {:title (or error stale) :revealed true}])
       ;; a GtkBox child takes its natural height unless told to expand, and a
       ;; scrolled window's natural height is tiny -- without this the whole
       ;; dashboard collapses to a 58px sliver
       [:scroll {:vexpand true :hexpand true}
        (cond
          vm       (dashboard vm)
          loading? (loading)
          error    (failed error)
          :else    (loading))]]]]))

;; ---------------------------------------------------------------------------
;; run
;; ---------------------------------------------------------------------------

(defn app []
  (show-cached!)
  (fn [] (home state)))

(defn -main [& _]
  (let [stop (start-polling!)]
    (try
      (ui/run (app)
              :title "Weather"
              :app-id "cz.brdloush.BbWeather"
              :app-name "Weather"
              :width 560 :height 820
              :window adw/window
              :on-ready (fn [_win tree]
                          (ui/load-css! wcss/css)
                          (reset! overlay (:widget tree))))
      (finally (stop)))))
