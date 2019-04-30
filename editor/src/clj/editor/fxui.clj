(ns editor.fxui
  (:require [cljfx.api :as fx]
            [editor.error-reporting :as error-reporting]
            [editor.ui :as ui]
            [editor.util :as eutil])
  (:import [cljfx.lifecycle Lifecycle]
           [javafx.application Platform]
           [javafx.scene Node]
           [javafx.beans.value ChangeListener]))

(def ext-value
  "Extension lifecycle that returns value on `:value` key"
  (reify Lifecycle
    (create [_ desc _]
      (:value desc))
    (advance [_ _ desc _]
      (:value desc))
    (delete [_ _ _])))

(defn ext-focused-by-default
  "Function component that mimics extension lifecycle. Focuses node specified by
   `:desc` key when it gets added to scene graph"
  [{:keys [desc]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^Node node]
                 (if (some? (.getScene node))
                   (.requestFocus node)
                   (.addListener (.sceneProperty node)
                                 (reify ChangeListener
                                   (changed [this _ _ new-scene]
                                     (when (some? new-scene)
                                       (.removeListener (.sceneProperty node) this)
                                       (.requestFocus node)))))))
   :desc desc})

(defmacro provide-single-default [m k v]
  `(let [m# ~m
         k# ~k]
     (if (contains? m# k#)
       m#
       (assoc m# k# ~v))))

(defmacro provide-defaults
  "Like assoc, but does nothing if key is already in this map. Evaluates values
  only when key is not present"
  [m & kvs]
  `(-> ~m
       ~@(map #(cons `provide-single-default %) (partition 2 kvs))))

(defn mount-renderer-and-await-result!
  "Mounts `renderer` and blocks current thread until `state-atom`'s value
  receives has a `:result` key"
  [state-atom renderer]
  (let [event-loop-key (Object.)
        result-promise (promise)]
    (future
      (error-reporting/catch-all!
        (let [result @result-promise]
          (fx/on-fx-thread
            (Platform/exitNestedEventLoop event-loop-key result)))))
    (add-watch state-atom event-loop-key
               (fn [k r _ n]
                 (let [result (:result n ::no-result)]
                   (when-not (= result ::no-result)
                     (deliver result-promise result)
                     (remove-watch r k)))))
    (fx/mount-renderer state-atom renderer)
    (Platform/enterNestedEventLoop event-loop-key)))

(defn dialog-showing? [props]
  (not (contains? props :result)))

(defn show-dialog-and-await-result!
  "Creates a dialog, shows it and block current thread until dialog has a result
  (which is checked by presence of a `:result` key in state map)

  Options:
  - `:initial-state` (optional, default `{}`) - map containing initial state of
    a dialog, should not contain `:result` key
  - `:event-handler` (required) - 2-argument event handler, receives current
    state as first argument and event map as second, returns new state. Once
    state of a dialog has `:result` key in it, dialog interaction is considered
    complete and dialog should close
  - `:description` (required) - fx description used for this dialog, gets merged
    into current state map, meaning that state map contents, including
    eventually a `:result` key, will also be present in description props. You
    can use `editor.fxui/dialog-showing?` and pass it resulting props to check
    if dialog stage's `:showing` property should be set to true"
  [& {:keys [initial-state event-handler description]
      :or {initial-state {}}}]
  (let [state-atom (atom initial-state)
        renderer (fx/create-renderer
                   :opts {:fx.opt/map-event-handler #(swap! state-atom event-handler %)}
                   :middleware (fx/wrap-map-desc merge description))]
    (mount-renderer-and-await-result! state-atom renderer)))

(defn wrap-state-handler [state-atom f]
  (-> f
      (fx/wrap-co-effects
        {:state (fx/make-deref-co-effect state-atom)})
      (fx/wrap-effects
        {:state (fx/make-reset-effect state-atom)})))

(defn stage
  "Generic `:stage` that mirrors behavior of `editor.ui/make-stage`"
  [props]
  (assoc props
    :fx/type :stage
    :on-focused-changed ui/focus-change-listener
    :icons (if (eutil/is-mac-os?) [] [ui/application-icon-image])))

(defn dialog-stage
  "Generic dialog `:stage` that mirrors behavior of `editor.ui/make-dialog-stage`"
  [props]
  (let [owner (:owner props ::no-owner)]
    (-> props
        (assoc :fx/type stage)
        (assoc :owner (cond
                        (= owner ::no-owner)
                        {:fx/type fx/ext-instance-factory
                         :create ui/main-stage}

                        (:fx/type owner)
                        owner

                        :else
                        {:fx/type ext-value
                         :value owner}))
        (provide-defaults
          :resizable false
          :style :decorated
          :modality (if (nil? owner) :application-modal :window-modal)))))

(defn add-style-classes [style-class & classes]
  (let [existing-classes (if (string? style-class) [style-class] style-class)]
    (into existing-classes classes)))

(defn label
  "Generic `:label` with sensible defaults (`:wrap-text` is true)

  Additional keys:
  - `:variant` (optional, default `:label`) - a styling variant, either `:label`
     or `:header`"
  [{:keys [variant]
    :or {variant :label}
    :as props}]
  (-> props
      (assoc :fx/type :label)
      (dissoc :variant)
      (provide-defaults :wrap-text true)
      (update :style-class add-style-classes (case variant
                                               :label "label"
                                               :header "header"))))

(defn button
  "Generic button

  Additional keys:
  - `:variant` (optional, default `:secondary`) - a styling variant, either
    `:secondary` or `:primary`"
  [{:keys [variant]
    :or {variant :secondary}
    :as props}]
  (-> props
      (assoc :fx/type :button)
      (dissoc :variant)
      (update :style-class add-style-classes "button" (case variant
                                                        :primary "button-primary"
                                                        :secondary "button-secondary"))))

(defn two-col-input-grid-pane
  "Grid pane whose children are partitioned into pairs and displayed in 2
  columns, useful for multiple label + input fields"
  [props]
  (-> props
      (assoc :fx/type :grid-pane
             :column-constraints [{:fx/type :column-constraints}
                                  {:fx/type :column-constraints
                                   :hgrow :always}])
      (update :style-class add-style-classes "input-grid")
      (update :children (fn [children]
                          (into []
                                (comp
                                  (partition-all 2)
                                  (map-indexed
                                    (fn [row [label input]]
                                      [(assoc label :grid-pane/column 0
                                                    :grid-pane/row row
                                                    :grid-pane/halignment :right)
                                       (assoc input :grid-pane/column 1
                                                    :grid-pane/row row)]))
                                  (mapcat identity))
                                children)))))

(defn text-field
  "Generic `:text-field`

  Additional keys:
  - `:variant` (optional, default `:default`) - a styling variant, either
    `:default` or `:error`"
  [{:keys [variant]
    :or {variant :default}
    :as props}]
  (-> props
      (assoc :fx/type :text-field)
      (dissoc :variant)
      (update :style-class add-style-classes "text-field" (case variant
                                                            :default "text-field-default"
                                                            :error "text-field-error"))))

(defn text-area
  "Generic `:text-area`

  Additional keys:
  - `:variant` (optional, default `:default`) - a styling variant, either
    `:default`, `:error` or `:borderless`"
  [{:keys [variant]
    :or {variant :default}
    :as props}]
  (-> props
      (assoc :fx/type :text-area)
      (dissoc :variant)
      (update :style-class add-style-classes "text-area" (case variant
                                                           :default "text-area-default"
                                                           :error "text-area-error"
                                                           :borderless "text-area-borderless"))))

(defn icon
  "Optionally scaled svg icon

  Supported keys:
  - `:type` (required) - icon type, either `:error`, `:check-circle` or
    `:info-circle`
  - `:scale` (optional, default 1) - icon scale"
  [{:keys [type scale]
    :or {scale 1}}]
  {:fx/type :group
   :children [{:fx/type :svg-path
               :scale-x scale
               :scale-y scale
               :fill (case type
                       :check-circle "#65c647"
                       :error "#e32f44"
                       :info-circle "#9FB0BE")
               :content (case type
                          :check-circle "M17.5,0C7.8,0,0,7.8,0,17.5S7.8,35,17.5,35S35,27.2,35,17.5S27.2,0,17.5,0z M17.5,34C8.4,34,1,26.6,1,17.5S8.4,1,17.5,1S34,8.4,34,17.5S26.6,34,17.5,34z M26,13c0.2,0.2,0.2,0.5,0,0.7L15.8,23.9C15.7,24,15.6,24,15.5,24s-0.3-0.1-0.4-0.1l-6-6c-0.2-0.2-0.2-0.5,0-0.7s0.5-0.2,0.7,0l5.6,5.6l9.8-9.8C25.5,12.8,25.8,12.8,26,13z"
                          :error "M33.6,27.4L20.1,3.9c-0.6-1-1.6-1.5-2.6-1.5s-2,0.5-2.6,1.5L1.4,27.4C0.2,29.4,1.7,32,4.1,32h26.8 C33.3,32,34.8,29.4,33.6,27.4z M32.7,30c-0.4,0.7-1.1,1.1-1.8,1.1H4.1c-0.8,0-1.4-0.4-1.8-1.1c-0.4-0.7-0.4-1.4,0-2.1L15.8,4.4 c0.4-0.6,1-1,1.7-1s1.3,0.4,1.7,1l13.5,23.4C33.1,28.5,33.1,29.3,32.7,30z M18.1,25.5c0,0.4-0.3,0.7-0.7,0.7s-0.7-0.3-0.7-0.7 c0-0.4,0.3-0.7,0.7-0.7S18.1,25.1,18.1,25.5z M17,22.6v-9.1c0-0.3,0.2-0.5,0.5-0.5s0.5,0.2,0.5,0.5v9.1c0,0.3-0.2,0.5-0.5,0.5 S17,22.9,17,22.6z"
                          :info-circle "M17.5,0C7.8,0,0,7.8,0,17.5S7.8,35,17.5,35S35,27.2,35,17.5S27.2,0,17.5,0zM17.5,34C8.4,34,1,26.6,1,17.5S8.4,1,17.5,1S34,8.4,34,17.5S26.6,34,17.5,34zM18.4,10.5c0,0.5-0.4,0.9-0.9,0.9c-0.5,0-0.9-0.4-0.9-0.9c0-0.5,0.4-0.9,0.9-0.9C18,9.6,18.4,10,18.4,10.5zM18,14.5v11c0,0.3-0.2,0.5-0.5,0.5c-0.3,0-0.5-0.2-0.5-0.5v-11c0-0.3,0.2-0.5,0.5-0.5C17.8,14,18,14.2,18,14.5z")}]})
