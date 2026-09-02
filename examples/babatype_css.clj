(ns babatype-css
  "Babatype's look. Monkeytype's palette is grey-on-grey with amber; this keeps
   the amber accent and the monospace passage but sits it on a warmer ground and
   uses libadwaita's card vocabulary for the results.")

(def amber "#e2b714")

(def css "
.bg            { background-image: linear-gradient(165deg, #26282c 0%, #1b1d21 100%); }

/* --- the passage ------------------------------------------------------- */
.passage       { font-family: 'JetBrains Mono','Fira Mono','DejaVu Sans Mono',monospace;
                 font-size: 30px; line-height: 1.75; }
.hint          { font-size: 12px; color: alpha(#ffffff,0.34); }
/* a thin bar between characters, as in the reference -- not a block over one */
.caret         { background: #e2b714; border-radius: 2px; }
.hint .k       { font-family: monospace; color: alpha(#ffffff,0.55); }

/* --- the header ------------------------------------------------------- */
.wordmark      { font-size: 21px; font-weight: 800; color: #eceff2;
                 letter-spacing: -0.5px; }
.tagline       { font-size: 10px; color: alpha(#ffffff,0.30);
                 letter-spacing: 1.6px; font-family: monospace; }

/* --- the mode bar ----------------------------------------------------- */
.modebar       { background: alpha(#000000,0.22); border-radius: 12px;
                 padding: 4px; }
.modebar button           { background: none; box-shadow: none; border: none;
                            color: alpha(#ffffff,0.42); font-size: 13px;
                            padding: 5px 12px; min-height: 0; }
.modebar button:hover     { background: alpha(#ffffff,0.06); color: #ffffff; }
.modebar button.on        { color: #e2b714; font-weight: 700; }

/* --- the live counter ------------------------------------------------- */
.live          { font-family: monospace; font-size: 15px; color: #e2b714;
                 font-weight: 700; letter-spacing: 1px; }

/* --- results ---------------------------------------------------------- */
.big           { font-size: 68px; font-weight: 200; color: #e2b714; }
.biglabel      { font-size: 13px; color: alpha(#ffffff,0.40);
                 letter-spacing: 2px; font-weight: 700; }
.stat          { font-size: 26px; font-weight: 300; color: #ffffff; }
.statlabel     { font-size: 11px; color: alpha(#ffffff,0.38);
                 letter-spacing: 1.5px; font-weight: 700; }
.card          { background: alpha(#ffffff,0.045); border-radius: 16px;
                 padding: 20px 26px; }

/* --- the wpm-per-second chart, built from plain boxes ----------------- */
.bar           { background: alpha(#e2b714,0.85); border-radius: 3px;
                 min-width: 7px; }
.bar.dim       { background: alpha(#e2b714,0.30); }
.chartbase     { background: alpha(#ffffff,0.07); min-height: 2px;
                 border-radius: 1px; }
.errmark       { font-size: 12px; font-weight: 800; color: #ca4754; }
.errmark.quiet { color: transparent; }
")
