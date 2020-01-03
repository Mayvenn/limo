(defproject limo "0.2.10"
  :description "A clojure wrapper around selenium webdriver"
  :url "https://github.com/mayvenn/limo"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.logging "0.4.0"]
                 [org.seleniumhq.selenium/selenium-support "3.141.59"]
                 [org.seleniumhq.selenium/selenium-api "3.141.59"]
                 [org.seleniumhq.selenium/selenium-server "3.141.59"
                  :exclusions [org.seleniumhq.selenium/selenium-api
                               org.seleniumhq.selenium/selenium-support
                               org.eclipse.jetty/jetty-http
                               org.eclipse.jetty/jetty-io
                               org.eclipse.jetty/jetty-util]]
                 [org.seleniumhq.selenium/selenium-java "3.141.59"
                  :exclusions [org.seleniumhq.selenium/selenium-api
                               org.seleniumhq.selenium/selenium-support
                               org.eclipse.jetty/jetty-http
                               org.eclipse.jetty/jetty-io
                               org.eclipse.jetty/jetty-util]]
                 [org.seleniumhq.selenium/selenium-firefox-driver "3.141.59"]
                 [environ "1.1.0"]
                 [cheshire "5.8.0"]
                 [ring/ring-jetty-adapter "1.6.2"]]
  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]]
  :profiles
  {:dev {:source-paths ["dev"]
         :dependencies [[pjstadig/humane-test-output "0.8.2"]
                        [org.clojure/tools.namespace "0.2.11"]
                        [log4j "1.2.17"]]
         :plugins [[lein-cljfmt "0.5.6"]
                   [lein-codox "0.10.3"]]
         :injections [(require 'pjstadig.humane-test-output)
                      (pjstadig.humane-test-output/activate!)]}})
