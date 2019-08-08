(ns juxt.crux-ui.frontend.views.query.datepicker-slider
  (:require [re-frame.core :as rf]
            [juxt.crux-ui.frontend.logging :as log]
            [juxt.crux-ui.frontend.logic.time :as time]
            [juxt.crux-ui.frontend.views.query.datepicker-native :as dpn]
            ["react-input-range" :as ir]
            [reagent.core :as r]
            [garden.core :as garden]))


(def style
  [:style
   (garden/css
     [:.slider-date-time-picker
      {:width :100%}
      [:&__header
       {:display :flex
        :align-items :center
        :justify-content :space-between}
       [:&-label]
       [:&-native
        {:width :250px
         :flex "0 0 100px"}]]
      [:&__row
       {:display :flex
        :align-items :center
        :height :48px}
       [:&-label
        {:flex "0 0 50px"}]
       [:&-slider
        {:flex "1 1 auto"}]]])])


(defn root
  [{:keys [^js/Date value
           ^js/String label
           on-change
           on-change-complete]
    :as prms}]
  (let [v (r/atom (time/date->comps value))
        ;
        on-change-internal
        (fn [time-component value & [complete?]]
          (swap! v assoc time-component value)
          (if (and complete? on-change-complete)
            (on-change-complete (time/comps->date @v)))
          (if on-change
           (on-change (time/comps->date @v))))]

    (fn -actual-render []
      [:div.slider-date-time-picker
       [:div.slider-date-time-picker__header
        [:label.slider-date-time-picker__header-label label]
        [:div.slider-date-time-picker__header-native
         ^{:key @v}
         [dpn/picker {:value (time/comps->date @v) :on-change on-change-complete}]]]
       [:div.slider-date-time-picker__row
        [:div.slider-date-time-picker__row-label "Year"]
        [:div.slider-date-time-picker__row-slider
         [:> ir {:value (get @v :time/year)
                 :step 1
                 :minValue 1970
                 :maxValue 2020
                 :onChangeComplete #(on-change-internal :time/year % true)
                 :onChange #(on-change-internal :time/year %)}]]]
       [:div.slider-date-time-picker__row
        [:div.slider-date-time-picker__row-label "Month"]
        [:div.slider-date-time-picker__row-slider
         [:> ir {:value (get @v :time/month)
                 :step 1
                 :minValue 1
                 :maxValue 12
                 :onChangeComplete #(on-change-internal :time/month % true)
                 :onChange #(on-change-internal :time/month %)}]]]
       [:div.slider-date-time-picker__row
        [:div.slider-date-time-picker__row-label "Day"]
        [:div.slider-date-time-picker__row-slider
         [:> ir {:value (get @v :time/date)
                 :step 1
                 :minValue 0
                 :maxValue 31
                 :onChangeComplete #(on-change-internal :time/date % true)
                 :onChange #(on-change-internal :time/date %)}]]]
       [:div.slider-date-time-picker__row
        [:div.slider-date-time-picker__row-label "Hour"]
        [:div.slider-date-time-picker__row-slider
         [:> ir {:value (get @v :time/hour)
                 :step 1
                 :minValue 0
                 :maxValue 23
                 :onChangeComplete #(on-change-internal :time/hour % true)
                 :onChange #(on-change-internal :time/hour %)}]]]
       [:div.slider-date-time-picker__row
        [:div.slider-date-time-picker__row-label "Minute"]
        [:div.slider-date-time-picker__row-slider
         [:> ir {:value (get @v :time/minute)
                 :step 1
                 :minValue 0
                 :maxValue 59
                 :onChangeComplete #(on-change-internal :time/minute % true)
                 :onChange #(on-change-internal :time/minute %)}]]]])))

