(ns cxc.bundle
  "iOS/macOS `.app` bundle assembly. Called from build.clj after
   clang produces the raw mach-o when the target spec declares
   `:bundle-kind :app`. Writes Info.plist, PkgInfo, moves the
   binary into `<name>.app/<name>`, and ad-hoc codesigns the
   bundle. Returns the bundle path.

   Info.plist shape mirrors what Xcode produces for a bare SwiftUI/
   UIKit app template — the minimum keys iOS loads and that
   `simctl install` accepts."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn- plist-xml
  "Assemble the Info.plist XML for the given keys. Values are
   escaped minimally (ampersand, angle brackets); keys are not
   user-supplied so escape there is unnecessary."
  [{:keys [bundle-id display-name executable-name min-os-version
           sdk-name platform]}]
  (let [esc (fn [s]
              (-> s
                  (str/replace "&" "&amp;")
                  (str/replace "<" "&lt;")
                  (str/replace ">" "&gt;")))]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
         "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
         "<plist version=\"1.0\">\n"
         "<dict>\n"
         "    <key>CFBundleDevelopmentRegion</key><string>en</string>\n"
         "    <key>CFBundleExecutable</key><string>" (esc executable-name) "</string>\n"
         "    <key>CFBundleIdentifier</key><string>" (esc bundle-id) "</string>\n"
         "    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>\n"
         "    <key>CFBundleName</key><string>" (esc display-name) "</string>\n"
         "    <key>CFBundleDisplayName</key><string>" (esc display-name) "</string>\n"
         "    <key>CFBundlePackageType</key><string>APPL</string>\n"
         "    <key>CFBundleShortVersionString</key><string>1.0</string>\n"
         "    <key>CFBundleVersion</key><string>1</string>\n"
         "    <key>LSRequiresIPhoneOS</key><true/>\n"
         "    <key>MinimumOSVersion</key><string>" (esc min-os-version) "</string>\n"
         "    <key>DTPlatformName</key><string>" (esc platform) "</string>\n"
         "    <key>DTSDKName</key><string>" (esc sdk-name) "</string>\n"
         "    <key>UILaunchScreen</key>\n"
         "    <dict>\n"
         "        <key>UIColorName</key><string>systemBackgroundColor</string>\n"
         "    </dict>\n"
         "    <key>UIRequiredDeviceCapabilities</key>\n"
         "    <array><string>arm64</string></array>\n"
         "    <key>UISupportedInterfaceOrientations</key>\n"
         "    <array>\n"
         "        <string>UIInterfaceOrientationPortrait</string>\n"
         "        <string>UIInterfaceOrientationLandscapeLeft</string>\n"
         "        <string>UIInterfaceOrientationLandscapeRight</string>\n"
         "    </array>\n"
         ;; UIApplicationSceneManifest points iOS at CxcSceneDelegate
         ;; (emitted by cxc.emit/emit-uikit-delegate). Without this
         ;; block iOS falls back to the legacy UIApplicationDelegate
         ;; window path, logs a hard warning about missing UIScene
         ;; adoption, and will refuse to launch on a future SDK.
         "    <key>UIApplicationSceneManifest</key>\n"
         "    <dict>\n"
         "        <key>UIApplicationSupportsMultipleScenes</key><false/>\n"
         "        <key>UISceneConfigurations</key>\n"
         "        <dict>\n"
         "            <key>UIWindowSceneSessionRoleApplication</key>\n"
         "            <array>\n"
         "                <dict>\n"
         "                    <key>UISceneConfigurationName</key>"
         "<string>Default Configuration</string>\n"
         "                    <key>UISceneDelegateClassName</key>"
         "<string>CxcSceneDelegate</string>\n"
         "                </dict>\n"
         "            </array>\n"
         "        </dict>\n"
         "    </dict>\n"
         "</dict>\n"
         "</plist>\n")))

