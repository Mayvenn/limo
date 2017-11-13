(ns limo.test
  "Limo features useful under a testing context"
  (:require  [clojure.test :as t]
             [cheshire.core :as json]
             [limo.api :refer [*driver* *default-timeout*]]
             [limo.java :as java])
  (:import java.util.Date))

(defn steal-logs!
  "Retrieves logs of a given type from the browser being control by selenium.

  NOTE: The browser may discard the log information after the request to retrive
  the logs occurs. This means multiple calls to steal-logs! can return different
  results.

    > (count (steal-logs!)) => 5
    > (count (steal-logs!)) => 0

  steal-logs! is pretty low-level in comparison to most of the other limo apis.
  Considering using with-stolen-performance-logs!
  "
  ([log-type-kw] (steal-logs! *driver* log-type-kw))
  ([driver log-type-kw]
   (->> (.. driver
            manage
            logs
            (get (java/->log-type log-type-kw)))
        seq
        (map java/log-entry->map))))

(defn steal-json-logs!
  "Identical steal-logs!, but parses the message body as JSON.

  NOTE: the same limitations as steal-logs! applies: that is, that the browser
  may discard the log information after the request to retrive the logs occurs.

  This is known to be useful with Chrome's performance logs to get network and
  rendering information. Chrome's performance log data is encoded in JSON.

  steal-json-logs! is pretty low-level in comparison to most of the other limo apis.
  Considering using with-stolen-performance-logs!
  "
  ([log-type-kw] (steal-json-logs! *driver* log-type-kw))
  ([driver log-type-kw]
   (->> (steal-logs! driver log-type-kw)
        (map (fn [m] (update m :message #(json/parse-string % true)))))))

(defmacro with-simulated-test-run [& body]
  `(let [results# (atom [])]
     (binding [t/report (fn [m#] (swap! results# conj m#))]
       ~@body)
     @results#))

(defmacro read-performance-logs-until-test-pass!
  "Repeatedly fetches performance logs until the body returns no test failures or unless a timeout occurs.

  NOTE: this destructively consumes performance logs messages from the browser.

  Example:

    (with-stolen-performance-logs! logs _
       (is (first (filter #{\"Network.requestWillBeSent\" :method :message :message} logs))
           \"FAIL: a network request was not sent!\"))
  "
  {:style/indent 1}
  [logs-sym {:keys [timeout interval driver log-type]} & body]
  `(let [start# (.getTime (Date.))
         timeout# ~(or timeout *default-timeout*)
         interval# ~(or interval 500)
         driver# ~driver
         logs# (atom [])
         log-type# ~(or log-type :performance)]
     (loop [~logs-sym (do (swap! logs# into (steal-json-logs! (or driver# *driver*) log-type#))
                          @logs#)]
       (let [has-test-failures# (seq (filter (comp #{:fail :error} :type)
                                             (with-simulated-test-run ~@body)))
             duration# (- (.getTime (Date.)) start#)]
         (if has-test-failures#
           (if (> duration# timeout#)
             (do ~@body) ;; report test failures
             (do ;; retry
               (Thread/sleep interval#)
               (recur (do (swap! logs# into (steal-json-logs! (or driver# *driver*) log-type#))
                          @logs#))))
           (do ~@body)))))) ;; report test success

