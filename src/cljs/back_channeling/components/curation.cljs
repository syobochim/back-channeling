(ns back-channeling.components.curation
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout]]
            [back-channeling.api :as api]
            [back-channeling.components.avatar :refer [avatar]])
  (:import [goog.i18n DateTimeFormat]))

(def date-format-m  (DateTimeFormat. goog.i18n.DateTimeFormat.Format.MEDIUM_DATETIME
                                     (aget goog.i18n (str "DateTimeSymbols_" (.-language js/navigator)))))

(defn open-thread [thread]
  (api/request (str "/api/thread/" (:db/id thread))
               {:handler (fn [response]
                           (om/update! thread [:thread/comments] (:thread/comments response)))}))

(defcomponent editorial-space-view [curating-block owner {:keys [save-fn]}]
  (init-state [_]
    {:editing? true})
  (render-state [_ {:keys [editing?]}]
    (html
     (if editing?
       [:div.ui.form
        [:div.field
         [:textarea
          {:default-value (:comment/content curating-block)
           :on-key-up (fn [e]
                        (when (and (= (.-which e) 0x0d) (.-ctrlKey e))
                          (let [content (.. (om/get-node owner) (querySelector "textarea") -value)]
                            (save-fn content)
                            (om/set-state! owner :editing? false))))}]]]
       [:div {;:dangerouslySetInnerHTML {:__html (js/marked (:comment/content curating-block))}
              :on-click (fn [_]
                          (om/set-state! owner :editing? true))}
        (:comment/content curating-block)]))))

(defn generate-markdown [curating-blocks]
  (->> curating-blocks
       (map #(if (= (:comment/format %) :comment.format/markdown)
               (:comment/content %)
               (str "```\n" (:comment/content %) "\n```\n")))
       (clojure.string/join "\n\n")))

(defcomponent curation-page [thread owner {:keys [user]}]
  (init-state [_]
    {:selected-thread-comments #{}
     :curating-blocks []
     :editorial-space {:db/id 0
                       :comment/format :comment.format/markdown
                       :comment/content ""
                       :comment/posted-by user}})
  
  (will-mount [_]
    (open-thread thread))

  (did-mount [_]
    (when-let [markdown-btn (.. (om/get-node owner) (querySelector "button.markdown.button"))]
      (let [clipboard (js/ZeroClipboard. markdown-btn)]
        (.on clipboard "ready"
             (fn [_]
               (.on clipboard "copy"
                    (fn [e]
                      (.. e -clipboardData (setData "text/plain"
                                                    (generate-markdown (om/get-state owner :curating-blocks)))))))))))
  
  (render-state [_ {:keys [selected-thread-comments curating-blocks editorial-space]}]
    (html
     [:div.curation.content
      [:div.ui.grid
      [:div.row
       [:div.seven.wide.column
        [:div.ui.thread.comments
         [:h3.ui.dividing.header (:thread/title thread)]
         [:div.comment {:on-click (fn [_]
                                    (om/update-state! owner :selected-thread-comments
                                                      #(if ((om/get-state owner :selected-thread-comments) 0)
                                                         (disj % 0) (conj % 0))))
                        :class (if (selected-thread-comments 0) "selected" "")}
          [:div.content
           [:div.ui.message "Editorial space"]]]
         (for [comment (:thread/comments thread)]
           [:div.comment {:on-click (fn [_]
                                      (if ((om/get-state owner :selected-thread-comments) (:db/id comment))
                                        (om/update-state! owner :selected-thread-comments #(disj % (:db/id comment)))
                                        (om/update-state! owner :selected-thread-comments #(conj % (:db/id comment)))))
                          :class (if (selected-thread-comments (:db/id comment)) "selected" "")}
            (om/build avatar (get-in comment [:comment/posted-by :user/email]))
            [:div.content
             [:a.number (:comment/no comment)] ": "
             [:a.author (get-in comment [:comment/posted-by :user/name])]
             [:div.metadata
              [:span.date (.format date-format-m (get-in comment [:comment/posted-at]))]]
             [:div.text (case (get-in comment [:comment/format :db/ident])
                          :comment.format/markdown {:dangerouslySetInnerHTML {:__html (js/marked (:comment/content comment))}}
                          (:comment/content comment))]]])]]
       
       [:div.column
        (when (not-empty selected-thread-comments)
          [:i.citation.huge.arrow.circle.outline.right.icon
           {:on-click (fn [_]
                        (om/update-state! owner :curating-blocks
                                          (fn [curating-blocks]
                                            (into curating-blocks
                                                  (->> (om/get-state owner :selected-thread-comments)
                                                       (map (fn [comment-id]
                                                              (->> (conj (:thread/comments thread) editorial-space)
                                                                   (filter #(= (:db/id %) comment-id))
                                                                   first)))))))
                        (om/set-state! owner :selected-thread-comments #{}))}])]
       
       [:div.eight.wide.column
        [:div.ui.input
         [:input {:type "text" :placeholder "Curation name"}]
         [:button.ui.olive.basic.markdown.button (when (= (count curating-blocks) 0)
                                                   {:class "hidden"})
          [:i.paste.icon]
          "Markdown"]]
        [:div.ui.comments
         (map-indexed
          (fn [index curating-block]
            (list
             [:div.ui.divider]
             [:div.comment.curating-block
              [:div.ui.mini.basic.icon.buttons
               [:button.ui.button
                {:on-click (fn [_]
                             (when (> index 0)
                               (om/update-state! owner :curating-blocks
                                                 (fn [curating-blocks]
                                                   (assoc curating-blocks
                                                          (dec index) (get curating-blocks index)
                                                          index (get curating-blocks (dec index)))))))}
                [:i.caret.up.icon]]
               [:button.ui.button
                {:on-click (fn [_]
                             (when (< index (dec (count (om/get-state owner :curating-blocks))))
                               (om/update-state! owner :curating-blocks
                                                 (fn [curating-blocks]
                                                   (assoc curating-blocks
                                                          (inc index) (get curating-blocks index)
                                                          index (get curating-blocks (inc index)))))))}
                [:i.caret.down.icon]]
               [:button.ui.button
                {:on-click (fn [_]
                             (om/update-state! owner :curating-blocks
                                               (fn [curating-blocks]
                                                 (remove #(= (:db/id %) (:db/id curating-block)) curating-blocks))))}
                [:i.close.icon]]]
              [:div.metadata
               [:span (get-in curating-block [:comment/posted-by :user/name]) "(" (.format date-format-m (get-in curating-block [:comment/posted-at] (js/Date.))) ")"]]
              [:div.text
               (if (= (:db/id curating-block) 0)
                 (om/build editorial-space-view (om/root-cursor (atom curating-block)) 
                           {:opts {:save-fn (fn [value]
                                              (om/set-state! owner [:curating-blocks index :comment/content] value))}})
                 (case (get-in curating-block [:comment/format :db/ident])
                   :comment.format/markdown {:dangerouslySetInnerHTML {:__html (js/marked (:comment/content curating-block))}}
                   (:comment/content curating-block)))]]))
          curating-blocks)]]]]])))