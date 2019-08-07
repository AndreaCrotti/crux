(ns juxt.crux-ui.frontend.views.query.datepicker-native
  (:require [juxt.crux-ui.frontend.logging :as log]
            [juxt.crux-ui.frontend.functions :as f]
            [reagent.core :as r]))

(defn- on-time-change--native [on-change-external evt]
  (try
    (let [v (f/jsget evt "target" "value")
          ts (js/Date.parse v)]
      (if (js/isNaN ts)
        (on-change-external nil)
        (on-change-external (js/Date. ts))))
    (catch js/Error err
      (on-change-external nil)
      (log/error err))))

(defn picker [{:keys [label on-change] :as prms}]
  [:div.native-date-time-picker
   [:label.native-date-time-picker__label label]
   [:input.native-date-time-picker__input
    {:type "datetime-local"
     :on-change (r/partial on-time-change--native on-change)}]])
