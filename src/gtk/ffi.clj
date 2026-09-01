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
(defcfn idle-add "g_idle_add" [:pointer :pointer] :int)
(defcfn main-context-wakeup "g_main_context_wakeup" [:pointer] :void)

;; -- signals ----------------------------------------------------------------
(defcfn signal-connect-data "g_signal_connect_data"
  [:pointer :string :pointer :pointer :pointer :int] :long)

;; -- window -----------------------------------------------------------------
(defcfn window-new "gtk_window_new" [] :pointer)
(defcfn window-set-title "gtk_window_set_title" [:pointer :string] :void)
(defcfn window-set-default-size "gtk_window_set_default_size" [:pointer :int :int] :void)
(defcfn window-set-child "gtk_window_set_child" [:pointer :pointer] :void)
(defcfn window-present "gtk_window_present" [:pointer] :void)
(defcfn window-destroy "gtk_window_destroy" [:pointer] :void)

;; -- widget (common) --------------------------------------------------------
(defcfn widget-set-sensitive "gtk_widget_set_sensitive" [:pointer :int] :void)
(defcfn widget-set-tooltip-text "gtk_widget_set_tooltip_text" [:pointer :string] :void)
(defcfn widget-set-hexpand "gtk_widget_set_hexpand" [:pointer :int] :void)
(defcfn widget-set-vexpand "gtk_widget_set_vexpand" [:pointer :int] :void)
(defcfn widget-set-margin-top "gtk_widget_set_margin_top" [:pointer :int] :void)
(defcfn widget-set-margin-bottom "gtk_widget_set_margin_bottom" [:pointer :int] :void)
(defcfn widget-set-margin-start "gtk_widget_set_margin_start" [:pointer :int] :void)
(defcfn widget-set-margin-end "gtk_widget_set_margin_end" [:pointer :int] :void)
(defcfn widget-set-halign "gtk_widget_set_halign" [:pointer :int] :void)
(defcfn widget-set-valign "gtk_widget_set_valign" [:pointer :int] :void)
(defcfn widget-add-css-class "gtk_widget_add_css_class" [:pointer :string] :void)
(defcfn widget-remove-css-class "gtk_widget_remove_css_class" [:pointer :string] :void)

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
(defcfn entry-set-placeholder "gtk_entry_set_placeholder_text" [:pointer :string] :void)
(defcfn editable-get-text "gtk_editable_get_text" [:pointer] :string)
(defcfn editable-set-text "gtk_editable_set_text" [:pointer :string] :void)

;; -- check button -----------------------------------------------------------
(defcfn check-button-new-with-label "gtk_check_button_new_with_label" [:string] :pointer)
(defcfn check-button-set-label "gtk_check_button_set_label" [:pointer :string] :void)
(defcfn check-button-get-active "gtk_check_button_get_active" [:pointer] :int)
(defcfn check-button-set-active "gtk_check_button_set_active" [:pointer :int] :void)

;; -- snapshot to png (gtk.dev/screenshot!) -----------------------------------
(defcfn widget-paintable-new "gtk_widget_paintable_new" [:pointer] :pointer)
(defcfn widget-get-width "gtk_widget_get_width" [:pointer] :int)
(defcfn widget-get-height "gtk_widget_get_height" [:pointer] :int)
(defcfn snapshot-new "gtk_snapshot_new" [] :pointer)
(defcfn snapshot-scale "gtk_snapshot_scale" [:pointer :float :float] :void)
(defcfn widget-get-scale-factor "gtk_widget_get_scale_factor" [:pointer] :int)
(defcfn texture-get-width "gdk_texture_get_width" [:pointer] :int)
(defcfn texture-get-height "gdk_texture_get_height" [:pointer] :int)
(defcfn paintable-snapshot "gdk_paintable_snapshot" [:pointer :pointer :double :double] :void)
(defcfn snapshot-free-to-node "gtk_snapshot_free_to_node" [:pointer] :pointer)
(defcfn widget-get-root "gtk_widget_get_root" [:pointer] :pointer)
(defcfn native-get-renderer "gtk_native_get_renderer" [:pointer] :pointer)
(defcfn renderer-render-texture "gsk_renderer_render_texture" [:pointer :pointer :pointer] :pointer)
(defcfn texture-save-to-png "gdk_texture_save_to_png" [:pointer :string] :int)

;; -- css --------------------------------------------------------------------
(defcfn css-provider-new "gtk_css_provider_new" [] :pointer)
(defcfn css-provider-load-from-string "gtk_css_provider_load_from_string" [:pointer :string] :void)
(defcfn display-get-default "gdk_display_get_default" [] :pointer)
(defcfn style-context-add-provider-for-display "gtk_style_context_add_provider_for_display"
  [:pointer :pointer :int] :void)

;; -- environment (must be set before GTK reads it, i.e. before the first
;; -- window is realized) ----------------------------------------------------
(defcfn setenv "g_setenv" [:string :string :int] :int)
(defcfn getenv "g_getenv" [:string] :string)

;; -- helpers ----------------------------------------------------------------
(def HORIZONTAL 0)
(def VERTICAL 1)

(def align
  "GtkAlign. :fill is the GTK default."
  {:fill 0 :start 1 :end 2 :center 3 :baseline 4})

(def policy
  "GtkPolicyType, for a scrolled window's scrollbars."
  {:always 0 :automatic 1 :never 2 :external 3})

(defn ->gbool [x] (if x 1 0))
(defn <-gbool [x] (not (zero? x)))
