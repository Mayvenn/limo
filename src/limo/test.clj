(ns limo.test
  "Limo features useful under a testing context"
  (:require  [clojure.test :as t]
             [limo.api :as api :refer [*driver*]]
             [limo.helpers :as helpers]
             [limo.java :as java])
  (:import java.util.Date))

(defmacro with-simulated-test-run [& body]
  `(let [results# (atom [])]
     (binding [t/report (fn [m#] (swap! results# conj m#))]
       ~@body)
     @results#))

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
     (helpers/retry-until {:reader (fn []
                                     (swap! logs# into (api/read-json-logs! (or driver# *driver*) log-type#))
                                     @logs#)
                           :pred test-failures?
                           :timeout ~timeout
                           :interval ~interval}
                          ~@body)))

