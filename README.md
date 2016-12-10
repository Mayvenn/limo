# limo

A Clojure library that's a small wrapper around selenium webdriver.

Why a new wrapper? This library aims to provide a high-level capybara-styled api.

Limo embraces its tight coupling to Selenium Webdriver. That means no wrapper types. When you need to drop down the native selenium API, you are free to do so.

## Installation

Add your `project.clj` file:

[![Clojars Project](https://img.shields.io/clojars/v/limo.svg)](https://clojars.org/limo)

## Usage

Currently similar to [clj-webdriver](https://github.com/semperos/clj-webdriver). Unlike clj-webdriver, all actions will poll and ignore stale element exceptions for 15 seconds (by default).

```clojure
(ns app.test
  (:require [limo.api :as api]
            [limo.driver :as driver]
            [clojure.test :refer :all]))

(deftest test-login
  ;; sets the implicit driver to use.
  ;; Alternatively you can pass the driver in as the first argument
  (api/set-driver! (driver/create-chrome))
  ;; tells selenium webdriver to implicitly wait 1s, for up the *default-timeout* (explicit wait) of 15 seconds
  ;; see http://www.seleniumhq.org/docs/04_webdriver_advanced.jsp for more details
  (api/implicit-wait 1000)
  (api/to "http://example.com")
  (api/fill-form {"#email" email
                  "#password" password})
  (api/click "#login")
  (is (contains-text? "#flash-message" "Welcome! You have signed up successfully."))
```


## License

Copyright © 2016 Mayvenn

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
