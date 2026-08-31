(ns todo
  "Slightly bigger demo: dynamic child list, entry, check buttons.
   Shows the reconciler adding and removing widgets."
  (:require [clojure.string :as str]
            [gtk.core :as ui]
            [gtk.ratom :as r]))

(defn home [state]
  (let [{:keys [draft items]} @state
        add! (fn []
               (when (seq (str/trim draft))
                 (swap! state #(-> %
                                   (update :items conj {:text (:draft %) :done? false})
                                   (assoc :draft "")))))]
    [:vbox {:spacing 10 :margin 16}
     [:hbox {:spacing 8}
      [:entry {:value draft
               :hexpand true
               :on-change #(swap! state assoc :draft %)
               :on-activate (fn [_] (add!))}]
      [:button {:label "Add" :on-click add! :sensitive (seq (str/trim draft))}]]
     [:vbox {:spacing 4}
      (map-indexed
       (fn [i {:keys [text done?]}]
         [:hbox {:spacing 8}
          [:check {:label text
                   :active done?
                   :hexpand true
                   :on-toggle #(swap! state assoc-in [:items i :done?] %)}]
          [:button {:label "x"
                    :on-click #(swap! state update :items
                                      (fn [v] (vec (concat (subvec v 0 i)
                                                           (subvec v (inc i))))))}]])
       items)]
     [:label {:label (str (count (filter :done? items)) " / " (count items) " done")}]]))

(defn app []
  (let [state (r/atom {:draft "" :items []})]
    (fn []
      (#'home state))))

(defn -main [& _]
  (ui/run (app) :title "todo" :width 420 :height 320))

(comment
  ;; 1. start it on its own thread so the REPL prompt stays free
  (def app-thread (future (ui/run (app) :title "todo" :width 420 :height 320)))

  ;; 2. edit `home` above, re-evaluate it, then re-render.
  ;;    `app` calls it through #'home, so the new code is picked up.
  ;;    Redefining a fn does not touch any atom, so nothing marks the tree
  ;;    dirty by itself -- that is what refresh! is for.
  (ui/refresh!)

  ;; 3. poke at the live tree
  (:window @ui/current)
  (-> @ui/current :tree :children)

  ;; 4. shut the window and let the loop return
  (ui/close!))
