# Babatype: a typing test, in babashka

**Status: done.** `bb babatype` works, `bb shot babatype` and
`bb shot babatype-results` produce the screenshots. Notes at the bottom.

Monkeytype's shape, our look, and a word list that could only exist here.

## The idea that makes it ours

The words are **the names of `clojure.core` functions, gathered at boot** from
the running interpreter. No data file, no download: the app types the language
it is written in.

Measured in this bb: **651 publics** -- 465 plain names and 186 with
punctuation. That splits neatly into difficulty:

| source | what it is | count |
| --- | --- | --- |
| `:core` | plain names, 3-12 characters. The default | ~400 |
| `:symbols` | adds `->` `->>` `some?` `swap!` `deref` `pos?` `*ns*` | +186 |
| `:everything` | adds the monsters: `reset-thread-binding-frame-impl` (31 chars) | 651 |

Every character those names can contain is `!'*+-./123<=>?` plus letters, and
all of them round-trip through GDK cleanly -- checked, no failures. So the
punctuation modes genuinely work rather than silently dropping keys.

## Phase 0 -- spikes. Done.

**Per-character colouring is one label.** This was the whole risk. Monkeytype
uses a `<span>` per letter; the obvious GTK translation is a widget per letter,
which would be hundreds of widgets churning on every keystroke -- straight into
the keyed-reconciliation and widget-lifetime debt we have been avoiding.

Instead: **one `GtkLabel`, one Pango markup string, rebuilt per keystroke.**
Pango does the wrapping, the monospace face and the per-character colour. Cost
measured at **0.171 ms per keystroke** for a 250-character passage, which at
120 WPM (10 keys/second) is nothing. Screenshotted: it already looks like
Monkeytype.

The caret is a `background` span on the current character. Errors are
`foreground` plus `underline='single'`, exactly as in the reference.

**Keys give characters.** `gdk_keyval_to_unicode` turns a keyval into the typed
character, including every punctuation character the word list needs.

**One trap found:** Escape, Tab and BackSpace return unicode 27, 9 and 8 --
control characters, not nothing. So "is this text?" must test `>= 32`, never
`> 0`, or Escape would be typed into the passage.

## The framework work

One small core change: `:on-key` currently passes `{:key "a" :ctrl? ...}`. It
gains **`:char`**, set only for genuinely printable input. Everything else
(`:carousel`, `:on-key`, controllers) already exists from the deck.

That is the whole list. This app is almost entirely example code, which is the
point -- the framework has finally caught up.

## The engine is a pure state machine

Same split that worked for `sysinfo` and `openmeteo`: all the logic in a pure
namespace, tested without a window.

```clojure
(apply-key state {:char \a})        ; -> new state
(apply-key state {:key "BackSpace"})
(apply-key state {:key "space"})    ; commits the word
```

State holds the target words, the current word's input, the committed words, and
a keystroke log with timestamps. Everything else is derived:

- **raw wpm** -- all typed characters / 5, over elapsed minutes
- **wpm** -- correctly typed characters only
- **accuracy** -- correct keystrokes / total keystrokes
- **consistency** -- how flat the per-second wpm series is
- **per-second series** -- for the results chart

Deriving rather than accumulating means the results screen cannot disagree with
the live counters.

## Phases

### Phase 1 -- `:char` on `:on-key`, with a test

Including the `>= 32` rule: a test that Escape and Tab do **not** arrive as
text.

### Phase 2 -- the word list, pure

`babawords.clj`: read `ns-publics`, classify, filter by length, sample without
repeating a word twice in a row. Tested against the live namespace, so it cannot
drift from what bb actually has.

### Phase 3 -- the engine, pure

`babaengine.clj` and its test. Typing, mistakes, backspace, word commit, the
end conditions for both time and word modes, and every metric. This is where the
bugs would live, so it gets the most tests.

### Phase 4 -- the look

The passage, the mode bar, the live counter. Screenshot early, as always: if it
is not more beautiful than the reference, stop.

Palette: Monkeytype's own is grey on grey with amber. Ours keeps the amber
accent for the caret but sits on libadwaita's dark ground, with a proper card
for the results and the boxed-list vocabulary the other examples use.

### Phase 5 -- the results screen

WPM, accuracy, consistency, character breakdown, and a **per-second wpm bar
row** built from small styled boxes -- no `GtkDrawingArea`, no Cairo, because we
still do not have those and this does not need them.

### Phase 6 -- the logo

`babashka`'s mark is a red teardrop head in black sunglasses whose lenses read
`λ_`. Ours keeps the head and the glasses, and puts a keyboard on it:

- the same red teardrop, same black wrap-around glasses
- **lenses show a text caret and an underscore** instead of `λ_` -- a typing
  cursor, blinking in spirit
- a small dark **keyboard** below the chin, three rows of keys, one amber
- wordmark **`ba·ba·type`**, echoing `ba·bash·ka`'s dot separators

Two SVGs, as with the other icons: a square app icon (head plus keyboard, no
wordmark) and a wide lockup for the app's own header.

### Phase 7 -- the deliverable

