(ns jukebox-client.core
  (:require [clojure.string :as str]
            [antizer.rum :as ant]
            [rum.core :as rum]
            [citrus.core :as citrus]
            [goog.events :as events]
            [goog.history.EventType :as HistoryEventType])
  (:import (goog.history Html5History)))

(declare history)

(defn history-down
  [rel]
  (let [path (.getToken history)]
    (.setToken history (str path "/" rel))))

(defn history-up
  []
  (let [path (.getToken history)
        last-idx (str/last-index-of path "/")
        up-path (subs path 0 last-idx)]
    (.setToken history up-path)))

;;; components

(rum/defc Directory
  [r {:keys [name]}]
  [:div
   {:key name}
   (ant/button {:type :primary :shape :circle :icon :caret-right})
   (ant/button {:on-click #(history-down name)} name)])

(rum/defc File
  [r {:keys [name]}]
  [:div
   {:key name}
   (ant/button {:type :primary :shape :circle :icon :caret-right})
   [:span name]])

(rum/defc App <
  rum/reactive
  [r]
  (let [{:keys [loading? error data]} (rum/react (citrus/subscription r [:navigation]))]
    (ant/layout {:style {:min-height "100vh"}}
                (ant/layout
                 [:div
                  (ant/button {:type :primary :icon :caret-up :on-click history-up} "Up")]
                 (cond
                   loading? (ant/spin)
                   error (ant/message-error error)
                   (seq data)
                   [:div
                    (for [item (take 40 data)]
                      (case (:type item)
                        "directory" (Directory r item)
                        "file" (File r item)
                        (str item)))])))))

;;; controllers

(defmulti navigation identity)

(defmethod navigation :init []
  {:http {:url "http://localhost:8080/jukebox/sorted"
          :on-success :on-success
          :on-failure :on-failure}
   :state {:root "http://localhost:8080/jukebox"
           :path "/sorted"
           :loading? true}})

(defmethod navigation :goto [_ [path] {:keys [root] :as state}]
  {:http {:url (str root path)
          :on-success :on-success
          :on-failure :on-failure}
   :state (assoc state
                 :path path
                 :data nil
                 :error nil
                 :loading? true)})

(defmethod navigation :on-success [_ [resp] state]
  {:state (assoc state 
                 :data resp 
                 :loading? false 
                 :error nil)})

(defmethod navigation :on-failure [_ [error] state]
  {:state (assoc state 
                 :data nil
                 :loading? false 
                 :error (.-message error))})

;;; effect handlers

(defn http [r c {:keys [url on-success on-failure]}]
  (-> (js/fetch url)
      (.then #(.json %))
      (.then #(js->clj % :keywordize-keys true))
      (.then #(citrus/dispatch! r c on-success %))
      (.catch #(citrus/dispatch! r c on-failure %))))

;;; setup

(defonce reconciler
  (citrus/reconciler
   {:state (atom {})
    :controllers {:navigation navigation}
    :effect-handlers {:http http}}))

(defonce init-ctrl
  (citrus/broadcast-sync! reconciler :init))

(defonce history
  (doto (Html5History.)
    (.setToken "/sorted")
    (events/listen
     HistoryEventType/NAVIGATE
     (fn [event]
       (citrus/dispatch! reconciler :navigation :goto (.-token event))))
    (.setEnabled true)))

(rum/mount (App reconciler) 
           (js/document.getElementById "app")) 

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
)
