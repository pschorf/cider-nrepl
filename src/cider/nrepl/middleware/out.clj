(ns cider.nrepl.middleware.out
  "Change *out* to print on sessions in addition to process out.

  Automatically changes the root binding of *out* to print to any
  active sessions. An active session is one that has sent at least one
  \"eval\" op.

  We use an eval message, instead of the clone op, because there's no
  guarantee that the channel that sent the clone message will properly
  handle output replies."
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.interruptible-eval :as ie]
            [clojure.tools.nrepl.middleware.session :as session])
  (:import [java.io PrintWriter Writer]))

;;; OutStream
(defonce original-out *out*)

(declare tracked-sessions-map)

(defmacro with-out-binding
  "Run body with v bound to the output stream of each msg in msg-seq.
  Also run body with v bound to `original-out`."
  [[v msg-seq] & body]
  `(do (let [~(with-meta v {:tag Writer}) original-out]
         ~@body)
       (doseq [{:keys [~'session] :as ~'msg} ~msg-seq]
         (let [~(with-meta v {:tag Writer}) (get @~'session #'*out*)]
           (try (binding [ie/*msg* ~'msg]
                  ~@body)
                ;; If a channel is faulty, dissoc it.
                (catch Exception ~'e
                  (swap! tracked-sessions-map dissoc
                         (:id (meta ~'session)))))))))

(defn fork-out
  "Returns a PrintWriter suitable for binding as *out* or *err*. All
  operations are forwarded to all output bindings in the sessions of
  messages in addition to the server's usual PrintWriter (saved in
  `original-out`)."
  [messages]
  (PrintWriter. (proxy [Writer] []
                  (close [] (.flush ^Writer this))
                  (write
                    ([x]
                     (with-out-binding [out messages]
                       (.write out x)))
                    ([x ^Integer off ^Integer len]
                     (with-out-binding [out messages]
                       (.write out x off len))))
                  (flush []
                    (with-out-binding [out messages]
                      (.flush out))))
                true))

;;; Known eval sessions
(def tracked-sessions-map
  "Map from session ids to eval `*msg*`s.
  Only the most recent message from each session is stored."
  (atom {}))

(defn tracked-sessions-map-watch [_ _ _ new-state]
  (let [o (fork-out (vals new-state))]
    ;; FIXME: This won't apply to Java loggers unless we also
    ;; `setOut`, but for that we need to convert a `PrintWriter` to a
    ;; `PrintStream` (or maybe just not use a `PrintWriter` above).
    ;; (System/setOut (PrintStream. o))
    (alter-var-root #'*out* (constantly o))))

(add-watch tracked-sessions-map :update-out tracked-sessions-map-watch)

(defn maybe-register-session
  "Add msg to `tracked-sessions-map` if it is an eval op."
  [{:keys [op session] :as msg}]
  (try
    (when (= op "eval")
      (when-let [session (:id (meta session))]
        (swap! tracked-sessions-map assoc session
               (select-keys msg [:transport :session :id]))))
    (catch Exception e nil)))

(defn wrap-out [handler]
  (fn [msg]
    (maybe-register-session msg)
    (handler msg)))

(set-descriptor!
 #'wrap-out
 (cljs/expects-piggieback
  {:requires #{#'session/session}
   :expects #{"eval"}
   :handles
   {"out-middleware"
    {:doc "Change #'*out* so that it also prints to active sessions, even outside an eval scope."}}}))
