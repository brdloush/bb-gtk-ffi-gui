(ns gtk.adw
  "libadwaita widgets, registered into gtk.core. Optional: core does not know
   this namespace exists, and nothing here changes how rendering works.

   Requiring this namespace calls adw_init and adds these tags:

     :adw-window     AdwWindow, the shell. Use with (ui/run ... :window window)
     :toolbar-view   AdwToolbarView -- :slot :top / :bottom for bars
     :header-bar     AdwHeaderBar   -- :slot :start / :end for buttons
     :window-title   AdwWindowTitle, the title/subtitle in a header bar
     :page           AdwPreferencesPage, a scrolling page of groups
     :group          AdwPreferencesGroup, a titled boxed list
     :row            AdwActionRow   -- :slot :prefix / :suffix for widgets
     :toast-overlay  AdwToastOverlay, shows toasts over its child
     :status-page    AdwStatusPage, for empty states
     :bin            AdwBin, a plain single-child holder
     :scroll         GtkScrolledWindow
     :level          GtkLevelBar
     :icon           GtkImage from an icon name
     :icon-button    GtkButton from an icon name

   Slots are a plain `:slot` prop on the child, honoured by the parent's
   :append. A child with no :slot goes to the parent's main content."
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [gtk.core :as ui]
            [gtk.ffi :as g]))

(ffi/load-system-library "adwaita-1")

;; ---------------------------------------------------------------------------
;; bindings
;; ---------------------------------------------------------------------------

(defcfn adw-init "adw_init" [] :void)
(defcfn get-major "adw_get_major_version" [] :int)
(defcfn get-minor "adw_get_minor_version" [] :int)

(defcfn window-new "adw_window_new" [] :pointer)
(defcfn window-set-content "adw_window_set_content" [:pointer :pointer] :void)

(defcfn bin-new "adw_bin_new" [] :pointer)
(defcfn bin-set-child "adw_bin_set_child" [:pointer :pointer] :void)

(defcfn toolbar-view-new "adw_toolbar_view_new" [] :pointer)
(defcfn toolbar-add-top "adw_toolbar_view_add_top_bar" [:pointer :pointer] :void)
(defcfn toolbar-add-bottom "adw_toolbar_view_add_bottom_bar" [:pointer :pointer] :void)
(defcfn toolbar-remove "adw_toolbar_view_remove" [:pointer :pointer] :void)
(defcfn toolbar-set-content "adw_toolbar_view_set_content" [:pointer :pointer] :void)

(defcfn header-bar-new "adw_header_bar_new" [] :pointer)
(defcfn header-set-title-widget "adw_header_bar_set_title_widget" [:pointer :pointer] :void)
(defcfn header-pack-start "adw_header_bar_pack_start" [:pointer :pointer] :void)
(defcfn header-pack-end "adw_header_bar_pack_end" [:pointer :pointer] :void)
(defcfn header-remove "adw_header_bar_remove" [:pointer :pointer] :void)

(defcfn window-title-new "adw_window_title_new" [:string :string] :pointer)
(defcfn window-title-set-title "adw_window_title_set_title" [:pointer :string] :void)
(defcfn window-title-set-subtitle "adw_window_title_set_subtitle" [:pointer :string] :void)

(defcfn prefs-page-new "adw_preferences_page_new" [] :pointer)
(defcfn prefs-page-add "adw_preferences_page_add" [:pointer :pointer] :void)
(defcfn prefs-page-remove "adw_preferences_page_remove" [:pointer :pointer] :void)

(defcfn prefs-group-new "adw_preferences_group_new" [] :pointer)
(defcfn prefs-group-add "adw_preferences_group_add" [:pointer :pointer] :void)
(defcfn prefs-group-remove "adw_preferences_group_remove" [:pointer :pointer] :void)
(defcfn prefs-group-set-title "adw_preferences_group_set_title" [:pointer :string] :void)
(defcfn prefs-group-set-description "adw_preferences_group_set_description" [:pointer :string] :void)

(defcfn action-row-new "adw_action_row_new" [] :pointer)
(defcfn pref-row-set-title "adw_preferences_row_set_title" [:pointer :string] :void)
(defcfn action-row-set-subtitle "adw_action_row_set_subtitle" [:pointer :string] :void)
(defcfn action-row-add-prefix "adw_action_row_add_prefix" [:pointer :pointer] :void)
(defcfn action-row-add-suffix "adw_action_row_add_suffix" [:pointer :pointer] :void)
(defcfn action-row-remove "adw_action_row_remove" [:pointer :pointer] :void)

