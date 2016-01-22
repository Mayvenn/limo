(ns limo.api-test
  (:require [clojure.test :refer :all]
            [limo.api :refer :all]
            [limo.driver :refer :all]))

(deftest opening-a-browser
  (with-fresh-browser create-chrome
    (to "http://httpbin.org")
    (click "a[href='/html']")
    (is (text= "h1" "Herman Melville - Moby-Dick"))))