`docs/babatype.png`, the README, and `bb install-desktop` picking up a fourth
app.

## Modes

Enough to be a real typing test, no more:

- **time**: 15 / 30 / 60 / 120 seconds
- **words**: 10 / 25 / 50 / 100
- **source**: core / symbols / everything
- keys: any character types, space commits, BackSpace corrects,
  **Tab** restarts, **Escape** leaves

## Done means

- [x] `bb babatype` gives a typing test driven entirely by the keyboard
- [x] the words come from the live `clojure.core`, gathered at boot
- [x] the engine and the word list are pure and covered without a window
- [x] core grew one field on one map, plus two generic size props
- [x] `docs/babatype.png`, `docs/babatype-results.png`, and a logo that is
      recognisably babashka with a keyboard

## What actually happened

The plan held; the one-label approach was the whole ballgame. Four notes:

- **Two small core additions, not one.** `:char` on `:on-key` as planned, plus
  `:width`/`:height` as common props -- a size request is generic, and the
  results chart is built out of plain boxes whose height is a size request.
  `:level` dropped its private copy of the same thing.
- **The visible window matters.** A 30 second test lays out 120 words. Handing
  all of them to Pango drew eight lines of grey; the reference shows three. The
  passage now renders a sliding window of 18 words with the cursor five words
  in, so the text scrolls under the caret.
- **Two of my test expectations were wrong, not the code.** A finished test
  freezes its clock at the last keystroke, so wpm was 127 where I had predicted
  120 by assuming the timestamp I passed in. That freeze is correct and now has
  its own assertion.
- **The synthetic screenshot needed slowing down.** Typing the fake run at 55ms
  a character produced 166 wpm, which reads as a lie. At 133ms it gives 80 wpm
  and 97% accuracy, which is a person.

## What the first real use found

Three bugs, all in the dynamics rather than the engine, and all found by
actually typing:

- **Space restarted the test.** The mode bar is made of `GtkButton`s, GTK gave
  the first one focus, and our key controller was in the default *bubble*
  phase -- so the focused button saw the space bar first and activated itself.
  Controllers now attach in the **capture** phase, and the mode buttons are
  `:focusable false` as well. The same reading explains why Tab felt like it
  was moving something: it was, it was moving focus.
- **Overtyping shoved the whole passage sideways.** The passage is one label, so
  every extra character re-wraps the paragraph. Extras are now capped at three;
  past that the keystroke still counts against accuracy but does not appear.
- **The logo needed three goes.** `:picture` with a size request came out at
  512px, because a request is a minimum. `:icon` with `:size` is the right
  widget. Then the SVG looked soft, because `GdkTexture` rasterises at the
  file's intrinsic size -- fixed by setting it to 512 while keeping the viewBox
  at 128. And raising the intrinsic size broke loading entirely until the long
  explanatory comment moved *inside* the element: GdkPixbuf sniffs the first
  bytes of a file to pick a loader.

## The model was wrong at first

The first version treated space as a **command**: it committed the current word
and moved on, wherever the cursor happened to be. Typing it mid-word skipped the
rest of that word, and the state was a list of committed words plus the current
word's input.

That is roughly what Monkeytype does, and it is not what it feels like it should
do. Corrected on first real use to: **the target is one flat string, spaces
included, and space is just another character.** Every keystroke is compared
against the character under the cursor.

The rewrite made the engine smaller -- no word commit, no per-word input, no
special rule for backspacing into a previous word -- and it fixed the
sideways-shifting bug for free, because a wrong key now consumes a target
position rather than being appended beside one. The character cap that bug had
needed became unreachable dead code and went.

Two consequences worth writing down:

- A **substitution** stays aligned, so one typo costs exactly one character.
  An **insertion** puts you out of step until you backspace -- the honest cost
  of a flat target, where the word model would have absorbed it.
- "extra" and "missed" characters can no longer happen: the input cannot outgrow
  the target, and characters past the cursor were never reached rather than
  skipped. Counting the unread remainder of a timed passage as mistakes had been
  reporting 728 errors on a clean run.

The results screen also had to stop conflating two things: `words-passed` is how
far you got, `words-correct` is how much of it was right. The old single
function stopped counting at the first mistyped word, which reported 6 words for
a 26-word run.

## And then the motion was wrong

Two rounds of real use found two more things, both about how it feels rather
than what it computes.

**Space was a command.** The first engine treated it as "commit this word and
move on", wherever the cursor happened to be. Corrected to the model above:
space is a character in the target like any other.

**Pango's wrapping slid the passage sideways.** With `wrap` on, the label
re-flows whenever the text changes. The visible window advanced by one *word* at
a time, so finishing a word shifted the whole paragraph left under a caret that
stayed put -- rather than the caret advancing through three still lines and the
block scrolling up by one when it ran out. Pango also broke `keep-indexed` in
half at the hyphen.

