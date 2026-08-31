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
bb test       # reconciler, signal, REPL-reload and dev-helper checks
bb dev        # nREPL server on 1667, for editor-driven work

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
marks the tree dirty and the window keeps showing the old render, even though
the new code is already loaded.

You do *not* need `#'home` for this. A plain call like `(home state)` resolves
through the var at call time in babashka, exactly as on the JVM, so a redef is
picked up on the next render. All that is missing is the render.

So a redef sits pending until *something* re-renders. Type into an entry, tick a
checkbox, click a button -- the resulting `swap!` marks the tree dirty, and the
very next render already uses your new code. `ui/refresh!` is just "re-render
now, without touching the UI", and `gtk.dev` automates that away entirely.

### Setting it up

Structure the app so the view is a plain fn of state:

```clojure
(defn home [state]
  [:vbox {:spacing 10 :margin 16}
   [:label {:label (str "items: " (count (:items @state)))}]
   ...])

(defn app []
  (let [state (r/atom {:draft "" :items []})]
    (fn [] (home state))))
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

- Calling a fn never captures it, so redefining works. Passing it as a **value**
  does capture: `(def v home)` or `(map home xs)` freeze the fn as it is now.
- Re-evaluating `(ui/run (app) ...)` calls `(app)` again, which builds a **fresh**
  `r/atom`, so your state resets. To keep state across restarts, move it to a
  top-level `defonce`.
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

## Dev helpers

`gtk.dev` removes the manual `refresh!`. Four mechanisms; pick one, or compose
them. Nothing here is needed at runtime.

| | what it catches | you must | cost |
| --- | --- | --- | --- |
| `(dev/auto-refresh!)` | everything | keep views pure | ~0.3% of a core |
| `(dev/watch-ns! 'todo)` | fns already interned when it ran | re-run it after adding a fn | none |
| `(dev/defview home [st] ...)` | that one var | use `defview` instead of `defn` | none |
| `(dev/watch-files! "src" "examples")` | anything saved to disk | `defonce` for state | a `stat` every 300ms |

```clojure
(require '[gtk.dev :as dev])

(dev/auto-refresh!)        ; re-render on a timer. no registration at all
(dev/watch-ns! 'todo)      ; event-driven, no wasted renders
(dev/watch-files! "src")   ; edit and save, no REPL attached

(dev/status)               ; what is running
(dev/stop!)                ; stop all of it (leaves the window up)
```

### auto-refresh!

Re-renders on a timer whether anything changed or not. A no-op re-render of ~90
widgets measures **0.28 ms**, so at the default 100 ms interval this is well
under 1% of a core, and the dirty flag was never buying much.

Nothing to register and nothing to remember. It catches a redefined view, a
redefined nested component, a whole namespace reload, a changed top-level value.

The one rule: **views must be pure.** They now run 10 times a second, so a
`println` or a `swap!` inside one fires continuously.

### watch-ns! and defview

Babashka vars support `add-watch`, and a plain `(defn home ...)` redef fires it --
repeatedly, because the watch survives the redef. So these are event-driven: no
polling and no wasted renders.

`watch-ns!` arms every fn currently interned in a namespace. A fn you define
*afterwards* is not covered, since there was nothing to watch at the time. Either
re-run it, or declare views with `defview`, which arms itself:

```clojure
(dev/defview home [state]
  [:vbox {} [:label {:label (str "items: " (count (:items @state)))}]])
```

The ordering works out: a redef fires the watch armed by the previous definition,
then re-arms under the same key. So the first eval arms it, and every later eval
both refreshes and re-arms. Forget `defview` on one view and that view silently
stops live-updating -- which is the trade against `auto-refresh!`.

### watch-files!

Polls `.clj` mtimes, `load-file`s what changed, then re-renders. The only option
that needs no REPL: save in your editor and the window updates. A syntax error in
a half-saved file is reported and the watcher keeps going.

`load-file` re-runs the file's top-level forms, so a top-level `(def state
(r/atom ...))` is rebuilt on every save. Use `defonce`, or keep state inside the
fn `run` closes over -- which is what the todo example does, so its items survive
a save. Keep `ui/run` out of the top level or a save opens a second window.

`test/dev_test.clj` drives all four against a live window and reads the results
out of the real `GtkLabel`.

## When a view is broken

A typo in a view -- `[:label "foo!"]` instead of `[:label {:label "foo!"}]` --
used to freeze the window. The exception escaped `render!`, escaped the main
loop, and killed the thread. Nothing pumped GTK after that, so the window sat
there unresponsive, and because it died inside a `future` the error was
swallowed: no message at all. `ui/close!` could not help either, since the loop
that destroys the window was gone.

Now a failed render is contained. The window keeps pumping, the last good render
stays on screen, and the error is printed:

```
[gtk] render failed: invalid hiccup inside :label: "foo!"
  expected a vector like [:label {:label "hi"}], a seq of those, or nil
  a bare string is not a child -- write [:label {:label "foo!"}]
       {:form "foo!", :parent :label}
```

Fix the view, re-render, and it recovers. Nothing to restart.

Three things make that work:

- **`normalize` validates the whole tree before `reconcile` touches a widget**,
  so a malformed view is rejected without half-mutating the window.
- **Event handlers are wrapped**, so an exception in your `:on-click` is reported
  instead of crossing back into C, where behaviour is undefined.
- **Errors are deduplicated.** With `dev/auto-refresh!` running, a broken view
  would otherwise print ten times a second.

The last failure is kept in `gtk.core/last-error` and on `(:error @ui/current)`,
and clears on the next good render. `test/error_recovery_test.clj` covers a bad
child, an unknown widget, recovery, and a throwing handler.

## How it works

Three small namespaces, plus one for dev comfort:

| File | Job |
| --- | --- |
| `src/gtk/ffi.clj` | `defcfn` bindings for the ~30 GTK4/GObject symbols the POC uses |
| `src/gtk/ratom.clj` | reactive atom: a normal atom whose watch marks the UI dirty |
| `src/gtk/core.clj` | hiccup -> widgets, plus a reconciler and the main loop |
| `src/gtk/dev.clj` | dev-only: var watches, auto-refresh, file watching |

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

No error is shown *in* the window -- it goes to stderr, so with no terminal in
view a broken render looks like nothing happened.

`gtk.dev` is dev-only and unpolished too: one window at a time, `watch-files!`
polls rather than using inotify, and there is no `require`-graph awareness, so
reloading a file does not reload the files that depend on it.
