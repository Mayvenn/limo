# limo

A Clojure library that's a small wrapper around selenium webdriver.

Why a new wrapper? This library aims to provide a high-level capybara-styled api.

Limo embraces its tight coupling to Selenium Webdriver. That means no wrapper types. When you need to drop down the native selenium API, you are free to do so.

## Installation

Add your `project.clj` file:

[![Clojars Project](https://img.shields.io/clojars/v/limo.svg)](https://clojars.org/limo)

## Usage

Currently similar to [clj-webdriver](https://github.com/semperos/clj-webdriver). Unlike clj-webdriver, all actions will poll and ignore stale element exceptions for 15 seconds (by default).

## License

Copyright © 2016 Mayvenn

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
