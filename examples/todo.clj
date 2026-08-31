(ns todo
  "Slightly bigger demo: dynamic child list, entry, check buttons.
   Shows the reconciler adding and removing widgets."
  (:require [clojure.string :as str]
            [gtk.core :as ui]
            [gtk.ratom :as r]))

(defn app []
  (let [state (r/atom {:draft "" :items []})]
    (fn []
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
         [:label {:label (str (count (filter :done? items)) " / " (count items) " done")}]]))))

(defn -main [& _]
  (ui/run (app) :title "todo" :width 420 :height 320))
