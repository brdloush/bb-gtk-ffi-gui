(ns gtk.dev
  "Development helpers. None of this is needed to run an app -- it exists to
   shorten the edit/see loop.

   Three ways to get a redefined view onto the screen. Pick one; they compose.

     (dev/auto-refresh!)      ; re-render on a timer. no rules, catches everything
     (dev/watch-ns! 'todo)    ; re-render when any fn in the ns is redefined
     (dev/defview home [st]   ; same, but armed at definition time
       [:vbox {} ...])

   And one that needs no REPL at all:

     (dev/watch-files! \"src\" \"examples\")   ; save in your editor, window updates

   (dev/status) shows what is active. (dev/stop!) shuts it all down."
  (:require [babashka.fs :as fs]
            [gtk.core :as ui]))

;; ---------------------------------------------------------------------------
;; var watching
;; ---------------------------------------------------------------------------

(defonce ^:private watched-vars (atom #{}))

(defn watch-var!
  "Re-renders whenever v is redefined. Idempotent: re-registering under the
   same key replaces the previous watch instead of stacking up."
  [v]
  (add-watch v ::dev (fn [_ _ _ _] (ui/refresh!)))
  (swap! watched-vars conj v)
  v)

(defn unwatch-var! [v]
  (remove-watch v ::dev)
  (swap! watched-vars disj v)
  v)

(defn- fn-vars
  "The vars in ns whose value is a fn. bb's ns-interns yields values rather
   than vars, so each var is looked up by name."
  [ns]
  (->> (keys (ns-interns ns))
       (keep #(ns-resolve ns %))
       (filter #(fn? @%))))

(defn watch-ns!
  "Watches every fn currently interned in ns. Returns how many.

   A var defined *later* is not covered, because there was nothing to watch
   when this ran. Re-run it after adding a fn, or use `defview`, or use
   `auto-refresh!` which needs no registration at all."
  [ns]
  (let [vs (fn-vars ns)]
    (run! watch-var! vs)
    (count vs)))

(defn unwatch-all! []
  (run! unwatch-var! @watched-vars)
  nil)

(defmacro defview
  "Like defn, but the var re-renders the UI when redefined.

   A redef fires the watch armed by the *previous* definition, then this one
   re-arms under the same key. So the first eval arms it, and every later eval
   both refreshes and re-arms."
  [name & body]
  `(do (defn ~name ~@body)
       (watch-var! (var ~name))
       (var ~name)))

;; ---------------------------------------------------------------------------
;; auto refresh
;; ---------------------------------------------------------------------------

(defonce ^:private auto (atom nil))

(defn auto-refresh!
  "Re-renders on a timer, whether anything changed or not. No registration and
   no rules: it catches a redefined view, a redefined nested component, a whole
   namespace reload, a changed top-level value.

   A no-op re-render of ~90 widgets costs about 0.3 ms, so at the default 100 ms
   this is well under 1% of a core.

   The catch: your view fns now run 10 times a second. Keep them pure -- a
   `println` or a `swap!` inside a view will fire continuously."
  ([] (auto-refresh! 100))
  ([ms]
   (swap! auto (fn [old]
                 (when old (vreset! (:running? old) false))
                 (let [running? (volatile! true)]
                   {:ms ms
                    :running? running?
                    :thread (future
                              (while @running?
                                (ui/refresh!)
                                (Thread/sleep ms)))})))
   ms))

(defn stop-auto-refresh! []
  (swap! auto (fn [old] (when old (vreset! (:running? old) false)) nil))
  nil)

;; ---------------------------------------------------------------------------
;; file watching
;; ---------------------------------------------------------------------------

(defonce ^:private files (atom nil))

(defn- clj-files [paths]
  (sort (mapcat #(map str (fs/glob % "**.clj")) paths)))

(defn- mtimes [paths]
  (into {} (for [f (clj-files paths)]
             [f (str (fs/last-modified-time f))])))

(defn- reload! [path]
  (try
    (load-file path)
    (println "reloaded" path)
    true
    (catch Exception e
      ;; a half-saved file is normal. report and keep watching.
      (println "reload failed" path "-" (ex-message e))
      false)))

(defn watch-files!
  "Polls paths for changed .clj files, reloads them, then re-renders. Lets you
   edit and save with no REPL attached.

   Two things to know. `load-file` re-runs the file's top-level forms, so a
   top-level `(def state (r/atom ...))` is rebuilt on every save -- use `defonce`
   for anything you want to survive, or keep state inside the fn that `run`
   closes over, which is what the todo example does. And keep `ui/run` itself
   out of the top level, or a save will open a second window."
  [& paths]
  (let [paths (or (seq paths) ["src" "examples"])
        ms    300]
    (swap! files
           (fn [old]
             (when old (vreset! (:running? old) false))
             (let [running? (volatile! true)
                   seen     (atom (mtimes paths))]
               {:paths paths
                :running? running?
                :thread
                (future
                  (while @running?
                    (Thread/sleep ms)
                    (let [now     (mtimes paths)
                          ;; forced before `seen` is replaced -- a lazy seq here
                          ;; would compare against the new snapshot and never
                          ;; see a change
                          changed (into [] (for [[f t] now
                                                 :when (not= t (get @seen f))]
                                             f))]
                      (reset! seen now)
                      (when (seq changed)
                        (run! reload! changed)
                        (ui/refresh!)))))})))
    (println "watching" (count (clj-files paths)) "files in" (vec paths))
    nil))

(defn stop-watching-files! []
  (swap! files (fn [old] (when old (vreset! (:running? old) false)) nil))
  nil)

;; ---------------------------------------------------------------------------
;; status / teardown
;; ---------------------------------------------------------------------------

(defn status
  "What dev tooling is currently running, and whether a window is up."
  []
  {:window-open?  (some? @ui/current)
   :watched-vars  (count @watched-vars)
   :auto-refresh  (when-let [a @auto] (str (:ms a) "ms"))
   :watching-files (when-let [f @files] (vec (:paths f)))})

(defn stop!
  "Stops every dev helper. Leaves the window alone -- use ui/close! for that."
  []
  (stop-auto-refresh!)
  (stop-watching-files!)
  (unwatch-all!)
  (status))
