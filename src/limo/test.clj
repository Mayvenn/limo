(ns limo.test
  "Limo features useful under a testing context"
  (:require  [clojure.test :as t]
             [cheshire.core :as json]
             [limo.api :refer [*driver* *default-timeout*]]
             [limo.java :as java])
  (:import java.util.Date))

(defn read-logs!
  "Retrieves logs of a given type from the browser being control by selenium.

  NOTE: The browser may discard the log information after the request to retrive
  the logs occurs. This means multiple calls to readonly-logs! can return different
  results.

    > (count (read-logs!)) => 5
    > (count (read-logs!)) => 0

  read-logs! is pretty low-level in comparison to most of the other limo apis.
  Considering using with-stolen-performance-logs!
  "
  ([log-type-kw] (read-logs! *driver* log-type-kw))
  ([driver log-type-kw]
   (->> (.. driver
            manage
            logs
            (get (java/->log-type log-type-kw)))
        seq
        (map java/log-entry->map))))

(defn read-json-logs!
  "Identical read-logs!, but parses the message body as JSON.

  NOTE: the same limitations as read-logs! applies: that is, that the browser
  may discard the log information after the request to retrive the logs occurs.

  This is known to be useful with Chrome's performance logs to get network and
  rendering information. Chrome's performance log data is encoded in JSON.

  read-json-logs! is pretty low-level in comparison to most of the other limo apis.
  Considering using with-stolen-performance-logs!
  "
  ([log-type-kw] (read-json-logs! *driver* log-type-kw))
  ([driver log-type-kw]
   (->> (read-logs! driver log-type-kw)
        (map (fn [m] (update m :message #(json/parse-string % true)))))))

(defmacro with-simulated-test-run [& body]
  `(let [results# (atom [])]
     (binding [t/report (fn [m#] (swap! results# conj m#))]
       ~@body)
     @results#))

(defmacro retry-until
  {:style/indent 1}
  [{:keys [timeout interval reader pred]} & body]
  `(let [start# (.getTime (Date.))
         timeout# ~(or timeout *default-timeout*)
         interval# ~(or interval 500)
         read!# ~reader
         pred# ~pred]
     (loop [_# (read!#)]
       (let [has-test-failures# (pred# (fn [] ~@body))
             duration# (- (.getTime (Date.)) start#)]
         (if has-test-failures#
           (if (> duration# timeout#)
             (do ~@body)
             (do
               (Thread/sleep interval#)
               (recur (read!#))))
           (do ~@body))))))

(defn test-failures? [test-fn]
  (boolean (seq (filter (comp #{:fail :error} :type)
                        (with-simulated-test-run (test-fn))))))

(defmacro read-performance-logs-until-test-pass!
  "Repeatedly fetches performance logs until the body returns no test failures or unless a timeout occurs.

  NOTE: this destructively consumes performance logs messages from the browser.

  Example:

    (read-performance-logs-until-test-pass! [logs]
       (is (first (filter #{\"Network.requestWillBeSent\" :method :message :message} logs))
           \"FAIL: a network request was not sent!\"))
  "
  {:style/indent 1
   :arglists '([[logs-atom] & body]
               [[logs-atom {:keys [timeout interval driver log-type pred]}] & body])}
  [[logs-atom & [{:keys [timeout interval driver log-type pred]}]] & body]
  `(let [driver# ~driver
         log-type# (or ~log-type :performance)
         logs# ~logs-atom]
     (retry-until {:reader (fn []
                             (swap! logs# into (read-json-logs! (or driver# *driver*) log-type#))
                             @logs#)
                   :pred test-failures?
                   :timeout ~timeout
                   :interval ~interval}
                  ~@body)))

