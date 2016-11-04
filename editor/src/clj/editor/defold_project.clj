(ns editor.defold-project
  "Define the concept of a project, and its Project node type. This namespace bridges between Eclipse's workbench and
  ordinary paths."
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.build-errors-view :as build-errors-view]
            [editor.collision-groups :as collision-groups]
            [editor.console :as console]
            [editor.core :as core]
            [editor.dialogs :as dialogs]
            [editor.engine :as engine]
            [editor.handler :as handler]
            [editor.ui :as ui]
            [editor.prefs :as prefs]
            [editor.progress :as progress]
            [editor.resource :as resource]
            [editor.targets :as targets]
            [editor.workspace :as workspace]
            [editor.outline :as outline]
            [editor.validation :as validation]
            [editor.game-project-core :as gpc]
            [editor.properties :as properties]
            [service.log :as log]
            [editor.graph-util :as gu]
            [util.http-server :as http-server]
            ;; TODO - HACK
            [internal.graph.types :as gt]
            [clojure.string :as str])
  (:import [java.io File InputStream]
           [java.nio.file FileSystem FileSystems PathMatcher]
           [java.lang Process ProcessBuilder]
           [editor.resource FileResource]
           [com.defold.editor Platform]))

(set! *warn-on-reflection* true)

(def ^:dynamic *load-cache* nil)

(def ^:private unknown-icon "icons/32/Icons_29-AT-Unknown.png")

(def ^:const hot-reload-url-prefix "/build")

(g/defnode ResourceNode
  (inherits core/Scope)
  (inherits outline/OutlineNode)
  (inherits resource/ResourceNode)

  (output save-data g/Any (g/fnk [resource] {:resource resource}))
  (output build-targets g/Any (g/constantly []))
  (output node-outline outline/OutlineData :cached
    (g/fnk [_node-id resource source-outline child-outlines]
           (let [rt (resource/resource-type resource)
                 children (cond-> child-outlines
                            source-outline (into (:children source-outline)))]
             {:node-id _node-id
              :label (or (:label rt) (:ext rt) "unknown")
              :icon (or (:icon rt) unknown-icon)
              :children children}))))

(g/defnode PlaceholderResourceNode
  (inherits ResourceNode))

(defn graph [project]
  (g/node-id->graph-id project))

(defn- load-node [project node-id node-type resource]
  (let [loaded? (and *load-cache* (contains? @*load-cache* node-id))]
    (if-let [load-fn (and resource (not loaded?) (:load-fn (resource/resource-type resource)))]
      (if (resource/exists? resource)
        (try
          (when *load-cache*
            (swap! *load-cache* conj node-id))
          (concat
            (load-fn project node-id resource)
            (when (instance? FileResource resource)
              (g/connect node-id :save-data project :save-data)))
          (catch Exception e
            (log/warn :exception e)
            (g/mark-defective node-id node-type (g/error-fatal (format "The file '%s' could not be loaded." (resource/proj-path resource)) {:type :invalid-content}))))
        (g/mark-defective node-id node-type (g/error-fatal (format "The file '%s' could not be found." (resource/proj-path resource)) {:type :file-not-found})))
      [])))

(defn load-resource-nodes [basis project node-ids render-progress!]
  (let [progress (atom (progress/make "Loading resources" (count node-ids)))]
    (doall
     (for [node-id node-ids
           :let [type (g/node-type* basis node-id)]
           :when (g/has-output? type :resource)
           :let [resource (g/node-value node-id :resource {:basis basis})]]
       (do
         (when render-progress!
           (render-progress! (swap! progress progress/advance)))
         (load-node project node-id type resource))))))

(defn- load-nodes! [project node-ids render-progress!]
  (g/transact
    (load-resource-nodes (g/now) project node-ids render-progress!)))

(defn- connect-if-output [src-type src tgt connections]
  (let [outputs (g/output-labels src-type)]
    (for [[src-label tgt-label] connections
          :when (contains? outputs src-label)]
      (g/connect src src-label tgt tgt-label))))

