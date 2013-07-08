(ns cascalog.checkpoint
  "Alpha!"
  ;; from cascalog checkpoint
  (:use [cascalog.api :only [with-job-conf get-out-fields]])
  (:require [hadoop-util.core :as h]
            [cascalog.conf :as conf]
            [jackknife.core :as u]
            [jackknife.seq :as seq])
  (:import [java.util Collection]
           [org.apache.log4j Logger]
           [java.util.concurrent Semaphore]
           [org.apache.hadoop.fs FileSystem Path]
           [org.apache.hadoop.conf Configuration]
           java.io.FileOutputStream
           java.io.ObjectOutputStream
           java.io.ObjectInputStream java.io.FileInputStream))

(defn serializable? [obj]
  (instance? java.io.Serializable obj))

(defn serialize-state [state-file state]
  (let [serializable-state (into {} (filter (comp serializable? val) state))]
    (with-open [file-out (FileOutputStream. state-file)
                object-out (ObjectOutputStream. file-out)]
      (.writeObject object-out serializable-state))))

(defn deserialize-state [state-file]
  (with-open [file-in (FileInputStream. state-file)
              object-in (ObjectInputStream. file-in)]
    (.readObject object-in)))

(defstruct WorkflowNode ::tmp-dirs ::fn ::deps ::error ::value)
(defstruct Workflow ::fs ::checkpoint-dir ::graph-atom ::previous-steps ::sem ::log ::options)

(defn mk-workflow [checkpoint-dir initial-graph & [options]]
  (let [fs (h/filesystem)
        log (Logger/getLogger "checkpointed-workflow")
        sem (Semaphore. 0)
        state-file (clojure.java.io/file (str checkpoint-dir "/state.bin"))
        previous-steps (when (.exists state-file) (deserialize-state state-file))]
    (h/mkdirs fs checkpoint-dir)
    (struct Workflow fs checkpoint-dir (atom initial-graph) previous-steps sem log options)))

(defn- mk-runner
  [node workflow]
  (let [log (::log workflow)
        fs (::fs workflow)
        graph-atom (::graph-atom workflow)
        previous-steps (::previous-steps workflow)
        sem (::sem workflow)
        node-name (::name node)
        config conf/*JOB-CONF*]
    (Thread.
     (fn []
       (with-job-conf config
         (try
           ;; Check previous value, doesn't take into account changes graph
           ;; removing checkpoint dir is the only option for these case
           ;; TODO smarter graph checking
           (let [value (if-let [previous-value (get previous-steps node-name)]
                         (do (.info log (str "Skipping " node-name "..."))
                             previous-value)
                         (do (.info log (str "Calculating " node-name "..."))
                             (h/delete fs (::tmp-dir node) true)
                             ((::fn node) @graph-atom)))]
             (swap! graph-atom assoc node-name value)
             (reset! (::value node) value))
           (reset! (::status node) :successful)
           (catch Throwable t
             (.error log (str "Component failed " node-name) t)
             (reset! (::error node) t)
             (reset! (::status node) :failed))
           (finally (.release sem))))))))

(defn mk-node [{:keys [name fn tmp-dir deps]}]
  {:pre [name fn tmp-dir deps]}
  (let [node
        (struct-map WorkflowNode
          ::name name
          ::fn fn
          ::status (atom :unstarted)
          ::error (atom nil)
          ::value (atom nil)
          ::tmp-dir  tmp-dir (str (::checkpoint-dir workflow) "/" (name k))tmp-dir
          ::deps deps)]
    (assoc node ::runner-thread (mk-runner node workflow))))

(defn run-success-callback [workflow]
  (when-let [callback (get-in workflow [::options :after-success])]
    (.info (::log workflow) "Running success callback")
    (callback {})))

(defn run-failure-callback [workflow error]
  (when-let [callback (get-in workflow [::options :after-failure])]
    (.info (::log workflow) "Running failure callback")
    (callback {:error error})))

(defn- fail-workflow!
  [workflow nodes-map]
  (let [log (::log workflow)
        nodes (vals nodes-map)
        failed-nodes (filter (comp deref ::error) nodes)
        succesful-nodes (filter (comp (partial = :successful) deref ::status) nodes)
        running-nodes (filter (comp (partial = :running) deref ::status) nodes)
        threads (map ::runner-thread running-nodes)]
    (.info log "Workflow failed - interrupting components")
    (doseq [t threads] (.interrupt t))
    (.info log "Waiting for running components to finish")
    (doseq [t threads] (.join t))
    (.info log "Serializing succesful nodes")
    (serialize-state (str (::checkpoint-dir workflow) "/state.bin")
                     (into {} (map (fn [n] [(::name n) @(::value n)]) succesful-nodes)))

    (run-failure-callback workflow failed-nodes)
    ;; TODO we would like to print the Exceptions that help us fix bugs, but it is unclear
    ;;      how to reach those Exceptions (e.g. TupleExceptions)
    (u/throw-runtime (str "Workflow failed during " (clojure.string/join ", " (map ::name failed-nodes))))))

(defn exec-workflow! [workflow nodes]
  ;; run checkpoint as graph
  (let [log (::log workflow)]
    (loop []
      ;; Start nodes t
      (doseq [[name node] nodes
              :when (and (= :unstarted @(::status node))
                         (every? (fn [[_ n]] (= :successful @(::status n)))
                                 (select-keys nodes (::deps node))))]
        (reset! (::status node) :running)
        (.start (::runner-thread node)))
      ;; Wait for nodes to finish
      (.acquire (::sem workflow))
      ;; Check for failures
      (let [statuses (set (map (comp deref ::status val) nodes))]
        (cond (contains? statuses :failed) (fail-workflow! workflow nodes)
              (some #{:running :unstarted} statuses) (recur)
              :else (do
                      (.info log "Workflow completed successfully")
                      (run-success-callback workflow)
                      ))))
    ;; TODO make deleting checkpoint dir optional
    (h/delete (::fs workflow) (::checkpoint-dir workflow) true)))

(defn run-workflow! [])