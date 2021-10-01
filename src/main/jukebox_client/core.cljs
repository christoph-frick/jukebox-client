(ns jukebox-client.core
  (:require [clojure.string :as str]
            [clojure.core.async :as a]
            [rum.core :as rum]
            [citrus.core :as citrus]
            [goog.events :as events]
            [goog.history.EventType :as HistoryEventType]
            ["file-saver" :as file-saver]
            ["moment" :as moment]
            ["antd/es/breadcrumb" :default Breadcrumb]
            ["antd/es/button" :default Button]
            ["antd/es/table" :default Table]
            ["antd/es/grid" :as Grid]
            ["antd/es/layout" :default Layout]
            ["antd/es/input" :default Input]
            ["antd/es/alert" :default Alert]
            ["@ant-design/icons" :as Icons])
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

(defn split-path
  [path]
  (->> (str/split path "/")
       (mapv #(js/decodeURI %))))

(defn join-path
  [path-segments]
  (str/join "/" (map #(js/encodeURI %) path-segments)))

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
  (file-saver/saveAs
   (js/Blob.
    [data]
    #js {:type "audio/mpegurl"})
   "playlist.m3u"))

(defn item-to-path
  [root item]
  (str root "/" (js/encodeURI (aget item "name"))))

(defn path-to-root-and-item
  [root path]
  (let [path-segments (split-path (js/decodeURI path))
        [root-path-segments item-name] ((juxt butlast last) path-segments)]
    [(str root "/" (join-path root-path-segments))
     [#js {:name item-name :type "directory"}]]))

(defn to-playlist
  [urls]
  (str/join "\n" urls))

(defn- playlist-transform
  [root js-item]
  (let [url (item-to-path root js-item)
        item (js->clj js-item)
        {:strs [type]} item]
    {:type type :url url}))

(defn playlist [root js-items]
  (let [items (a/chan (a/dropping-buffer 1000))
        results (a/chan (a/dropping-buffer 25))
        _ (a/pipeline 1
                      items
                      (mapcat
                       (fn [[root items]]
                         (map (partial playlist-transform root) items)))
                      results)
        [queue files] (a/split (fn [{:keys [type]}] (= type "directory")) items 25 1000)]
    ; prime the chain
    (run! (fn [js-item]
            (a/put! items (playlist-transform root js-item)))
          js-items)
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
          (let [{:keys [url]} val]
            (-> (request url)
                (.then (fn [data]
                         (a/go (a/>! results [url data])))))
            (recur)))))))

;;; history

(declare ^Html5History history)

(defn history-path
  []
  (let [token (.getToken history)]
    (if (not (str/starts-with? token "/"))
      (str "/" token)
      token)))

(defn history-path-parts
  []
  (split-path (history-path)))

;;; components

(rum/defc Breadcrumbs
  []
  (rum/adapt-class Breadcrumb
                   (let [parts (history-path-parts)
                         key-gen (fn [part] (str "bc-" part))]
                     (for [i (range (count parts))
                           :let [path (str (join-path (subvec parts 0 (inc i))) "/")]]
                       (if (zero? i)
                         (rum/adapt-class (.-Item Breadcrumb)
                                          {:key (key-gen :home)}
                                          [:a {:href "#/"} (rum/adapt-class (.-HomeOutlined Icons) {})])
                         (rum/adapt-class (.-Item Breadcrumb)
                                          {:key (key-gen path)}
                                          [:a {:href (str "#" path)} (js/decodeURI (nth parts i))]))))))

(rum/defc GoDown
  [name]
  [:a {:href (str "#" (history-path) name "/")} name])

(rum/defc PlayButton
  [title icon on-click-fn]
  (rum/adapt-class Button {:title title
                           :icon (rum/adapt-class (.-CaretRightOutlined Icons) {})
                           :ghost true
                           :type :primary
                           :onClick on-click-fn}
                   (rum/adapt-class icon {})))

(rum/defc PlaySelected
  [root path selected]
  [:div
   (rum/adapt-class Button {:title "Play selected"
                            :icon (rum/adapt-class (.-CaretRightOutlined Icons) {})
                            :ghost true
                            :type :primary
                            :disabled (not (seq selected))
                            :onClick (fn []
                                        (playlist (str root "/" path) selected))}
                    (str/join ", " (map #(aget % "name") selected)))])

(defn icon-by-type
  [type-str]
  (case type-str
    "directory" (.-FolderOutlined Icons)
    "file" (.-FileOutlined Icons)
    (.-QuestionCircleOutlined Icons)))

(rum/defc PlayList
  [r loading? root path selected data]
  (let [row-key-fn (fn [item]
                     (str root "/" path "/" (aget item "name")))]
    (rum/adapt-class Table
                     {:loading loading?
                      :footer (fn []
                                (PlaySelected root path selected))
                      :rowSelection {:seletion-type "checkbox"
                                      :selectedRowKeys (clj->js (map row-key-fn selected))
                                      :onChange (fn [_ selected-rows]
                                                  (citrus/dispatch! r :navigation :select selected-rows))}
                      :pagination {:position :bottom
                                   :showTotal (fn [total [start end]]
                                                (str start "-" end " of " total " items"))
                                   :showQuickJumper true}
                      :dataSource data
                      :rowKey row-key-fn
                      :columns [{:title (PlayButton
                                         "Play all (recursive)"
                                         (.-BarsOutlined Icons)
                                         (fn []
                                           (apply playlist (path-to-root-and-item root path))))
                                 :align :center
                                 :width 1
                                 :render (fn [_ item]
                                           (let [type (aget item "type")]
                                             (PlayButton
                                              (str "Play " (case type "directory" "directory (recursive)" "file"))
                                              (icon-by-type type)
                                              (fn []
                                                (playlist (str root "/" path) [item])))))}
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
                                 :width "12em"
                                 :align "right"
                                 :dataIndex :mtime
                                 :defaultSortOrder "descend"
                                 :sortDirections ["descend" "ascend"]
                                 :sorter (fn [a b]
                                           (let [f #(-> % (aget "mtime") (js/Date.parse))]
                                             (compare (f a) (f b))))

                                 :render (fn [mtime]
                                           (-> (moment. mtime)
                                               (.format "YYYY-MM-DD")))}]})))

(rum/defc App <
  rum/reactive
  [r]
  (let [{:keys [loading? random? error root path filter-term effective-data selected]} (rum/react (citrus/subscription r [:navigation]))]
    (rum/adapt-class Layout {:style {:height "100vh"}}
                     (rum/adapt-class (.-Content Layout)
                                      {:style {:padding "0 2em"}}
                                      [:div {:style {:margin "2ex 0"}}
                                       (rum/adapt-class Grid/Row {}
                                                        (rum/adapt-class Grid/Col {:span 12}
                                                                         (Breadcrumbs))
                                                        (rum/adapt-class Grid/Col {:span 12 :align :right}
                                                                         (rum/adapt-class (.-Search Input) {:value filter-term
                                                                                                            :placeholder "Filter by name"
                                                                                                            :allowClear true
                                                                                                            :style {:width "20em"}
                                                                                                            :onChange #(citrus/dispatch! r :navigation :filter (.-value (.-target %)))})
                                                                         (rum/adapt-class Button {:onClick #(citrus/dispatch! r :navigation :randomize) :clicked "true" :type (if random? "primary" "")} "Random")
                                                                         (rum/adapt-class Button {:onClick #(citrus/dispatch! r :navigation :reset) :title "Reset filters"} (rum/adapt-class (.-RollbackOutlined Icons) {}))))]
                                      [:div
                                       (cond
                                         error (rum/adapt-class Alert {:type "error" :message error})
                                         :else (PlayList r loading? root path selected effective-data))]))))

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
                 :random? nil
                 :filter-term nil
                 :data nil
                 :effective-data nil
                 :error nil
                 :selected []
                 :loading? true)})

(defmethod navigation :filter [_ [term] {:keys [data] :as state}]
  (let [lower-case-term (str/lower-case term)]
    {:state (assoc state
                   :random? nil
                   :filter-term term
                   :effective-data (into-array (sort-by #(aget % "name") (filter #(str/includes? (str/lower-case (aget % "name")) lower-case-term) data))))}))

(defmethod navigation :randomize [_ _ {:keys [data] :as state}]
  {:state (assoc state
                 :random? true
                 :effective-data (into-array (take 10 (shuffle data))))})

(defmethod navigation :select [_ [items] state]
  {:state (assoc state
                 :selected items)})

(defmethod navigation :reset [_ _ {:keys [data] :as state}]
  {:state (assoc state
                 :random? nil
                 :filter-term nil
                 :effective-data data)})

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
     (fn [^goog.history.Event event]
       (citrus/dispatch! reconciler :navigation :goto (.-token event))))
    (.setEnabled true)))

(defn main
  []
  (rum/mount (App reconciler)
             (js/document.getElementById "app")))
