(ns cider.nrepl.middleware.info
  (:require [clojure.string :as s]
            [clojure.java.io :as io]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.misc :as u]
            [clojure.repl]
            [cljs-tooling.info :as cljs-info]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]))

(defn maybe-protocol
  [info]
  (if-let [prot-meta (meta (:protocol info))]
    (merge info {:file (:file prot-meta)
                 :line (:line prot-meta)})
    info))

(defn var-meta
  [v]
  (-> v meta maybe-protocol))

(defn ns-meta
  [ns]
  (merge
   (meta ns)
   {:ns ns
    :file (-> (ns-publics ns)
              first
              second
              var-meta
              :file)
    :line 1}))

(defn resolve-var
  [ns sym]
  (if-let [ns (find-ns ns)]
    (try (ns-resolve ns sym)
         ;; Impl might try to resolve it as a class, which may fail
         (catch ClassNotFoundException _
           nil))))

(defn resolve-aliases
  [ns]
  (if-let [ns (find-ns ns)]
    (ns-aliases ns)))

(defn info-clj
  [ns sym]
  (cond
   ;; sym is an alias for another ns
   (get (resolve-aliases ns) sym) (ns-meta (get (resolve-aliases ns) sym))
   ;; it's simply a full ns
   (find-ns sym) (ns-meta (find-ns sym))
   ;; it's a var
   (resolve-var ns sym) (var-meta (resolve-var ns sym))))

(defn info-cljs
  [env symbol ns]
  (let [x (cljs-info/info env symbol ns)]
    (select-keys x [:file :line :ns :doc :column :name :arglists])))

(defn info
  [{:keys [ns symbol] :as msg}]
  (let [[ns symbol] (map u/as-sym [ns symbol])]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      (info-cljs cljs-env symbol ns)
      (info-clj ns symbol))))

(defn resource-path
  "If it's a resource, return a tuple of the relative path and the full resource path."
  [x]
  (or (if-let [full (io/resource x)]
        [x full])
      (if-let [[_ relative] (re-find #".*jar!/(.*)" x)]
        (if-let [full (io/resource relative)]
          [relative full]))
      ;; handles load-file on jar resources from a cider buffer
      (if-let [[_ relative] (re-find #".*jar:(.*)" x)]
        (if-let [full (io/resource relative)]
          [relative full]))))

(defn file-path
  [x]
  (if (seq x)
    (let [f (io/file x)]
      (if (.exists f)
        f))))

(defn file-info
  [path]
  (let [[resource-relative resource-full] (resource-path path)]
    (merge {:file (or (file-path path) resource-full path)}
           ;; Classpath-relative path if possible
           (if resource-relative
             {:resource resource-relative}))))

(defn format-response
  [info]
  (if info
    (-> (update-in info [:ns] str)
        (merge {:arglists-str (pr-str (:arglists info))}
               (file-info (:file info)))
        u/transform-value)))

(defn info-reply
  [{:keys [transport] :as msg}]
  (transport/send transport (response-for msg :value (format-response (info msg))))
  (transport/send transport (response-for msg :status :done)))

(defn wrap-info
  "Middleware that looks up info for a symbol within the context of a particular namespace."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "info" op)
      (info-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-info
 (cljs/maybe-piggieback
  {:handles
   {"info"
    {:doc "Return a map of information about the specified symbol."
     :requires {"symbol" "The symbol to lookup"
                "ns" "The current namespace"}
     :returns {"status" "done"}}}}))


