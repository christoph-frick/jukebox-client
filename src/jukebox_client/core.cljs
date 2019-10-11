(ns jukebox-client.core
  (:require [clojure.string :as str]
            [antizer.rum :as ant]
            [rum.core :as rum]
            [citrus.core :as citrus]
            [goog.events :as events]
            [goog.history.EventType :as HistoryEventType]
            [cljsjs.filesaverjs])
  (:import (goog.history Html5History)))

;;; tools

(defn request
  [url]
  (-> url
      (js/fetch)
      (.then #(.json %))))

(defn download-playlist
    [data]
    (js/saveAs
        (js/Blob.
            [data]
            #js {:type "audio/mpegurl"})
        "playlist.m3u"))

(defn to-playlist
  [urls]
  (str/join "\n" urls))

(defn is-type?-fn
  [type]
  (fn [x]
    (= (aget x "type") type)))

(defn build-playlist
  [root]
  ; TODO: recurse
  (-> root
      (request)
      (.then (partial filter (is-type?-fn "file")))
      (.then (partial map (fn [item] (str root "/" (js/encodeURI (aget item "name"))))))))

(defn playlist
  [root]
  (-> root
      (build-playlist)
      (.then to-playlist)
      (.then download-playlist)))

;;; history

(declare history)

(defn history-path
  []
  (.getToken history))

(defn history-path-parts
  []
  (str/split (history-path) "/"))

;;; components

(rum/defc Breadcrumbs
  []
  (ant/breadcrumb
   (let [parts (history-path-parts)]
     (for [i (range (count parts))
           :let [path (str/join "/" (subvec parts 0 (inc i)))]]
       (ant/breadcrumb-item
        [:a {:href (str "#" path)} (js/decodeURI (nth parts i))])))))

(rum/defc GoDown
    [name]
    [:a {:href (str "#" (history-path) "/" name)} name])

(rum/defc App <
  rum/reactive
  [r]
  (let [{:keys [loading? error root path data]} (rum/react (citrus/subscription r [:navigation]))]
    (ant/layout {:style {:min-height "100vh"}}
                (ant/layout-content
                 {:style {:padding "0 2em"}}
                 [:div {:style {:margin-top "2ex" :margin-bottom "2ex"}}
                  (Breadcrumbs)]
                 (cond
                   loading? (ant/spin)
                   error (ant/message-error error)
                   (seq data)
                   (ant/table {:style {:background "white"}
                               :pagination {:position :top}
                               :dataSource data
                               :columns [{:title ""
                                          :align :center
                                          :width 0
                                          :render (fn [_ item]
                                                    (ant/button {:icon :caret-right
                                                                 :type :primary
                                                                 :ghost true
                                                                 :shape :circle
                                                                 :size :large
                                                                 :onClick (fn [] (println) (playlist (str root "/" path "/" (aget item (js/encodeURI "name")))))}))}
                                         {:title "Type"
                                          :align :center
                                          :width 0
                                          :dataIndex :type
                                          :render (fn [type]
                                                    (ant/icon {:type (case type
                                                                       "directory" :folder
                                                                       "file" :file
                                                                       :question)}))}

                                         {:title "Name"
                                          :dataIndex :name
                                          :render (fn [name item]
                                                    (if (= (aget item "type") "directory")
                                                      (GoDown name)
                                                      name))}
                                         #_{:title "Modified"
                                            :width 0
                                            :dataIndex :mtime}]}))))))

;;; controllers

(defmulti navigation identity)

(defmethod navigation :init []
  (let [root "http://localhost:8080/_media"]
      {:http {:url root
          :on-success :on-success
          :on-failure :on-failure}
   :state {:root root
           :path "/"
           :loading? true}}))

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
  (-> (request url)
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
    #_(.setToken "/")
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