(defn make-resource-node
  ([graph project resource load? connections]
    (make-resource-node graph project resource load? connections nil))
  ([graph project resource load? connections attach-fn]
    (assert resource "resource required to make new node")
    (let [resource-type (resource/resource-type resource)
          found? (some? resource-type)
          node-type (:node-type resource-type PlaceholderResourceNode)]
      (g/make-nodes graph [node [node-type :resource resource]]
                    (if (some? resource-type)
                      (concat
                        (for [[consumer connection-labels] connections]
                          (connect-if-output node-type node consumer connection-labels))
                        (if load?
                          (load-node project node node-type resource)
                          [])
                        (if attach-fn
                          (attach-fn node)
                          []))
                      (concat
                        (g/connect node :_node-id project :nodes)
                        (if attach-fn
                          (attach-fn node)
                          [])))))))

(defn- make-nodes! [project resources]
  (let [project-graph (graph project)]
    (g/tx-nodes-added
      (g/transact
        (for [[resource-type resources] (group-by resource/resource-type resources)
              resource resources]
          (if (not= (resource/source-type resource) :folder)
            (make-resource-node project-graph project resource false {project [[:_node-id :nodes]
                                                                               [:resource :node-resources]]})
            []))))))

(defn get-resource-node [project path-or-resource]
  (when-let [resource (cond
                        (string? path-or-resource) (workspace/find-resource (g/node-value project :workspace) path-or-resource)
                        (satisfies? resource/Resource path-or-resource) path-or-resource
                        :else (assert false (str (type path-or-resource) " is neither a path nor a resource: " (pr-str path-or-resource))))]
    (let [nodes-by-resource-path (g/node-value project :nodes-by-resource-path)]
      (get nodes-by-resource-path (resource/proj-path resource)))))

