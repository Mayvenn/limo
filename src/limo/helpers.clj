(ns limo.helpers
  (:require [limo.api :refer [*default-timeout*]])
  (:import java.util.Date))

(defmacro ^{:style/indent 1} retry-until
  "Private - Subject to change.

  Retries a given reader fn until a timeout occurs or pred succeeds"
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
