(ns jukebox-client.core
  (:require [clojure.string :as str]
            [clojure.core.async :as a]
            [rum.core :as rum]
            [antizer.rum :as ant]
            [citrus.core :as citrus]
            [goog.events :as events]
            [goog.history.EventType :as HistoryEventType]
            [cljsjs.filesaverjs]
            [cljsjs.moment])
  (:import (goog.history Html5History)))

;;; config

(goog-define environment "dev")

(def root
  (str
   (if (= environment "dev")
     "http://localhost:8080/"
     (let [loc (str
                js/location.protocol "//"
                js/location.hostname
                (if js/location.port (str ":" js/location.port) "")
                js/location.pathname)]
       (if (not (str/ends-with? loc "/"))
         (str loc "/")
         loc)))
   "_media"))

;;; tools

(defn path-from-location
  []
  (let [hash js/location.hash]
    (if (= "" hash)
      "/"
      (str/replace-first hash "#" ""))))

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

(defn item-to-path
  [root item]
  (str root "/" (js/encodeURI (aget item "name"))))

(defn to-playlist
  [urls]
  (str/join "\n" urls))

(defn is-type?-fn
  [type]
  (fn [x]
    (= (aget x "type") type)))

(defn playlist [url]
  (let [items (a/chan (a/dropping-buffer 1000))
        results (a/chan (a/dropping-buffer 25))
        transform (a/pipeline 1 items (mapcat
                                       (fn [[root items]]
                                         (map (fn [js-item]
                                                (let [url (item-to-path root js-item)
                                                      item (js->clj js-item)
                                                      {:strs [type]} item]
                                                  {:type type :url url}))
                                              items)))
                              results)
        [queue files] (a/split (fn [{:keys [type]}] (= type "directory")) items 25 1000)]
    ; prime the queue
    (a/put! queue {:url url})
    (a/go-loop []
      ; wait for 1s or take the next thing from the queue
      (let [timeout (a/timeout 1000)
            [val ch] (a/alts! [queue timeout])]
        (if (= ch timeout)
          ; on timeout build the playlist
          (do
            (a/close! files)
            (let [files (a/<! (a/into [] files))]
              (->> files
                   (map :url)
                   (to-playlist)
                   (download-playlist))))
          ; otherwise fetch the queue item
          (do
            (let [{:keys [url]} val]
              (-> (request url)
                  (.then (fn [data]
                           (a/go (a/>! results [url data])))))
              (recur))))))))

;;; history

(declare history)

(defn history-path
  []
  (let [token (.getToken history)]
    (if (not (str/starts-with? token "/"))
      (str "/" token)
      token)))

(defn history-path-parts
  []
  (str/split (history-path) "/"))

;;; components

(rum/defc Breadcrumbs
  []
  (ant/breadcrumb
   (let [parts (history-path-parts)]
     (for [i (range (count parts))
           :let [path (str (str/join "/" (subvec parts 0 (inc i))) "/")]]
       (if (zero? i)
         (ant/breadcrumb-item
          {:id :home}
          [:a {:href "#/"} (ant/icon {:type :home})])
         (ant/breadcrumb-item
          {:id path}
          [:a {:href (str "#" path)} (js/decodeURI (nth parts i))]))))))

(rum/defc GoDown
    [name]
    [:a {:href (str "#" (history-path) name "/")} name])

(rum/defc PlayList
  [loading? root path data]
  (ant/table {:loading loading?
              :style {:background "white"}
              :pagination {:position :top
                           :showTotal (fn [total [start end]] (str start "-" end " of " total " items"))
                           :showQuickJumper true}
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
                         :sorter (fn [a b]
                                   (let [f #(aget % "name")]
                                     (compare (f a) (f b))))
                         :render (fn [name item]
                                   (if (= (aget item "type") "directory")
                                     (GoDown name)
                                     name))}
                        {:title "Modified"
                         :width 0
                         :dataIndex :mtime
                         :defaultSortOrder "descend"
                         :sortDirections ["descend" "ascend"]
                         :sorter (fn [a b]
                                   (let [f #(-> % (aget "mtime") (js/Date.parse))]
                                     (compare (f a) (f b))))

                         :render (fn [mtime]
                                   (-> (js/moment. mtime)
                                       (.format "YYYY-MM-DD")))}]}))

(rum/defc App <
  rum/reactive
  [r]
  (let [{:keys [loading? error root path filter-term effective-data]} (rum/react (citrus/subscription r [:navigation]))]
    (ant/layout
     (ant/layout-content
      {:style {:padding "0 2em"}}
      [:div {:style {:margin "2ex 0"}}
       (ant/row
        (ant/col {:span 18}
                 (Breadcrumbs))
        (ant/col {:span 6 :align :right}
                 (ant/input-search {:value filter-term
                                    :placeholder "Filter by name"
                                    :allow-clear true
                                    :on-change #(citrus/dispatch! r :navigation :filter (.-value (.-target %)))})))]

      [:div
       {:style {:height "200px"}}
       (cond
         error (ant/message-error error)
         :else (PlayList loading? root path effective-data))]))))

;;; controllers

(defmulti navigation identity)

(defmethod navigation :init []
  (let [path (path-from-location)]
    {:http {:url (str root path)
            :on-success :on-success
            :on-failure :on-failure}
     :state {:root root
             :path path
             :loading? true}}))

(defmethod navigation :goto [_ [path] {:keys [root] :as state}]
  {:http {:url (str root path)
          :on-success :on-success
          :on-failure :on-failure}
   :state (assoc state
                 :path path
                 :filter-term nil
                 :data nil
                 :effective-data nil
                 :error nil
                 :loading? true)})

(defmethod navigation :filter [_ [term] {:keys [data] :as state}]
  (let [lower-case-term (str/lower-case term)]
    {:state (assoc state
                   :filter-term term
                   :effective-data (seq (sort-by #(aget % "name") (filter #(str/includes? (str/lower-case (aget % "name")) lower-case-term) data))))}))

(defmethod navigation :on-success [_ [resp] state]
  {:state (assoc state
                 :data resp
                 :effective-data resp
                 :loading? false
                 :error nil)})

(defmethod navigation :on-failure [_ [error] state]
  {:state (assoc state
                 :data nil
                 :effective-data nil
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
