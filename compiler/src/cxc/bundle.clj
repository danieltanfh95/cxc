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
     spec       — target spec from cxc.target"
  [name ^java.io.File out-dir ^java.io.File mach-o spec]
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
            {:bundle-id       (derive-bundle-id name)
             :display-name    (derive-display-name name)
             :executable-name name
             :min-os-version  (or (:min-os-version spec) "15.0")
             :sdk-name        (or (:sdk spec) "iphonesimulator")
             :platform        (platform-key spec)}))
    (spit pkginfo-f "APPL????")
    app-dir))

(defn adhoc-codesign!
  "Ad-hoc sign the bundle with the host codesign tool. Simulator
   builds accept ad-hoc (`-s -`); device builds need a real
   identity and a provisioning profile (not implemented). Returns
   {:exit :out :err} from the codesign subprocess."
  [^java.io.File app-dir]
  (shell/sh "codesign" "--force" "--sign" "-" (.getPath app-dir)))
