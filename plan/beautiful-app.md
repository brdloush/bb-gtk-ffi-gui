# A beautiful GTK4 example, without leaving POC scope

## Goal

One screenshot that makes someone say "that's babashka?".

A native GNOME-looking app, driven by live local data, built on the existing
reconciler. The deliverable is the screenshot plus a line count -- everything
else is scaffolding for that.

## Constraint

Stay a POC. Core stays small and readable. New surface goes into an optional
namespace and the example itself. No architecture rewrites.

## The key insight: pick a demo that dodges the hard parts

A general-purpose list app would force three pieces of real framework work.
A **ranked dashboard** needs none of them:

| gap | why a general app needs it | why this demo does not |
| --- | --- | --- |
| keyed children | remove row 3 of 200 and every row below re-labels in place | rows are *ranks*, not identities. "row 1 = whatever is #1" -- re-labelling in place is exactly right |
| thread marshalling | a long scan on the GTK thread freezes the UI | a worker thread only `swap!`s a ratom, which flips a dirty flag. The render still happens on the GTK thread, from the loop. Nothing to marshal |
| callback lifetime | churning rows leaks a global-arena callback each time | a fixed number of rows, created once and patched forever |

So the demo is a **system monitor**: fixed gauges plus a top-N process list,
refreshed on a timer. Live numbers are the one thing a screenshot cannot fake
and a GIF proves instantly.

Deliberately **not** doing: charts (`GtkDrawingArea` + Cairo is a big new FFI
surface for little gain -- `GtkLevelBar` gets most of the look), search/filter
(re-orders and re-counts rows, which is where keys would start to matter),
multiple windows, `GtkApplication` integration.

## Phases

**Status: all phases done.** What follows is the plan as executed, with what
actually happened noted per phase.

### Phase 0 -- spike the unknowns (no committed code) -- DONE

Already verified: libadwaita 1.9 loads, `AdwApplicationWindow`,
`AdwToolbarView`, `AdwHeaderBar`, `AdwStatusPage`, `AdwPreferencesGroup`,
`AdwActionRow`, `AdwToastOverlay` all construct; `GtkCssProvider` works;
`g_idle_add` works.

Then verified too: `AdwWindow` (no `GtkApplication` needed) presents and takes
content, `adw_preferences_group_add`, `adw_action_row_add_prefix/suffix`,
`GtkLevelBar`, `GtkScrolledWindow`, `AdwWindowTitle`, icon buttons and toasts.

### Phase 1 -- extension points in core -- DONE (six, not four)

Each is a few lines, each gets a test. Nothing else in core moves.

1. **`widgets` and `signals` become atoms**, with `register-widget!`. Lets an
   optional namespace add tags without touching core. (glimmer-ui does the same.)
2. **`:append` / `:remove` receive the child's props.** Enables a `:slot` prop,
   which is what libadwaita needs: `AdwActionRow` has prefix and suffix slots,
   `AdwToolbarView` has top and bottom bars. A box ignores the extra argument.
3. **`run` takes a `:window` option** -- `{:ctor f :set-content f}`, defaulting
   to `GtkWindow`. `AdwWindow` uses `adw_window_set_content`, not
   `gtk_window_set_child`, and has no titlebar of its own, which is what makes
   an Adw header bar look right.
4. **`load-css!`** -- `:class` currently attaches classes to nothing, because no
   provider is ever installed. Ten lines makes it real.

Two more turned out to be needed, both small:

5. **`run` takes `:on-ready`** -- called once as `(f window root-node)` after
   `gtk_init` and the first render. Needed because `load-css!` requires a
   display, and because the example wants to keep a pointer to the toast overlay
   it just built.
6. **The main loop blocks.** It used to poll every 8ms so it could check the
   dirty flag. Now it blocks in `g_main_context_iteration`, and anything that
   dirties the UI also calls `g_main_context_wakeup`. An idle window went from
   **2.8% of a core to 0.00%**. This was optional until the demo made idle cost
   visible on screen -- a monitor that reports its own CPU has to be honest.

`test/extension_test.clj` covers 1-5; the whole suite covers 6, since a
mis-woken loop breaks everything.

### Phase 2 -- `src/gtk/adw.clj`, an optional namespace -- DONE

`adw_init`, plus widget specs registered into core:

`:adw-window` `:toolbar-view` `:header-bar` `:page` `:group` `:row`
`:toast-overlay` `:status-page` `:scroll` `:level` `:icon-button` `:pill-button`

Just `defcfn` and spec maps -- the boring, low-risk part. Core does not know
libadwaita exists. 58 bindings, 14 tags, 279 lines. `test/adw_test.clj` checks
every spec builds the GObject type it claims and that each slot lands in the
right place.

### Phase 3 -- the static shell, then screenshot it -- DONE

Window, header bar, one boxed list of fake rows. **Screenshot immediately.**
If that image is not compelling, change target before writing any data code.
Cheapest possible reality check.

Screenshotting turned out to be its own problem: GNOME refuses D-Bus
screenshots from unsandboxed callers, and `grim` needs wlroots. So the app
renders **itself** through GSK -- `gtk_widget_paintable_new` ->
`gtk_snapshot` -> `gsk_renderer_render_texture` -> `gdk_texture_save_to_png`.
No compositor cooperation at all. It lives in `gtk.dev/screenshot!`, and
`dev/later!` (`g_idle_add`) hops a waiting worker back onto the GTK thread,
because widget calls from the wrong thread segfault.

One thing missed the first time: widget sizes are **logical** pixels. On a 2x
display GTK paints at twice that, so snapshotting at the widget's reported size
wrote a half-resolution file that looked soft. `gtk_snapshot_scale` by the
widget's scale factor before snapshotting makes the texture device-sized --
1520x2080 instead of 760x1040 here.

### Phase 4 -- live data -- DONE

`/proc` only, no network, no dependencies:

- `/proc/stat` -- CPU percentage across a one-second delta
- `/proc/meminfo` -- memory used and total
- `/proc/loadavg`, `/proc/uptime`
- `/proc/*/stat` + `/proc/*/status` -- top processes by RSS

A worker thread reads every second and `reset!`s one ratom. The UI is a pure
function of that value.

Two things learned: `slurp` fails on /proc files under babashka ("Invalid
argument", they report zero size) -- `babashka.fs/read-all-lines` works. And a
full sweep costs 16ms if you read every process's name; reading `comm` only for
the winners cuts it to 5ms.

### Phase 5 -- polish and the actual deliverable -- DONE

Toast on an action, `AdwStatusPage` while the first sample lands, a little CSS
for the level bars and the highlighted row. Then the screenshot in the README.

`bb shot` regenerates `docs/monitor.png`. No GIF: a still already carries the
argument, and the numbers in it are real.

## Done means

- [x] `bb monitor` opens something that looks like a GNOME app
- [x] numbers move on their own
- [x] core grew by six small extension points, all tested
- [x] everything else lives in `gtk/adw.clj`, `examples/monitor.clj` and
      `examples/sysinfo.clj`
- [x] `bb shot` writes `docs/monitor.png`, the app shooting itself

## The nice accident

The demo shows its own row in the process list. An idle window costs 0.00% of a
core; the monitor sits at about 2%, and all of that is its own work -- re-reading
/proc and repainting fourteen rows every second. It is next to a JetBrains IDE
using 4.5 GB.

## Staying small

Asked after the fact: can bb be told to use less? Yes, and it was worth doing
since the app reports its own footprint.

- `GSK_RENDERER=cairo` -- 239 MB to 191. Nothing in this UI needs a GPU, and the
  output is pixel-identical.

  **Later reversed.** The deck example animates, and cairo managed only 36 fps
  against GL's 62. Worse, the CPU measurements showed cairo was not even cheaper
  on a static UI (1.00% against 0.83%). It bought 50 MB and a per-app trap, so
  all three examples now use whatever GTK picks. See the renderer section in the
  README.
- `-Xmx96m` -- 191 MB to 162. bb is a GraalVM native image and honours `-Xmx`;
  proved by `-Xmx32m` dying and `-Xmx2000m` not. It has to be on the command
  line, so the tasks re-exec babashka via `babashka.process/exec`, which keeps
  it to one process.

`-Xmx64m` works too (another 7 MB); below ~48 MB it dies at startup.

## Notes

- Window height is whatever the window manager says. On PaperWM every window is
  full height, so `:height` is ignored -- `gtk_window_get_default_size` itself
  reports the tiled value. Not worth fighting.
- The monitor deliberately avoids search and sort. Both re-order rows, which is
  exactly where the missing keyed reconciliation would start to show.