So the wrapping moved into `babaengine`: `wrap-lines` splits the target into
fixed 52-character spans at word boundaries, `visible-lines` returns the three
worth drawing with the caret on the second whenever there is one above it, and
the label has wrapping turned off. The layout cannot move, hyphenated names stay
whole, and both are now tested -- including that the caret is drawn exactly once
even when it sits on a line break, which the first version drew twice.

## Errors, added afterwards

The keystroke log already had everything needed -- every entry carries
`:correct?` -- so this was two derived functions rather than new state:
`errors` for the total and `errors-per-second` for when they happened, aligned
with the wpm series so the chart can mark the right second.

Two decisions worth recording:

- **Backspacing does not un-make a mistake.** The wrong key was still pressed,
  and a typing test that let you erase your error count would be measuring
  something else. There is a test for it.
- The per-second series is **per interval**, not cumulative like the wpm one,
  because its job is to say *when* rather than *how many so far*. A test asserts
  the two series are the same length, or the crosses would mark the wrong
  second.

## Ligatures

Found on first use of the `symbols` source: JetBrains Mono renders `->` as one
arrow glyph and `<=` as `\u2264`. In a typing test that is not cosmetic -- two
characters become a single cell, so the caret and the per-character colours sit
in the wrong place, and the user reported `<=` as hard to aim at and "maybe even
glitchy". It was.

Three approaches, screenshotted side by side:

| | result |
| --- | --- |
| plain label | ligated |
| every character in its own `<span>` | still ligated |
| GTK CSS `font-feature-settings` | **still ligated** -- silently does nothing |
| Pango `font_features='liga=0,calt=0,dlig=0,clig=0'` | plain characters |

Only the Pango attribute works, wrapped once around the whole passage. Worth
remembering that the CSS property exists, is accepted, and has no effect.

## Fullscreen, and a reverted experiment

**It starts fullscreen**, with F11 or F5 to return to a window. A typing test
wants the whole screen. The screenshots are unaffected: `shot.clj` supplies its
own `:on-ready`, so it never goes fullscreen.

**Removing the space at a line break was tried and reverted.** The idea was that
a line-ending space is invisible, so requiring it asks for a keystroke nobody
can see; the target was built by joining the lines with nothing between them.
In use it read worse, not better, so it went back: the target is simply the
words joined by spaces, and a line swallows the space that follows it so the
caret has somewhere visible to sit at the break.

What survived the experiment was the better half of it. `new-test` now works out
the line spans and the word spans **once**, and stores them, instead of three
functions each re-deriving offsets from the same assumption. That was worth
keeping regardless.

## The caret

A background span can only cover a whole character cell, and inserting a bar
into the text would shift everything after it -- the very problem the fixed line
breaks solved. So the caret is a 3px widget floated in a `GtkOverlay` and moved
by asking the label's Pango layout where the next character begins
(`pango_layout_index_to_pos` plus `gtk_label_get_layout_offsets`). Monospace
would let us just multiply, but asking is exact and survives a font change.

The awkward part was *when* to ask. Nothing measured from a laid-out widget is
available during the first render, and two obvious triggers both turned out to
fire too early -- `notify::width` and a frame-clock tick callback each still
reported a height of zero. What works is simply rendering again after
allocation, so the idle loop keeps a slow beat even before anyone types. About
0.05% of a core, and it is what puts the caret in the right place on the opening
screen.

Two things this needed in the framework, both reusable: individual margin props
(`:margin-start` and friends), and `run`'s `:on-render` hook for work that has
to measure widgets that already carry the frame's properties.

Also worth recording: the stray characters that kept appearing in the passage
during testing were not a bug. Every probe window takes focus, so it received
whatever was being typed at the keyboard at the time. The caret test compares
against the cursor's *actual* position rather than a fixed index for exactly
that reason.

## Deliberately not doing## The caret

A background span can only cover a whole character cell, and inserting a bar
into the text would shift everything after it -- the very problem the fixed line
breaks solved. So the caret is a 3px widget floated in a `GtkOverlay` and moved
by asking the label's Pango layout where the next character begins
(`pango_layout_index_to_pos` plus `gtk_label_get_layout_offsets`). Monospace
would let us just multiply, but asking is exact and survives a font change.

The awkward part was *when* to ask. Nothing measured from a laid-out widget is
available during the first render, and two obvious triggers both turned out to
fire too early -- `notify::width` and a frame-clock tick callback each still
reported a height of zero. What works is simply rendering again after
allocation, so the idle loop keeps a slow beat even before anyone types. About
0.05% of a core, and it is what puts the caret in the right place on the opening
screen.

Two things this needed in the framework, both reusable: individual margin props
(`:margin-start` and friends), and `run`'s `:on-render` hook for work that has
to measure widgets that already carry the frame's properties.

Also worth recording: the stray characters that kept appearing in the passage
during testing were not a bug. Every probe window takes focus, so it received
whatever was being typed at the keyboard at the time. The caret test compares
against the cursor's *actual* position rather than a fixed index for exactly
that reason.

## Deliberately not doing

Accounts, leaderboards, persistence beyond a personal best in the config file,
a live wpm graph *during* the test (the results chart is enough), themes beyond
the one good one, custom text, and a command palette.
