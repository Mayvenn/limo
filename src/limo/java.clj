(ns limo.java
  "This namespace in limo is for translating between clojure data structures and java classes."
  (:import [org.openqa.selenium.logging LogType LogEntry LoggingPreferences]
           org.openqa.selenium.remote.DesiredCapabilities
           org.openqa.selenium.remote.CapabilityType
           java.util.logging.Level))

(def keyword->log-type
  "Provides a clojure-convenient way of specifying selenium log types via keywords"
  {:performance LogType/PERFORMANCE
   :browser LogType/BROWSER
   :client LogType/CLIENT
   :driver LogType/DRIVER
   :profiler LogType/PROFILER
   :server LogType/SERVER})

(def keyword->log-level
  "Provides a clojure-convenient way of specfying java logging levels via keywords"
  {:all     Level/ALL
   :fine    Level/FINE
   :info    Level/INFO
   :warning Level/WARNING
   :severe  Level/SEVERE
   :off     Level/OFF})

(def log-level->keyword
  "Provides an easy way to convert java logging levels to clojure keywords"
  (into {} (map reverse keyword->log-level)))

(defn ^Level ->log-level [kw-or-instance]
  (if (instance? Level kw-or-instance)
    kw-or-instance
    (if-let [level (keyword->log-level kw-or-instance)]
      level
      (throw (IllegalArgumentException. (format "Expected one of (%s) or LogType instance" (pr-str (keys keyword->log-level))))))))

(defn ^LogType ->log-type
  "Attempts to coerce to selenium LogTypes"
  [kw-or-instance]
  (if (instance? LogType kw-or-instance)
    kw-or-instance
    (if-let [type (keyword->log-type kw-or-instance)]
      type
      (throw (IllegalArgumentException. (format "Expected one of (%s) or LogType instance" (pr-str (keys keyword->log-type))))))))

(defn log-entry->map
  "Coerces a selenium LogEntry instance into a clojure map."
  [^LogEntry entry]
  {:message   (.getMessage entry)
   :timestamp (.getTimestamp entry)
   :level     (level->keyword (.getLevel entry))})

(defn ^LoggingPreferences map->logging-preferences
  "Converts a clojure map into selenium LoggingPreferences.

  Keys maps to LogTypes (which can coerce from keywords)
  Values map to java log levels (which can be coerced from keywords)

  Examples:

    (map->logging-preferences {:browser :all})
    (map->logging-preferences {:browser     :all
                               :performance :all})
    (map->logging-preferences {LogType/BROWSER :all
                               :performance    Level/ALL})

  "
  [m]
  (let [prefs (LoggingPreferences)]
    (doseq [[type lvl] m]
      (.enable prefs (->log-type type) (->level lvl)))
    prefs))

(def ^LoggingPreferences default-logging-preferences
  (map->logging-preferences {:browser     :all
                             :performance :all}))

(def ^:dynamic *default-capabilities*
  {:chrome                          (DesiredCapabilities/chrome)
   :firefox                         (DesiredCapabilities/firefox)
   :android                         (DesiredCapabilities/android)
   :html-unit                       (DesiredCapabilities/htmlUnit)
   :edge                            (DesiredCapabilities/edge)
   :internet-explorer               (DesiredCapabilities/internetExplorer)
   :iphone                          (DesiredCapabilities/iphone)
   :ipad                            (DesiredCapabilities/ipad)
   :opera-blink                     (DesiredCapabilities/operaBlink)
   :safari                          (DesiredCapabilities/safari)
   ;; non-selenium builtins
   :browser-stack/samsung-galaxy-s4 (doto (DesiredCapabilities.)
                                      (.setCapability CapabilityType/LOGGING_PREFS default-logging-preferences)
                                      (logging-capability)
                                      (.setCapability "browserName" "android")
                                      (.setCapability "platform" "ANDROID")
                                      (.setCapability "device" "Samsung Galaxy S4")
                                      (.setCapability "browserstack.debug" "true"))})

(defn ^DesiredCapabilities ->capabilities [m]
  (cond
    (instance? DesiredCapabilities m) m
    (keyword? m)                      (*default-capabilities* m)
    :else                             (DesiredCapabilities. m)))

(defn ^DesiredCapabilities set-logging-capability [desired-capabilities m-or-instance]
  (doto (map->capabilities dc)
    (.setCapability CapabilityType/LOGGING_PREFS (map->logging-preferences m-or-instance))))
