(ns juxt.crux-ui.frontend.views.query.datepicker-slider
  (:require [re-frame.core :as rf]
            [juxt.crux-ui.frontend.logging :as log]
            [juxt.crux-ui.frontend.logic.time :as time]
            ["react-input-range" :as ir]
            [reagent.core :as r]))

(defn root [{:keys [value label on-change] :as prms}]
  (let [v (r/atom value)
        ;
        on-change-internal
        (fn [time-component value]
          (swap! v assoc time-component value)
          (on-change (time/comps->date @v)))]

    (fn -actual-render []
      [:div.slider-date-time-picker
       [:label.slider-date-time-picker__label label]
       [:div.slider-date-time-picker__input
        "Year"
        [:> ir {:value (get @v :time/year)
                :step 1
                :minValue 1970
                :maxValue 2020
                :onChange #(on-change-internal :time/year %)}]]
       [:div.slider-date-time-picker__input
        "Month"
        [:> ir {:value (get @v :time/month)
                :step 1
                :minValue 1
                :maxValue 12
                :onChange #(on-change-internal :time/month %)}]]
       [:div.slider-date-time-picker__input
        "Day"
        [:> ir {:value (get @v :time/date)
                :step 1
                :minValue 0
                :maxValue 31
                :onChange #(on-change-internal :time/date %)}]]
       [:div.slider-date-time-picker__input
        "Hour"
        [:> ir {:value (get @v :time/hour)
                :step 1
                :minValue 0
                :maxValue 23
                :onChange #(on-change-internal :time/hour %)}]]
       [:div.slider-date-time-picker__input
        "Minute"
        [:> ir {:value (get @v :time/minute)
                :step 1
                :minValue 0
                :maxValue 59
                :onChange #(on-change-internal :time/minute %)}]]])))

