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
            [clojure.set]
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
  "hiccup prop -> GTK signal + how to call the user's handler.
   An atom so an optional namespace can add its own."
  (atom
  {:on-click  {:signal "clicked"
               :invoke (fn [f _w] (f))}
   :on-change {:signal "changed"
               :invoke (fn [f w] (f (g/editable-get-text w)))}
   :on-toggle {:signal "toggled"
               :invoke (fn [f w] (f (g/<-gbool (g/check-button-get-active w))))}
   :on-activate {:signal "activate"
                 :invoke (fn [f w] (f (g/editable-get-text w)))}}))

(defn- connect!
  "Connects one GTK signal to a stable C callback that reads the current
   handler out of `holder`. The closure captured at creation time therefore
   never goes stale when a later render supplies a new handler fn."
  [widget prop holder]
  (let [{:keys [signal invoke]} (@signals prop)
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

(defn- ->classes
  "A :class prop is one name or a collection of them. nils are dropped -- it is
   easy to build a class list with a hole in it, and GTK only complains at
   runtime with a CRITICAL."
  [c]
  (cond
    (nil? c)    #{}
    (string? c) #{c}
    :else       (into #{} (remove nil?) c)))

(defn- apply-common!
  "Pushes the common props. A prop that has been *removed* falls back to its
   GTK default rather than to nil, so dropping :sensitive re-enables the widget
   instead of disabling it."
  [w props prev-props changed]
  (when (contains? changed :sensitive)
    (g/widget-set-sensitive w (g/->gbool (get props :sensitive true))))
  (when (contains? changed :tooltip)
    (g/widget-set-tooltip-text w (:tooltip props)))
  (when (contains? changed :hexpand)
    (g/widget-set-hexpand w (g/->gbool (get props :hexpand false))))
  (when (contains? changed :vexpand)
    (g/widget-set-vexpand w (g/->gbool (get props :vexpand false))))
  (when (contains? changed :margin)
    (let [m (get props :margin 0)]
      (g/widget-set-margin-top w m)
      (g/widget-set-margin-bottom w m)
      (g/widget-set-margin-start w m)
      (g/widget-set-margin-end w m)))
  (when (contains? changed :halign)
    (g/widget-set-halign w (get g/align (get props :halign :fill) 0)))
  (when (contains? changed :valign)
    (g/widget-set-valign w (get g/align (get props :valign :fill) 0)))
  (when (contains? changed :class)
    (let [old (->classes (:class prev-props))
          new (->classes (:class props))]
      (doseq [c (clojure.set/difference old new)] (g/widget-remove-css-class w c))
      (doseq [c (clojure.set/difference new old)] (g/widget-add-css-class w c)))))

;; ---------------------------------------------------------------------------
;; widget specs
;; ---------------------------------------------------------------------------

(defn- box-spec [orientation]
  {:ctor      (fn [p] (g/box-new orientation (or (:spacing p) 0)))
   :apply     (fn [w p changed]
                (when (contains? changed :spacing)
                  (g/box-set-spacing w (or (:spacing p) 0))))
   :append    (fn [parent child _props] (g/box-append parent child))
   :remove    (fn [parent child _props] (g/box-remove parent child))
   :container true})

(def widgets
  "tag -> widget spec. An atom so an optional namespace -- gtk.adw, say -- can
   register its own tags without core knowing they exist.

   A spec is:
     :ctor       (fn [props] ptr)
     :apply      (fn [widget props changed-key-set] ...)
     :text-prop  which prop string/number children fold into  (optional)
     :append     (fn [parent child-ptr child-props] ...)       (containers only)
     :remove     (fn [parent child-ptr child-props] ...)       (containers only)

   `child-props` is there so a container can honour a :slot prop, which is how
   libadwaita's prefix/suffix/top-bar slots are addressed."
  (atom
  {:vbox   (box-spec g/VERTICAL)
   :hbox   (box-spec g/HORIZONTAL)

   :label  {:text-prop :label
            :ctor  (fn [p] (g/label-new (str (:label p ""))))
            :apply (fn [w p changed]
                     (when (contains? changed :label)
                       (g/label-set-text w (str (:label p "")))))}

   :button {:text-prop :label
            :ctor  (fn [p] (g/button-new-with-label (str (:label p ""))))
            :apply (fn [w p changed]
                     (when (contains? changed :label)
                       (g/button-set-label w (str (:label p "")))))}

   :entry  {:text-prop :value
            :ctor  (fn [p]
                     (doto (g/entry-new)
                       (g/entry-set-placeholder (:placeholder p))
                       (g/editable-set-text (str (:value p "")))))
            :apply (fn [w p changed]
                     (when (contains? changed :placeholder)
                       (g/entry-set-placeholder w (:placeholder p)))
                     ;; only write back when it really differs, so we do not
                     ;; fight the caret while the user types
                     (when (contains? changed :value)
                       (let [v (str (:value p ""))]
                         (when (not= v (g/editable-get-text w))
                           (g/editable-set-text w v)))))}

   :check  {:text-prop :label
            :ctor  (fn [p]
                     (doto (g/check-button-new-with-label (str (:label p "")))
                       (g/check-button-set-active (g/->gbool (:active p)))))
            :apply (fn [w p changed]
                     (when (contains? changed :label)
                       (g/check-button-set-label w (str (:label p ""))))
                     (when (contains? changed :active)
                       (let [v (g/->gbool (:active p))]
                         (when (not= v (g/check-button-get-active w))
                           (g/check-button-set-active w v)))))}}))

(defn register-widget!
  "Adds or replaces a widget spec. This is how an optional namespace extends the
   set of tags without core needing to know about it."
  [tag spec]
  (swap! widgets assoc tag spec)
  tag)

(defn register-signal!
  "Adds an :on-* prop. `signal` is the GTK signal name; `invoke` is
   (fn [user-fn widget] ...) and decides what the handler receives."
  [prop signal invoke]
  (swap! signals assoc prop {:signal signal :invoke invoke})
  prop)

;; ---------------------------------------------------------------------------
;; hiccup normalization
;; ---------------------------------------------------------------------------

(defn- text-like?
  "A child that reads as content rather than as a widget."
  [x]
  (or (string? x) (number? x)))

(defn- hiccup-error! [msg data]
  (throw (ex-info msg data)))

(defn- bad-child! [form parent]
  (hiccup-error!
   (str "invalid hiccup " (if parent (str "inside " parent) "at the root of a view")
        ": " (pr-str form)
        "\n  expected a vector like [:label \"hi\"], a string, a number, a seq of"
        " those, or nil")
   {:form form :parent parent}))

(defn- normalize
  "Expands function components, flattens seqs, and folds text children into the
   widget's text prop, so [:label \"Count: \" 3] means [:label {:label \"Count: 3\"}].

   Returns {:tag kw :props map :children [normalized...]} or nil.

   Runs over the whole tree before `reconcile` touches a single widget, so a
   malformed view is rejected without half-mutating the window."
  ([form] (normalize form nil))
  ([form parent]
   (cond
     (nil? form) nil
     (not (vector? form)) (bad-child! form parent)
     :else
     (let [[tag & more] form]
       (if (fn? tag)
         (normalize (apply tag more) parent)
         (let [spec (or (@widgets tag)
                        (hiccup-error!
                         (str "unknown widget " (pr-str tag)
                              "\n  known widgets: "
                              (clojure.string/join " " (sort (map str (keys @widgets)))))
                         {:tag tag :known (set (keys @widgets))}))
               props (if (map? (first more)) (first more) {})
               kids  (->> (if (map? (first more)) (rest more) more)
                          (mapcat #(if (seq? %) % [%]))
                          (remove nil?))
               _     (run! #(when-not (or (vector? %) (text-like? %))
                              (bad-child! % tag))
                           kids)
               texts (remove vector? kids)
               nodes (filterv vector? kids)
               tprop (:text-prop spec)]
           (when (and (seq texts) (nil? tprop))
             (hiccup-error!
              (str tag " has no text of its own: " (pr-str (vec texts))
                   "\n  put the text in a child, e.g. [" tag " {} [:label "
                   (pr-str (str (first texts))) "]]")
              {:tag tag :texts (vec texts)}))
           (when (and (seq texts) (contains? props tprop))
             (hiccup-error!
              (str tag " was given both a " tprop " prop and text children"
                   "\n  drop one: [" tag " {" tprop " ...}] or [" tag " \"...\"]")
              {:tag tag :prop tprop :texts (vec texts)}))
           (when (and (seq nodes) (nil? (:append spec)))
             (hiccup-error!
              (str tag " cannot contain child widgets"
                   "\n  wrap them in a :vbox or :hbox instead")
              {:tag tag :children (count nodes)}))
           {:tag tag
            :props (cond-> props
                     (seq texts) (assoc tprop (apply str texts)))
            :children (mapv #(normalize % tag) nodes)}))))))

;; ---------------------------------------------------------------------------
;; reconciler
;; ---------------------------------------------------------------------------

(declare reconcile)

(defn- prop-keys [props] (set (keys props)))

(def ^:private absent
  "Marks a key that is not there, so an explicit nil counts as a change.
   Without this, :sensitive nil -- which is what (seq \"\") gives you -- reads
   the same as no :sensitive at all and never reaches the widget."
  ::absent)

(defn- changed-props [props prev-props]
  (into #{}
        (remove #(= (get props % absent) (get prev-props % absent)))
        (into (prop-keys props) (prop-keys prev-props))))

(defn- ensure-handlers!
  "Returns the widget's handler holders, connecting any signal it did not have
   at creation time.

   A widget can gain a handler in a later render -- you add an :on-click to a
   button that is already on screen. Without this, no holder exists, nothing is
   ever connected, and that button stays dead for as long as it lives."
  [widget handlers props]
  (reduce (fn [hs prop]
            (if (contains? hs prop)
              hs
              (let [holder (atom (get props prop))]
                (connect! widget prop holder)
                (assoc hs prop holder))))
          handlers
          (filter #(contains? props %) (keys @signals))))

(defn- sync-props!
  "Pushes changed props into the widget. Returns the node, whose :handlers may
   have grown."
  [node props prev-props]
  (let [{:keys [tag widget]} node
        spec     (@widgets tag)
        changed  (changed-props props prev-props)
        handlers (ensure-handlers! widget (:handlers node) props)]
    ;; swap the fn inside each holder; the GTK connection itself stays put.
    ;; a prop that went away leaves nil behind, which the callback ignores.
    (doseq [[prop holder] handlers]
      (reset! holder (get props prop)))
    (apply-common! widget props prev-props changed)
    ((:apply spec) widget props changed)
    (assoc node :handlers handlers)))

(defn- create [{:keys [tag props children] :as vnode}]
  (let [spec     (@widgets tag)
        widget   ((:ctor spec) props)
        handlers (into {} (for [prop (keys @signals)
                                :when (contains? props prop)]
                            [prop (atom (get props prop))]))
        node     (assoc vnode :widget widget :handlers handlers)]
    (doseq [[prop holder] handlers]
      (connect! widget prop holder))
    (apply-common! widget props {} (prop-keys props))
    ((:apply spec) widget props #{})            ; ctor already set the basics
    (assoc node :children
           (mapv (fn [child]
                   (let [c (create child)]
                     ((:append spec) widget (:widget c) (:props c))
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
      ((:append parent-spec) parent-widget (:widget n) (:props n))
      n)

    ;; different widget kind -> replace
    (not= (:tag old-node) (:tag new-vnode))
    (do ((:remove parent-spec) parent-widget (:widget old-node) (:props old-node))
        (let [n (create new-vnode)]
          ((:append parent-spec) parent-widget (:widget n) (:props n))
          n))

    ;; same kind -> patch in place
    :else
    (let [spec (@widgets (:tag new-vnode))
          node (-> (assoc old-node :props (:props new-vnode))
                   (sync-props! (:props new-vnode) (:props old-node)))]
      (let [olds (:children old-node)
            news (:children new-vnode)
            kept (mapv (fn [o n] (reconcile spec (:widget node) o n))
                       olds news)
            ;; extra children in the new tree
            added (mapv (fn [n] (reconcile spec (:widget node) nil n))
                        (drop (count olds) news))]
        ;; children that disappeared
        (doseq [o (drop (count news) olds)]
          ((:remove spec) (:widget node) (:widget o) (:props o)))
        (assoc node :children (into kept added))))))

;; ---------------------------------------------------------------------------
;; run
;; ---------------------------------------------------------------------------

(def default-window
  "How to make the top-level window and put content in it. `gtk.adw` supplies
   an AdwWindow instead, which has no titlebar of its own -- which is what makes
   an Adw header bar look right."
  {:ctor        g/window-new
   :set-content g/window-set-child})

(defn- root-spec [{:keys [set-content]}]
  {:append (fn [win child _props] (set-content win child))
   :remove (fn [win _child _props] (set-content win nil))})

(defn- wake!
  "Interrupts a blocking g_main_context_iteration so the loop can notice a
   dirty flag set from another thread. Safe from any thread -- it is one of the
   few GLib calls that is."
  []
  (g/main-context-wakeup nil))

(defn load-css!
  "Installs CSS for the whole display, so :class has something to attach to.
   Call after the window exists. Later calls stack; a higher priority wins."
  ([css] (load-css! css 800))
  ([css priority]
   (let [p (g/css-provider-new)]
     (g/css-provider-load-from-string p css)
     (g/style-context-add-provider-for-display (g/display-get-default) p priority)
     p)))

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

   `:on-ready` is called once as (f window root-node), after gtk_init and the
   first render but before the window is presented.

   Blocks. At the REPL, start it on its own thread so the prompt stays free:

     (def app-thread (future (ui/run (app) :title \"todo\")))

   Every GTK call then happens on that thread, which is what GTK requires.
   `refresh!` and `swap!` from the REPL only set a flag, so they are safe."
  [component & {:keys [title width height window on-ready]
                :or   {title "babashka + gtk4" width 360 height 200
                       window default-window}}]
  (g/gtk-init)
  (let [root      (root-spec window)
        win       ((:ctor window))
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
                      (vreset! tree (reconcile root win @tree vtree))
                      (reset! last-error nil)
                      (swap! current assoc :tree @tree :error nil))
                    (catch Throwable t
                      ;; keep the old tree and keep pumping GTK, so the window
                      ;; stays alive and the next good render recovers it
                      (report! "render failed" t)
                      (swap! current assoc :error @last-error))))]
    (reset! current {:window win :tree nil
                     ;; the thread running this loop is the GTK thread: every
                     ;; widget call must happen here. Recorded so helpers can
                     ;; check, or marshal onto it.
                     :thread (.getId (Thread/currentThread))
                     :stop! #(do (vreset! running false) (wake!))})
    (g/window-set-title win title)
    (g/window-set-default-size win width height)
    (let [cb (ffi/callback (ffi/global-arena)
                           (fn [_ _]
                             (vreset! destroyed true)
                             (vreset! running false))
                           [:pointer :pointer] :void)]
      (g/signal-connect-data win "destroy" cb nil nil 0))

    ;; the loop blocks in g_main_context_iteration, so anything that makes the
    ;; UI stale has to wake it as well as set the flag
    (r/set-invalidate! #(do (vreset! dirty true) (wake!)))
    (render!)
    ;; after gtk_init and the first render, so a caller can install CSS (which
    ;; needs a display) or keep a pointer to a widget it just built
    (when on-ready (on-ready win @tree))
    (g/window-present win)

    (try
      (while @running
        ;; Block until GTK has something to do. An idle app costs nothing: no
        ;; timer, no polling. A state change on any thread calls wake! and the
        ;; iteration returns immediately.
        (g/main-iteration nil 1)
        ;; then drain anything else already queued, bounded so a busy source
        ;; cannot starve the re-render below
        (loop [i 0]
          (when (and (< i 64) (g/<-gbool (g/main-iteration nil 0)))
            (recur (inc i))))
        (when @dirty (render!)))
      (finally
        ;; whatever happened, do not leave an unpumped window on screen
        (when-not @destroyed
          (g/window-destroy win)
          (loop [i 0] (when (and (< i 64) (g/<-gbool (g/main-iteration nil 0)))
                        (recur (inc i)))))
        (reset! current nil)))
    nil))
