(ns limo.v2-test
  (:require [limo.v2 :as v2]
            [clojure.test :refer [deftest is testing]]))


(def header-text "httpbin.org\n 0.9.2 ")

(deftest opening-chrome-with-polling-driver
  (with-open [d (v2/->PollingDriver (v2/create-driver :remote/chrome nil)
                                    3000
                                    500)]
    (is d "driver wasn't created properly")
    (v2/navigate-to d "https://httpbin.org")
    (v2/click d "a[href='/forms/post']")
    (is (v2/poll d
                 (= (v2/inner-text d "label") "Customer name:"))
        (pr-str (v2/inner-text d "label")))))

#_
(deftest opening-a-browser
  (with-fresh-browser create-chrome
    (to "https://httpbin.org")
    (click "a[href='/forms/post']")
    (is (v2/poll (= (v2/text d "label") "Customer name:"))
        (pr-str (text "label")))))

#_
(deftest multiple-windows
  (with-fresh-browser create-chrome
    (to "https://httpbin.org")
    (is (text= "h2" header-text)
        (pr-str (text "h2")))
    (execute-script *driver* "window.open('https://httpbin.org/get');")
    (is (= 2 (count (all-windows))))
    (switch-to-window (last (all-windows)))
    (is (contains-text? "body" "text/html,application/xhtml+xml"))
    (switch-to-window (first (all-windows)))
    (is (text= "h2" header-text)
        (pr-str (text "h2")))))

#_
(deftest without-implicit-driver
  (set-driver! nil) ;; just to be sure
  (let [driver (create-chrome)]
    (to driver "https://httpbin.org")
    (is (text= driver "h2" header-text) (pr-str (text driver "h2")))
    (execute-script driver "window.open('https://httpbin.org/get');")
    (is (= 2 (count (all-windows driver))))
    (switch-to-window driver (last (all-windows driver)))
    (is (contains-text? driver "body" "text/html,application/xhtml+xml"))
    (switch-to-window driver (first (all-windows driver)))
    (is (text= driver "h2" header-text) (pr-str (text driver "h2")))
    (click driver "a[href='/forms/post']")
    (is (text= driver "label" "Customer name:"))))

#_
(deftest network
  (with-fresh-browser (partial create-chrome (doto (->capabilities :chrome)
                                               ;; for chromedrivers <76.x.x
                                               (set-logging-capability {:browser     :all
                                                                        :performance :all
                                                                        :profiler    :all})
                                               ;; for chromedrivers >=76.x.x
                                               (.setCapability "goog:loggingPrefs"
                                                               (map->logging-preferences {:browser     :all
                                                                                          :performance :all
                                                                                          :profiler    :all}))))
    (to "https://httpbin.org")
    (execute-script *driver* "var r = new XMLHttpRequest(); r.open(\"GET\", \"/get\", null); r.send();")
    (let [logs (atom [])]
      (read-performance-logs-until-test-pass! [logs]
                                              (is (first (filter (comp #{"Network.requestWillBeSent"} :method :message :message) @logs)))))))

#_
(deftest test-various-by-locators
  (with-fresh-browser create-chrome
    (to "https://httpbin.org")
    (click-when-visible "#operations-tag-Anything")
    (click-when-visible {:partial-link-text "anything"})
    (is (= (current-url) "https://httpbin.org/#/Anything/delete_anything"))

    (to "https://httpbin.org")
    ;; Note: click on link with label 'GET /images'
    (click-when-visible "#operations-tag-Images")
    (click-when-visible {:xpath "//*[@id=\"operations-Images-get_image\"]"})
    (is (= (current-url) "https://httpbin.org/#/Images/get_image"))

    (to "https://httpbin.org")
    ;; Note: click on the non secure link using :css-selector locator
    (click-when-visible "#operations-tag-Cookies")
    (click-when-visible {:css-selector ".opblock-tag-section.is-open > div:nth-child(2)  > #operations-Cookies-get_cookies"})
    (is (= (current-url) "https://httpbin.org/#/Cookies/get_cookies"))

    (to "https://httpbin.org")
    ;; Note: click on the secure link using :css locator (the other selector for css)
    (click-when-visible "#operations-tag-Cookies")
    (click-when-visible {:css ".opblock-tag-section.is-open > div:nth-child(2)  > #operations-Cookies-get_cookies"})
    (is (= (current-url) "https://httpbin.org/#/Cookies/get_cookies"))))

#_
(deftest send-keys-drops-nils
  (testing "Newer selenium versions rejects empty CharSequences"
    (with-fresh-browser create-chrome
      (to "http://httpbin.org/forms/post")
      ;; fill-form will implicitly send empty sequence by deleting nothing
      (fill-form {"[name=custname]" "yo"})
      (is (value= "[name=custname]" "yo"))

      (fill-form {"[name=custname]" ""})
      (is (value= "[name=custname]" "")))))

#_
(deftest implicit-scrolling-only-scrolls-if-needed
  (with-fresh-browser create-chrome
    (window-resize {:width 780 :height 200})
    (to "http://httpbin.org/forms/post")

    (testing "does not scroll if on screen"
      (#'limo.api/on-screen? api/*driver* "[name=size][value=small]")

      (click "[name=size][value=small]")
      (is (zero? (.doubleValue (execute-script *driver* "return window.scrollY")))))
    (testing "scrolls if the element is not on screen, attempting to center the element"
      (click "[name=topping][value=mushroom]")
      (is (< 100 (.doubleValue (execute-script *driver* "return window.scrollY")))
          "window did not scroll")
      (is (< 100 (.doubleValue (execute-script *driver* "return document.querySelector('[name=topping][value=mushroom]').getBoundingClientRect().top")))))))
