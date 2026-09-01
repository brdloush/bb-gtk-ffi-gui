# A slide presenter: proving motion and input

**Status: done.** `bb deck examples/talk.md` presents, `docs/deck.png` comes
from `bb shot deck`. What actually happened is at the bottom.

## Goal

The monitor proved *tidy*. The weather app proved *designed* -- colour and type.
Neither one moves, and neither one takes a keystroke.

A presenter is the smallest useful app that needs both. Markdown in, fullscreen
slides out, arrow keys to drive it, animated between them.

Third deliverable: `docs/deck.png`, and something genuinely usable for a talk.

## Why this one, and why now

Each example so far has paid for exactly one new piece of framework:

| example | what it bought |
| --- | --- |
| monitor | the extension points that let libadwaita live outside core |
| weather | almost nothing -- two alignment props |
| **deck** | **keyboard input** |

That last one is a real gap. We have four `:on-*` props and every one of them is
a plain widget signal with the same `(instance, data)` shape. Keyboard input is
neither: it needs an *event controller*, and a callback with a different
signature and a return value.

So this app is worth building for the framework, not only for the screenshot.

## Phase 0 -- spikes. Done.

All verified before writing this plan:

- **Key input works.** `gtk_event_controller_key_new`, attach with
  `gtk_widget_add_controller`, connect to the controller's `key-pressed`. The
  callback is `[:pointer :int :int :int :pointer] :int` and the keyvals arrive
  correctly -- 0xff53 for Right, 0x20 for space, and so on.
- **No key table needed.** `gdk_keyval_name` turns a keyval into `"Right"`,
  `"space"`, `"Escape"`, `"Page_Down"`, `"F5"`. `gdk_keyval_to_unicode` covers
  printable keys.
- **Spurious events exist.** A keyval of 0xffff (`Delete`) arrives around focus
  changes. Handle only the keys you know and return 0 for the rest.
- **Fullscreen works**: `gtk_window_fullscreen` / `unfullscreen` /
  `is_fullscreen`.
- **AdwCarousel animates**: position went 0 -> 1.282 -> 1.994 -> 2.000 over about
  250ms, and `animate=0` jumps instantly. **It must be in the widget tree to
  animate** -- an unrealised carousel accepts `scroll_to` and silently does
  nothing, which cost me one wrong measurement.
- **No markdown parser in babashka**, so we write a small one. Which is fine:
  a presenter only needs a subset, and a pure parser is easy to test.

## The framework work

Two changes, both in `gtk.core`, both small.

### 1. Signals with their own signature

`connect!` currently hardcodes `[:pointer :pointer] :void`. The `signals` table
entries gain optional `:argtypes` and `:rettype`, defaulting to exactly what
they use today, so nothing existing changes.

### 2. Event controllers

A controller is not a signal on the widget. You make one, connect to *it*, then
add it to the widget. A signals entry may therefore carry `:controller` -- a fn
returning a fresh controller -- and `connect!` wires to that instead.

With those, `:on-key` becomes an ordinary prop:

```clojure
[:bin {:on-key (fn [key] (case key "Right" (next!) "Left" (prev!) nil))}
 ...]
```

The handler gets the key *name*, not a number, because `gdk_keyval_name` exists.

## The design

### Slides are data

```
markdown file -> [{:type :title :heading "..." :sub "..."}
                  {:type :bullets :heading "..." :items [...]}
                  {:type :quote :text "..."}]
```

`---` on its own line splits slides. A slide whose only content is a heading
becomes a title slide, which is how the deck gets visual variety for free.

Inline markup becomes Pango markup: `**bold**`, `*italic*`, `` `code` ``.
**Escaping matters** -- a raw `&` or `<` in the source will break Pango markup,
so escape before inserting, and test that.

### Motion is declarative

The interesting decision. `scroll_to` is imperative, but it does not have to
look that way:

```clojure
[:carousel {:page (:index @state)} slide-1 slide-2 ...]
```

The spec's `:apply` sees `:page` change and calls `adw_carousel_scroll_to`. So
the animation is a side effect of a prop change, and the app stays a pure
function of state.

One wrinkle: `scroll_to` needs the *child widget*, and `:apply` only receives
the parent. Walk to the nth child with `gtk_widget_get_first_child` and
`gtk_widget_get_next_sibling` -- no need to thread our tree into the spec.

## Phases

### Phase 1 -- core: signal signatures and controllers, plus `:on-key`

With a test that emits `key-pressed` at the controller and asserts the handler
ran. This is testable precisely because `g_signal_emit_by_name` works, which the
spike confirmed -- no human typing required.

### Phase 2 -- markdown to slides, pure and tested

Headings, bullets, quotes, code, slide splitting, inline markup, and escaping.
No GTK involved, so this is the cheapest part to get right.

### Phase 3 -- the tags

`:carousel` with the declarative `:page`, and `:revealer` for the counter and
timer fading in and out. Test that the position actually moves, in a realised
window.

### Phase 4 -- the app

Layout per slide type, big type via CSS, a slide counter, an elapsed timer,
fullscreen on F5, quit on Escape. Keys: Right/space/Page_Down forward,
Left/BackSpace/Page_Up back, Home/End to jump.

### Phase 5 -- live reload (optional)

Poll the markdown file's mtime and re-parse. Editing your talk while it is on
screen is a genuinely nice thing, and it is about fifteen lines.

### Phase 6 -- the deliverable

`docs/deck.png` and the README.

## Done means

- [x] `bb deck talk.md` presents, driven entirely by the keyboard
- [x] slides animate between each other
- [x] core gained signal signatures and controllers, both tested
- [x] markdown parsing is pure and covered, including Pango escaping
- [x] `bb test` still needs no network and no human -- key presses are delivered
      with `g_signal_emit_by_name`

## What actually happened

The plan held. Five things worth recording:

- **`:after-children` had to be invented.** A carousel's initial `:page` was
  silently ignored, because `create` runs `:apply` *before* the children are
  appended, so `scroll_to` had nothing to scroll to. Specs can now declare
  `:after-children`, run once after the children are in place. Any container
  whose props refer to its children needs this.
- **A NULL pointer from C is not `nil`.** It comes back as a live MemorySegment
  at address 0, so `nil?` and `some?` both lie. Walking a sibling chain with
  `nil?` ran off the end and GTK printed CRITICALs. `gtk.ffi/null?` exists now,
  and this is a trap for every future pointer-walking code.
- **`(ui/run view ...)` captures the fn.** A test that redefined `view` through
  `alter-var-root` had no effect until it passed `#'view` instead. Obvious in
  hindsight, invisible while debugging.
- **Cairo makes animation choppy.** The other two examples force
  `GSK_RENDERER=cairo` to save 47 MB, and copying that here was a mistake:
  counting real frames with a GTK tick callback gave 36 fps under cairo against
  62 with GL. Software rendering cannot repaint a full-screen window at 60 Hz.
  Measuring properly then killed the idea altogether: on the *static* monitor,
  cairo was 165 MB at 1.00% CPU against the default's 213 MB at 0.83%. It bought
  memory and nothing else, so the forcing was removed from all three examples
  rather than just this one. Vulkan matches the default on memory, is mixed on
  CPU, and needs drivers, so it is not forced either.
- **Live reload was fifteen lines** and is the nicest thing about it: edit the
  markdown while presenting and the slides reload, keeping your place.

## Deliberately not doing

Speaker notes on a second screen (two windows, and `ui/current` holds one),
PDF export, images inside slides (that is the image-loading piece, and it
belongs to a different example), tables, and any markdown beyond the subset
above.