(defcfn toast-overlay-new "adw_toast_overlay_new" [] :pointer)
(defcfn toast-overlay-set-child "adw_toast_overlay_set_child" [:pointer :pointer] :void)
(defcfn toast-overlay-add-toast "adw_toast_overlay_add_toast" [:pointer :pointer] :void)
(defcfn toast-new "adw_toast_new" [:string] :pointer)
(defcfn toast-set-timeout "adw_toast_set_timeout" [:pointer :int] :void)

(defcfn status-page-new "adw_status_page_new" [] :pointer)
(defcfn status-set-title "adw_status_page_set_title" [:pointer :string] :void)
(defcfn status-set-description "adw_status_page_set_description" [:pointer :string] :void)
(defcfn status-set-icon-name "adw_status_page_set_icon_name" [:pointer :string] :void)

;; plain GTK4 pieces the Adw layouts need
(defcfn scroll-new "gtk_scrolled_window_new" [] :pointer)
(defcfn scroll-set-child "gtk_scrolled_window_set_child" [:pointer :pointer] :void)
(defcfn level-new "gtk_level_bar_new" [] :pointer)
(defcfn level-set-value "gtk_level_bar_set_value" [:pointer :double] :void)
(defcfn level-set-min "gtk_level_bar_set_min_value" [:pointer :double] :void)
(defcfn level-set-max "gtk_level_bar_set_max_value" [:pointer :double] :void)
(defcfn level-set-mode "gtk_level_bar_set_mode" [:pointer :int] :void)
(defcfn image-new-from-icon "gtk_image_new_from_icon_name" [:string] :pointer)
(defcfn image-set-from-icon "gtk_image_set_from_icon_name" [:pointer :string] :void)
(defcfn image-set-pixel-size "gtk_image_set_pixel_size" [:pointer :int] :void)
(defcfn button-new-from-icon "gtk_button_new_from_icon_name" [:string] :pointer)
(defcfn button-set-icon-name "gtk_button_set_icon_name" [:pointer :string] :void)
(defcfn widget-set-size-request "gtk_widget_set_size_request" [:pointer :int :int] :void)
(defcfn widget-set-valign "gtk_widget_set_valign" [:pointer :int] :void)

(def ALIGN-CENTER 3)

;; ---------------------------------------------------------------------------
;; the window, for (ui/run ... :window adw/window)
;; ---------------------------------------------------------------------------

(def window
  "Pass as (ui/run view :window adw/window). AdwWindow has no titlebar of its
   own, which is what lets an :header-bar sit flush at the top."
  {:ctor        window-new
   :set-content window-set-content})

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- slot-of [props] (:slot props :content))

(defn- single-child
  "A container that holds exactly one child, ignoring slots."
  [set-child]
  {:append (fn [parent child _props] (set-child parent child))
   :remove (fn [parent _child _props] (set-child parent nil))})

(defn- title-subtitle-apply
  "AdwPreferencesRow title + AdwActionRow subtitle."
  [w p changed]
  (when (contains? changed :title)
    (pref-row-set-title w (str (:title p ""))))
  (when (contains? changed :subtitle)
    (action-row-set-subtitle w (str (:subtitle p "")))))

;; ---------------------------------------------------------------------------
;; specs
;; ---------------------------------------------------------------------------

