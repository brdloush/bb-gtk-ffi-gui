(ns gtk.ffi
  "Raw GTK4 / GObject bindings via babashka.ffi.
   Only the handful of symbols the POC needs."
  (:require [babashka.ffi :as ffi :refer [defcfn]]))

(ffi/load-system-library "gtk-4")
(ffi/load-system-library "gobject-2.0")
(ffi/load-system-library "glib-2.0")

;; -- lifecycle / main loop ---------------------------------------------------
(defcfn gtk-init "gtk_init" [] :void)
(defcfn main-iteration "g_main_context_iteration" [:pointer :int] :int)

;; -- signals ----------------------------------------------------------------
(defcfn signal-connect-data "g_signal_connect_data"
  [:pointer :string :pointer :pointer :pointer :int] :long)

;; -- window -----------------------------------------------------------------
(defcfn window-new "gtk_window_new" [] :pointer)
(defcfn window-set-title "gtk_window_set_title" [:pointer :string] :void)
(defcfn window-set-default-size "gtk_window_set_default_size" [:pointer :int :int] :void)
(defcfn window-set-child "gtk_window_set_child" [:pointer :pointer] :void)
(defcfn window-present "gtk_window_present" [:pointer] :void)

;; -- widget (common) --------------------------------------------------------
(defcfn widget-set-sensitive "gtk_widget_set_sensitive" [:pointer :int] :void)
(defcfn widget-set-tooltip-text "gtk_widget_set_tooltip_text" [:pointer :string] :void)
(defcfn widget-set-hexpand "gtk_widget_set_hexpand" [:pointer :int] :void)
(defcfn widget-set-vexpand "gtk_widget_set_vexpand" [:pointer :int] :void)
(defcfn widget-set-margin-top "gtk_widget_set_margin_top" [:pointer :int] :void)
(defcfn widget-set-margin-bottom "gtk_widget_set_margin_bottom" [:pointer :int] :void)
(defcfn widget-set-margin-start "gtk_widget_set_margin_start" [:pointer :int] :void)
(defcfn widget-set-margin-end "gtk_widget_set_margin_end" [:pointer :int] :void)
(defcfn widget-add-css-class "gtk_widget_add_css_class" [:pointer :string] :void)

;; -- box --------------------------------------------------------------------
(defcfn box-new "gtk_box_new" [:int :int] :pointer)
(defcfn box-append "gtk_box_append" [:pointer :pointer] :void)
(defcfn box-remove "gtk_box_remove" [:pointer :pointer] :void)
(defcfn box-set-spacing "gtk_box_set_spacing" [:pointer :int] :void)

;; -- label ------------------------------------------------------------------
(defcfn label-new "gtk_label_new" [:string] :pointer)
(defcfn label-set-text "gtk_label_set_text" [:pointer :string] :void)
(defcfn label-get-text "gtk_label_get_text" [:pointer] :string)

;; -- button -----------------------------------------------------------------
(defcfn button-new-with-label "gtk_button_new_with_label" [:string] :pointer)
(defcfn button-set-label "gtk_button_set_label" [:pointer :string] :void)

;; -- entry / editable -------------------------------------------------------
(defcfn entry-new "gtk_entry_new" [] :pointer)
(defcfn editable-get-text "gtk_editable_get_text" [:pointer] :string)
(defcfn editable-set-text "gtk_editable_set_text" [:pointer :string] :void)

;; -- check button -----------------------------------------------------------
(defcfn check-button-new-with-label "gtk_check_button_new_with_label" [:string] :pointer)
(defcfn check-button-set-label "gtk_check_button_set_label" [:pointer :string] :void)
(defcfn check-button-get-active "gtk_check_button_get_active" [:pointer] :int)
(defcfn check-button-set-active "gtk_check_button_set_active" [:pointer :int] :void)

;; -- helpers ----------------------------------------------------------------
(def HORIZONTAL 0)
(def VERTICAL 1)

(defn ->gbool [x] (if x 1 0))
(defn <-gbool [x] (not (zero? x)))
