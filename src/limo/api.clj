(ns limo.api
  "The core API wrapper around selenium webdrivers"
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [limo.java :as java]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]]
            [cheshire.core :as json]
            [clojure.set :as set])
  (:import java.util.concurrent.TimeUnit
           org.openqa.selenium.firefox.FirefoxDriver
           org.openqa.selenium.By
           org.openqa.selenium.By$ByCssSelector
           org.openqa.selenium.Dimension
           [org.openqa.selenium
            Keys
            StaleElementReferenceException
            TimeoutException
            NoSuchElementException]
           org.openqa.selenium.OutputType
           org.openqa.selenium.TakesScreenshot
           org.openqa.selenium.WebDriver
           org.openqa.selenium.WebElement
           org.openqa.selenium.WebDriverException
           org.openqa.selenium.ElementClickInterceptedException
           org.openqa.selenium.ElementNotInteractableException
           org.openqa.selenium.NoSuchElementException
           org.openqa.selenium.interactions.Actions
           [org.openqa.selenium.support.ui
            ExpectedCondition
            ExpectedConditions
            WebDriverWait]
           org.openqa.selenium.support.ui.Select))

(def ^:dynamic *driver*
  "The implied selenium WebDriver instance to use when invoking functions with api calls.

  All API functions can explicitly accept the WebDriver as the first argument.
  Otherwise if that argument is excluded, then this dynamically bounded var is
  used instead.

  Example:

    > (limo.api/click \"#button\")
    ;; becomes
    > (limo.api/click *driver* \"#button\")

  Defaults to `nil`. Use [[set-driver!]] as a way to quickly set this variable.
  "
  nil)

(def ^:dynamic *default-timeout*
  "The default timeout in milliseconds until limo gives up attempting to try an action.
  Defaults to 15 seconds (15000).

  This value is used for wait-* set of functions which most other function calls
  rely upon.

  The default value is generous enough to try and cover a variety of machine
  speeds, but you may find value in tweaking this parameter when checking for a
  negative state (eg - verifying that a checkbox isn't checked).
  "
  15000) ;; msec

(def ^:dynamic *default-interval*
  0)

(def ^:dynamic *ignored-exceptions*
  "A sequence of exception classes to ignore and retry for polling via [[wait-until]]."
  [StaleElementReferenceException
   ElementClickInterceptedException
   NoSuchElementException
   TimeoutException
   ElementNotInteractableException])

;; Internal to wait-for to prevent nesting poll loops, which creates flakier builds.
(def ^:private ^:dynamic *is-waiting* false)

(def ^:private ^:dynamic *ignore-nested-wait-exception* false)

(defn set-driver!
  "Sets the current implied active selenium WebDriver ([[*driver*]]).

  Note: (set-driver! nil) is a no-op.
  "
  [d]
  (when d
    (alter-var-root #'*driver* (constantly d))))

;; Helpers

(defmacro narrate [msg & args]
  `(when-let [m# ~msg]
     (log/info m# ~@args)))

(defn- wrap-narration [f msg]
  (fn [& args]
    (narrate msg (map pr-str args))
    (apply f args)))

(defn- lower-case [s]
  (string/lower-case (str s)))

(defn- case-insensitive= [s1 s2]
  (= (lower-case s1) (lower-case s2)))

(defn- join-paths [& paths]
  (apply str (interpose "/" paths)))

;; Elements

(defn element?
  "Helper function. A predicate that indicates if the given value is a selenium WebElement instance."
  [e]
  (instance? WebElement e))

(defn ^By by
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

(defn ^WebElement element
  "Returns a selenium WebElement of a selector that [[by]] accepts.

  If selector-or-element is an [[element?]], then the value is simply returned.

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([selector-or-element] (element *driver* selector-or-element))
  ([driver selector-or-element]
   (if (element? selector-or-element)
     selector-or-element
     (.findElement driver (by selector-or-element)))))

(defn elements
  "Returns a sequence of WebElement instances that match the selector that
  [[by]] accepts.

  If selector-or-element is an [[element?]], then the value is simply returned.

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([selector-or-elements] (elements *driver* selector-or-elements))
  ([driver selector-or-elements]
   (cond
     (element? selector-or-elements) [selector-or-elements]
     (seq? selector-or-elements) selector-or-elements
     :else (.findElements driver (by selector-or-elements)))))

(defn exists?
  "Returns a boolean indicates if the given selector (that [[by]] accepts)
  matches an element that is not the current page the selenium browser is
  viewing.

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([selector-or-element] (exists? *driver* selector-or-element))
  ([driver selector-or-element]
   (try
     (not (= nil (element driver selector-or-element)))
     (catch org.openqa.selenium.NoSuchElementException _ false))))

;; Polling / Waiting

(defn ^:private wait-until* ;; TODO(jeff): promote to replace wait-until fn
  ([pred] (wait-until* pred {}))
  ([pred options]
   (let [{:keys [driver timeout interval poll? suppress-non-poll-exception? ignored-exceptions]
          :or   {driver                       *driver*
                 timeout                      *default-timeout*
                 interval                     *default-interval*
                 poll?                        *is-waiting*
                 suppress-non-poll-exception? *ignore-nested-wait-exception*
                 ignored-exceptions           *ignored-exceptions*}}
         options]
     (if poll?
       (if suppress-non-poll-exception?
         (pred)
         (or (pred) (throw (StaleElementReferenceException. "Inside another wait-until. Forcing retry."))))
       (binding [*is-waiting* true]
         (let [return-value (atom nil)
               wait         (WebDriverWait. driver (/ timeout 1000) interval)]
           (try
             (.ignoreAll wait ignored-exceptions)
             (.until wait (proxy [ExpectedCondition] []
                            (apply [d] (reset! return-value (pred)))))
             @return-value
             (catch TimeoutException te
               (binding [*ignore-nested-wait-exception* true]
                 (pred))))))))))

(defn wait-until
  "Runs a given predicate pred repeatedly until a timeout occurs or pred returns
  a truthy value.

  This is usually called by other limo APIs unless IMMEDIATE is indicated in the
  docs.

  Parameters:
   - `pred` can return a value, and that becomes the return value of wait-until.
   - `timeout` in the time in milliseconds to wait for. Uses
     [[*default-timeout*]] if not explicitly specified.
   - `interval` is the time in milliseconds between calling `pred`. Defaults to
     0 because [[implicit-wait]] is probably what you want to set.
   - `driver` is an alternative WebDriver instance to use other than [[*driver*]].

  Waits & polls:
    There are 3-values that dicate polling in Limo: timeout interval, sleep
    interval, and browser wait interval.

     - Timeout interval is the maximum time in Selenium (& Limo) which an action
       can take in its entirety.
     - Sleep interval is the Thead.sleep call Selenium calls inbetween calls to
       `pred` returning a falsy value.
     - Browser wait interval (aka - ImplicitlyWait) is the maximum time the browser
       will wait for an element that matches to appear.

    In our experience, the browser wait interval is usually what keeps actions
    held. An action like \"click this button\" relies on the button existing
    before it can be clicked.

    [[*ignored-exceptions*]] are captured and indicate a falsy return
    value of `pred`. Other exceptions will be rethrown.
  "
  ([pred] (wait-until* pred))
  ([pred timeout] (wait-until* pred {:timeout timeout}))
  ([pred timeout interval] (wait-until* pred {:timeout timeout
                                              :interval interval}))
  ([driver pred timeout interval] (wait-until* pred {:driver driver
                                                     :timeout timeout
                                                     :interval interval})))

(defn wait-until-clickable
  "A specialized version of wait-until that waits until an element is clickable."
  ([selector] (wait-until-clickable *driver* selector *default-timeout*))
  ([driver selector timeout]
   (wait-until* driver
                #(.isDisplayed (element selector))
                timeout
                *default-interval*)))

(defmacro wait-for
  "A specialized version of wait-until that includes narration (printing to stdout) the action that is taken."
  [driver narration & body]
  (if (empty? narration)
    `(wait-until ~driver (fn [] ~@body) *default-timeout* *default-interval*)
    `(do
       (log/info (str ~@narration))
       (wait-until ~driver (fn [] ~@body) *default-timeout* *default-interval*))))

(defmacro wait-for-else
  "Like wait-for, but has a default return value if the waiting predicate fails."
  [driver narration default-value & body]
  `(try
     ~(if (empty? narration)
        `(wait-until ~driver (fn [] ~@body) *default-timeout* *default-interval*)
        `(do
           (narrate ~@narration)
           (wait-until ~driver (fn [] ~@body) *default-timeout* *default-interval*)))
     (catch TimeoutException te#
       ~default-value)))

(defn implicit-wait
  "Sets the driver's implicit wait interval. The implicit wait interval is poll
  interval is how much a browser will wait.

  There are two kinds of waits:

   - The test suite polls & waits (see [[*default-interval*]],
    [[*default-timeout*]], [[wait-until]], [[wait-for]], [[wait-until-clickable]],
    [[wait-for-else]]). Here the test suite / limo is responsible for polling
    and asking the browser to see if an element exists.
   - The browser waits for an element to appear. This is what [[implicit-wait]] configures.

  For example, if we ask the browser to click on #button, but it isn't
  immediately available, the browser will use the implicit-wait value to
  internally wait up to the given time until returning an element not found
  error.

  Read Selenium's explaination of waits for another perspective of the same thing:
  http://www.seleniumhq.org/docs/04_webdriver_advanced.jsp#explicit-and-implicit-waits
  "
  ([timeout] (implicit-wait *driver* timeout))
  ([driver timeout] (.. driver manage timeouts (implicitlyWait timeout TimeUnit/MILLISECONDS))))

;; Act on Driver

(defn execute-script
  "Evaluates a given javascript string on the page.

  Generally you want to control most of your interacts via the supported browser
  operations, but sometimes it's needed to run some javascript on the page
  directly - like activating a verbose logging mode, or forcing one into a
  specific A/B test.

  Parameters:
   - `driver` is the selenium driver to use
   - `js` is the javascript string to eval on the page.
   - `js-args` is the variadic arguments of values to pass into the eval
     function. These values are limited to what can be translated to javascript,
     which are the following:
     - numbers
     - booleans
     - strings
     - WebElements (objects returned via [[element]])
     - lists of any above types

  Note:
    The javascript script string is executed in anonymous closure like this:

      (function(){ $JS })($JS-ARGS);

    which means you'll need to use `arguments` in `js` to access arguments pass
    through from clojure to javascript.

  Returns:
    The return value is whatever the return value of the javascript eval
    expression, which are also constrained to the same types that can be
    translated as a js-args value.

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  [driver js & js-args]
  (.executeScript driver (str js) (into-array Object js-args)))

(defn quit
  "Closes the driver, which implies closing all browser windows the driver has created.

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([] (quit *driver*))
  ([driver] (.quit driver)))

(defn delete-all-cookies
  "Deletes all cookies associated with the page the browser is currently on.

  There is no way in Selenium's APIs to clear all cookies in a driver without
  re-creating the driver. If you wish to reuse a driver, you must navigate to
  every domain and call this function to clear cookies.

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([] (delete-all-cookies *driver*))
  ([driver] (.. driver manage deleteAllCookies)))

(defn switch-to-frame
  "Changes the driver's DOM queries to target a given frame or iframe.

  Drivers do no walk through child frames/iframes' DOM elements. This function
  allows all subsequent calls (eg - [[element]]) will target elements inside
  that frame.

  See [[switch-to-main-page]] to restore querying against the page elements.
  See [[switch-to-window]] to query against windows.
  "
  ([frame-element] (switch-to-frame *driver* frame-element))
  ([driver frame-element]
   (exists? driver frame-element {:wait? true})
   (.. driver (switchTo) (frame (element driver frame-element)))))

(defn switch-to-main-page
  "Changes the driver's DOM queries to target the main page body.

  Drivers do no walk through child frames/iframes' DOM elements. This function
  allows all subsequent calls (eg - [[element]]) will target elements directly
  on the page.

  See [[switch-to-frame]] to query against iframes / frames.
  See [[switch-to-window]] to query against windows.

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([] (switch-to-main-page *driver*))
  ([driver] (.. driver switchTo defaultContent)))

(defn all-windows
  "Returns a sequence of all window ids (strings) that refer to specific browser
  windows the driver controls.

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([] (all-windows *driver*))
  ([driver] (seq (.getWindowHandles driver))))

(defn switch-to-window
  "Changes the driver's DOM queries to target a given browser window.

  See [[switch-to-frame]] to query against iframes / frames.
  See [[all-windows]] to list all window ids
  See [[active-window]] to get the current window id
  "
  ([window-handle] (switch-to-window *driver* window-handle))
  ([driver window-handle]
   (wait-for driver [(format "switch-to-window %s" (pr-str window-handle))]
             (some (partial = window-handle) (all-windows driver)))
   (.. driver (switchTo) (window window-handle))))

(defn active-window
  "Returns the window id (string) of the current window that the driver is focused on.

  The active-window is the window where (switch-to-window (active-window)) is a no-op.

  See [[switch-to-window]] to switch focused window.
  See [[all-windows]] to list all window ids
  See [[active-window]] to get the current window id

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([] (active-window *driver*))
  ([driver] (.getWindowHandle driver)))

(defmacro in-new-window
  "Creates a temporary new browser window that `do-body` runs inside."
  ([opts action do-body] `(in-new-window *driver* ~opts ~action ~do-body))
  ([driver {:keys [auto-close?]} action do-body]
   `(let [prev-handle# (active-window ~driver)
          old-handles# (all-windows ~driver)]
      ~action
      (wait-until #(> (count (all-windows ~driver))
                      (count old-handles#)))
      (switch-to-window ~driver
                        (first (set/difference (set (all-windows ~driver))
                                               old-handles#)))
      ~do-body
      (if ~auto-close?
        (wait-until #(= (count (set (all-windows ~driver)))
                        (count old-handles#)))
        (.close ~driver))
      (switch-to-window ~driver prev-handle#))))

(defn read-logs!
  "Retrieves logs of a given type from the browser being control by selenium.

  NOTE: The browser may discard the log information after the request to retrive
  the logs occurs. This means multiple calls to readonly-logs! can return different
  results.

    > (count (read-logs!)) => 5
    > (count (read-logs!)) => 0

  read-logs! is pretty low-level in comparison to most of the other limo apis.
  Considering using [[read-performance-logs-until-test-pass!]]
  "
  ([log-type-kw] (read-logs! *driver* log-type-kw))
  ([driver log-type-kw]
   (->> (.. driver
            manage
            logs
            (get (java/->log-type log-type-kw)))
        seq
        (map java/log-entry->map))))

(defn read-json-logs!
  "Identical read-logs!, but parses the message body as JSON.

  NOTE: the same limitations as read-logs! applies: that is, that the browser
  may discard the log information after the request to retrive the logs occurs.

  This is known to be useful with Chrome's performance logs to get network and
  rendering information. Chrome's performance log data is encoded in JSON.

  read-json-logs! is pretty low-level in comparison to most of the other limo apis.
  Considering using [[read-performance-logs-until-test-pass!]]
  "
  ([log-type-kw] (read-json-logs! *driver* log-type-kw))
  ([driver log-type-kw]
   (->> (read-logs! driver log-type-kw)
        (map (fn [m] (update m :message #(json/parse-string % true)))))))

;; Act on Element

(defn ^:private js-resolve [selector-or-element]
  (if (string? selector-or-element)
    "document.querySelector(arguments[0])"
    "arguments[0]"))

(defn ^:private on-screen? [driver selector-or-element]
  ;;  An element, relative to window
  ;; -----------------------------
  ;;   |<pos.top   |
  ;;   |           |
  ;; ++++++++++++++|++++
  ;; +             |   +
  ;; +             |   +
  ;; +  pos.bottom>|   +
  ;; +             |   +
  ;; +             |   +
  ;; +             |   +
  ;; +             |   +
  ;; +++++++++++++++++++
  ;;
  (try
    (.booleanValue
     (execute-script driver (str "var pos = " (js-resolve selector-or-element)
                                 ".getBoundingClientRect();"
                                 "return (0 <= pos.top && pos.top <= window.innerHeight) || (0 <= pos.bottom && pos.bottom <= window.innerHeight);")
                     (if (string? selector-or-element)
                       selector-or-element
                       (element selector-or-element))))
    (catch WebDriverException e
      (.printStackTrace e)
      ;; This occurs if the javascript fails to resolve an element, in which it throws:
      ;; org.openqa.selenium.WebDriverException: unknown error: Cannot read property 'getBoundingClientRect' of null
      false)))

(defn scroll-to
  "Scrolls the browser to a given element so that it visible on the screen.

  NOTE:
    This calls the underlying DOM API, Element.scrollIntoView(). See MDN for more information
    https://developer.mozilla.org/en-US/docs/Web/API/Element/scrollTo

  Parameters:
    `behavior`: how should the scrolling to element behave
      - 'auto' indicates instantaneous scrolling to element. This is the default value.
      - 'smooth' animates the scrolling from the current location to the target
                 element. This may be better at triggering InteractionObservers
                 at the cost of running time.
    `block`: where is the target element allowed to be vertically in the viewport
      - 'start' keeps the target element at the very top of the viewport (when possible)
      - 'center' keeps the target element at the center of the viewport (when
                 possible). This is the default to minimize sticky top/bottom
                 elements from overlapping with the target element.
      - 'end' keeps the target element at the end of the view port (when possible)
      - 'nearest' keeps the target element either at the start or end, whichever is closest (when possible)
    `inline`: where is the target element allowed to be horizontally in the viewport
      - 'start' keeps the target element at the very top of the viewport (when possible)
      - 'center' keeps the target element at the center of the viewport (when
                 possible). This is the default to minimize sticky top/bottom
                 elements from overlapping with the target element.
      - 'end' keeps the target element at the end of the view port (when possible)
      - 'nearest' keeps the target element either at the start or end, whichever is closest (when possible)
    `force?`: scroll-to defaults to not doing anything if the element is already
              in the viewport. Setting this to true will override that behavior
              and always attempt to scroll the element.

  WARNING:
    Input parameters must conform to [[execute-script]] limitations."
  ([selector-or-element] (scroll-to *driver* selector-or-element nil))
  ([driver selector-or-element] (scroll-to driver selector-or-element nil))
  ([driver selector-or-element {:keys [behavior block inline force?]
                                :or   {behavior "auto"
                                       block    "center"
                                       inline   "center"
                                       force?   false}}]
   (let [behavior (str behavior)
         block    (str block)
         inline   (str inline)]
     (wait-until*
      #(and (exists? driver selector-or-element)
            (or
             (and (not force?)
                  (on-screen? driver selector-or-element))
             (try
               (execute-script driver (str (js-resolve selector-or-element) ".scrollIntoView({behavior: arguments[1], block: arguments[2], inline: arguments[3]}); ")
                               (if (string? selector-or-element)
                                 selector-or-element
                                 (element selector-or-element))
                               behavior
                               block
                               inline)
               true
               (catch WebDriverException e
                 ;; This occurs if the javascript fails to resolve an element, in which it throws:
                 ;; org.openqa.selenium.WebDriverException: unknown error: Cannot read property 'scrollIntoView' of null
                 false))))
      {:driver driver}))
   selector-or-element))

(defn click
  "Clicks on a given element.

  Unlike (.click (element *driver* selector-or-element)), this click does a few more operations:
   - Attempts to scroll the viewport to the element
   - Waits for the element to be clickable (see [[wait-until-clickable]])
   - Then clicks the element
  "
  ([selector-or-element] (click *driver* selector-or-element))
  ([driver selector-or-element] (click driver selector-or-element nil))
  ([driver selector-or-element {:keys [scroll-options scroll?]
                                :or   {scroll? true}}]
   (wait-for driver ["click" selector-or-element]
             (when scroll?
               (scroll-to driver selector-or-element scroll-options))
             (when (.isDisplayed (element driver selector-or-element))
               (.click (element driver selector-or-element))
               true))))

(def submit
  "Alias to [[click]]. Typically reads nice when referring to submit buttons."
  click)

(def toggle
  "Alias to [[click]]. Typically reads nice when referring to checkboxes"
  click)

(defn select-by-text
  "Selects a given option in a drop-down element by the user-visible text on the element.

  Useful if you know you want to select a given option that is visible on screen
  and its value changes more often that its display text.
  "
  ([selector-or-element value] (select-by-text *driver* selector-or-element value))
  ([driver selector-or-element value]
   (wait-for driver ["select-by-text" selector-or-element value]
             (scroll-to driver selector-or-element)
             (doto (Select. (element driver selector-or-element))
               (.selectByVisibleText value))
             selector-or-element)))

(defn select-by-value
  "Selects a given option in a drop-down element by the server-provided value of the element.

  Useful if you know you want to select a given option that has a constant
  value, but may change its user-visible text more often.
  "
  ([selector-or-element value] (select-by-value *driver* selector-or-element value))
  ([driver selector-or-element value]
   (wait-for driver ["select-by-value" selector-or-element value]
             (scroll-to driver selector-or-element)
             (doto (Select. (element driver selector-or-element))
               (.selectByValue value))
             selector-or-element)))

(defn send-keys
  "Sends keypresses to a given element. Types on a given input field.

  Characters can be strings or vector of strings.
  "
  ([selector-or-element s] (send-keys *driver* selector-or-element s))
  ([driver selector-or-element s]
   ;; Newer selenium versions no longer allow empty CharSequences for sendKeys
   (when (not-empty s)
     (wait-for driver nil
               (.sendKeys (element driver selector-or-element)
                          (into-array CharSequence (if (vector? s) s [s])))
               true))))

(def input-text
  "Alias to [[send-keys]]. Sends keypresses to a given element. Types on a given input field.

  Characters can be strings or vector of strings.
  "
  send-keys)

;; Query Element

(defn tag
  "Returns an element's html tag name."
  ([selector-or-element] (tag *driver* selector-or-element))
  ([driver selector-or-element]
   (wait-for-else driver ["tag" selector-or-element] nil
                  (.getTagName (element driver selector-or-element)))))

(defn text
  "Returns an element's innerText."
  ([selector-or-element] (text *driver* selector-or-element))
  ([driver selector-or-element]
   (wait-for-else driver ["text" selector-or-element] ""
                  (.getText (element driver selector-or-element)))))

(defn attribute
  "Returns an element's attribute value for a given attribute name."
  ([selector-or-element attr] (attribute *driver* selector-or-element attr))
  ([driver selector-or-element attr]
   (if (= attr :text)
     (text selector-or-element)
     (let [attr (name attr)
           boolean-attrs ["async", "autofocus", "autoplay", "checked", "compact", "complete",
                          "controls", "declare", "defaultchecked", "defaultselected", "defer",
                          "disabled", "draggable", "ended", "formnovalidate", "hidden",
                          "indeterminate", "iscontenteditable", "ismap", "itemscope", "loop",
                          "multiple", "muted", "nohref", "noresize", "noshade", "novalidate",
                          "nowrap", "open", "paused", "pubdate", "readonly", "required",
                          "reversed", "scoped", "seamless", "seeking", "selected", "spellcheck",
                          "truespeed", "willvalidate"]
           webdriver-result (wait-for-else driver ["read-attribute" attr] nil
                                           (.getAttribute (element driver selector-or-element) (name attr)))]
       (if (some #{attr} boolean-attrs)
         (when (= webdriver-result "true")
           attr)
         webdriver-result)))))

(defn allow-backspace?
  "Returns a true if the given element can handle backspace keypresses.

  Hitting backspace on anything except selects, radios, checkboxes will cause
  the browser to go back to the previous page.
  "
  [e]
  (when e
    (case (tag e)
      "select" false
      "input" (-> (attribute e "type")
                  #{"radio" "checkbox" "button"}
                  not)
      true)))

(defn has-class
  "Returns a true if a given element has a class on it."
  [q class]
  (wait-for-else *driver* ["has-class"]
                 (-> (element q)
                     (.getAttribute "Class")
                     (or "")
                     (string/split #" +")
                     set
                     (get class)
                     boolean)))

(defn has-not-class
  "Returns a true if a given element does not have a class on it."
  [q class]
  (wait-for-else *driver* ["has-class"] (not (has-class q class))))

(defn window-size
  "Returns the current window's size

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([] (window-size *driver*))
  ([driver]
   (wait-for driver ["window-size"]
             (let [d (.. driver manage window getSize)]
               {:width (.getWidth d)
                :height (.getHeight d)}))))

(defn window-resize
  "Resizes the current window to the given dimensions.

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([dimensions-map] (window-resize *driver* dimensions-map))
  ([driver {:keys [width height] :as dimensions-map}]
   (narrate "window-resize")
   (-> driver
       .manage
       .window
       (.setSize (Dimension. width height)))))

(defn refresh
  "Refreshes/Reloads the current page the browser is on.

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([] (refresh *driver*))
  ([driver]
   (narrate "refresh")
   (-> driver .navigate .refresh)))

(defn to
  "Navigates to a given url. As if one types on the address bar.

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([^String url] (to *driver* url))
  ([driver ^String url]
   (narrate "to" url)
   (-> driver .navigate (.to url))))

(defn current-url
  "Returns the current url the browser is on.

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([] (current-url *driver*))
  ([^WebDriver driver]
   (narrate "current-url")
   (.getCurrentUrl driver)))

(defn options
  "Returns a sequence of all form value and visible text for a given drop-down"
  ([selector-or-element] (options *driver* selector-or-element))
  ([driver selector-or-element]
   (let [select-elem (Select. (element driver selector-or-element))]
     (map (fn [el]
            {:value (.getAttribute el "value")
             :text (.getText el)})
          (.getAllSelectedOptions select-elem)))))

;; modified queries from taxi to retry if StaleElementReferenceException is thrown
;; Any timeouts (aka - element not found) are converted to default return values

(defn visible?
  "Returns true if the given element is visible?"
  ([selector-or-element] (visible? *driver* selector-or-element))
  ([driver selector-or-element]
   (wait-for-else driver ["visible?" selector-or-element] false
                  (.isDisplayed (element driver selector-or-element)))))
(defn selected?
  "Returns true if the given element is selected (eg - checkbox)"
  ([selector-or-element] (selected? *driver* selector-or-element))
  ([driver selector-or-element]
   (wait-for-else driver ["selected?" selector-or-element] false
                  (.isSelected (element driver selector-or-element)))))

(defn value
  "Returns the input's value of a given input element"
  ([selector-or-element] (value *driver* selector-or-element))
  ([driver selector-or-element]
   (wait-for-else driver ["value" selector-or-element] ""
                  (.getAttribute (element driver selector-or-element) "value"))))

(defn invisible?
  "Returns true if the given element is invisible."
  ([selector-or-element] (invisible? *driver* selector-or-element))
  ([driver selector-or-element]
   (wait-for-else driver ["invisible?" selector-or-element] false
                  (not (.isDisplayed (element driver selector-or-element))))))

(defn current-url-contains?
  "Returns true if the current url contains some text"
  [substr]
  (narrate "current-url-contains?" (pr-str substr))
  (let [result (try
                 (wait-until #(.contains (current-url) substr))
                 (catch TimeoutException te
                   false))]
    (narrate "  -> " (pr-str result))
    result))

;; Assert on Elements

(defn text=
  "Returns true if the element has innerText of a given value. The comparison is
  case-insensitive and will timeout if a match does not occur."
  ([selector-or-element expected-value] (text= *driver* selector-or-element expected-value))
  ([driver selector-or-element expected-value]
   (wait-for-else driver ["assert text=" selector-or-element expected-value] false
                  (case-insensitive= (.getText (element driver selector-or-element)) expected-value))))

(defn value=
  "Returns true if the element has a value of a given string. The comparison is
  case-insensitive and will timeout if a match does not occur."
  ([selector-or-element expected-value] (value= *driver* selector-or-element expected-value))
  ([driver selector-or-element expected-value]
   (wait-for-else driver ["assert value=" selector-or-element expected-value] false
                  (case-insensitive= (.getAttribute (element driver selector-or-element) "value")
                                     expected-value))))

(defn contains-text?
  "Returns true if the element has innerText contains a given value. The comparison is
  case-insensitive and will timeout if a match does not occur."
  ([selector-or-element expected-substr] (contains-text? *driver* selector-or-element expected-substr))
  ([driver selector-or-element expected-substr]
   (wait-for-else driver ["assert contains-text?" selector-or-element expected-substr] false
                  (.contains (lower-case (.getText (element driver selector-or-element)))
                             (lower-case expected-substr)))))

(defn num-elements=
  "Returns true if the element has a certain number of elements that matches the given query."
  ([selector-or-element expected-count] (num-elements= *driver* selector-or-element expected-count))
  ([driver selector-or-element expected-count]
   (wait-for-else driver ["assert num-elements=" selector-or-element expected-count] false
                  (= (count (elements selector-or-element)) expected-count))))

(defn element-matches
  "Returns true if the element has a certain number of elements that matches the given query."
  ([selector-or-element pred] (element-matches *driver* selector-or-element pred))
  ([driver selector-or-element pred]
   (wait-for-else driver ["match element with pred" selector-or-element] false
                  (pred (element selector-or-element)))))

;; Actions based on queries on elements

(defn click-when-visible
  "Clicks on a given element, but makes sure it's visible before doing so."
  [selector]
  (is (visible? selector))
  (click selector))

(defn set-checkbox
  "Sets a checkbox element to the given state (true = check, false = unchecked)"
  [selector checked?]
  (when-not (= (selected? selector) checked?)
    (toggle selector)))

;; - Form Filling

(defn clear-fields
  "Like [[fill-form]], but clears all the text contents of inputs by deleting its contents.

  NOTE: currently this is very naive presses backspace and delete N times, where
  N is the len of the text."
  [fields] ;- {selector function-or-string-to-enter}
  (doseq [[selector _] (filter (fn [[key value]] (string? value)) fields)]
    (when (allow-backspace? selector)
      (let [times (count (value selector))]
        (send-keys selector (vec (repeat times Keys/BACK_SPACE)))
        (send-keys selector (vec (repeat times Keys/DELETE)))))))

(defn normalize-fields
  "Converts all string values that indicate typing text into functions"
  [fields] ;- {selector function-or-string} -> {selector function}
  (into {}
        (map (fn [[k v]] [k (if (string? v)
                              #(input-text % v)
                              v)])
             fields)))

(defn- fill-form*
  ([fields] ;- {selector function-or-string-to-enter}
   (doseq [[selector action] (normalize-fields fields)]
     (action selector)))
  ([fields & more-fields]
   (fill-form* fields)
   (apply fill-form* more-fields)))

(defn fill-form
  "Fills forms either by input text (if a string is given) or calling a function.

  This function is variadic to allow ordered-filling of inputs.

  If text is filled in, then its prior contents is cleared first.

  Example:

    (fill-form {\"input[name=name]\" \"my name\"
                \"input[email=email]\" \"me@example.com\"}
               {\"input[type=submit]\" click})
  "
  ([fields1 fields2 & more-fields] ;- {selector function-or-string-to-enter}
   (fill-form fields1)
   (apply fill-form fields2 more-fields))
  ([fields]
   (clear-fields fields)
   (fill-form* fields)
   (doseq [[selector value] (filter string? fields)]
     (is (value= selector value)
         (format "Failed to fill form element '%s' with '%s' (mis-entered to '%s')"
                 selector value (value selector))))))

;; Screenshots

(defn ^OutputType take-screenshot
  "Tells the driver to capture a screenshot of the currently active window.

  Paramters:

    format: Can be :file, :base64, or :bytes which prescribes the return value
    destination: An optional path to save the screenshot to disk. Set nil to ignore.

  Returns:

    org.openqa.selenium.OutputType instance with the screenshot data in the desired format.

  IMMEDIATE:
    This function is considered immediate, and does not poll using [[wait-until]]
    or [[wait-for]]. Thus, it is unaffected by [[*default-timeout*]].
  "
  ([] (take-screenshot *driver* :file))
  ([format] (take-screenshot *driver* format nil))
  ([format destination] (take-screenshot *driver* format destination))
  ([driver format destination]
   (let [driver ^TakesScreenshot driver
         output (.getScreenshotAs driver (java/->output-type format))]
     (if destination
       (do
         (io/copy output (io/file destination))
         (log/info "Screenshot written to destination")
         output)
       output))))

(defn- screenshot-dir []
  (or (:circle-artifacts env)
      "screenshots"))

(defn- save-screenshot [name screenshot-dir]
  (let [f (io/file (screenshot-dir) name)]
    (io/make-parents f)
    (take-screenshot :file f)))

(defn screenshot
  "A higher-level function to take a screenshot and immediately save it on disk."
  ([name] (screenshot name screenshot-dir))
  ([name dir-f] (save-screenshot (str name ".png") dir-f)))

;; Window Size
(defn with-window-size*
  "Use [[with-window-size]] instead."
  ([new-size actions-fn]
   (with-window-size* *driver* new-size actions-fn))
  ([driver new-size actions-fn]
   (let [w-size (window-size driver)]
     (window-resize driver new-size)
     (let [result (actions-fn)]
       (window-resize driver w-size)
       result))))

(defmacro with-window-size
  "Temporarily resizes the current driver window when evaluating the body expression."
  [new-size & body]
  `(with-window-size* ~new-size (fn [] ~@body)))