(def specs
  {;; -- shell ---------------------------------------------------------------
   :adw-window   (merge {:ctor  (fn [_] (window-new))
                         :apply (fn [_ _ _] nil)}
                        (single-child window-set-content))

   :bin          (merge {:ctor  (fn [_] (bin-new))
                         :apply (fn [_ _ _] nil)}
                        (single-child bin-set-child))

   :toolbar-view {:ctor   (fn [_] (toolbar-view-new))
                  :apply  (fn [_ _ _] nil)
                  :append (fn [parent child props]
                            (case (slot-of props)
                              :top    (toolbar-add-top parent child)
                              :bottom (toolbar-add-bottom parent child)
                              (toolbar-set-content parent child)))
                  :remove (fn [parent child props]
                            (case (slot-of props)
                              (:top :bottom) (toolbar-remove parent child)
                              (toolbar-set-content parent nil)))}

   :header-bar   {:ctor   (fn [_] (header-bar-new))
                  :apply  (fn [_ _ _] nil)
                  :append (fn [parent child props]
                            (case (slot-of props)
                              :start (header-pack-start parent child)
                              :end   (header-pack-end parent child)
                              (header-set-title-widget parent child)))
                  :remove (fn [parent child props]
                            (case (slot-of props)
                              (:start :end) (header-remove parent child)
                              (header-set-title-widget parent nil)))}

   :window-title {:text-prop :title
                  :ctor  (fn [p] (window-title-new (str (:title p ""))
                                                   (str (:subtitle p ""))))
                  :apply (fn [w p changed]
                           (when (contains? changed :title)
                             (window-title-set-title w (str (:title p ""))))
                           (when (contains? changed :subtitle)
                             (window-title-set-subtitle w (str (:subtitle p "")))))}

   ;; -- content -----------------------------------------------------------
   :page         {:ctor   (fn [_] (prefs-page-new))
                  :apply  (fn [_ _ _] nil)
                  :append (fn [parent child _props] (prefs-page-add parent child))
                  :remove (fn [parent child _props] (prefs-page-remove parent child))}

   :group        {:ctor   (fn [p] (doto (prefs-group-new)
                                    (prefs-group-set-title (str (:title p "")))))
                  :apply  (fn [w p changed]
                            (when (contains? changed :title)
                              (prefs-group-set-title w (str (:title p ""))))
                            (when (contains? changed :description)
                              (prefs-group-set-description w (str (:description p "")))))
                  :append (fn [parent child _props] (prefs-group-add parent child))
                  :remove (fn [parent child _props] (prefs-group-remove parent child))}

   :row          {:text-prop :title
                  :ctor   (fn [p] (doto (action-row-new)
                                    (pref-row-set-title (str (:title p "")))
                                    (action-row-set-subtitle (str (:subtitle p "")))))
                  :apply  title-subtitle-apply
                  :append (fn [parent child props]
                            (if (= :prefix (slot-of props))
                              (action-row-add-prefix parent child)
                              (action-row-add-suffix parent child)))
                  :remove (fn [parent child _props] (action-row-remove parent child))}

   :toast-overlay (merge {:ctor  (fn [_] (toast-overlay-new))
                          :apply (fn [_ _ _] nil)}
                         (single-child toast-overlay-set-child))

   :status-page  {:text-prop :title
                  :ctor  (fn [p] (doto (status-page-new)
                                   (status-set-title (str (:title p "")))))
                  :apply (fn [w p changed]
                           (when (contains? changed :title)
                             (status-set-title w (str (:title p ""))))
                           (when (contains? changed :description)
                             (status-set-description w (str (:description p ""))))
                           (when (contains? changed :icon)
                             (status-set-icon-name w (:icon p))))}

   :scroll       (merge {:ctor  (fn [_] (scroll-new))
                         :apply (fn [_ _ _] nil)}
                        (single-child scroll-set-child))

   ;; -- leaves ------------------------------------------------------------
   :level        {:ctor  (fn [p]
                           (doto (level-new)
                             (level-set-min (double (:min p 0)))
                             (level-set-max (double (:max p 1)))
                             (level-set-value (double (:value p 0)))
                             (widget-set-valign ALIGN-CENTER)
                             (widget-set-size-request (int (:width p 120)) -1)))
                  :apply (fn [w p changed]
                           (when (contains? changed :min)
                             (level-set-min w (double (:min p 0))))
                           (when (contains? changed :max)
                             (level-set-max w (double (:max p 1))))
                           (when (contains? changed :value)
                             (level-set-value w (double (:value p 0))))
                           (when (contains? changed :width)
                             (widget-set-size-request w (int (:width p 120)) -1)))}

   :icon         {:text-prop :icon
                  :ctor  (fn [p] (doto (image-new-from-icon (:icon p))
                                   (image-set-pixel-size (int (:size p -1)))))
                  :apply (fn [w p changed]
                           (when (contains? changed :icon)
                             (image-set-from-icon w (:icon p)))
                           (when (contains? changed :size)
                             (image-set-pixel-size w (int (:size p -1)))))}

   :icon-button  {:text-prop :icon
                  :ctor  (fn [p] (button-new-from-icon (:icon p)))
                  :apply (fn [w p changed]
                           (when (contains? changed :icon)
                             (button-set-icon-name w (:icon p))))}})

;; ---------------------------------------------------------------------------
;; install
;; ---------------------------------------------------------------------------

(defn toast!
  "Shows a toast over a :toast-overlay widget. Find the overlay in the live
   tree, or keep its pointer when you build it."
  ([overlay message] (toast! overlay message 3))
  ([overlay message seconds]
   (let [t (toast-new (str message))]
     (toast-set-timeout t (int seconds))
     (toast-overlay-add-toast overlay t))))

(defonce ^{:doc "adw_init runs once, when this namespace loads."} initialized
  (do (adw-init)
      (doseq [[tag spec] specs] (ui/register-widget! tag spec))
      {:version (str (get-major) "." (get-minor))
       :widgets (vec (sort (keys specs)))}))
