(ns juxt.crux-ui.frontend.views.query.time-controls
  (:require [garden.core :as garden]
            [juxt.crux-ui.frontend.functions :as f]
            [juxt.crux-ui.frontend.logging :as log]
            ["react-input-range" :as ir]
            [re-frame.core :as rf]))


(defn- on-time-change--native [time-type evt]
  (try
    (let [v (f/jsget evt "target" "value")
          d (js/Date. v)]
      (log/log "value parsed" d)
      (if (js/isNaN d)
        (rf/dispatch [:evt.ui.query/time-reset time-type])
        (rf/dispatch [:evt.ui.query/time-change time-type d])))
    (catch js/Error err
      (rf/dispatch [:evt.ui.query/time-reset time-type])
      (log/error err))))

(defn- on-vt-change [evt]
  (on-time-change--native :crux.ui.time-type/vt evt))

(defn- on-tt-change [evt]
  (on-time-change--native :crux.ui.time-type/tt evt))


(defn- on-time-change--slider [time-type evt]
  (try
    (let [v (f/jsget evt "target" "value")
          d (js/Date. v)]
      (log/log "value parsed" d)
      (if (js/isNaN d)
        (rf/dispatch [:evt.ui.query/time-reset time-type])
        (rf/dispatch [:evt.ui.query/time-change time-type d])))
    (catch js/Error err
      (rf/dispatch [:evt.ui.query/time-reset time-type])
      (log/error err))))

(defn- on-vt-change--slider [evt]
  (on-time-change--slider :crux.ui.time-type/vt evt))

(defn- on-tt-change--slider [evt]
  (on-time-change--slider :crux.ui.time-type/tt evt))



(defn date-time-picker [{:keys [label on-change] :as prms}]
  [:div.native-date-time-picker
   [:label.native-date-time-picker__label label]
   [:input.native-date-time-picker__input
    {:type "datetime-local" :on-change on-change}]])

(def ^:const day-millis (* 1000 60 60 24))

(defn date-time-picker--slider [{:keys [label on-change] :as prms}]
   [:div.slider-date-time-picker
    [:label.slider-date-time-picker__label label]
    [:div.slider-date-time-picker__input
     "Year"
     [:> ir {:value 2019
             :step 1
             :minValue 1970
             :maxValue 2020
             :onChange on-change}]]
    [:div.slider-date-time-picker__input
     "Month"
     [:> ir {:value 2019
             :step 1
             :minvalue 1970
             :maxvalue 2020
             :onchange on-change}]]
    [:div.slider-date-time-picker__input
     "Day"
     [:> ir {:value 2019
             :step 1
             :minvalue 1970
             :maxvalue 2020
             :onchange on-change}]]
    [:div.slider-date-time-picker__input
     "Hour"
     [:> ir {:value 2019
             :step 1
             :minvalue 1970
             :maxvalue 2020
             :onchange on-change}]]
    [:div.slider-date-time-picker__input
     "Minute"
     [:> ir {:value 2019
             :step 1
             :minvalue 1970
             :maxvalue 2020
             :onchange on-change}]]])


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
    [date-time-picker {:label "Valid time" :on-change on-vt-change}]]
   [:div.time-controls__item
    [date-time-picker {:label "Transaction Time" :on-change on-tt-change}]]])

(defn range-pickers []
  [:<>
   [:div.time-controls__item
    [date-time-picker--slider {:label "Valid time" :on-change on-vt-change--slider}]]
   #_[:div.time-controls__item
      [date-time-picker--slider {:label "Transaction Time" :on-change on-tt-change--slider}]]])


(defn root []
  [:div.time-controls
   time-controls-styles
   #_[native-pickers]
   [range-pickers]])

