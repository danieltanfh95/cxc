(ns cxc.plugin-manifest-test
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.parse :as parse]
            [cxc.plugin-manifest :as pm]))

(def happy-src
  "def plugin:
    name:    String!       = \"std.console\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
")

(deftest returns-nil-when-no-def-plugin
  (let [src   "def Foo:\n    x: Int! = 1\n"
        nodes (parse/parse src)]
    (is (nil? (pm/extract nodes)))))

(deftest happy-path-extracts-full-manifest
  (let [nodes (parse/parse happy-src)
        m     (pm/extract nodes)]
    (is (map? m))
    (is (= "std.console" (:name m)))
    (is (= {:major 0 :minor 1 :patch 0} (:version m)))
    (is (= {:min {:major 1 :minor 0 :patch 0}
            :max {:major 2 :minor 0 :patch 0}}
           (:compat m)))
    (is (= #{} (:requires m)) "requires defaults to empty set when absent")
    (is (= [] (:foreign-blocks m)))
    (testing "carries source-node for downstream diagnostics"
      (is (= :def-plugin (get-in m [:source-node :node]))))))

(deftest missing-required-field-fails
  (doseq [[field remaining] [["name"    "    version: SemVer!       = SemVer(0, 1, 0)\n    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"]
                             ["version" "    name:    String!       = \"p\"\n    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"]
                             ["compat"  "    name:    String!       = \"p\"\n    version: SemVer!       = SemVer(0, 1, 0)\n"]]]
    (testing (str "missing " field)
      (let [nodes (parse/parse (str "def plugin:\n" remaining))
            ex    (try (pm/extract nodes)
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str "expected throw when `" field "` is absent"))
        (is (= :plugin-manifest-invalid (:kind (ex-data ex))))
        (is (re-find (re-pattern (str "`" field ":`")) (ex-message ex))
            (str "diagnostic should name the missing field, got: "
                 (ex-message ex)))))))

(deftest version-must-be-semver-call
  (let [src   "def plugin:
    name:    String!       = \"p\"
    version: SemVer!       = \"0.1.0\"
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
"
        nodes (parse/parse src)
        ex    (try (pm/extract nodes)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :plugin-manifest-invalid (:kind (ex-data ex))))))

(deftest compat-must-be-version-range-call
  (let [src   "def plugin:
    name:    String!       = \"p\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = SemVer(1, 0, 0)
"
        nodes (parse/parse src)
        ex    (try (pm/extract nodes)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :plugin-manifest-invalid (:kind (ex-data ex))))))

(deftest semver-rejects-wrong-arity
  (let [src   "def plugin:
    name:    String!       = \"p\"
    version: SemVer!       = SemVer(0, 1)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
"
        nodes (parse/parse src)
        ex    (try (pm/extract nodes)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (re-find #"3 args" (ex-message ex)))))

(deftest version-range-rejects-missing-max
  (let [src   "def plugin:
    name:    String!       = \"p\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0))
"
        nodes (parse/parse src)
        ex    (try (pm/extract nodes)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (re-find #"max" (ex-message ex)))))

(deftest unknown-manifest-field-rejected
  (let [src   "def plugin:
    name:    String!       = \"p\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
    author:  String!       = \"nobody\"
"
        nodes (parse/parse src)
        ex    (try (pm/extract nodes)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (re-find #"author:" (ex-message ex))
        "diagnostic names the unknown field")))

(deftest wrong-type-annotation-rejected
  (let [src   "def plugin:
    name:    Int!          = \"p\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
"
        nodes (parse/parse src)
        ex    (try (pm/extract nodes)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (re-find #"`name:`" (ex-message ex)))
    (is (re-find #"String" (ex-message ex)))))

(deftest foreign-blocks-passed-through
  (let [src   "def plugin:
    name:    String!       = \"p\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

    foreign \"libc\":
        symbol \"write\" -> Int32

    foreign \"cxc_runtime\":
        symbol \"cxc_wall_now\" -> Int64
"
        nodes (parse/parse src)
        m     (pm/extract nodes)]
    (is (= 2 (count (:foreign-blocks m))))
    (is (= "libc"        (get-in m [:foreign-blocks 0 :library])))
    (is (= "cxc_runtime" (get-in m [:foreign-blocks 1 :library])))))

(deftest find-def-plugin-single
  (let [nodes (parse/parse happy-src)]
    (is (= :def-plugin (:node (pm/find-def-plugin nodes))))))

(deftest find-def-plugin-nil-when-absent
  (is (nil? (pm/find-def-plugin (parse/parse "def Foo:\n    x: Int! = 1\n")))))
