(ns cider.nrepl.middleware.track-state-test
  (:require [cider.nrepl.middleware.track-state :as st]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [clojure.test :refer :all]
            [clojure.tools.nrepl.transport :as t])
  (:import clojure.tools.nrepl.transport.Transport))

(def some-ns-map {'cider.nrepl.middleware.track-state-test
                  (st/ns-as-map (find-ns 'cider.nrepl.middleware.track-state-test))})

;;; This is to prevent the agent from flooding test reports with
;;; irrelevant exceptions.
(set-error-handler! st/ns-cache (constantly nil))
(set-error-mode! st/ns-cache :continue)

(def ^:const msg {:session :dummy})

(deftest make-transport
  (is (instance? Transport (st/make-transport msg)))
  (is (try (send (st/make-transport msg) 10)
           nil
           (catch Exception e true))))

(deftest track-ns?
  (is (not (some st/track-ns? st/jar-namespaces))))

(deftest update-and-send-cache
  (let [sent-value (atom nil)]
    (with-redefs [st/track-ns? (constantly true)
                  t/send (fn [t m] (reset! sent-value m))]
      (let [new-data (st/update-and-send-cache nil msg)]
        (is (map? new-data))
        (is (> (count new-data) 100)))
      (let [{:keys [repl-type changed-namespaces]} @sent-value]
        (is (= repl-type :clj))
        (is (map? changed-namespaces))
        (is (> (count changed-namespaces) 100)))
      (let [full-cache (st/update-and-send-cache nil msg)
            get-sent-value (fn [old] (st/update-and-send-cache old msg)
                             @sent-value)]
        ;; Return value depends only on the current state.
        (is (= (st/update-and-send-cache nil msg)
               (st/update-and-send-cache (into {} (take 5 full-cache)) msg)
               (st/update-and-send-cache full-cache msg)))
        ;; Sent message depends on the first arg.
        (is (= (get-sent-value full-cache)
               (get-sent-value full-cache)))
        (is (= (get-sent-value (into {} (drop 3 full-cache)))
               (get-sent-value (into {} (drop 3 full-cache))))))
      ;; In particular, the sent message only contains the diff.
      (let [changed-again (:changed-namespaces @sent-value)]
        (is (map? changed-again))
        (is (= (count changed-again) 3)))
      ;; Check repl-type :cljs
      (with-redefs [cljs/grab-cljs-env (constantly true)]
        (st/update-and-send-cache nil msg)
        (let [{:keys [repl-type changed-namespaces]} @sent-value]
          (is (= repl-type :cljs))
          (is (map? changed-namespaces)))))))

(deftest update-vals
  (is (= (st/update-vals inc {1 2 3 4 5 6})
         {1 3 3 5 5 7}))
  (is (= (st/update-vals range {1 2 3 4 5 6})
         '{5 (0 1 2 3 4 5), 3 (0 1 2 3), 1 (0 1)}))
  (is (= (st/update-vals str {:a :b :c :d :e :f})
         {:e ":f", :c ":d", :a ":b"}))
  (is (= (st/update-vals odd? {1 2 3 4 5 6})
         {1 false 3 false 5 false})))

(deftest filter-core-and-get-meta
  (is (= (st/filter-core-and-get-meta {'and #'and, 'b #'map, 'c #'deftest})
         '{c {:macro "true", :arglists "([name & body])"}}))
  (is (-> (find-ns 'clojure.core)
          ns-map st/filter-core-and-get-meta
          seq not)))

(defn- test-fn "docstring"
  ([a b] nil)
  ([a] nil)
  ([]))

(deftest relevant-meta
  (is (= (st/relevant-meta (meta #'test-fn))
         {:arglists "([a b] [a] [])"}))
  (is (= (:macro (st/relevant-meta (meta #'deftest)))
         "true"))
  (alter-meta! #'update-vals merge {:indent 1 :cider-instrumented 2 :something-else 3})
  (is (= (st/relevant-meta (meta #'update-vals))
         {:cider-instrumented "2", :indent "1",
          :test (pr-str (:test (meta #'update-vals)))})))

(deftest ns-as-map
  (is (empty? (st/ns-as-map nil)))
  (alter-meta! #'update-vals
               merge {:indent 1 :cider-instrumented 2 :something-else 3})
  (let [{:keys [interns aliases] :as ns} (st/ns-as-map (find-ns 'cider.nrepl.middleware.track-state-test))]
    (is (> (count interns) 5))
    (is (map? interns))
    (is (interns 'ns-as-map))
    (is (:test (interns 'ns-as-map)))
    (is (= (into #{} (keys (interns 'update-vals)))
           #{:cider-instrumented :indent :test}))
    (is (> (count aliases) 2))
    (is (= (aliases 'st)
           'cider.nrepl.middleware.track-state)))
  (with-redefs [st/track-ns? (constantly nil)]
    (let [{:keys [interns aliases] :as ns}
          (st/ns-as-map (find-ns 'cider.nrepl.middleware.track-state-test))]
      (is interns))))

(deftest ns-as-map-cljs
  (let [cljs-ns {:use-macros {'sym-0 #'test-fn}
                 :uses {'sym-1 #'ns-as-map-cljs}
                 :defs {'sym-2 #'ns-as-map-cljs}
                 :require-macros {'sym-3 'some-namespace}
                 :requires {'sym-4 'some-namespace}}
        {:keys [aliases interns]} (st/ns-as-map cljs-ns)]
    (is (= aliases '{sym-3 some-namespace sym-4 some-namespace}))
    (is (= interns '{sym-0 {:arglists "([a b] [a] [])"}
                     sym-1 {}
                     sym-2 {}}))))

(deftest calculate-used-aliases
  (is (contains? (into #{} (st/calculate-used-aliases some-ns-map nil))
                 'cider.nrepl.middleware.track-state))
  (is (contains? (into #{} (st/calculate-used-aliases some-ns-map {'cider.nrepl.middleware.track-state nil}))
                 'cider.nrepl.middleware.track-state))
  (is (contains? (into #{} (st/calculate-used-aliases (assoc some-ns-map 'cider.nrepl.middleware.track-state nil) nil))
                 'cider.nrepl.middleware.track-state)))
