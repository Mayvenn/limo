(ns limo.v2
  "Experimental. V2 API explorations."
  (:require [limo.java :as java]
            [limo.api :as v1]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import org.openqa.selenium.Dimension
           org.openqa.selenium.WebDriver
           org.openqa.selenium.WebElement
           org.openqa.selenium.By
           org.openqa.selenium.By$ByCssSelector
           org.openqa.selenium.support.ui.Select
           org.openqa.selenium.TakesScreenshot
           org.openqa.selenium.Keys
           org.openqa.selenium.remote.RemoteWebDriver
           org.openqa.selenium.chrome.ChromeDriver
           org.openqa.selenium.firefox.FirefoxDriver
           java.net.URL
           java.awt.image.BufferedImage
           java.io.ByteArrayInputStream
           javax.imageio.ImageIO))

;; What would be nice:
;;  - To move away from the classical clj-selenium api
;;    - Have global state be optional
;;    - Perhaps use the clj-selenium style of protocols
;;  - Better centralized behavior (instead of having to insert polling everywhere)
;;    - Logging activity
;;    - Polling
;;    - Scroll-before-do
;;    - Reload after a subset of actions...
;;  - To be more functional? (This is hard to justify with the large state
;;    machine that is a browser)
;;  - A less error-prone assertion mechanism

