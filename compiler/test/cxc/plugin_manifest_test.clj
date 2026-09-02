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

;; ============================================================================
;; B4 (F76) — linker gate: `use plugin: Plugin` outside plugin modules
;; ============================================================================

(deftest gate-noop-when-no-plugin-uses
  (let [nodes (parse/parse "def Foo:\n    x: Int! = 1\n")]
    (is (nil? (pm/validate-plugin-capability-uses! nodes)))))

(deftest gate-noop-when-plugin-marker-present
  (let [src   "def plugin:
    name:    String!       = \"p\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

def Console:
    use plugin: Plugin
    x: Int! = 1
"
        nodes (parse/parse src)]
    (is (nil? (pm/validate-plugin-capability-uses! nodes))
        "atom in plugin file may bind Plugin capability")))

(deftest gate-rejects-plugin-use-without-marker
  (let [src   "def App:
    use plugin: Plugin
    x: Int! = 1
"
        nodes (parse/parse src)
        ex    (try (pm/validate-plugin-capability-uses! nodes)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "expected throw")
    (is (= :plugin-capability-outside-plugin-module (:kind (ex-data ex))))
    (is (= "App"    (:atom-name (ex-data ex))))
    (is (= "plugin" (:cap-name  (ex-data ex))))
    (is (re-find #"def plugin:" (ex-message ex))
        "diagnostic points at the missing marker")))

(deftest gate-rejects-any-plugin-bind-name
  (let [src   "def App:
    use ext: Plugin
    x: Int! = 1
"
        nodes (parse/parse src)
        ex    (try (pm/validate-plugin-capability-uses! nodes)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "gate keys off the type `Plugin`, not the bind-name `plugin`")
    (is (= "ext" (:cap-name (ex-data ex))))))

(deftest gate-allows-non-plugin-capability-imports
  (let [src   "def App:
    use console: Console
    x: Int! = 1
"
        nodes (parse/parse src)]
    (is (nil? (pm/validate-plugin-capability-uses! nodes))
        "app-side capability atom imports are unaffected")))

;; ============================================================================
;; B5 — plugin symbol resolver: plugin.get(LIT) against foreign blocks
;; ============================================================================

(def foreign-block-src
  "def plugin:
    name:    String!       = \"p\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

    foreign \"libc\":
        symbol \"write\" -> Int32

    foreign \"cxc_runtime\":
        symbol \"cxc_wall_now\" -> Int64
")

(deftest foreign-index-collects-symbols
  (let [m   (pm/extract (parse/parse foreign-block-src))
        idx (pm/build-foreign-index (:foreign-blocks m))]
    (is (= "libc"        (get-in idx ["write"        :library])))
    (is (= "cxc_runtime" (get-in idx ["cxc_wall_now" :library])))
    (is (= 2 (count idx)))))

(deftest foreign-index-rejects-collision
  (let [m   (pm/extract (parse/parse "def plugin:
    name:    String!       = \"p\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

    foreign \"libc\":
        symbol \"write\" -> Int32

    foreign \"cxc_runtime\":
        symbol \"write\" -> Int32
"))
        ex  (try (pm/build-foreign-index (:foreign-blocks m))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :plugin-foreign-symbol-collision (:kind (ex-data ex))))
    (is (= "write" (:symbol (ex-data ex))))))

(deftest resolve-happy-path
  (let [full (str foreign-block-src "
def Console:
    use plugin: Plugin
    x: Int! = plugin.get(\"write\")
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)]
    (is (nil? (pm/validate-plugin-symbol-refs! nodes manifest)))))

(deftest resolve-unresolved-symbol-fails
  (let [full (str foreign-block-src "
def Console:
    use plugin: Plugin
    x: Int! = plugin.get(\"nope\")
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)
        ex       (try (pm/validate-plugin-symbol-refs! nodes manifest)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :plugin-symbol-unresolved (:kind (ex-data ex))))
    (is (= "nope" (:symbol (ex-data ex))))))

(deftest resolve-non-literal-arg-fails
  (let [full (str foreign-block-src "
def Console:
    use plugin: Plugin
    key: String! = \"write\"
    x: Int! = plugin.get(self.key)
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)
        ex       (try (pm/validate-plugin-symbol-refs! nodes manifest)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :plugin-get-non-literal-arg (:kind (ex-data ex))))))

(deftest resolve-noop-on-non-plugin-module
  (let [nodes (parse/parse "def Foo:\n    x: Int! = 1\n")]
    (is (nil? (pm/validate-plugin-symbol-refs! nodes nil)))))

(deftest resolve-flags-send-and-class-send-as-nyi
  (doseq [method ["send" "class_send"]]
    (testing (str "plugin." method " NYI")
      (let [full (str foreign-block-src "
def Console:
    use plugin: Plugin
    x: Int! = plugin." method "(\"write\")
")
            nodes    (parse/parse full)
            manifest (pm/extract nodes)
            ex       (try (pm/validate-plugin-symbol-refs! nodes manifest)
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= :plugin-method-nyi (:kind (ex-data ex))))
        (is (= method (:method (ex-data ex))))))))