(defn load-project
  ([project]
   (load-project project (g/node-value project :resources)))
  ([project resources]
   (load-project project resources progress/null-render-progress!))
  ([project resources render-progress!]
   (with-bindings {#'*load-cache* (atom (into #{} (g/node-value project :nodes)))}
     (let [nodes (make-nodes! project resources)]
       (load-nodes! project nodes render-progress!)
       (when-let [game-project (get-resource-node project "/game.project")]
         (g/transact
           (concat
             (g/connect game-project :display-profiles-data project :display-profiles)
             (g/connect game-project :settings-map project :settings))))
       project))))

(defn make-embedded-resource [project type data]
  (when-let [resource-type (get (g/node-value project :resource-types) type)]
    (resource/make-memory-resource (g/node-value project :workspace) resource-type data)))

(defn save-data [project]
  (g/node-value project :save-data {:skip-validation true}))

(defn save-all [project {:keys [render-progress! basis cache]
                         :or {render-progress! progress/null-render-progress!
                              basis            (g/now)
                              cache            (g/cache)}
                         :as opts}]
  (try
    (let [save-data (g/node-value project :save-data {:basis basis :cache cache :skip-validation true})]
      (if-not (g/error? save-data)
        (do
          (progress/progress-mapv
            (fn [{:keys [resource content]} _]
              (when-not (resource/read-only? resource)
                (spit resource content)))
            save-data
            render-progress!
            (fn [{:keys [resource]}] (and resource (str "Saving " (resource/resource->proj-path resource)))))
          (workspace/resource-sync! (g/node-value project :workspace {:basis basis :cache cache}) false [] render-progress!))
        (do
          (ui/run-later
            (throw (Exception. ^String (properties/error-message save-data)))))))
    (catch Exception e
      (ui/run-later
        (throw e)))))

(defn compile-find-in-files-regex
  "Convert a search-string to a java regex"
  [search-str]
  (let [clean-str (str/replace search-str #"[\<\(\[\{\\\^\-\=\$\!\|\]\}\)\?\+\.\>]" "")]
    (re-pattern (format "^(.*)(%s)(.*)$"
                        (str/lower-case
                         (str/replace clean-str #"\*" ".*"))))))

(defn- line->caret-pos [content line]
  (let [line-counts (map (comp inc count) (str/split-lines content))]
    (reduce + (take line line-counts))))

(defn search-in-files
  "Returns a list of {:resource resource :content content :matches [{:line :snippet}]}
  with resources that matches the search-str"
  [project file-extensions-str search-str]
  (when-not (empty? search-str)
    (let [save-data      (->> (save-data project)
                              (filter #(= :file (and (:resource %)
                                                     (resource/source-type (:resource %))))))
          file-exts      (some-> file-extensions-str
                                 (str/replace #" " "")
                                 (str/split #","))
          file-ext-pats  (->> file-exts
                              (remove empty?)
                              (map compile-find-in-files-regex))
          matching-files (filter (fn [data]
                                   (let [rext (-> data :resource resource/resource-type :ext)]
                                     (or (empty? file-ext-pats)
                                         (some #(re-matches % rext) file-ext-pats))))
                                 save-data)
          pattern        (compile-find-in-files-regex search-str)]
      (->> matching-files
           (filter :content)
           (map (fn [{:keys [content] :as hit}]
                  (assoc hit :matches
                         (->> (str/split-lines (:content hit))
                              (map str/lower-case)
                              (keep-indexed (fn [idx l]
                                              (let [[_ pre m post] (re-matches pattern l)]
                                                (when m
                                                  {:line           idx
                                                   :caret-position (line->caret-pos (:content hit) idx)
                                                   :match          (str (apply str (take-last 24 (str/triml pre)))
                                                                        m
                                                                        (apply str (take 24 (str/trimr post))))}))))
                              (take 10)))))
           (filter #(seq (:matches %)))))))

(defn workspace [project]
  (g/node-value project :workspace))

(defonce ongoing-build-save? (atom false))

;; We want to save any work done by the save/build, so we use our 'save/build cache' as system cache
;; if the system cache hasn't changed during the build.
(defn- update-system-cache! [old-cache-val new-cache]
  (swap! (g/cache) (fn [current-cache-val]
                     (if (= old-cache-val current-cache-val)
                       @new-cache
                       current-cache-val))))

(handler/defhandler :save-all :global
  (enabled? [] (not @ongoing-build-save?))
  (run [project]
    (when-not @ongoing-build-save?
      (reset! ongoing-build-save? true)
      (let [workspace     (workspace project)
            old-cache-val @(g/cache)
            cache         (atom old-cache-val)]
        (future
          (try
            (ui/with-progress [render-fn ui/default-render-progress!]
              (save-all project {:render-progress! render-fn
                                 :basis            (g/now)
                                 :cache            cache})
              (workspace/update-version-on-disk! workspace)
              (update-system-cache! old-cache-val cache))
            (finally (reset! ongoing-build-save? false))))))))

(defn- target-key [target]
  [(:resource (:resource target))
   (:build-fn target)
   (:user-data target)])

(defn- build-target [basis target all-targets build-cache]
  (let [resource (:resource target)
        key (:key target)
        cache (let [cache (get @build-cache resource)] (and (= key (:key cache)) cache))]
    (if cache
     cache
     (let [node (:node-id target)
           dep-resources (into {} (map #(let [resource (:resource %)
                                              key (target-key %)] [resource (:resource (get all-targets key))]) (flatten (:deps target))))
           result ((:build-fn target) node basis resource dep-resources (:user-data target))
           result (assoc result :key key)]
       (swap! build-cache assoc resource (assoc result :cached true))
       result))))

(defn targets-by-key [build-targets]
  (into {} (map #(let [key (target-key %)] [key (assoc % :key key)]) build-targets)))

(defn prune-build-cache! [cache build-targets]
  (reset! cache (into {} (filter (fn [[resource result]] (contains? build-targets (:key result))) @cache))))

(defn- build-targets-deep [build-targets]
  (loop [targets build-targets
         queue []
         seen #{}
         result []]
    (if-let [t (first targets)]
      (let [key (target-key t)]
        (if (contains? seen key)
          (recur (rest targets) queue seen result)
          (recur (rest targets) (conj queue (flatten (:deps t))) (conj seen key) (conj result t))))
      (if-let [targets (first queue)]
        (recur targets (rest queue) seen result)
        result))))

(defn build [project node {:keys [render-progress! render-error! basis cache]
                           :or   {render-progress! progress/null-render-progress!
                                  basis            (g/now)
                                  cache            (g/cache)}
                           :as   opts}]
  (let [build-cache          (g/node-value project :build-cache {:basis basis :cache cache})
        build-targets        (g/node-value node :build-targets {:basis basis :cache cache})
        build-targets-by-key (and (not (g/error? build-targets))
                                  (->> (g/node-value node :build-targets {:basis basis :cache cache})
                                       build-targets-deep
                                       targets-by-key))]
    (if (g/error? build-targets)
      (do
        (when render-error!
          (render-error! build-targets))
        nil)
      (do
        (prune-build-cache! build-cache build-targets-by-key)
        (progress/progress-mapv
          (fn [target _]
            (build-target basis (second target) build-targets-by-key build-cache))
          build-targets-by-key
          render-progress!
          (fn [e] (str "Building " (resource/resource->proj-path (:resource (second e))))))))))

(defn- prune-fs [files-on-disk built-files]
  (let [files-on-disk (reverse files-on-disk)
        built (set built-files)]
    (doseq [^File file files-on-disk
            :let [dir? (.isDirectory file)
                  empty? (= 0 (count (.listFiles file)))
                  keep? (or (and dir? (not empty?)) (contains? built file))]]
      (when (not keep?)
        (.delete file)))))

(defn prune-fs-build-cache! [cache build-results]
  (let [build-resources (set (map :resource build-results))]
    (reset! cache (into {} (filter (fn [[resource key]] (contains? build-resources resource)) @cache)))))

(defn- pump-engine-output [^InputStream stdout]
  (let [buf (byte-array 1024)]
    (loop []
      (let [n (.read stdout buf)]
        (when (> n -1)
          (let [msg (String. buf 0 n)]
            (console/append-console-message! msg)
            (recur)))))))

(defn- launch-engine [launch-dir]
  (let [suffix (.getExeSuffix (Platform/getHostPlatform))
        path   (format "%s/bin/dmengine%s" (System/getProperty "defold.unpack.path") suffix)
        pb     (doto (ProcessBuilder. ^java.util.List (list path))
                 (.redirectErrorStream true)
                 (.directory launch-dir))]
    (let [p  (.start pb)
          is (.getInputStream p)]
      (.start (Thread. (fn [] (pump-engine-output is)))))))

(defn build-and-write [project node {:keys [render-progress! basis cache]
                                     :or {render-progress! progress/null-render-progress!
                                          basis            (g/now)
                                          cache            (g/cache)}
                                     :as opts}]
  (let [files-on-disk  (file-seq (io/file (workspace/build-path
                                           (g/node-value project :workspace {:basis basis :cache cache}))))
        fs-build-cache (g/node-value project :fs-build-cache {:basis basis :cache cache})
        build-results  (build project node opts)]
    (prune-fs files-on-disk (map #(File. (resource/abs-path (:resource %))) build-results))
    (prune-fs-build-cache! fs-build-cache build-results)
    (progress/progress-mapv
     (fn [result _]
       (let [{:keys [resource content key]} result
             abs-path (resource/abs-path resource)
             mtime (let [f (File. abs-path)]
                     (if (.exists f)
                       (.lastModified f)
                       0))
             build-key [key mtime]
             cached? (= (get @fs-build-cache resource) build-key)]
         (when (not cached?)
           (let [parent (-> (File. (resource/abs-path resource))
                            (.getParentFile))]
             ;; Create underlying directories
             (when (not (.exists parent))
               (.mkdirs parent))
             ;; Write bytes
             (with-open [out (io/output-stream resource)]
               (.write out ^bytes content))
             (let [f (File. abs-path)]
               (swap! fs-build-cache assoc resource [key (.lastModified f)]))))))
     build-results
     render-progress!
     (fn [{:keys [resource]}] (str "Writing " (resource/resource->proj-path resource))))))

(handler/defhandler :undo :global
  (enabled? [project-graph] (g/has-undo? project-graph))
  (run [project-graph] (g/undo! project-graph)))

(handler/defhandler :redo :global
    (enabled? [project-graph] (g/has-redo? project-graph))
    (run [project-graph] (g/redo! project-graph)))

(ui/extend-menu ::menubar :editor.app-view/open
                [{:label "Save All"
                  :id ::save-all
                  :acc "Shortcut+S"
                  :command :save-all}])

(ui/extend-menu ::menubar :editor.app-view/edit
                [{:label "Project"
                  :id ::project
                  :children [{:label "Build"
                              :acc "Shortcut+B"
                              :command :build}
                             {:label "Fetch Libraries"
                              :command :fetch-libraries}
                             {:label :separator}
                             {:label "Target"
                              :on-submenu-open targets/update!
                              :command :target}
                             {:label "Enter Target IP"
                              :command :target-ip}
                             {:label "Target Discovery Log"
                              :command :target-log}]}])

(defn- outputs [node]
  (mapv #(do [(second (gt/head %)) (gt/tail %)]) (gt/arcs-by-head (g/now) node)))

(defn- loadable? [resource]
  (not (nil? (:load-fn (resource/resource-type resource)))))

;; Reloading works like this:
;; * All moved files are handled by simply updating their resource property
;; * All truly added files are added/loaded
;;   - "Truly added" means it was not referenced before
;;   - Referenced missing files will still be represented with a node
;; * The remaining files are handled by:
;;   - Adding the new nodes
;;   - Transfering overrides from old nodes
;;   - Deleting old nodes
;;   - Loading new nodes
;;   - Reconnecting remaining connections from old nodes
;; * Invalidate external resource nodes, e.g. png's and wav's
;; * Reset undo history when necessary
(defn- handle-resource-changes [project changes render-progress!]
  (with-bindings {#'*load-cache* (atom (into #{} (g/node-value project :nodes)))}
    (let [project-graph (g/node-id->graph-id project)]
      (let [nodes-by-path (g/node-value project :nodes-by-resource-path)]
        (g/transact
          (concat
            ;; Moved resources
            (for [[from to] (:moved changes)
                  :let [resource-node (nodes-by-path (resource/proj-path from))]
                  :when resource-node]
              (g/set-property resource-node :resource to))
            ;; Added resources
            (for [resource (:added changes)
                  :when (not (contains? nodes-by-path (resource/proj-path resource)))]
              (make-resource-node project-graph project resource true {project [[:_node-id :nodes]
                                                                                [:resource :node-resources]]})))))
      (let [nodes-by-path (g/node-value project :nodes-by-resource-path)
            res->node (comp nodes-by-path resource/proj-path)
            known? (fn [r] (contains? nodes-by-path (resource/proj-path r)))
            all-but-moved (vec (mapcat (fn [[k vs]] (map vector (repeat k) vs)) (select-keys changes [:added :removed :changed])))
            reset-undo?     (or (not (empty? (:moved changes)))
                                (some (comp loadable? second) all-but-moved))
            unknown-changed (filterv (complement known?) (:changed changes))
            to-reload       (filterv (comp known? second) all-but-moved)
            to-reload-int   (filterv (comp loadable? second) to-reload)
            to-reload-ext   (filterv (comp (complement loadable?) second) to-reload)
            old-outputs (reduce (fn [res [_ resource]]
                                  (let [nid (res->node resource)]
                                    (assoc res nid (outputs nid))))
                                {} to-reload-int)
            old->new (atom {})]
        ;; Internal resources to reload
        (let [in-use? (fn [resource-node-id]
                        (reduce (fn [_ [target _]]
                                  (if (and (not= project target)
                                           (= project-graph (g/node-id->graph-id target)))
                                    (reduced true)
                                    false))
                                false
                                (gt/targets (g/now) resource-node-id)))
              should-create-node? (fn [[operation resource]]
                                    (or (not= :removed operation)
                                        (-> resource
                                            resource/proj-path
                                            nodes-by-path
                                            in-use?)))
              resources-to-create (into [] (comp (filter should-create-node?) (map second)) to-reload-int)
              new-nodes (make-nodes! project resources-to-create)
              new-nodes-by-path (into {} (map (fn [n] (let [r (g/node-value n :resource)]
                                                        [(resource/proj-path r) n]))
                                              new-nodes))
              old->new (into {} (map (fn [[p n]] [(nodes-by-path p) n]) new-nodes-by-path))]
          (g/transact
            (concat
              (for [[_ r] to-reload-int
                    :let [p (resource/proj-path r)]
                    :when (contains? new-nodes-by-path p)
                    :let [old-node (nodes-by-path p)]]
                (g/transfer-overrides old-node (new-nodes-by-path p)))
              (for [[_ r] to-reload-int
                    :let [old-node (nodes-by-path (resource/proj-path r))]]
                (g/delete-node old-node))))
          (load-nodes! project new-nodes render-progress!)
          ;; Re-connect existing outputs
          (g/transact
            (for [[old new] old->new
                  :when new
                  :let [existing (set (outputs new))]
                  [src-label [tgt-id tgt-label]] (old-outputs old)
                  :let [tgt-id (get old->new tgt-id tgt-id)]
                  :when (not (contains? existing [src-label [tgt-id tgt-label]]))]
              (g/connect new src-label tgt-id tgt-label))))
        ;; External resources to invalidate
        (let [all-outputs (mapcat (fn [[_ resource]]
                                    (let [n (res->node resource)]
                                      (map (fn [[tgt-label _]] [n tgt-label]) (outputs n)))) to-reload-ext)]
          (g/invalidate! all-outputs))
        (when reset-undo?
          (g/reset-undo! (graph project)))
        (assert (empty? unknown-changed) (format "The following resources were changed but never loaded before: %s"
                                                 (clojure.string/join ", " (map resource/proj-path unknown-changed))))))))

(g/defnk produce-collision-groups-data
  [collision-group-nodes]
  (collision-groups/make-collision-groups-data collision-group-nodes))

(g/defnode Project
  (inherits core/Scope)

  (extern workspace g/Any)

  (property build-cache g/Any)
  (property fs-build-cache g/Any)
  (property sub-selection g/Any)

  (input selected-node-ids g/Any :array)
  (input selected-node-properties g/Any :array)
  (input resources g/Any)
  (input resource-map g/Any)
  (input resource-types g/Any)
  (input save-data g/Any :array :substitute (fn [save-data] (vec (remove g/error? save-data))))
  (input node-resources resource/Resource :array)
  (input settings g/Any :substitute (constantly (gpc/default-settings)))
  (input display-profiles g/Any)
  (input collision-group-nodes g/Any :array)

  (output selected-node-ids g/Any :cached (gu/passthrough selected-node-ids))
  (output selected-node-properties g/Any :cached (gu/passthrough selected-node-properties))
  (output sub-selection g/Any :cached (g/fnk [selected-node-ids sub-selection]
                                             (let [nids (set selected-node-ids)]
                                               (filterv (comp nids first) sub-selection))))
  (output resource-map g/Any (gu/passthrough resource-map))
  (output nodes-by-resource-path g/Any :cached (g/fnk [node-resources nodes] (into {} (map (fn [n] [(resource/proj-path (g/node-value n :resource)) n]) nodes))))
  (output save-data g/Any :cached (g/fnk [save-data] (filter #(and % (:content %)) save-data)))
  (output settings g/Any :cached (gu/passthrough settings))
  (output display-profiles g/Any :cached (gu/passthrough display-profiles))
  (output nil-resource resource/Resource (g/constantly nil))
  (output collision-groups-data g/Any :cached produce-collision-groups-data))

(defn get-resource-type [resource-node]
  (when resource-node (resource/resource-type (g/node-value resource-node :resource))))

(defn get-project [resource-node]
  (g/graph-value (g/node-id->graph-id resource-node) :project-id))

(defn filter-resources [resources query]
  (let [file-system ^FileSystem (FileSystems/getDefault)
        matcher (.getPathMatcher file-system (str "glob:" query))]
    (filter (fn [r] (let [path (.getPath file-system (resource/path r) (into-array String []))] (.matches matcher path))) resources)))

(defn find-resources [project query]
  (let [resource-path-to-node (g/node-value project :nodes-by-resource-path)
        resources        (filter-resources (g/node-value project :resources) query)]
    (map (fn [r] [r (get resource-path-to-node (resource/proj-path r))]) resources)))


(defn build-and-save-project [project build-errors-view]
  (when-not @ongoing-build-save?
    (reset! ongoing-build-save? true)
    (let [workspace     (workspace project)
          game-project  (get-resource-node project "/game.project")
          old-cache-val @(g/cache)
          cache         (atom old-cache-val)]
      (future
        (try
          (ui/with-progress [render-fn ui/default-render-progress!]
            (ui/run-later (build-errors-view/clear-build-errors build-errors-view))
            (when-not (empty? (build-and-write project game-project
                                               {:render-progress! render-fn
                                                :render-error!    (fn [errors]
                                                                    (ui/run-later
                                                                     (build-errors-view/update-build-errors
                                                                      build-errors-view
                                                                      errors)))
                                                :basis            (g/now)
                                                :cache            cache}))
              (update-system-cache! old-cache-val cache)))
          (finally (reset! ongoing-build-save? false)))))))

(defn get-selected-target [prefs]
  (prefs/get-prefs prefs "last-target" targets/local-target))

(handler/defhandler :build :global
  (enabled? [] (not @ongoing-build-save?))
  (run [project prefs web-server build-errors-view]
    (let [build  (build-and-save-project project build-errors-view)]
      (when (and (future? build) @build)
        (or (when-let [target (get-selected-target prefs)]
              (let [local-url (format "http://%s:%s%s" (:local-address target) (http-server/port web-server) hot-reload-url-prefix)]
                (engine/reboot (:url target) local-url)))
            (launch-engine (io/file (workspace/project-path (g/node-value project :workspace)))))))))

(handler/defhandler :target :global
  (run [user-data prefs]
    (when user-data
      (prefs/set-prefs prefs "last-target" user-data)))
  (state [user-data prefs]
         (let [last-target (prefs/get-prefs prefs "last-target" nil)]
           (= user-data last-target)))
  (options [user-data prefs]
           (when-not user-data
             (let [targets     (targets/get-targets)
                   last-target (when-let [lt (prefs/get-prefs prefs "last-target" nil)]
                                 [lt])]
               (mapv (fn [target]
                       (let [[_ _ ip] (re-matches #"^(http://)([\w\.]+)(:)(.*)$" (:url target))]
                         {:label     (format "%s (%s)" (:name target) ip)
                          :command   :target
                          :check     true
                          :user-data target}))
                     (distinct (concat last-target targets)))))))

(handler/defhandler :target-ip :global
  (run [prefs]
    (ui/run-later
     (when-let [ip (dialogs/make-target-ip-dialog)]
       (let [url (format "http://%s:8001" ip)]
         (prefs/set-prefs prefs "last-target" {:name "Manual IP"
                                               :url  url})
         (ui/invalidate-menus!))))))

(handler/defhandler :target-log :global
  (run []
    (ui/run-later (targets/make-target-log-dialog))))

(defn settings [project]
  (g/node-value project :settings))

(defn project-dependencies [project]
  (when-let [settings (settings project)]
    (settings ["project" "dependencies"])))

(defn- disconnect-from-inputs [src tgt connections]
  (let [outputs (set (g/output-labels (g/node-type* src)))
        inputs (set (g/input-labels (g/node-type* tgt)))]
    (for [[src-label tgt-label] connections
          :when (and (outputs src-label) (inputs tgt-label))]
      (g/disconnect src src-label tgt tgt-label))))

(defn disconnect-resource-node [project path-or-resource consumer-node connections]
  (let [resource (if (string? path-or-resource)
                   (workspace/resolve-workspace-resource (workspace project) path-or-resource)
                   path-or-resource)
        node (get-resource-node project resource)]
    (disconnect-from-inputs node consumer-node connections)))

(defn connect-resource-node
  ([project path-or-resource consumer-node connections]
    (connect-resource-node project path-or-resource consumer-node connections nil))
  ([project path-or-resource consumer-node connections attach-fn]
    (if-let [resource (if (string? path-or-resource)
                        (workspace/resolve-workspace-resource (workspace project) path-or-resource)
                        path-or-resource)]
      (if-let [node (get-resource-node project resource)]
        (concat
          (if *load-cache*
            (load-node project node (g/node-type* node) resource)
            [])
          (connect-if-output (g/node-type* node) node consumer-node connections)
          (if attach-fn
            (attach-fn node)
            []))
        (make-resource-node (g/node-id->graph-id project) project resource true {project [[:_node-id :nodes]
                                                                                          [:resource :node-resources]]
                                                                                 consumer-node connections}
                            attach-fn))
      [])))

(defn select
  [project-id node-ids]
  (assert (not-any? nil? node-ids) "Attempting to select nil values")
  (concat
    (for [[node-id label] (g/sources-of project-id :selected-node-ids)]
      (g/disconnect node-id label project-id :selected-node-ids))
    (for [[node-id label] (g/sources-of project-id :selected-node-properties)]
      (g/disconnect node-id label project-id :selected-node-properties))
    (for [node-id (distinct node-ids)]
      (concat
        (g/connect node-id :_node-id    project-id :selected-node-ids)
        (g/connect node-id :_properties project-id :selected-node-properties)))))

(defn select!
  ([project node-ids]
    (select! project node-ids (gensym)))
  ([project node-ids op-seq]
    (let [old-nodes (g/node-value project :selected-node-ids)]
      (when (not= node-ids old-nodes)
        (g/transact
          (concat
            (g/operation-sequence op-seq)
            (g/operation-label "Select")
            (select project node-ids)))))))

(defn sub-select!
  ([project sub-selection]
    (sub-select! project sub-selection (gensym)))
  ([project sub-selection op-seq]
    (g/transact
      (concat
        (g/operation-sequence op-seq)
        (g/operation-label "Select")
        (g/set-property project :sub-selection sub-selection)))))

(deftype ProjectResourceListener [project-id]
  resource/ResourceListener
  (handle-changes [this changes render-progress!]
    (handle-resource-changes project-id changes render-progress!)))

(deftype ProjectSelectionProvider [project-id]
  handler/SelectionProvider
  (selection [this] (g/node-value project-id :selected-node-ids)))

(defn selection-provider [project-id] (ProjectSelectionProvider. project-id))

(defn make-project [graph workspace-id]
  (let [project-id
        (first
          (g/tx-nodes-added
            (g/transact
              (g/make-nodes graph
                            [project [Project :workspace workspace-id :build-cache (atom {}) :fs-build-cache (atom {})]]
                            (g/connect workspace-id :resource-list project :resources)
                            (g/connect workspace-id :resource-map project :resource-map)
                            (g/connect workspace-id :resource-types project :resource-types)
                            (g/set-graph-value graph :project-id project)))))]
    (workspace/add-resource-listener! workspace-id (ProjectResourceListener. project-id))
    project-id))

(defn- read-dependencies [game-project-resource]
  (-> (slurp game-project-resource)
    gpc/string-reader
    gpc/parse-settings
    (gpc/get-setting ["project" "dependencies"])))

(defn open-project! [graph workspace-id game-project-resource render-progress! login-fn]
  (let [progress (atom (progress/make "Updating dependencies" 3))]
    (render-progress! @progress)
    (workspace/set-project-dependencies! workspace-id (read-dependencies game-project-resource))
    (workspace/update-dependencies! workspace-id (progress/nest-render-progress render-progress! @progress) login-fn)
    (render-progress! (swap! progress progress/advance 1 "Syncing resources"))
    (workspace/resource-sync! workspace-id false [] (progress/nest-render-progress render-progress! @progress))
    (render-progress! (swap! progress progress/advance 1 "Loading project"))
    (let [project (make-project graph workspace-id)]
      (load-project project (g/node-value project :resources) (progress/nest-render-progress render-progress! @progress)))))

(defn resource-setter [basis self old-value new-value & connections]
  (let [project (get-project self)]
    (concat
     (when old-value (disconnect-resource-node project old-value self connections))
     (when new-value (connect-resource-node project new-value self connections)))))