(defprotocol IDriver
  (selenium-driver [driver] "Returns the underlying selenium webdriver. Useful when using the selenium API directly, at the cost of losing any benefits IDriver provides.")
  (find-first-element [driver selector-or-element] "Find an element by selector")
  (find-elements [driver selector-or-elements] "Find elements by selector")
  (execute-script [driver js js-args] "Executes javascript on the page")
  (close [driver] "Closes the driver")
  (delete-cookies [driver] "Deletes cookies on the current domain")
  (switch-to-frame [driver selector-or-frame-element] "Switches the element querying to a given frame instead of the root document")
  (switch-to-main-page [driver] "Switches the element query to the root document, away from a frame")
  (switch-to-window [driver window-id] "Changes the driver's DOM queries toa given browser window")
  (current-window-size [driver])
  (set-current-window-size [driver size])
  (all-window-ids [driver] "Lists all the window ids controlled by a given driver.")
  (active-window-id [driver] "Returns the window id of the active window.")
  (read-logs! [driver log-type] "Retrieves logs of a given type from the browser being control by selenium.

  NOTE: The browser may discard the log information after the request to retrive
  the logs occurs. This means multiple calls to readonly-logs! can return different
  results.

    > (count (read-logs!)) => 5
    > (count (read-logs!)) => 0

  read-logs! is pretty low-level in comparison to most of the other limo apis.
  Considering using [[read-performance-logs-until-test-pass!]]
  ")
  (click [driver selector-or-element])
  (select-by-text [driver selector-or-element text-value])
  (select-by-value [driver selector-or-element value])
  (select-options [driver selector-or-element] "List all options for a select input")
  (send-keys [driver selector-or-element charseq])
  (tagname [driver selector-or-element])
  (inner-text [driver selector-or-element])
  (attribute [driver selector-or-element attrname])
  (refresh [driver] "Refreshes the current page")
  (navigate-to [driver url] "Loads given url")
  (current-url [driver] "Returns the current url of the page")

  (visible? [driver selector-or-element])
  (selected? [driver selector-or-element])
  (input-value [driver selector-or-element])
  (set-checkbox [driver selector-or-element checked?])

  (^BufferedImage take-screenshot [driver]))

(defn- element?
  "Helper function. A predicate that indicates if the given value is a selenium WebElement instance."
  [e]
  (instance? WebElement e))

(defn- ^By by
  "Creates a Selenium By instance (aka - a selenium element search query) to
  find an HTML element.

  In general, you shouldn't probably be calling this function directly. All limo
  functions call `element` which internally calls this function to find an
  element.

  This function accepts either a String, Selenium WebElement or a map containing
  one of the following keys to indicate how to find a DOM element:

   - :css or :css-selector => search by CSS selector
   - :id                   => search by element ID attribute
   - :xpath                => search by element xpath
   - :tag-name             => search by element tag name (eg - p, h1, div)
   - :link-text            => search by anchor text
   - :partial-link-text    => search by anchor text containing some text
   - :name                 => search by name attribute (eg - input fields)
   - :class-name           => search by a css class name the element has

  Examples:

    ;; return By instance to query by CSS
    > (by \"h1\")
    ;; Implies
    > (by {:css \"h1\"})
  "
  [s]
  (cond
    (element? s)           (By/id (.getId s))
    (:xpath s)             (By/xpath (:xpath s))
    (:id s)                (By/id (:id s))
    (:tag-name s)          (By/tagName (:tag-name s))
    (:link-text s)         (By/linkText (:link-text s))
    (:partial-link-text s) (By/partialLinkText (:partial-link-text s))
    (:name s)              (By/name (:name s))
    (:class-name s)        (By/className (:class-name s))
    (:css s)               (By$ByCssSelector. (:css s))
    (:css-selector s)      (By/cssSelector (:css-selector s))
    :else                  (By/cssSelector s)))

(defn- allow-backspace?
  "Returns a true if the given element can handle backspace keypresses.

  Hitting backspace on anything except selects, radios, checkboxes will cause
  the browser to go back to the previous page.
  "
  [driver e]
  (when e
    (case (tagname driver e)
      "select" false
      "input" (-> (attribute driver e "type")
                  #{"radio" "checkbox" "button"}
                  not)
      true)))

(defn clear-fields
  "Like [[fill-form]], but clears all the text contents of inputs by deleting its contents.

  NOTE: currently this is very naive presses backspace and delete N times, where
  N is the len of the text."
  [driver fields] ;- {selector function-or-string-to-enter}
  (doseq [[selector _] (filter (fn [[key value]] (string? value)) fields)]
    (when (allow-backspace? driver selector)
      (let [times (count (input-value driver selector))]
        (send-keys driver selector (vec (repeat times Keys/BACK_SPACE)))
        (send-keys driver selector (vec (repeat times Keys/DELETE)))))))

(defn- normalize-fields
  "Converts all string values that indicate typing text into functions"
  [driver fields] ;- {selector function-or-string} -> {selector function}
  (into {}
        (map (fn [[k v]] [k (if (string? v)
                              #(send-keys driver % v)
                              v)])
             fields)))

(defn- fill-form*
  ([driver fields] ;- {selector function-or-string-to-enter}
   (doseq [[selector action] (normalize-fields driver fields)]
     (action driver selector)))
  ([driver fields & more-fields]
   (fill-form* driver fields)
   (apply fill-form* driver more-fields)))

(defn fill-form
  [driver fields more-fields]
  (clear-fields driver fields)
  (doseq [f more-fields] (clear-fields driver f))
  (apply fill-form* driver fields more-fields))

(extend-type WebDriver
  IDriver
  (selenium-driver [driver] driver)
  (find-first-element [driver selector-or-element]
    (if (element? selector-or-element)
      selector-or-element
      (.findElement driver (by selector-or-element))))
  (find-elements [driver selector-or-element]
    (cond
      (element? selector-or-element)        [selector-or-element]
      (every? element? selector-or-element) selector-or-element
      :else                                 (.findElements driver (by selector-or-element))))
  (execute-script [driver js js-args]
    (.executeScript driver (str js) (into-array Object js-args)))
  (close [driver] (.quit driver))
  (delete-cookies [driver] (.. driver manage deleteAllCookies))
  (switch-to-frame [driver selector-or-frame-element] (.. driver (switchTo) (frame (find-first-element driver selector-or-frame-element))))
  (switch-to-main-page [driver] (.. driver switchTo defaultContent))
  (current-window-size [driver]
    (let [d (.. driver manage window getSize)]
      {:width  (.getWidth d)
       :height (.getHeight d)}))
  (set-current-window-size [driver {:keys [width height] :as dimensions-map}]
    (.. driver manage window (setSize (Dimension. width height))))
  (all-window-ids [driver] (seq (.getWindowHandles driver)))
  (active-window-id [driver] (.getWindowHandle driver))
  (read-logs! [driver log-type] (map java/log-entry->map (seq (.. driver manage logs (get (java/->log-type log-type))))))
  (click [driver selector-or-element] (.click (find-first-element driver selector-or-element)))
  (select-by-text [driver selector-or-element text-value]
    (doto (Select. (find-first-element driver selector-or-element))
      (.selectByVisibleText text-value)))
  (select-by-value [driver selector-or-element value]
    (doto (Select. (find-first-element driver selector-or-element))
      (.selectByValue value)))
  (select-options [driver selector-or-element]
    (let [select-elem (Select. (find-first-element driver selector-or-element))]
      (map (fn [el]
             {:value (.getAttribute el "value")
              :text  (.getText el)})
           (.getAllSelectedOptions select-elem))))
  (send-keys [driver selector-or-element s]
    (.sendKeys (find-first-element driver selector-or-element)
               (into-array CharSequence (if (vector? s) s [s]))))
  (tagname [driver selector-or-element]
    (.getTagName (find-first-element driver selector-or-element)))
  (inner-text [driver selector-or-element] (.getText (find-first-element driver selector-or-element)))
  (attribute [driver selector-or-element attrname]
    (condp = attrname
      :text    (inner-text driver selector-or-element)
      :classes (-> (find-first-element driver selector-or-element)
                   (.getAttribute "Class")
                   (or "")
                   (string/split #" +"))
      (let [attr             (name attrname)
            boolean-attrs    ["async", "autofocus", "autoplay", "checked", "compact", "complete",
                              "controls", "declare", "defaultchecked", "defaultselected", "defer",
                              "disabled", "draggable", "ended", "formnovalidate", "hidden",
                              "indeterminate", "iscontenteditable", "ismap", "itemscope", "loop",
                              "multiple", "muted", "nohref", "noresize", "noshade", "novalidate",
                              "nowrap", "open", "paused", "pubdate", "readonly", "required",
                              "reversed", "scoped", "seamless", "seeking", "selected", "spellcheck",
                              "truespeed", "willvalidate"]
            webdriver-result (.getAttribute (find-first-element driver selector-or-element) (name attr))]
        (if (some #{attr} boolean-attrs)
          (when (= webdriver-result "true")
            attr)
          webdriver-result))))
  (refresh [driver] (.. driver navigate refresh))
  (navigate-to [driver url] (.. driver navigate (to url)))
  (current-url [driver] (.getCurrentUrl driver))

  (visible? [driver selector-or-element] (.isDisplayed (find-first-element driver selector-or-element)))
  (selected? [driver selector-or-element] (.isSelected (find-first-element driver selector-or-element)))
  (input-value [driver selector-or-element] (attribute driver selector-or-element "value"))
  (set-checkbox [driver selector-or-element checked?]
    (when-not (= (selected? driver selector-or-element) checked?)
      (click driver selector-or-element)))

  (take-screenshot [driver]
    (let [driver ^TakesScreenshot driver
          output ^bytes (.getScreenshotAs driver (java/->output-type :bytes))]
      (ImageIO/read (ByteArrayInputStream. output)))))

(defn write-image
  ([^BufferedImage bi destination] (write-image bi destination "png"))
  ([^BufferedImage bi destination file-format] (ImageIO/write bi file-format (io/file destination))))

(defn form-selector-or-element [form]
  (let [third #(nth % 2)]
    (condp = (first form)
      `find-first-element      (third form)
      `find-elements           (third form)
      `execute-script          nil
      `close                   nil
      `delete-cookies          nil
      `switch-to-frame         (third form)
      `switch-to-main-page     nil
      `current-window-size     nil
      `set-current-window-size nil
      `all-window-ids          nil
      `active-window-id        nil
      `read-logs!              nil
      `click                   (third form)
      `select-by-text          (third form)
      `select-by-value         (third form)
      `select-options          (third form)
      `send-keys               (third form)
      `tagname                 (third form)
      `inner-text              (third form)
      `attribute               (third form)
      `refresh                 nil
      `navigate-to             nil
      `current-url             nil
      `visible?                (third form)
      `selected?               (third form)
      `input-value             (third form)
      `set-checkbox            (third form)
      `take-screenshot         nil)))

(defmacro ^{:style/indent :defrecord} defderiveddriver
  [name
   [underlying-driver & _ :as record-fields]
   {:keys [every-form] :or {every-form `(fn [ctx form] form)} :as options}
   & overrides]

  (let [driver              (gensym "driver")
        selector-or-element (gensym "selector-or-element")
        js                  (gensym "js")
        js-args             (gensym "js-args")
        log-type            (gensym "log-type")
        attrname            (gensym "attrname")
        url                 (gensym "url")
        fields              (gensym "fields")
        more-fields         (gensym "more-fields")
        s                   (gensym "s")
        checked?            (gensym "checked?")
        value               (gensym "value")
        text-value          (gensym "text-value")
        dimensions-map      (gensym "dimensions-map")
        ctx                 {:driver            driver
                             :other-fields      record-fields}
        every               (partial (eval every-form) ctx)
        method-pair         (fn [form]
                              [(.getName ^clojure.lang.Symbol (first form))
                               (cons
                                (symbol (.getName ^clojure.lang.Symbol (first form)))
                                (rest form))])

        default-impls (into {}
                            (map method-pair)
                            `((selenium-driver [~driver] ~underlying-driver)
                              (find-first-element [~driver ~selector-or-element] ~(every `(find-first-element ~underlying-driver ~selector-or-element)))
                              (find-elements [~driver ~selector-or-element] ~(every `(find-elements ~underlying-driver ~selector-or-element)))
                              (execute-script [~driver ~js ~js-args] ~(every `(execute-script ~underlying-driver ~js ~js-args)))
                              (close [~driver] ~(every `(close ~underlying-driver)))
                              (delete-cookies [~driver] ~(every `(delete-cookies ~underlying-driver)))
                              (switch-to-frame [~driver ~selector-or-element] ~(every `(switch-to-frame ~underlying-driver ~selector-or-element)))
                              (switch-to-main-page [~driver] ~(every `(switch-to-main-page ~underlying-driver)))
                              (current-window-size [~driver] ~(every `(current-window-size ~underlying-driver)))
                              (set-current-window-size [~driver ~dimensions-map] ~(every `(set-current-window-size ~underlying-driver ~dimensions-map)))
                              (all-window-ids [~driver] ~(every `(all-window-ids ~underlying-driver)))
                              (active-window-id [~driver] ~(every `(active-window-id ~underlying-driver)))
                              (read-logs! [~driver ~log-type] ~(every `(read-logs! ~underlying-driver ~log-type)))
                              (click [~driver ~selector-or-element] ~(every `(click ~underlying-driver ~selector-or-element)))
                              (select-by-text [~driver ~selector-or-element ~text-value] ~(every `(select-by-text ~underlying-driver ~selector-or-element ~text-value)))
                              (select-by-value [~driver ~selector-or-element ~value] ~(every `(select-by-text ~underlying-driver ~selector-or-element ~value)))
                              (select-options [~driver ~selector-or-element] ~(every `(select-options ~underlying-driver ~selector-or-element)))
                              (send-keys [~driver ~selector-or-element ~s] ~(every `(send-keys ~underlying-driver ~selector-or-element ~s)))
                              (tagname [~driver ~selector-or-element] ~(every `(tagname ~underlying-driver ~selector-or-element)))
                              (inner-text [~driver ~selector-or-element] ~(every `(inner-text ~underlying-driver ~selector-or-element)))
                              (attribute [~driver ~selector-or-element ~attrname] ~(every `(attribute ~underlying-driver ~selector-or-element ~attrname)))
                              (refresh [~driver] ~(every `(refresh ~underlying-driver)))
                              (navigate-to [~driver ~url] ~(every `(navigate-to ~underlying-driver ~url)))
                              (current-url [~driver] ~(every `(current-url ~underlying-driver)))

                              (visible? [~driver ~selector-or-element] ~(every `(visible? ~underlying-driver ~selector-or-element)))
                              (selected? [~driver ~selector-or-element] ~(every `(selected? ~underlying-driver ~selector-or-element)))
                              (input-value [~driver ~selector-or-element] ~(every `(input-value ~underlying-driver ~selector-or-element)))
                              (set-checkbox [~driver ~selector-or-element ~checked?] ~(every `(set-checkbox ~underlying-driver ~selector-or-element ~checked?)))

                              (take-screenshot [~driver] ~(every `(take-screenshot ~underlying-driver)))))
        overridden-impls (into {}
                               (map method-pair)
                               overrides)
        final-impls      (merge default-impls overridden-impls)]
    `(defrecord ~name ~record-fields
       IDriver
       ~@(into []
           (map second)
           final-impls))))

;; TODO: rewrite this....
(defmacro poll [driver form]
  `(v1/wait-until (selenium-driver ~driver) (fn [] (boolean ~form)) v1/*default-timeout* v1/*default-interval*))

(defn- remote-addr [{:keys [remote-address]}]
  (or remote-address
      (get (System/getenv) "LIMO_REMOTE_SELENIUM_HUB_URL")))

(defmulti create-driver (fn dispatch [type options] type))
(defmethod create-driver :remote create-driver--remote
  [type {:keys [remote-address capabilities] :as opt}]
  (if-let [hub-url (remote-addr opt)]
    (RemoteWebDriver. (str hub-url) (java/->capabilities capabilities))
    (RemoteWebDriver. (java/->capabilities capabilities))))

(defmethod create-driver :chrome create-driver--chrome
  [type {:keys [capabilities]}]
  (ChromeDriver. (java/->capabilities (or capabilities :chrome))))

(defmethod create-driver :chrome/headless create-driver--chrome-headless
  [type _]
  (ChromeDriver. (java/->capabilities :chrome/headless)))

(defmethod create-driver :firefox create-driver--firefox
  [type {:keys [capabilities]}]
  (FirefoxDriver. (java/->capabilities (or capabilities :firefox))))

(defmethod create-driver :remote/chrome create-driver--remote-or-chrome
  [type {:keys [remote-address capabilities] :as opt}]
  (if-let [hub-url (remote-addr opt)]
    (RemoteWebDriver. (URL. (str hub-url)) (java/->capabilities (or capabilities :chrome)))
    (ChromeDriver. (java/->capabilities (or capabilities :chrome)))))

(defn- log-queries [ctx form]
  `(do
     (when-let [query# ~(form-selector-or-element form)]
       (println ~(name (first form)) query#))
     ~form))

(defderiveddriver PollingDriver [driver timeout interval]
  {:every-form log-queries})

