(ns cxc.target
  "Build-target registry. A target spec says what SDK, triple,
   frameworks, bundle layout, and codesign mode clang needs for a
   given platform. macOS has been implicit; this makes it a first-
   class entry alongside the iOS entries that S2/S3 build on.

   Shape:
     :name           — target keyword (:macos, :ios-sim, :ios-device)
     :sdk            — xcrun SDK name, nil for host default
     :triple         — clang -target triple, nil for host default
     :min-os-version — platform min version, nil for host default
     :frameworks     — -framework names clang links
     :extra-cflags   — target-specific preprocessor/arch flags
     :extra-ldflags  — target-specific link flags
     :bundle-kind    — :bare (mach-o only) or :app (.app bundle)
     :codesign       — :none, :adhoc, or :dev

   `clang-args` renders the SDK/triple/frameworks/min-version flags
   into clang's argv shape. Callers assemble the full invocation on
   top of a `clang -fobjc-arc` prefix.")

(def ^:private registry
  {:macos
   {:name           :macos
    :sdk            nil
    :triple         nil
    :min-os-version nil
    :frameworks     ["Foundation"]
    :extra-cflags   []
    :extra-ldflags  []
    :bundle-kind    :bare
    :codesign       :none}

   :ios-sim
   {:name           :ios-sim
    :sdk            "iphonesimulator"
    :triple         "arm64-apple-ios15.0-simulator"
    :min-os-version "15.0"
    :frameworks     ["Foundation" "UIKit" "CoreGraphics" "QuartzCore"]
    :extra-cflags   []
    :extra-ldflags  []
    :bundle-kind    :app
    :codesign       :adhoc}

   :ios-device
   {:name           :ios-device
    :sdk            "iphoneos"
    :triple         "arm64-apple-ios15.0"
    :min-os-version "15.0"
    :frameworks     ["Foundation" "UIKit" "CoreGraphics" "QuartzCore"]
    :extra-cflags   []
    :extra-ldflags  []
    :bundle-kind    :app
    :codesign       :dev}})

(defn spec
  "Look up the target spec for a target keyword. Returns nil for an
   unknown target so callers can produce an actionable error with
   their own context."
  [target]
  (get registry target))

(defn known-targets []
  (sort (keys registry)))

(defn clang-command
  "Prefix argv that invokes clang for this target. For host-default
   targets (:macos with nil :sdk) returns `[\"clang\"]`, matching the
   pre-S1 invocation exactly. For non-host SDKs returns `[\"xcrun\"
   \"-sdk\" <sdk> \"clang\"]` so xcrun resolves the SDK sysroot and
   developer-tools paths for us. Keeps the compiler free of hard-
   coded Xcode paths."
  [{:keys [sdk]}]
  (if sdk
    ["xcrun" "-sdk" sdk "clang"]
    ["clang"]))

(defn clang-args
  "Render the triple/frameworks/extra-flags part of the clang
   invocation. Returns a flat vector of argv tokens placed after
   `-fobjc-arc`. On :macos with a nil :triple the triple flag
   collapses, so the clang defaults apply and the pre-S1 argv for
   macOS is reproduced token-for-token."
  [{:keys [triple frameworks extra-cflags extra-ldflags]}]
  (vec
   (concat
    (when triple ["-target" triple])
    extra-cflags
    extra-ldflags
    (mapcat (fn [fw] ["-framework" fw]) frameworks))))

(defn apple? [spec]
  (contains? #{:macos :ios-sim :ios-device} (:name spec)))

(defn ios? [spec]
  (contains? #{:ios-sim :ios-device} (:name spec)))
