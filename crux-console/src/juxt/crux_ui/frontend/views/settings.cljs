(ns juxt.crux-ui.frontend.views.settings
  (:require [garden.core :as garden]
            [re-frame.core :as rf]
            [juxt.crux-ui.frontend.views.commons.input :as input]
            [juxt.crux-ui.frontend.views.commons.form-line :as fl]
            [reagent.core :as r]))

(def ^:private -sub-settings (rf/subscribe [:subs.sys/settings]))

(def ^:private root-styles
  [:style
    (garden/css [])])

(defn- on-prop-change [prop-name {v :value :as change-complete-evt}]
  (rf/dispatch [:evt.db/prop-change {:evt/prop-name prop-name
                                     :evt/value v}]))

(defn root []
  (fn []
    [:div.settings
     [fl/line
      {:label "Crux HTTP-Server Host and port"
       :control
       [input/text
        {:on-change-complete (r/partial on-prop-change :db.sys/host)
         :value (:db.sys/host @-sub-settings)}]}]
     [fl/line
      {:label "Query results limit"
       :control
       [input/text
        {:on-change-complete (r/partial on-prop-change :db.query/limit)
         :value (:db.query/limit @-sub-settings)}]}]]))

