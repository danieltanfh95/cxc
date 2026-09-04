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

;; ============================================================================
;; B6 — cross-plugin scope: `plugin.get(sym)` in module M only reaches M's
;; own foreign blocks. Invariant is architectural (B5 resolver reads only the
;; per-module manifest); these tests lock it against future regression.
;; ============================================================================

(def plugin-a-src
  "def plugin:
    name:    String!       = \"a\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

    foreign \"liba\":
        symbol \"sym_a\" -> Int32
")

(def plugin-b-src
  "def plugin:
    name:    String!       = \"b\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

    foreign \"libb\":
        symbol \"sym_b\" -> Int32
")

(deftest cross-plugin-b-cannot-reach-a-symbols
  (testing "plugin B's atom calling plugin.get(\"sym_a\") fails at B's resolver"
    (let [b-full (str plugin-b-src "
def BCap:
    use plugin: Plugin
    x: Int! = plugin.get(\"sym_a\")
")
          nodes    (parse/parse b-full)
          manifest (pm/extract nodes)
          ex       (try (pm/validate-plugin-symbol-refs! nodes manifest)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :plugin-symbol-unresolved (:kind (ex-data ex))))
      (is (= "sym_a" (:symbol (ex-data ex)))
          "sym_a only exists in plugin A's manifest — B cannot reach it"))))

(deftest per-module-manifests-are-disjoint
  (testing "extracting A's manifest gives only A's symbols; B likewise"
    (let [ma  (pm/extract (parse/parse plugin-a-src))
          mb  (pm/extract (parse/parse plugin-b-src))
          ixa (pm/build-foreign-index (:foreign-blocks ma))
          ixb (pm/build-foreign-index (:foreign-blocks mb))]
      (is (contains? ixa "sym_a"))
      (is (not (contains? ixa "sym_b"))
          "A's index carries no B symbols")
      (is (contains? ixb "sym_b"))
      (is (not (contains? ixb "sym_a"))
          "B's index carries no A symbols"))))

(deftest cross-plugin-b-can-reuse-a-library-name
  (testing "two plugins may declare the same library (libc) with disjoint symbols"
    (let [same-lib "def plugin:
    name:    String!       = \"b\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

    foreign \"liba\":
        symbol \"sym_b\" -> Int32

def BCap:
    use plugin: Plugin
    x: Int! = plugin.get(\"sym_b\")
"
          nodes    (parse/parse same-lib)
          manifest (pm/extract nodes)]
      (is (nil? (pm/validate-plugin-symbol-refs! nodes manifest))
          "sharing a library name (liba) across plugins is fine when symbols disjoint"))))

;; NYI holdout retired 2026-09-02: FL-13 landed `plugin.send` and FL-14
;; landed `plugin.class_send`. Failure modes for both live in the
;; dedicated FL-13 / FL-14 test sections below.

;; ============================================================================
;; B7 / F77 — .cxc-caps manifest emission
;; ============================================================================

(deftest caps-map-empty-when-no-plugins
  (let [caps (pm/build-caps-map [nil nil] :macos "0.11.0")]
    (is (= pm/caps-schema-tag (:schema caps)))
    (is (= "0.11.0" (:cxc_version caps)))
    (is (= "macos"  (:target caps)))
    (is (= []       (:plugins caps)))
    (is (= []       (:os_primitives caps)))))

(deftest caps-map-sorts-plugins-by-name
  (let [m-b (pm/extract (parse/parse plugin-b-src))
        m-a (pm/extract (parse/parse plugin-a-src))
        caps (pm/build-caps-map [m-b m-a] :macos "0.11.0")]
    (is (= ["a" "b"] (mapv :name (:plugins caps)))
        "plugins deterministically ordered by name")
    (is (= "0.1.0" (get-in caps [:plugins 0 :version])))))

(deftest caps-map-drops-nil-entries
  (let [m-a (pm/extract (parse/parse plugin-a-src))]
    (is (= 1 (count (:plugins (pm/build-caps-map [nil m-a nil] :macos "0.11.0"))))
        "non-plugin files contribute nil, which is dropped")))

(deftest caps-map-sorts-os-primitives
  ;; requires-set carrier: we synthesize a manifest with a requires set
  ;; since the source-level set-literal grammar isn't parsed yet.
  (let [m-c {:name "c" :version {:major 1 :minor 0 :patch 0}
             :requires #{:fs_read :stdio :clock_monotonic}}
        m-d {:name "d" :version {:major 1 :minor 0 :patch 0}
             :requires #{:stdio :net_dns}}
        caps (pm/build-caps-map [m-c m-d] :macos "0.11.0")]
    (is (= ["clock_monotonic" "fs_read" "net_dns" "stdio"]
           (:os_primitives caps))
        "primitives unioned across all plugins, deterministically sorted")))

(deftest emit-caps-writes-json-file
  (let [tmp (java.io.File/createTempFile "cxc-caps-test" ".bin")
        _   (.deleteOnExit tmp)
        caps-map (pm/build-caps-map [nil] :macos "0.11.0")
        f   (pm/emit-caps! (.getPath tmp) caps-map)]
    (is (.exists f))
    (is (.endsWith (.getPath f) ".cxc-caps"))
    (let [content (slurp f)]
      (is (re-find #"\"schema\": \"cxc-caps/v1\"" content))
      (is (re-find #"\"target\": \"macos\"" content))
      (is (re-find #"\"plugins\": \[\]" content)))))

(deftest extract-all-manifests-pass-through-order
  (let [nodes-a  (parse/parse plugin-a-src)
        nodes-b  (parse/parse plugin-b-src)
        non-plug (parse/parse "def Foo:\n    x: Int! = 1\n")
        results  (pm/extract-all-manifests [nodes-a non-plug nodes-b])]
    (is (= 3 (count results)))
    (is (= "a" (:name (nth results 0))))
    (is (nil? (nth results 1)))
    (is (= "b" (:name (nth results 2))))))

;; ============================================================================
;; FL-1 — parse-post rewrite of plugin.get(LIT) → :foreign-ref
;; ============================================================================

(def fl-1-src
  "def plugin:
    name:    String!       = \"p\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

    foreign \"libc\":
        symbol \"puts\" -> Int

def MyShim:
    use plugin: Plugin
    handle: Int! = plugin.get(\"puts\")
")

(deftest fl-1-rewrites-plugin-get-to-foreign-ref
  (let [nodes    (parse/parse fl-1-src)
        manifest (pm/extract nodes)
        out      (pm/rewrite-plugin-refs nodes manifest)
        field    (get-in out [1 :body 1 :fields 0])
        deflt    (:default field)]
    (is (= :foreign-ref (:node deflt)))
    (is (= "libc"       (:library deflt)))
    (is (= "puts"       (:symbol deflt)))
    (is (= :symbol      (:sig-kind deflt)))
    (is (= {:node :type-nonnil :inner {:node :type-ref :name "Int"}}
           (:signature deflt))
        "2026-09-04 — FFI-position `Int` defaults to `Int!` (non-null); the parser wraps bare primitive :type-refs at foreign-signature positions so the returned wire value opts out of sentinel-lifting.")
    (testing "preserves the original :call for downstream diagnostics"
      (is (= :call (get-in deflt [:orig-node :node]))))))

(deftest fl-1-noop-on-non-plugin-module
  (let [nodes (parse/parse "def Foo:\n    x: Int! = 1\n")]
    (is (= nodes (pm/rewrite-plugin-refs nodes nil)))))

(deftest fl-1-idempotent
  (let [nodes    (parse/parse fl-1-src)
        manifest (pm/extract nodes)
        once     (pm/rewrite-plugin-refs nodes manifest)
        twice    (pm/rewrite-plugin-refs once manifest)]
    (is (= once twice)
        "rewriting an already-rewritten AST is a no-op")))

(deftest fl-1-leaves-unrelated-calls-alone
  (let [src   "def plugin:
    name:    String!       = \"p\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

    foreign \"libc\":
        symbol \"puts\" -> Int

def MyShim:
    use plugin: Plugin
    other: Int! = 42
    handle: Int! = plugin.get(\"puts\")
"
        nodes    (parse/parse src)
        manifest (pm/extract nodes)
        out      (pm/rewrite-plugin-refs nodes manifest)
        other    (get-in out [1 :body 1 :fields 0])]
    (is (= :field (:node other)))
    (is (= "other" (:name other)))
    (is (= :lit-int (get-in other [:default :node]))
        "non-plugin.get expressions untouched")))

(deftest fl-1-rewrites-nested-inside-call-args
  (let [src   "def plugin:
    name:    String!       = \"p\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

    foreign \"libc\":
        symbol \"puts\" -> Int

def MyShim:
    use plugin: Plugin
    handle: Int! = f(plugin.get(\"puts\"), 1)
"
        nodes    (parse/parse src)
        manifest (pm/extract nodes)
        out      (pm/rewrite-plugin-refs nodes manifest)
        deflt    (get-in out [1 :body 1 :fields 0 :default])]
    (is (= :call (:node deflt)))
    (is (= :foreign-ref (get-in deflt [:args 0 :value :node]))
        "plugin.get inside an outer call is rewritten in place")))

;; ---------------------------------------------------------------------------
;; FL-13 (2026-09-02) — plugin.send instance-selector resolution + rewrite.
;; build-selector-index keys by SEL string across all foreign blocks;
;; validate resolves against the index; rewrite-plugin-refs produces
;; `:foreign-selector-call` nodes carrying receiver + args separately.
;; ---------------------------------------------------------------------------

(def fl-13-src
  "def plugin:
    name:    String!       = \"fl13\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

    foreign \"Foundation\":
        type \"NSString\" as NSStr
        selector \"NSString\" \"stringByAppendingString:\" -> (NSStr) -> NSStr
        selector \"NSString\" \"length\" -> () -> Int
")

(deftest fl-13-selector-index-collects-selectors
  (let [nodes    (parse/parse fl-13-src)
        manifest (pm/extract nodes)
        idx      (pm/build-selector-index (:foreign-blocks manifest))]
    (is (contains? idx "stringByAppendingString:"))
    (is (contains? idx "length"))
    (is (= "Foundation" (get-in idx ["length" :library])))
    (is (= "NSString"   (get-in idx ["length" :cls])))))

(deftest fl-13-selector-index-rejects-collision
  (let [src "def plugin:
    name:    String!       = \"fl13\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
    foreign \"A\":
        selector \"C1\" \"sel:\" -> (Int) -> Int
    foreign \"B\":
        selector \"C2\" \"sel:\" -> (Int) -> Int
"
        nodes    (parse/parse src)
        manifest (pm/extract nodes)
        ex       (try (pm/build-selector-index (:foreign-blocks manifest))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :plugin-foreign-selector-collision (:kind (ex-data ex))))
    (is (= "sel:" (:selector (ex-data ex))))))

(deftest fl-13-resolve-happy-path
  (let [full (str fl-13-src "
def MyAtom:
    use plugin: Plugin
    -> ready
        then:
            let a = \"hi\"
            let b = \"there\"
            let c = plugin.send(\"stringByAppendingString:\", a, b)
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)]
    (is (nil? (pm/validate-plugin-symbol-refs! nodes manifest))
        "known selector + receiver + arg validates cleanly")))

(deftest fl-13-resolve-unresolved-selector-fails
  (let [full (str fl-13-src "
def MyAtom:
    use plugin: Plugin
    -> ready
        then:
            let a = \"x\"
            let b = plugin.send(\"noSuchSelector:\", a, a)
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)
        ex       (try (pm/validate-plugin-symbol-refs! nodes manifest)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :plugin-selector-unresolved (:kind (ex-data ex))))
    (is (= "noSuchSelector:" (:selector (ex-data ex))))))

(deftest fl-13-resolve-missing-receiver-fails
  (let [full (str fl-13-src "
def MyAtom:
    use plugin: Plugin
    -> ready
        then:
            let n = plugin.send(\"length\")
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)
        ex       (try (pm/validate-plugin-symbol-refs! nodes manifest)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :plugin-send-missing-receiver (:kind (ex-data ex))))))

(deftest fl-13-resolve-non-literal-selector-fails
  (let [full (str fl-13-src "
def MyAtom:
    use plugin: Plugin
    -> ready
        then:
            let s = \"length\"
            let a = \"x\"
            let n = plugin.send(s, a)
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)
        ex       (try (pm/validate-plugin-symbol-refs! nodes manifest)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :plugin-send-non-literal-selector (:kind (ex-data ex))))))

(deftest fl-13-rewrite-produces-foreign-selector-call
  (let [full (str fl-13-src "
def MyAtom:
    use plugin: Plugin
    -> ready
        then:
            let a = \"hi\"
            let b = \"there\"
            let c = plugin.send(\"stringByAppendingString:\", a, b)
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)
        out      (pm/rewrite-plugin-refs nodes manifest)
        ;; walk to find the :foreign-selector-call node
        find-fsc (fn walk [x]
                   (cond
                     (and (map? x) (= :foreign-selector-call (:node x))) x
                     (map? x)      (some walk (vals x))
                     (sequential? x) (some walk x)
                     :else nil))
        fsc      (find-fsc out)]
    (is (some? fsc) ":foreign-selector-call node appears somewhere in rewritten AST")
    (is (= "Foundation"                 (:library fsc)))
    (is (= "NSString"                   (:cls fsc)))
    (is (= "stringByAppendingString:"   (:selector fsc)))
    (is (= 1                            (count (:args fsc)))
        "receiver split off from positional args; only the trailing arg remains")))

;; ---------------------------------------------------------------------------
;; FL-14 (2026-09-02) — plugin.class_send class-selector resolution + rewrite.
;; Mirrors FL-13 but keyed by `:class_selector` entries; the receiver is the
;; class name from the manifest entry (not a call-site arg).
;; ---------------------------------------------------------------------------

(def fl-14-src
  "def plugin:
    name:    String!       = \"fl14\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

    foreign \"Foundation\":
        type \"NSString\" as NSStr
        class_selector \"NSString\" \"stringWithUTF8String:\" -> (CString) -> NSStr
        class_selector \"NSDate\" \"date\" -> () -> NSStr
")

(deftest fl-14-class-selector-index-collects
  (let [nodes    (parse/parse fl-14-src)
        manifest (pm/extract nodes)
        idx      (pm/build-class-selector-index (:foreign-blocks manifest))]
    (is (contains? idx "stringWithUTF8String:"))
    (is (contains? idx "date"))
    (is (= "NSString" (get-in idx ["stringWithUTF8String:" :cls])))
    (is (= "NSDate"   (get-in idx ["date" :cls])))))

(deftest fl-14-class-selector-index-rejects-collision
  (let [src "def plugin:
    name:    String!       = \"fl14-col\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
    foreign \"A\":
        class_selector \"C1\" \"sel:\" -> (Int) -> Int
    foreign \"B\":
        class_selector \"C2\" \"sel:\" -> (Int) -> Int
"
        nodes    (parse/parse src)
        manifest (pm/extract nodes)
        ex       (try (pm/build-class-selector-index (:foreign-blocks manifest))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :plugin-foreign-class-selector-collision (:kind (ex-data ex))))))

(deftest fl-14-class-selector-index-does-not-see-instance-selectors
  (let [src "def plugin:
    name:    String!       = \"fl14-mix\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
    foreign \"Foundation\":
        selector \"NSString\" \"length\" -> () -> Int
        class_selector \"NSDate\" \"date\" -> () -> Int
"
        nodes    (parse/parse src)
        manifest (pm/extract nodes)
        inst-idx (pm/build-selector-index (:foreign-blocks manifest))
        cls-idx  (pm/build-class-selector-index (:foreign-blocks manifest))]
    (is (contains? inst-idx "length"))
    (is (not (contains? inst-idx "date")))
    (is (contains? cls-idx "date"))
    (is (not (contains? cls-idx "length")))
    ;; No cross-kind collision even when SEL names happen to overlap
    (let [src2 "def plugin:
    name:    String!       = \"fl14-sameel\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
    foreign \"F\":
        selector       \"NSString\" \"copy\" -> () -> Int
        class_selector \"NSDate\"   \"copy\" -> () -> Int
"
          m2 (pm/extract (parse/parse src2))]
      (is (contains? (pm/build-selector-index       (:foreign-blocks m2)) "copy"))
      (is (contains? (pm/build-class-selector-index (:foreign-blocks m2)) "copy")))))

(deftest fl-14-resolve-happy-path
  (let [full (str fl-14-src "
def MyAtom:
    use plugin: Plugin
    -> ready
        then:
            let s = plugin.class_send(\"stringWithUTF8String:\", \"x\")
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)]
    (is (nil? (pm/validate-plugin-symbol-refs! nodes manifest))
        "known class-selector + args validates cleanly")))

(deftest fl-14-resolve-unresolved-fails
  (let [full (str fl-14-src "
def MyAtom:
    use plugin: Plugin
    -> ready
        then:
            let s = plugin.class_send(\"noSuchClsSel\")
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)
        ex       (try (pm/validate-plugin-symbol-refs! nodes manifest)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :plugin-class-selector-unresolved (:kind (ex-data ex))))
    (is (= "noSuchClsSel" (:selector (ex-data ex))))))

(deftest fl-14-resolve-non-literal-fails
  (let [full (str fl-14-src "
def MyAtom:
    use plugin: Plugin
    -> ready
        then:
            let sel = \"date\"
            let d = plugin.class_send(sel)
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)
        ex       (try (pm/validate-plugin-symbol-refs! nodes manifest)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :plugin-class-send-non-literal-selector (:kind (ex-data ex))))))

(deftest fl-14-rewrite-produces-foreign-class-selector-call
  (let [full (str fl-14-src "
def MyAtom:
    use plugin: Plugin
    -> ready
        then:
            let s = plugin.class_send(\"stringWithUTF8String:\", \"x\")
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)
        out      (pm/rewrite-plugin-refs nodes manifest)
        find-node (fn walk [x]
                    (cond
                      (and (map? x) (= :foreign-class-selector-call (:node x))) x
                      (map? x)      (some walk (vals x))
                      (sequential? x) (some walk x)
                      :else nil))
        fcsc     (find-node out)]
    (is (some? fcsc))
    (is (= "Foundation"             (:library fcsc)))
    (is (= "NSString"               (:cls fcsc)))
    (is (= "stringWithUTF8String:"  (:selector fcsc)))
    (is (= 1                        (count (:args fcsc)))
        "no receiver split — all args after SEL are call args")))

(deftest fl-14-rewrite-is-idempotent
  (let [full (str fl-14-src "
def MyAtom:
    use plugin: Plugin
    -> ready
        then:
            let s = plugin.class_send(\"date\")
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)
        once     (pm/rewrite-plugin-refs nodes manifest)
        twice    (pm/rewrite-plugin-refs once manifest)]
    (is (= once twice))))

(deftest fl-13-rewrite-is-idempotent
  (let [full (str fl-13-src "
def MyAtom:
    use plugin: Plugin
    -> ready
        then:
            let a = \"hi\"
            let b = \"there\"
            let c = plugin.send(\"stringByAppendingString:\", a, b)
")
        nodes    (parse/parse full)
        manifest (pm/extract nodes)
        once     (pm/rewrite-plugin-refs nodes manifest)
        twice    (pm/rewrite-plugin-refs once manifest)]
    (is (= once twice)
        "second application is a fixpoint (:foreign-selector-call is not re-walked)")))

;; ---------------------------------------------------------------------------
;; FL-12 (2026-09-02) — `type "NSFoo"` ObjC type alias registration.
;; Rewrites `:type-ref` inside foreign-block entry signatures matching a
;; registered alias to `{:type-ref \"Any\"}` (id at C level), preserving
;; the original name under `:objc-original` for tooling.
;; ---------------------------------------------------------------------------

(deftest fl-12-collapses-alias-in-return-type
  (let [src "def plugin:
    name:    String!       = \"fl12-return\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
    foreign \"Foundation\":
        type \"NSURLSession\"
        symbol \"session_shared\" -> () -> NSURLSession
"
        nodes    (parse/parse src)
        out      (pm/rewrite-objc-type-aliases nodes)
        entries  (mapcat :entries
                         (get-in (first (filter #(= :def-plugin (:node %)) out))
                                 [:foreign-blocks]))
        sym-sig  (:signature (first (filter #(= :symbol (:kind %)) entries)))]
    (is (= {:node :type-ref :name "Any" :objc-original "NSURLSession"}
           (:return sym-sig))
        "bare `type \"NSURLSession\"` registers itself as alias; return becomes Any")))

(deftest fl-12-collapses-alias-in-named-arg
  (let [src "def plugin:
    name:    String!       = \"fl12-arg\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
    foreign \"Foundation\":
        type \"NSString\" as NSStr
        symbol \"utf8_of\" -> (s: NSStr) -> Ptr of Byte
"
        nodes    (parse/parse src)
        out      (pm/rewrite-objc-type-aliases nodes)
        entries  (mapcat :entries
                         (get-in (first (filter #(= :def-plugin (:node %)) out))
                                 [:foreign-blocks]))
        arg      (first (get-in (first (filter #(= :symbol (:kind %)) entries))
                                [:signature :args :fields]))]
    (is (= {:node :type-ref :name "Any" :objc-original "NSStr"}
           (:type arg))
        "explicit `as NSStr` alias flows through named-arg field types")))

(deftest fl-12-noop-when-no-def-plugin
  (let [src   "def Foo:\n    x: Int! = 1\n"
        nodes (parse/parse src)]
    (is (= nodes (pm/rewrite-objc-type-aliases nodes))
        "non-plugin modules pass through untouched")))

(deftest fl-12-noop-when-no-type-entries
  (let [src "def plugin:
    name:    String!       = \"fl12-none\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
    foreign \"libc\":
        symbol \"abs\" -> (Int) -> Int
"
        nodes (parse/parse src)]
    (is (= nodes (pm/rewrite-objc-type-aliases nodes))
        "plugin without any `type` entries is untouched")))

(deftest fl-12-leaves-non-alias-type-refs-alone
  (let [src "def plugin:
    name:    String!       = \"fl12-mixed\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
    foreign \"Foundation\":
        type \"NSURLSession\"
        symbol \"mix\" -> (fd: Int32, s: NSURLSession) -> Int32
"
        nodes    (parse/parse src)
        out      (pm/rewrite-objc-type-aliases nodes)
        entries  (mapcat :entries
                         (get-in (first (filter #(= :def-plugin (:node %)) out))
                                 [:foreign-blocks]))
        sym      (first (filter #(= :symbol (:kind %)) entries))
        fields   (get-in sym [:signature :args :fields])]
    ;; 2026-09-04 — FFI-position primitives (Int32/Int64/Bool/…) now
    ;; default to non-null; parse wraps them in `:type-nonnil`.
    ;; Non-primitive alias slots (Any / NSURLSession) unchanged.
    (is (= "Int32"  (get-in (nth fields 0) [:type :inner :name])))
    (is (= "Any"    (get-in (nth fields 1) [:type :name])))
    (is (= "Int32"  (get-in sym [:signature :return :inner :name]))
        "non-alias primitives survive; alias slot alone is collapsed")))

(deftest fl-12-is-idempotent
  (let [src "def plugin:
    name:    String!       = \"fl12-idem\"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))
    foreign \"Foundation\":
        type \"NSURLSession\"
        symbol \"session_shared\" -> () -> NSURLSession
"
        nodes (parse/parse src)
        once  (pm/rewrite-objc-type-aliases nodes)
        twice (pm/rewrite-objc-type-aliases once)]
    (is (= once twice)
        "second application is a fixpoint (alias set is empty on rewritten shape)")))

