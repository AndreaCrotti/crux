(ns juxt.crux-ui.frontend.logic.time)

(defn date->comps [^js/Date t]
  {:time/year   (.getFullYear t)
   :time/month  (.getMonth t)
   :time/date   (inc (.getDate t))
   :time/hour   (.getHours t)
   :time/minute (.getMinutes t)})

(defn comps->date [comps]
  (let [date-str (str (:time/year comps) "-"
                      (:time/month comps) "-"
                      (:time/date comps) " "
                      (:time/hour comps)  ":"
                      (:time/minute comps))
        ts (js/Date.parse date-str)]
    (if-not (js/isNaN ts)
      (js/Date. ts))))
