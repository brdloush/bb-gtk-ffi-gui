(ns gtk.core
  "Hiccup -> GTK4 widgets, with a tiny reconciler.

   A component is a fn of no args (or of its props) returning hiccup:

     [:vbox {:spacing 12}
      [:label  {:label (str \"Count: \" @count)}]
      [:button {:label \"+1\" :on-click #(swap! count inc)}]]

   State changes mark the tree dirty. On the next main-loop turn the render fn
   runs again, the new hiccup is diffed against the previous tree, and only the
   properties that actually changed are pushed into GTK. No widget is rebuilt
   unless its tag changed."
  (:require [babashka.ffi :as ffi]
            [clojure.string]
            [gtk.ffi :as g]
            [gtk.ratom :as r]))

;; ---------------------------------------------------------------------------
;; signals
;; ---------------------------------------------------------------------------

(def ^{:doc "Last thing that blew up, so a REPL can look at it."} last-error
  (atom nil))

(defn- report!
  "Prints what went wrong without killing the caller. Deduplicated, because
   with dev/auto-refresh! a broken view would otherwise print 10x a second."
  [what ^Throwable t]
  (let [msg (or (ex-message t) (str t))]
    (when (not= msg (:message @last-error))
      (reset! last-error {:what what :message msg :data (ex-data t)})
      (binding [*out* *err*]
        (println (str "\n[gtk] " what ": " msg))
        (when-let [d (ex-data t)] (println "      " (pr-str d)))))
    nil))

(def signals
  "hiccup prop -> GTK signal + how to call the user's handler."
  {:on-click  {:signal "clicked"
               :invoke (fn [f _w] (f))}
   :on-change {:signal "changed"
               :invoke (fn [f w] (f (g/editable-get-text w)))}
   :on-toggle {:signal "toggled"
               :invoke (fn [f w] (f (g/<-gbool (g/check-button-get-active w))))}
   :on-activate {:signal "activate"
                 :invoke (fn [f w] (f (g/editable-get-text w)))}})

(defn- connect!
  "Connects one GTK signal to a stable C callback that reads the current
   handler out of `holder`. The closure captured at creation time therefore
   never goes stale when a later render supplies a new handler fn."
  [widget prop holder]
  (let [{:keys [signal invoke]} (signals prop)
        cb (ffi/callback (ffi/global-arena)
                         (fn [_instance _data]
                           ;; never let an exception cross back into C
                           (try
                             (when-let [f @holder]
                               (invoke f widget))
                             (catch Throwable t
                               (report! (str prop " handler failed") t))))
                         [:pointer :pointer] :void)]
    (g/signal-connect-data widget signal cb nil nil 0)))

;; ---------------------------------------------------------------------------
;; common props
;; ---------------------------------------------------------------------------

(defn- apply-common! [w props changed]
  (when (contains? changed :sensitive)
    (g/widget-set-sensitive w (g/->gbool (:sensitive props))))
  (when (contains? changed :tooltip)
    (g/widget-set-tooltip-text w (:tooltip props)))
  (when (contains? changed :hexpand)
    (g/widget-set-hexpand w (g/->gbool (:hexpand props))))
  (when (contains? changed :vexpand)
    (g/widget-set-vexpand w (g/->gbool (:vexpand props))))
  (when (contains? changed :margin)
    (let [m (:margin props)]
      (g/widget-set-margin-top w m)
      (g/widget-set-margin-bottom w m)
      (g/widget-set-margin-start w m)
      (g/widget-set-margin-end w m)))
  (when (contains? changed :class)
    (doseq [c (let [c (:class props)] (if (string? c) [c] c))]
      (g/widget-add-css-class w c))))

;; ---------------------------------------------------------------------------
;; widget specs
;; ---------------------------------------------------------------------------

(defn- box-spec [orientation]
  {:ctor      (fn [p] (g/box-new orientation (or (:spacing p) 0)))
   :apply     (fn [w p changed]
                (when (contains? changed :spacing)
                  (g/box-set-spacing w (or (:spacing p) 0))))
   :append    g/box-append
   :remove    g/box-remove
   :container true})

(def widgets
  {:vbox   (box-spec g/VERTICAL)
   :hbox   (box-spec g/HORIZONTAL)

   :label  {:ctor  (fn [p] (g/label-new (str (:label p ""))))
            :apply (fn [w p changed]
                     (when (contains? changed :label)
                       (g/label-set-text w (str (:label p "")))))}

   :button {:ctor  (fn [p] (g/button-new-with-label (str (:label p ""))))
            :apply (fn [w p changed]
                     (when (contains? changed :label)
                       (g/button-set-label w (str (:label p "")))))}

   :entry  {:ctor  (fn [p]
                     (doto (g/entry-new)
                       (g/editable-set-text (str (:value p "")))))
            :apply (fn [w p changed]
                     ;; only write back when it really differs, so we do not
                     ;; fight the caret while the user types
                     (when (contains? changed :value)
                       (let [v (str (:value p ""))]
                         (when (not= v (g/editable-get-text w))
                           (g/editable-set-text w v)))))}

   :check  {:ctor  (fn [p]
                     (doto (g/check-button-new-with-label (str (:label p "")))
                       (g/check-button-set-active (g/->gbool (:active p)))))
            :apply (fn [w p changed]
                     (when (contains? changed :label)
                       (g/check-button-set-label w (str (:label p ""))))
                     (when (contains? changed :active)
                       (let [v (g/->gbool (:active p))]
                         (when (not= v (g/check-button-get-active w))
                           (g/check-button-set-active w v)))))}})

;; ---------------------------------------------------------------------------
;; hiccup normalization
;; ---------------------------------------------------------------------------

(defn- bad-hiccup! [form parent]
  (throw (ex-info (str "invalid hiccup "
                       (if parent (str "inside " parent) "at the root of a view")
                       ": " (pr-str form)
                       "\n  expected a vector like [:label {:label \"hi\"}], a seq of those, or nil"
                       (when (string? form)
                         (str "\n  a bare string is not a child -- write [:label {:label "
                              (pr-str form) "}]")))
                  {:form form :parent parent})))

(defn- normalize
  "Expands function components and flattens seqs. Returns
   {:tag kw :props map :children [normalized...]} or nil.

   Runs over the whole tree before `reconcile` touches a single widget, so a
   malformed view is rejected without half-mutating the window."
  ([form] (normalize form nil))
  ([form parent]
   (cond
     (nil? form) nil
     (not (vector? form)) (bad-hiccup! form parent)
     :else
     (let [[tag & more] form]
       (if (fn? tag)
         (normalize (apply tag more) parent)
         (let [props (if (map? (first more)) (first more) {})
               kids  (if (map? (first more)) (rest more) more)]
           (when-not (widgets tag)
             (throw (ex-info (str "unknown widget " (pr-str tag)
                                  "\n  known widgets: "
                                  (clojure.string/join " " (sort (map str (keys widgets)))))
                             {:tag tag :known (set (keys widgets))})))
           {:tag tag
            :props props
            :children (->> kids
                           (mapcat #(if (seq? %) % [%]))
                           (remove nil?)
                           (mapv #(normalize % tag)))}))))))

;; ---------------------------------------------------------------------------
;; reconciler
;; ---------------------------------------------------------------------------

(declare reconcile)

(defn- prop-keys [props] (set (keys props)))

(defn- sync-props! [node props prev-props]
  (let [{:keys [tag widget handlers]} node
        spec    (widgets tag)
        changed (into #{}
                      (remove #(= (get props %) (get prev-props %)))
                      (into (prop-keys props) (prop-keys prev-props)))]
    ;; handlers: just swap the fn inside the holder, connection stays put
    (doseq [[prop holder] handlers]
      (reset! holder (get props prop)))
    (apply-common! widget props changed)
    ((:apply spec) widget props changed)))

(defn- create [{:keys [tag props children] :as vnode}]
  (let [spec     (widgets tag)
        widget   ((:ctor spec) props)
        handlers (into {} (for [prop (keys signals)
                                :when (contains? props prop)]
                            [prop (atom (get props prop))]))
        node     (assoc vnode :widget widget :handlers handlers)]
    (doseq [[prop holder] handlers]
      (connect! widget prop holder))
    (apply-common! widget props (prop-keys props))
    ((:apply spec) widget props #{})            ; ctor already set the basics
    (assoc node :children
           (mapv (fn [child]
                   (let [c (create child)]
                     ((:append spec) widget (:widget c))
                     c))
                 children))))

(defn- reconcile
  "Diffs `new-vnode` against `old-node` under `parent-spec`/`parent-widget`.
   Returns the new node (with :widget attached)."
  [parent-spec parent-widget old-node new-vnode]
  (cond
    ;; nothing there yet -> build
    (nil? old-node)
    (let [n (create new-vnode)]
      ((:append parent-spec) parent-widget (:widget n))
      n)

    ;; different widget kind -> replace
    (not= (:tag old-node) (:tag new-vnode))
    (do ((:remove parent-spec) parent-widget (:widget old-node))
        (let [n (create new-vnode)]
          ((:append parent-spec) parent-widget (:widget n))
          n))

    ;; same kind -> patch in place
    :else
    (let [spec (widgets (:tag new-vnode))
          node (assoc old-node :props (:props new-vnode))]
      (sync-props! node (:props new-vnode) (:props old-node))
      (let [olds (:children old-node)
            news (:children new-vnode)
            kept (mapv (fn [o n] (reconcile spec (:widget node) o n))
                       olds news)
            ;; extra children in the new tree
            added (mapv (fn [n] (reconcile spec (:widget node) nil n))
                        (drop (count olds) news))]
        ;; children that disappeared
        (doseq [o (drop (count news) olds)]
          ((:remove spec) (:widget node) (:widget o)))
        (assoc node :children (into kept added))))))

;; ---------------------------------------------------------------------------
;; run
;; ---------------------------------------------------------------------------

(def ^:private root-spec
  {:append (fn [win child] (g/window-set-child win child))
   :remove (fn [win _child] (g/window-set-child win nil))})

(defonce ^{:doc "The running app, for REPL poking: {:window ptr :tree node}.
  Single window, so a single atom is enough for the POC."}
  current
  (atom nil))

(defn refresh!
  "Forces a re-render on the next main-loop turn.

   State changes do this on their own. Redefining a function does not: the
   reactive atoms never saw it. So after re-evaluating a view fn at the REPL,
   call this to make the running window pick it up.

   For that to work the view must be reached through a var, not captured:

     (fn [] (#'home state))   ; re-evaluating home is seen
     (fn [] (home state))     ; the old fn stays captured"
  []
  (r/invalidate!))

(defn close!
  "Closes the running window and lets its main loop return. Safe from any
   thread: it only flips a flag, and the window is destroyed by the loop
   itself, on the thread GTK expects."
  []
  (when-let [f (:stop! @current)] (f))
  nil)

(defn run
  "Opens a window, renders `component` (a fn returning hiccup) into it and
   drives the GTK main loop until the window is closed.

   Blocks. At the REPL, start it on its own thread so the prompt stays free:

     (def app-thread (future (ui/run (app) :title \"todo\")))

   Every GTK call then happens on that thread, which is what GTK requires.
   `refresh!` and `swap!` from the REPL only set a flag, so they are safe."
  [component & {:keys [title width height]
                :or   {title "babashka + gtk4" width 360 height 200}}]
  (g/gtk-init)
  (let [win       (g/window-new)
        running   (volatile! true)
        destroyed (volatile! false)
        dirty     (volatile! true)
        tree      (volatile! nil)
        render! (fn []
                  (vreset! dirty false)
                  (try
                    ;; normalize first: it validates the whole tree before
                    ;; reconcile mutates any widget
                    (let [vtree (normalize (component))]
                      (vreset! tree (reconcile root-spec win @tree vtree))
                      (reset! last-error nil)
                      (swap! current assoc :tree @tree :error nil))
                    (catch Throwable t
                      ;; keep the old tree and keep pumping GTK, so the window
                      ;; stays alive and the next good render recovers it
                      (report! "render failed" t)
                      (swap! current assoc :error @last-error))))]
    (reset! current {:window win :tree nil :stop! #(vreset! running false)})
    (g/window-set-title win title)
    (g/window-set-default-size win width height)
    (let [cb (ffi/callback (ffi/global-arena)
                           (fn [_ _]
                             (vreset! destroyed true)
                             (vreset! running false))
                           [:pointer :pointer] :void)]
      (g/signal-connect-data win "destroy" cb nil nil 0))

    (r/set-invalidate! #(vreset! dirty true))
    (render!)
    (g/window-present win)

    (try
      (while @running
        ;; drain whatever GTK has queued, bounded so a busy source cannot
        ;; starve the re-render below
        (loop [i 0]
          (when (and (< i 64) (g/<-gbool (g/main-iteration nil 0)))
            (recur (inc i))))
        (when @dirty (render!))
        (Thread/sleep 8))
      (finally
        ;; whatever happened, do not leave an unpumped window on screen
        (when-not @destroyed
          (g/window-destroy win)
          (loop [i 0] (when (and (< i 64) (g/<-gbool (g/main-iteration nil 0)))
                        (recur (inc i)))))
        (reset! current nil)))
    nil))