(defn- derive-bundle-id [name]
  (str "com.cxc." (-> name
                      (str/replace #"[^A-Za-z0-9-]" "-")
                      str/lower-case)))

(defn- derive-display-name [name]
  ;; Keep the raw name as display label. User overrides via plugin
  ;; manifest fields in a future pass once the parser exposes them.
  name)

(defn- platform-key [{:keys [name]}]
  (case name
    :ios-sim    "iPhoneSimulator"
    :ios-device "iPhoneOS"
    :macos      "MacOSX"
    (clojure.core/name name)))

(defn assemble
  "Build the `<name>.app` bundle around `mach-o` at `out-dir`.
   Returns the absolute bundle path. Overwrites any existing
   bundle at the destination.

   Args:
     name       — binary / executable / display name
     out-dir    — directory containing the raw mach-o
     mach-o     — java.io.File pointing at the raw binary clang
                  produced
     spec       — target spec from cxc.target
     overrides  — optional map from the plugin manifest with keys
                  :bundle-id, :display-name, :min-os-version. Each
                  value wins over the default derivation when
                  present; nil / absent → fall back to the
                  name-derived or spec-provided default."
  ([name out-dir mach-o spec]
   (assemble name out-dir mach-o spec {}))
  ([name ^java.io.File out-dir ^java.io.File mach-o spec overrides]
  (let [app-dir   (io/file out-dir (str name ".app"))
        _         (when (.exists app-dir)
                    (doseq [f (reverse (file-seq app-dir))]
                      (.delete f)))
        _         (.mkdirs app-dir)
        bin-dst   (io/file app-dir name)
        plist-f   (io/file app-dir "Info.plist")
        pkginfo-f (io/file app-dir "PkgInfo")]
    (java.nio.file.Files/move
      (.toPath mach-o)
      (.toPath bin-dst)
      (into-array java.nio.file.CopyOption
                  [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
    (spit plist-f
          (plist-xml
            {:bundle-id       (or (:bundle-id overrides)
                                  (derive-bundle-id name))
             :display-name    (or (:display-name overrides)
                                  (derive-display-name name))
             :executable-name name
             :min-os-version  (or (:min-os-version overrides)
                                  (:min-os-version spec)
                                  "15.0")
             :sdk-name        (or (:sdk spec) "iphonesimulator")
             :platform        (platform-key spec)}))
    (spit pkginfo-f "APPL????")
    app-dir)))

(defn adhoc-codesign!
  "Ad-hoc sign the bundle with the host codesign tool. Simulator
   builds accept ad-hoc (`-s -`); device builds route through
   `dev-codesign!`. Returns {:exit :out :err} from the codesign
   subprocess."
  [^java.io.File app-dir]
  (shell/sh "codesign" "--force" "--sign" "-" (.getPath app-dir)))

(defn- missing-dev-codesign-env [{:keys [identity profile entitlements]}]
  (cond-> []
    (str/blank? identity)
    (conj "CXC_IOS_CODESIGN_IDENTITY (e.g. \"Apple Development: Jane Doe (ABCDE12345)\")")
    (str/blank? profile)
    (conj "CXC_IOS_PROVISIONING_PROFILE (path to the .mobileprovision file)")
    (str/blank? entitlements)
    (conj "CXC_IOS_ENTITLEMENTS (path to the entitlements .plist)")))

(defn dev-codesign!
  "Device codesign path. Embeds the supplied .mobileprovision as
   `<app-dir>/embedded.mobileprovision`, then calls codesign with
   the real developer identity and the entitlements plist.

   `creds` is a map:
     :identity     — codesign -s argument (string, e.g.
                    \"Apple Development: Jane Doe (ABCDE12345)\")
     :profile      — path to the provisioning .mobileprovision
     :entitlements — path to the entitlements .plist

   Any missing field raises ex-info naming the specific env vars
   the user needs to set — callers construct the map from env in
   build.clj, so the ex-info message surfaces to the user without
   build.clj having to know the shape of the map.

   Returns the same `{:exit :out :err}` shape as `adhoc-codesign!`
   so build.clj's failure path can treat both uniformly."
  [^java.io.File app-dir {:keys [identity profile entitlements] :as creds}]
  (let [missing (missing-dev-codesign-env creds)]
    (when (seq missing)
      (throw (ex-info
               (str "dev codesign for :ios-device needs these to be set:\n"
                    (str/join "\n" (map #(str "  - " %) missing)))
               {:kind :dev-codesign-missing-env
                :missing missing}))))
  (let [profile-src (io/file profile)
        _           (when-not (.exists profile-src)
                      (throw (ex-info (str "provisioning profile not found: " profile)
                                      {:kind :dev-codesign-profile-missing
                                       :profile profile})))
        ent-src     (io/file entitlements)
        _           (when-not (.exists ent-src)
                      (throw (ex-info (str "entitlements plist not found: " entitlements)
                                      {:kind :dev-codesign-entitlements-missing
                                       :entitlements entitlements})))
        profile-dst (io/file app-dir "embedded.mobileprovision")]
    (java.nio.file.Files/copy
      (.toPath profile-src)
      (.toPath profile-dst)
      (into-array java.nio.file.CopyOption
                  [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
    (shell/sh "codesign" "--force"
              "--sign" identity
              "--entitlements" (.getPath ent-src)
              "--timestamp=none"
              (.getPath app-dir))))

(defn dev-codesign-creds-from-env
  "Build the credentials map `dev-codesign!` expects from process
   environment. Pulled out so build.clj stays declarative and so
   tests can call `dev-codesign!` with a hand-built map directly."
  []
  {:identity     (System/getenv "CXC_IOS_CODESIGN_IDENTITY")
   :profile      (System/getenv "CXC_IOS_PROVISIONING_PROFILE")
   :entitlements (System/getenv "CXC_IOS_ENTITLEMENTS")})
