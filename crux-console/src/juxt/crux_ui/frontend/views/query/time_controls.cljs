(ns juxt.crux-ui.frontend.views.query.time-controls
  (:require [garden.core :as garden]
            [juxt.crux-ui.frontend.functions :as f]
            [juxt.crux-ui.frontend.logging :as log]
            [re-frame.core :as rf]))


(defn- on-time-change [time-type evt]
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
  (on-time-change :crux.ui.time-type/vt evt))

(defn- on-tt-change [evt]
  (on-time-change :crux.ui.time-type/tt evt))

(defn date-time-picker [{:keys [label on-change] :as prms}]
   [:div.native-date-time-picker
    [:label.native-date-time-picker__label label]
    [:input.native-date-time-picker__input
     {:type "datetime-local" :on-change on-change}]])

(defn date-time-picker--slider [{:keys [label on-change] :as prms}]
  [:div.date-time-picker--slider])

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

(def native-pickers
  [:<>
   [:div.time-controls__item
    (date-time-picker {:label "Valid time" :on-change on-vt-change})]
   [:div.time-controls__item
    (date-time-picker {:label "Transaction Time" :on-change on-tt-change})]])

(def range-pickers
  [:<>
   [:div.time-controls__item
    (date-time-picker--slider {:label "Valid time" :on-change on-vt-change})]
   [:div.time-controls__item
    (date-time-picker--slider {:label "Transaction Time" :on-change on-tt-change})]])


(defn root []
  [:div.time-controls
   time-controls-styles
   native-pickers
   #_range-pickers])

