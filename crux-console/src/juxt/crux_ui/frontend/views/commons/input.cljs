(ns juxt.crux-ui.frontend.views.commons.input
  (:require [reagent.core :as r]
            [juxt.crux-ui.frontend.views.commons.user-intents :as user-intents]))


(defn- -data-attrs-mapper [[k v]]
  (vector (str "data-" (name k)) v))

(defn render-data-attrs [hmap]
  (into {} (map -data-attrs-mapper hmap)))


(defn text
  [id {:keys [on-change on-change-complete
              on-intent on-key-down
              process-paste]
       :as opts}]
  (let [node          (r/atom nil)
        cur-val       (atom nil)
        get-cur-value #(some-> @node (.-value))
        on-key-down   (cond
                        on-intent   #(some-> % user-intents/key-down-evt->intent-evt on-intent)
                        on-key-down on-key-down
                        :else       identity)

        on-blur-internal
        (fn [evt]
          (if on-change-complete
            (on-change-complete {:value (get-cur-value)})))

        on-key-up-internal
        (if on-change
          (fn [evt]
            (let [cur-value (get-cur-value)]
              (when (not= cur-value @cur-val)
                (on-change {:value  cur-value
                            :target @node})))))

        on-paste-internal
        (if on-change
          (fn [evt]
            (let [cur-html (get-cur-value)]
              (when (not= cur-html @cur-val)
                (let [paste-processed (if process-paste
                                        (process-paste cur-html)
                                        cur-html)]
                  (on-change {:value paste-processed
                              :target @node}))))))]
    (r/create-class
      {:display-name "SpaceInput"

       :component-did-mount
                     (fn [this]
                       (reset! node (r/dom-node this)))

       :should-component-update
                     (fn [this cur-argv [f id next-props :as next-argv]]
                       (and (not= @node js/document.activeElement)
                            (not= (get-cur-value) (:value next-props))))

       :reagent-render
                     (fn [id {:keys [data ; @param {map} with data attributes
                                     on-blur on-focus
                                     placeholder css-class value] :as opts}] ;; remember to repeat parameters
                       (reset! cur-val value)
                       (let [id-str (if (keyword? id) (name id) (str id))]
                         [:input.space-ui-input

                          (merge (render-data-attrs data)
                                 {:id           id-str
                                  :placeholder  placeholder
                                  :class        (if css-class (name css-class))
                                  :defaultValue value
                                  :autoFocus    (:autofocus opts)
                                  :spellCheck   "false"
                                  :on-key-up    on-key-up-internal
                                  :on-paste     on-paste-internal
                                  :on-key-down  on-key-down
                                  :on-focus     on-focus
                                  :on-blur      on-blur})]))})))

