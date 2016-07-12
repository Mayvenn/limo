(defproject limo "0.1.5-SNAPSHOT"
  :description "A clojure wrapper around selenium webdriver"
  :url "https://github.com/mayvenn/limo"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.seleniumhq.selenium/selenium-server "2.47.1"]
                 [org.seleniumhq.selenium/selenium-java "2.47.0"]
                 [environ "1.0.0"]]
  :profiles
  {:dev {:source-paths ["dev"]
         :dependencies [[pjstadig/humane-test-output "0.6.0"]
                        [org.clojure/tools.namespace "0.2.11"]]
         :plugins [[lein-cljfmt "0.3.0"]]
         :injections [(require 'pjstadig.humane-test-output)
                      (pjstadig.humane-test-output/activate!)]}})
