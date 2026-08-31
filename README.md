# bb-gtk-ffi-gui

A tiny proof of concept: **reactive, hiccup-style native GUIs in
[babashka](https://babashka.org), on GTK4, through `babashka.ffi`.**

Same idea as [glimmer-ui](https://yogthos.net/posts/2026-08-29-glimmer-ui.html)
(Reagent-style reactive atoms driving real native widgets), but using babashka's
new [FFI](https://blog.michielborkent.nl/babashka-ffi.html) instead of Jolt.

No JVM, no build step, no bindings to generate. `bb counter` and a native
window appears.

## The counter

```clojure
(ns counter
  (:require [gtk.core :as ui]
            [gtk.ratom :as r]))

(defn counter []
  (let [n (r/atom 0)]
    (fn []
      [:vbox {:spacing 12 :margin 16}
       [:label {:label (str "Count: " @n)}]
       [:hbox {:spacing 8}
        [:button {:label "- 1"   :on-click #(swap! n dec)}]
        [:button {:label "+ 1"   :on-click #(swap! n inc)}]
        [:button {:label "reset" :on-click #(reset! n 0)
                  :sensitive (not= 0 @n)}]]])))

(defn -main [& _]
  (ui/run (counter) :title "counter" :width 320 :height 160))
```

## Run it

```bash
bb counter    # the glimmer counter
bb todo       # dynamic list, entry, check buttons
bb test       # reconciler, signal and REPL-reload checks

bb tasks      # list them
```

Needs babashka >= 1.13.220 and GTK4 (`libgtk-4.so.1`).

## REPL workflow

You can keep the window open and reshape it while it runs. Two kinds of change,
and they behave differently.

**State changes are automatic.** That is the whole point of the reactive atom:
its watch marks the tree dirty, the loop notices, and only the props that
actually changed get pushed into GTK.

**Code changes are not.** Redefining a function touches no atom, so nothing
marks the tree dirty and the window keeps showing the old render. Two things
make a redef land:

1. reach the view **through a var**, so the new fn is picked up:
   `(#'home state)`, not `(home state)`
2. re-render, either by calling `(ui/refresh!)` or by doing anything in the UI
   that changes state

That second point is easy to miss. A redef sits there pending until *something*
re-renders. Type into an entry, tick a checkbox, click a button -- the resulting
`swap!` marks the tree dirty, and the very next render already uses your new
code. So `ui/refresh!` is really just "re-render now, without touching the UI".

### Setting it up

Structure the app so the view is a plain fn of state, called through its var:

```clojure
(defn home [state]
  [:vbox {:spacing 10 :margin 16}
   [:label {:label (str "items: " (count (:items @state)))}]
   ...])

(defn app []
  (let [state (r/atom {:draft "" :items []})]
    (fn [] (#'home state))))          ; <- the var, not the fn
```

`run` blocks, so start it on its own thread and keep the handle:

```clojure
(def app-thread (future (ui/run (app) :title "todo" :width 420 :height 320)))
```

Now edit `home`, re-evaluate just that form, and:

```clojure
(ui/refresh!)
```

The window repaints in place. Widgets are patched, not rebuilt, so focus and
the caret in a half-typed entry survive.

### Inspecting and stopping

```clojure
(:window @ui/current)                 ; the live GtkWindow pointer
(-> @ui/current :tree :children)      ; the reconciled tree

(ui/close!)                           ; shut the window, let the loop return
```

`ui/close!` is the clean way out. `future-cancel` kills the loop but leaves the
window on screen. Closing the window with the mouse also ends the loop.

### Threads

Every GTK call happens on the thread that ran `gtk_init` -- the future's thread
-- which is what GTK requires. `refresh!`, `close!` and `swap!` are safe to call
from the REPL thread because they only flip a flag; the render and the teardown
both happen back on the GTK thread.

### Gotchas

- `(home state)` captures the fn as it is now. Redefining `home` will not be
  seen. Use `(#'home state)`.
- Re-evaluating `(ui/run (app) ...)` calls `(app)` again, which builds a **fresh**
  `r/atom`, so your state resets. To keep state across restarts, move it to a
  top-level `def`.
- One window at a time. `ui/current` is a single atom, so `refresh!` and `close!`
  act on the most recently started window.

`test/repl_reload_test.clj` drives this whole loop end to end -- redefines a view,
calls `refresh!`, then reads the text back out of the real `GtkLabel`:

```
1) initial: old 7
2) after swap!, no refresh needed: old 8
3) after redef, before refresh! (stale, as expected): old 8
4) after ui/refresh!: NEW 8 123
```

## How it works

Three small namespaces:

| File | Job |
| --- | --- |
| `src/gtk/ffi.clj` | `defcfn` bindings for the ~30 GTK4/GObject symbols the POC uses |
| `src/gtk/ratom.clj` | reactive atom: a normal atom whose watch marks the UI dirty |
| `src/gtk/core.clj` | hiccup -> widgets, plus a reconciler and the main loop |

### Rendering

`ui/run` opens a `GtkWindow` and calls your render fn. The hiccup it returns is
normalized into vnodes and turned into real widgets.

When a reactive atom changes, the tree is marked dirty. On the next main-loop
turn the render fn runs again and the new hiccup is **diffed against the
previous tree**: only properties that actually changed are pushed into GTK, and
a widget is rebuilt only if its tag changed. `test/reconcile_test.clj` asserts
exactly this — the label pointer stays identical across a state change.

glimmer-ui avoids diffing altogether by having each widget subscribe to a path
in the state. Diffing was simply the shorter route to a working POC; the
subscription model is the natural next step.

### Signals and the stale-closure trap

An event handler closes over the state as it was *at the time of that render*.
If you connect it once and forget it, it goes stale. If you reconnect it on
every render, you leak connections.

So each widget gets one C callback, connected once, that reads the current
handler out of a holder atom. Re-renders just `reset!` the holder:

```clojure
(defn- connect! [widget prop holder]
  (let [{:keys [signal invoke]} (signals prop)
        cb (ffi/callback (ffi/global-arena)
                         (fn [_instance _data]
                           (when-let [f @holder] (invoke f widget)))
                         [:pointer :pointer] :void)]
    (g/signal-connect-data widget signal cb nil nil 0)))
```

The callback lives in `ffi/global-arena`, so GTK can never call a pointer whose
arena has been released. `test/signals_test.clj` covers the stale case: it
clicks, re-renders, then clicks a handler that captured the old value and checks
it sees the new one.

### Main loop

Babashka has no GTK main loop to hand over to, so `run` drives it:

```clojure
(while @running
  (loop [i 0]                                    ; drain queued GTK events
    (when (and (< i 64) (g/<-gbool (g/main-iteration nil 0)))
      (recur (inc i))))
  (when @dirty (render!))
  (Thread/sleep 8))
```

`g_main_context_iteration` is called non-blocking so the dirty check gets a turn.
The drain is bounded so a busy source cannot starve rendering.

## Widgets

`:vbox` `:hbox` `:label` `:button` `:entry` `:check`

Common props: `:margin` `:sensitive` `:tooltip` `:hexpand` `:vexpand` `:class`
Events: `:on-click` `:on-change` `:on-toggle` `:on-activate`

Adding a widget is one entry in `gtk.core/widgets`:

```clojure
:label {:ctor  (fn [p] (g/label-new (str (:label p ""))))
        :apply (fn [w p changed]
                 (when (contains? changed :label)
                   (g/label-set-text w (str (:label p "")))))}
```

## Not done

POC scope. Missing: keyed children (list reordering re-labels in place instead
of moving widgets), prop *removal* (setting a prop back to nil is ignored),
CSS class removal, more widgets, multiple windows, `GtkApplication` integration,
and path-based subscriptions instead of whole-tree diffing.
