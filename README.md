# Limo [![CircleCI](https://circleci.com/gh/Mayvenn/limo/tree/master.svg?style=svg&circle-token=e216b48367b08611e35c36e8531bdaa93b349be6)](https://circleci.com/gh/Mayvenn/limo/tree/master)

[API Docs](http://mayvenn.github.io/limo)

A Clojure library that's a small wrapper around selenium webdriver.

Why a new wrapper? This library aims to provide a high-level capybara-styled api.

Limo embraces its tight coupling to Selenium Webdriver. That means no wrapper types. When you need to drop down the native selenium API, you are free to do so.

## Installation

Add your `project.clj` file:

[![Clojars Project](https://img.shields.io/clojars/v/limo.svg)](https://clojars.org/limo)

## Basic Usage - functional test

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

## Alternate Usage - automate login, data scraping, etc

You can also create a new project that use Limo to automate manual tasks using
[limo-driver](https://github.com/agilecreativity/limo-driver).

e.g.

```sh
lein new limo-driver <your-project-name>
```
This will create the basic CLI project that you can use as starting point to quickly.

Please see [limo-driver's README.md](https://github.com/agilecreativity/limo-driver/blob/master/README.md) for more details.

## License

Copyright © 2017 Mayvenn

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
