(ns limo.api-test
  (:require [clojure.test :refer :all]
            [limo.api :refer :all]
            [limo.driver :refer :all]
            [limo.test :refer :all]
            [limo.java :refer :all]
            [limo.api :as api]))

(comment
  ;; eval this form to verify the ability to disable logging
  (let [^org.apache.log4j.Logger l (org.apache.log4j.Logger/getLogger "limo")]
    (.removeAllAppenders l)
    (.addAppender l (org.apache.log4j.varia.NullAppender.))))

(def header-text "httpbin.org\n 0.9.2 ")

(defn remote-browser? []
  (boolean (get (System/getenv) "LIMO_REMOTE_SELENIUM_HUB_URL")))

(defn create-browser
  ([]
   (if-let [url (get (System/getenv) "LIMO_REMOTE_SELENIUM_HUB_URL")]
     (create-remote url (->capabilities :chrome))
     (create-chrome)))
  ([capabilities]
   (if-let [url (get (System/getenv) "LIMO_REMOTE_SELENIUM_HUB_URL")]
     (create-remote url (->capabilities capabilities))
     (create-chrome capabilities))))

(deftest opening-a-browser
  (with-fresh-browser create-browser
    (to "https://httpbin.org")
    (Thread/sleep 1000) ;; the click handler is sometimes late...
    (click "a[href='/forms/post']")
    (is (text= "label" "Customer name:")
        (pr-str (text "label")))))

(deftest multiple-windows
  (with-fresh-browser create-browser
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

(deftest without-implicit-driver
  (set-driver! nil) ;; just to be sure
  (let [driver (create-browser)]
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

(deftest network
  (with-fresh-browser (partial create-browser (doto (->capabilities :chrome)
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

(deftest test-various-by-locators
  (with-fresh-browser create-browser
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

(deftest send-keys-drops-nils
  (testing "Newer selenium versions rejects empty CharSequences"
    (with-fresh-browser create-browser
      (to "http://httpbin.org/forms/post")
      ;; fill-form will implicitly send empty sequence by deleting nothing
      (fill-form {"[name=custname]" "yo"})
      (is (value= "[name=custname]" "yo"))

      (fill-form {"[name=custname]" ""})
      (is (value= "[name=custname]" "")))))

(deftest implicit-scrolling-only-scrolls-if-needed
  (with-fresh-browser create-browser
    (window-resize {:width 780
                    :height 375})
    (to "http://httpbin.org/forms/post")

    (testing "does not scroll if on screen"
      (is (#'limo.api/on-screen? api/*driver* "[name=size][value=small]"))

      (click "[name=size][value=small]")
      (let [value (execute-script *driver* "return window.scrollY")]
        (is (zero? (.doubleValue value))
            (pr-str value))))
    (testing "scrolls if the element is not on screen, attempting to center the element"
      (click "[name=topping][value=mushroom]")
      (is (< 20 (.doubleValue (execute-script *driver* "return window.scrollY")))
          "window did not scroll")
      (is (< 20 (.doubleValue (execute-script *driver* "return document.querySelector('[name=topping][value=mushroom]').getBoundingClientRect().top")))))))
