(ns redgenes.sections.querybuilder.views
  (:require [re-frame.core :refer [subscribe dispatch]]
            [reagent.core :as reagent :refer [create-class]]
            [imcljs.path :as p]
            [clojure.string :refer [split join blank?]]
            [oops.core :refer [ocall oget]]
            [clojure.string :as str :refer [starts-with? ends-with?]]
            [redgenes.utils :refer [uncamel]]
            [redgenes.components.bootstrap :refer [tooltip-new]]
            [redgenes.components.ui.constraint :refer [constraint]]
            [imcljs.path :as im-path]
            [cljs.reader :refer [read-string]]
            [redgenes.components.ui.results_preview :refer [preview-table]]))

(defn one-of? [haystack needle] (some? (some #{needle} haystack)))

(defn tree-view-recur [model root-class trail selected & [override]]
  (let [expanded? (reagent/atom (get-in selected trail))]   ; Recursively auto-expand to selected values
    (fn [model root-class trail selected]
      (let [{:keys [displayName attributes collections references] :as p} (get-in model [:classes root-class])]
        [:ul.qb {:class (if (and @expanded? (> (count trail) 1)) "open")}
         [:li {:on-click (fn [] (swap! expanded? not))}
          [:div.nowrap
           (if @expanded?
             [:span.glyphicon.glyphicon-chevron-down]
             [:span.glyphicon.glyphicon-chevron-right])
           [:div.button-group
            [:button.small-btn {:on-click (fn [e]
                                            (ocall e :stopPropagation)
                                            (dispatch [:qb/add-constraint trail]))} [:i.fa.fa-filter]]]
           [:div.class.nowrap.inlineblock (or override displayName)]]]


         (if @expanded?
           (concat
             ; Create a map of the attributes
             (map (fn [[_ {:keys [name]}]]
                    (let [selected? (get-in selected (conj trail name))]
                      ^{:key name}
                      [:li {:class (if selected? "selected")
                            :style {:padding-left "35px"}}
                       ;:on-click (fn [] (if selected?
                       ;                   (dispatch [:qb/remove-view (conj trail name)])
                       ;                   (dispatch [:qb/add-view (conj trail name)])))

                       [:div.button-group
                        [:button.small-btn {:on-click (fn [] (dispatch [:qb/add-constraint (conj trail name)]))} [:i.fa.fa-filter]]
                        (if selected?
                          [:button.small-btn {:on-click (fn [] (dispatch [:qb/remove-view (conj trail name)]))} [:i.fa.fa-minus]]
                          [:button.small-btn {:on-click (fn [] (dispatch [:qb/add-view (conj trail name)]))} [:i.fa.fa-plus]])]

                       [:span
                        (uncamel name)]]))

                  (sort (dissoc attributes :id)))
             ; Combined with a map of collections and references
             (map (fn [[_ colref]]
                    ^{:key (str (name root-class) (:name colref))}
                    [:li [tree-view-recur model (keyword (:referencedType colref)) (conj trail (:name colref)) selected (uncamel (:name colref))]])
                  (into (sorted-map) (merge collections references)))))]))))

(defn tree-view []
  (fn [m model root-class]
    (when root-class
      [tree-view-recur model root-class [(name root-class)] m])))

(defn table-header []
  [:div.grid
   [:div.col-1] [:div.col-1] [:div.col-5 [:h4 "Field"]] [:div.col-5 [:h4 "Constraints"]]])

(defn constraint-logic-row []
  (let [logic (subscribe [:qb/constraint-logic])]
    (fn []
      [:div.grid
       [:div.col-1]
       [:div.col-1]
       [:div.col-5]
       [:div.col-5.white
        [:label "Logic"]
        [:input.form-control
         {:value     @logic
          :on-change (fn [e] (dispatch [:qb/update-constraint-logic (oget e :target :value)]))
          :on-blur   (fn [] (dispatch [:qb/count-query]))
          :type      "text"}]]])))



(def aquery {:from            "Gene"
             :constraintLogic "A or B"
             :select          ["symbol"
                               "organism.name"
                               "alleles.name"
                               "alleles.dataSets.description"]
             :where           [{:path  "Gene.symbol"
                                :op    "="
                                :code  "A"
                                :value "zen"}
                               {:path  "Gene.symbol"
                                :op    "="
                                :code  "B"
                                :value "mad"}]})

(defn root-class-dropdown []
  (let [current-mine (subscribe [:current-mine])
        root-class   (subscribe [:qb/root-class])]
    (fn []
      (into [:select.form-control
             {:on-change (fn [e] (dispatch [:qb/set-root-class (oget e :target :value)]))
              :value     @root-class}]
            (map (fn [[class-kw details]]
                   [:option {:value class-kw} (:displayName details)])
                 (sort-by (comp :displayName second) (get-in @current-mine [:service :model :classes])))))))




(defn dotsplit [string] (split string "."))

(defn im-query->map [{:keys [select]}]
  (reduce (fn [total next] (assoc-in total next {})) {} (map dotsplit select)))

(defn attributes-first [[_ v]] (empty? v))

(defn has-children? [[_ v]] (some? (not-empty v)))

(defn within? [haystack needle] (some? (some #{needle} haystack)))

(defn not-selected [selected [k attributes-map]]
  (not (within? selected (name k))))

(defn atts []
  (fn [model path-vec selected]
    (let [attributes (im-path/attributes model (join "." path-vec))
          selected   (map first selected)]
      (into [:div.attributes]
            (map (fn [[field-kw properties]]
                   (let [selected? (within? selected (name field-kw))]
                     (if selected?
                       [:div
                        [:span.ex
                         {:on-click (fn [] (dispatch [:qb/mappy-remove-view (conj path-vec (name field-kw))]))}
                         [:i.fa.fa-check-square-o.fa-fw]
                         (uncamel (name field-kw))]]
                       [:div
                        [:span.ex
                         {:on-click (fn [] (dispatch [:qb/mappy-add-view (conj path-vec (name field-kw))]))}
                         [:i.fa.fa-square-o.fa-fw]
                         (uncamel (name field-kw))]])))

                 (sort attributes))))))


(defn rels []
  (fn [model path-vec]
    (let [relationships (im-path/relationships model (join "." path-vec))]
      (if (not-empty relationships)
        [:div.attributes
         (into [:div.attributes]
               (map (fn [[kw properties]]
                      [:div
                       [:span
                        [:i.fa.fa-plus]
                        (str " " (uncamel (name kw)))]]) relationships))]
        [:div "(No relationships)"]))))


(defn constr []
  (fn [model path]
    [constraint
     :model model
     :path (join "." path)
     :label? false]))



(defn tree []
  (let [editing?    (reagent/atom false)
        adding-rel? (reagent/atom false)]
    (fn [m model & [trail]]

      (let [with-no-children (filter (complement has-children?) m)
            with-children    (filter has-children? m)]

        (into [:ul]

              (concat

                ; Then a button to show the catalog
                (when trail (conj '() [:div
                                       [:div.btn-toolbar
                                        [:li.haschildren
                                         [:p
                                          [:button.btn.btn-primary.il
                                           {:class    (if @editing? "btn-raised")
                                            :on-click (fn [] (swap! editing? not))}
                                           [:i.fa.fa-pencil] " Attributes"]]]

                                        [:li.haschildren
                                         [:p
                                          [:button.btn.btn-primary.il
                                           {:class    (if @adding-rel? "btn-raised")
                                            :on-click (fn [] (swap! adding-rel? not))}
                                           [:i.fa.fa-share-alt] " Relationships"]]]]]))


                ; Attributes first!
                (if (and trail @editing?)

                  ; If editing then show the attribute catalog
                  (list [:li.haschildren [:p [atts model trail with-no-children]]])
                  ; Otherwise just show our attributes
                  (->> (sort with-no-children)
                       (map (fn [[k v]]
                              [:li.haschildren
                               [:p
                                [:span (str " " (uncamel k))]]]))))

                ;(list [:li.haschildren [:p [:span.hotlink [:a "ID"] [:a "Primary Identifier"] [:a "Another thing"]]]])



                ; Then collections!
                (if @adding-rel?

                  (list [:li.haschildren [:p [rels model trail]]])

                  (->> with-children
                       (map (fn [[k v]]
                              (let [loc (vec (conj trail k))]
                                [:li.haschildren
                                 [:p {:class "classy"}
                                  k
                                  ;[:button.btn.btn-primary.il [:i.fa.fa-share-alt]]
                                  #_[:button.btn.btn-primary.il
                                     {:on-click (fn [] (swap! adding-rel? not))}
                                     [:i.fa.fa-share-alt] " Add Relationship "]]

                                 [:div
                                  [tree v model loc]]])))))))))))


(def q {:from   "Gene"
        :select ["Gene.symbol"
                 "Gene.secondaryIdentifier"
                 "Gene.organism.name"]})

(defn attribute []
  (let [mappy (subscribe [:qb/mappy])]
    (fn [model [k properties] & [trail]]
      (let [path      (conj trail (name k))
            selected? (get-in @mappy path)]
        [:li.haschildren
         [:p
          [:span
           {:on-click (fn []
                        (if selected?
                          (dispatch [:qb/mappy-remove-view path])
                          (dispatch [:qb/mappy-add-view path])))}
           (if (get-in @mappy path)
             [:i.fa.fa-check-square-o.fa-fw]
             [:i.fa.fa-square-o.fa-fw.light])
           [:span.qb-label (str " " (uncamel (:name properties)))]]]]))))

(defn node []
  (let [open? (reagent/atom false)]
    (fn [model [k properties] & [trail]]
      (let [path (vec (conj trail (name k)))]
        [:li.haschildren
         [:p
          [:span {:on-click (fn [] (swap! open? not))}
           [:i.fa.fa-plus.fa-fw.arr.semilight {:class (when @open? "arrow-down")}]
           [:span.qb-class (uncamel (:name properties))]]]
         (when @open?
           (into [:ul]
                 (concat
                   (map (fn [i] [attribute model i path]) (sort (im-path/attributes model (:referencedType properties))))
                   (map (fn [i] [node model i path]) (sort (im-path/relationships model (:referencedType properties)))))))]))))

(defn model-browser []
  (fn [model root-class]
    [:div.model-browser
     (let [path [root-class]]
       (into [:ul]
             (concat
               (map (fn [i] [attribute model i path]) (sort (im-path/attributes model root-class)))
               (map (fn [i] [node model i path]) (sort (im-path/relationships model root-class))))))]))


(defn dissoc-keywords [m]
  (apply dissoc m (filter keyword? (keys m))))

(defn queryview-node []
  (fn [model [k properties] & [trail]]
    (let [path (vec (conj trail (name k)))]
      [:li.haschildren
       [:p.flexmex
        [:span.lab {:class (if (im-path/class? model (join "." path)) "qb-class" "qb-attribute")}


         [:span.qb-label {:style {:margin-left 5}} (uncamel k)]
         [:i.fa.fa-trash-o.fa-fw.semilight {:on-click (fn [] (dispatch [:qb/mappy-remove-view path]))}]
         [:span
          {:on-click (fn [] (dispatch [:qb/mappy-add-constraint path]))}
          [:span [:span [:i.fa.fa-filter.semilight]]]]
         (when-let [c (:id-count properties)]
           [:span.label.label-soft
            {:style {:margin-left 5}} (str c " row" (when (not= c 1) "s"))])]]

       (when-let [constraints (:constraints properties)]
         (into [:ul]
               (map-indexed (fn [idx con]
                              [:li.haschildren
                               [:p
                                [:div.contract
                                 {:class (when (empty? (:value con)) "empty")}
                                 [constraint
                                  :model model
                                  :path (join "." path)
                                  ;:lists (second (first @lists))
                                  :code (:code con)
                                  :on-remove (fn [] (dispatch [:qb/mappy-remove-constraint path idx]))
                                  ;:possible-values (map :value possible-values)
                                  :value (:value con)
                                  :op (:op con)
                                  :on-change (fn [c]
                                               (dispatch [:qb/mappy-update-constraint path idx c]))
                                  :on-blur (fn [x])
                                  ;(dispatch [:qb/build-im-query])

                                  :label? false]]]]) constraints)))
       (when (not-empty properties)
         (let [classes    (filter (fn [[k p]] (im-path/class? model (join "." (conj path k)))) (dissoc-keywords properties))
               attributes (filter (fn [[k p]] ((complement im-path/class?) model (join "." (conj path k)))) (dissoc-keywords properties))]
           (into [:ul]
                 (concat
                   (map (fn [n] [queryview-node model n path]) (sort attributes))
                   (map (fn [n] [queryview-node model n path]) (sort classes))))))])))

(defn queryview-browser []
  (let [mappy (subscribe [:qb/mappy])]
    (fn [model]
      (if (not-empty @mappy)
        [:div.query-browser
         (into [:ul]
               (map (fn [n]
                      [queryview-node model n]) @mappy))]
        [:div "Please select an attribute."]))))

(defn listify [logic-vector]
  (let [v logic-vector]
    (let [first-and (first (keep-indexed (fn [idx e] (if (some #{e} #{'and 'AND}) idx)) v))
          first-or  (or (first (keep-indexed (fn [idx e] (if (and (some #{e} #{'or 'OR}) (>= idx first-and)) idx)) v)) (count v))]
      (if first-and
        (let [trunk     (subvec v 0 (dec first-and))
              anded     (subvec v (dec first-and) first-or)
              remaining (subvec v first-or (count v))
              fixed     (reduce conj (conj trunk anded) remaining)]
          (recur fixed))
        (do
          (if (and (= 1 (count v)) (vector? (first v)))
            (first v)
            v))))))

(defn add-code [l code]
  (listify (reduce conj l [:and code])))


(defn wrap-string-in-parens [string]
  (if (and (starts-with? string "(") (ends-with? string ")"))
    string
    (str "(" string ")")))

(defn replace-parens-with-brackets [string]
  (-> string
      (clojure.string/replace (re-pattern "\\[") "(")
      (clojure.string/replace (re-pattern "\\]") ")")))

(defn stringify-vec [v]
  (apply str (interpose " " v)))

(defn lists-to-vectors [s]
  (clojure.walk/prewalk (fn [e] (if (list? e) (vec e) e)) s))


(defn format-logic-string [string]
  (->> string
       wrap-string-in-parens
       read-string
       vec
       listify
       stringify-vec
       replace-parens-with-brackets))

(defn remove-thing [vec]
  (clojure.walk/postwalk
    (fn [x]
      (println "X" x)
      x) vec))

(def <sub (comp deref subscribe))



(defn logic-box []
  (let [logic (subscribe [:qb/constraint-logic])]
    (fn []
      [:div
       [:input.form-control
        {:style     {:max-width "300"}
         :type      "text"
         :value     @logic
         :on-blur   (fn [] (dispatch [:qb/update-constraint-logic (format-logic-string @logic)]))
         :on-change (fn [e] (dispatch [:qb/update-constraint-logic (oget e :target :value)]))}]])))

(defn controls []
  [:div.button-group
   [:button.btn.btn-primary.btn-raised
    {:on-click (fn [] (dispatch [:qb/load-query aquery]))}
    ;{:on-click (fn [] (println "finished" (listify nil)))}

    "Example"]
   [:button.btn.btn-primary.btn-raised
    {:on-click (fn [] (dispatch [:qb/mappy-build-im-query]))}
    ;{:on-click (fn [] (println "finished" (listify nil)))}

    "Show Results"]])

(defn preview [result-count]
  (let [results-preview   (subscribe [:qb/preview])
        fetching-preview? (subscribe [:idresolver/fetching-preview?])]
    [preview-table
     :loading? @fetching-preview?
     :query-results @results-preview]))



(defn drop-nth [n coll] (vec (keep-indexed #(if (not= %1 n) %2) coll)))

(defn sortable-list []
  (let [order (subscribe [:qb/order])
        state (reagent/atom {:items ["A" "B" "C" "D"] :selected nil})]
    (fn []
      (into [:div.sort-order-container
             {:class (when (some? (:selected @state)) "dragtest")}]
            (map-indexed
              (fn [idx i]
                [:div {:class         (when (= idx (:selected @state)) "dragging")
                       :draggable     true
                       :on-drag-start (fn [e]
                                        (ocall e "dataTransfer.setData" "banana" "cakes")
                                        (swap! state assoc :selected idx))
                       :on-drag-enter (fn [] (let [selected-idx  (:selected @state)
                                                   items         @order
                                                   selected-item (get items selected-idx)
                                                   [before after] (split-at idx (drop-nth selected-idx items))]
                                               #_(swap! state assoc
                                                        :selected idx
                                                        :items (vec (concat (vec before) (vec (list selected-item)) (vec after))))
                                               (swap! state assoc :selected idx)
                                               (dispatch [:qb/set-order (vec (concat (vec before) (vec (list selected-item)) (vec after)))])))
                       :on-drag-end   (fn [] (swap! state assoc :selected nil))}
                 (map (fn [part] [:span.part part]) (interpose ">" (map uncamel (split i "."))))])) @order
            ))))

(defn main []
  (let [query        (subscribe [:qb/query])
        current-mine (subscribe [:current-mine])
        root-class   (subscribe [:qb/root-class])]
    (reagent/create-class
      {:component-did-mount (fn [x]
                              (when (empty? @query)
                                (dispatch [:qb/set-root-class "Gene"])))
       :reagent-render      (fn []
                              [:div.column-container
                               [:div.model-browser-column
                                [:div.container-fluid
                                 [:h4 "Model Browser"]
                                 [:span "Starting with..."]
                                 [root-class-dropdown]
                                 [model-browser (:model (:service @current-mine)) (name @root-class)]]]
                               [:div.query-view-column
                                [:div.container-fluid
                                 [:div.row
                                  [:div.col-md-6
                                   [:div
                                    [:h4 "Query"]
                                    [queryview-browser (:model (:service @current-mine))]
                                    [:h4 "Constraint Logic"]
                                    [logic-box]]]
                                  [:div.col-md-6
                                   [:div
                                    [:div
                                     [:h4 "Column Order"]
                                     [sortable-list]]
                                    [controls]
                                    ]]]]]])})))




;[:h4 "Preview"]

;[preview]




(defn toggle-all-checkbox []
  [:span
   [:input {:type    "checkbox"
            :checked @(subscribe [:todos/all-complete?])}]
   [:label {:for "toggle-all"} "Mark all as complete"]])