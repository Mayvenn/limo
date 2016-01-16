(ns limo.driver
  (:require [environ.core :refer [env]]
            [clojure.string :refer [lower-case]]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [heat.taxi-extensions :as ext :refer [to delete-all-cookies current-url implicit-wait]]
            [heat.config :as config])
  (:import org.openqa.selenium.remote.RemoteWebDriver
           org.openqa.selenium.remote.CapabilityType
           org.openqa.selenium.remote.DesiredCapabilities
           org.openqa.selenium.logging.LogEntries
           org.openqa.selenium.logging.LogEntry
           org.openqa.selenium.logging.LogType
           org.openqa.selenium.logging.LoggingPreferences
           org.openqa.selenium.WebDriver
           org.openqa.selenium.chrome.ChromeDriver
           org.openqa.selenium.WebDriverException
           org.openqa.selenium.Dimension
           java.util.logging.Level
           java.net.URL
           java.util.Date))

(def normal-report clojure.test/report)
(def ^:dynamic current-test-name nil)

(defn- screencapture-report [m]
  (when (#{:fail :error} (:type m))
    (let [filename (if (:file m)
                     (str (:file m) "/" "line-" (:line m) "/" current-test-name "-" (.getTime (Date.)))
                     (str "unknown-files/" current-test-name "-" (.getTime (Date.))))]
      (println "Saved screenshot to:" filename)
      (ext/screenshot filename)
      (doseq [driver (vals drivers)]
        (doseq [entry (.. driver manage logs (get LogType/BROWSER))]
          (println "[Console]" (.getTimestamp entry) (.getLevel entry) (.getMessage entry))))))
  (when (= :begin-test-var (:type m))
    (alter-var-root #'current-test-name (constantly (str (:name (meta (:var m)))))))
  (normal-report m))

(defn auto-screenshot [f]
  (binding [clojure.test/report screencapture-report]
    (f)))

(def capabilities
  {:chrome (DesiredCapabilities/chrome)
   :browser-stack {:samsung-galaxy-s4 (doto (DesiredCapabilities.)
                                        (.setCapability CapabilityType/LOGGING_PREFS
                                                        (doto (LoggingPreferences.)
                                                          (.enable LogType/BROWSER Level/ALL)))
                                        (.setCapability "browserName" "android")
                                        (.setCapability "platform" "ANDROID")
                                        (.setCapability "device" "Samsung Galaxy S4")
                                        (.setCapability "browserstack.debug" "true"))}})

(defn logging-capability
  ([desired-capabilities] (logging-capability desired-capabilities Level/ALL))
  ([desired-capabilities level]
   (doto desired-capabilities
     (.setCapability CapabilityType/LOGGING_PREFS
                     (doto (LoggingPreferences.)
                       (.enable LogType/BROWSER level))))))

(defn create-chrome
  ([] (create-chrome (:chrome capabilities)))
  ([capabilities] (ChromeDriver. capabilities)))

(defn create-remote
  ([desired-capabilities] (RemoteWebDriver. desired-capabilities))
  ([url desired-capabilities] (RemoteWebDriver. (io/as-url url) desired-capabilities)))
