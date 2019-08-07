(ns juxt.crux-ui.frontend.views.query.time-controls
  (:require [garden.core :as garden]
            [juxt.crux-ui.frontend.logic.time :as time]
            [juxt.crux-ui.frontend.views.query.datepicker-native :as ndt]
            [juxt.crux-ui.frontend.views.query.datepicker-slider :as sdt]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [juxt.crux-ui.frontend.functions :as f]))

(def on-time-commit-debounced
  (f/debounce
    (fn on-time-commit [^keyword time-type ^js/Date time]
      (rf/dispatch [:evt.ui.query/time-commit time-type time]))
    1000))


(defn on-vt-change [d]
  (on-time-commit-debounced :time/vt d)
  (rf/dispatch [:evt.ui.query/time-change :time/vt d]))

(defn on-tt-change [d]
  (on-time-commit-debounced :time/tt d)
  (rf/dispatch [:evt.ui.query/time-change :time/tt d]))


(def ^:private time-controls-styles
  [:style
   (garden/css

     [:.native-date-time-picker
      [:&__label
       {:width :100%
        :display :block
        :font-size :1.1em
        :letter-spacing :.04em}]

      [:&__input
       {:width         "auto"
        :padding       :4px
        :border-radius :2px
        :font-size :inherit
        :margin-top    :4px
        :border        "1px solid hsl(0, 0%, 85%)"}]]

     [:.time-controls
      {:display         :flex
       :flex-direction  :column
       :justify-content :space-between
       :padding         "24px 24px"}
      [:&__item
       {:margin-bottom :32px}]])])

(defn native-pickers []
  [:<>
   [:div.time-controls__item
    [ndt/picker {:label "Valid time" :on-change on-vt-change}]]
   [:div.time-controls__item
    [ndt/picker {:label "Transaction Time" :on-change on-tt-change}]]])

(def t (js/Date.))

(defn range-pickers []
  [:<>
   [:div.time-controls__item
    [sdt/root
     {:label "Valid time"
      :value (time/date->comps (js/Date.))
      :on-change on-vt-change}]]
   #_[:div.time-controls__item
      [sdt/root {:label "Transaction Time" :on-change on-tt-change}]]])


(defn root []
  [:div.time-controls
   time-controls-styles
   #_[native-pickers]
   [range-pickers]])

