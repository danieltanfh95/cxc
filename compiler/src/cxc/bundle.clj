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
            [clojure.string :as str]
            [cxc.emit :as emit]))

(defn copy-file
  "REPLACE_EXISTING java.nio copy. Shared by every call site in the
   compiler that moves a file into place without caring whether the
   destination already exists. Lives here (rather than a util ns)
   because bundle.clj is already the ns the build layer loads for
   file-into-bundle machinery and the three current callers are
   either here or in cxc.build."
  [^java.io.File src ^java.io.File dst]
  (io/make-parents dst)
  (java.nio.file.Files/copy (.toPath src) (.toPath dst)
                            (into-array java.nio.file.CopyOption
                                        [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))

(defn- plist-xml
  "Assemble the Info.plist XML for the given keys. Values are
   escaped minimally (ampersand, angle brackets); keys are not
   user-supplied so escape there is unnecessary."
  [{:keys [bundle-id display-name executable-name min-os-version
           sdk-name platform-dtname]}]
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
         "    <key>DTPlatformName</key><string>" (esc platform-dtname) "</string>\n"
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
         "<string>" emit/scene-configuration-name "</string>\n"
         "                    <key>UISceneDelegateClassName</key>"
         "<string>" emit/scene-delegate-class-name "</string>\n"
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

(def ^:private reserved-bundle-paths
  "Paths at the .app root that bundle assembly owns. A resource of
   the same name would clobber generated bundle machinery, so
   copy-resources! raises instead of overwriting."
  #{"Info.plist" "PkgInfo" "_CodeSignature" "embedded.mobileprovision"})

(defn- copy-resources!
  "Mirror every file under `<src-dir>/Resources/` into the .app
   root, preserving the sub-structure. NSBundle resolves
   `pathForResource:ofType:` relative to the .app root, so a layout
   of `Resources/beep.wav` + `Resources/sfx/boom.wav` lands at
   `<app>/beep.wav` + `<app>/sfx/boom.wav` and both resolve.
   Raises on collision with reserved paths or with `<name>` (the
   executable). Compares case-insensitively because HFS+ and APFS
   default to case-insensitive on macOS and iOS, so `info.plist` as
   a resource would still clobber the generated `Info.plist` on
   disk even though the strings differ. No-ops when Resources/ is
   absent."
  [^java.io.File src-dir ^java.io.File app-dir executable-name]
  (when src-dir
    (let [res-dir  (io/file src-dir "Resources")
          reserved (into #{} (map str/lower-case)
                             (conj reserved-bundle-paths executable-name))]
      (when (.isDirectory res-dir)
        (let [res-path (.toPath res-dir)]
          (doseq [^java.io.File f (file-seq res-dir)
                  :when (.isFile f)]
            (let [rel  (str (.relativize res-path (.toPath f)))
                  top  (first (str/split rel #"/"))
                  dst  (io/file app-dir rel)]
              (when (contains? reserved (str/lower-case top))
                (throw (ex-info
                         (str "Resources/" rel " would overwrite bundle-owned path "
                              top ". Rename the resource.")
                         {:kind :resource-reserved-collision
                          :resource rel
                          :reserved top})))
              (copy-file f dst))))))))

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
     opts       — optional map carrying two independent axes:
                  (1) plugin-manifest overrides for the Info.plist
                  derivations (`:bundle-id`, `:display-name`,
                  `:min-os-version`); each wins over the default
                  derivation when present and falls back to the
                  name-derived or spec-provided value otherwise.
                  (2) `:src-dir` — a user source root. When set,
                  every file under `<src-dir>/Resources/` is copied
                  into the .app root preserving sub-structure, so
                  plugins reach bundled assets via NSBundle at
                  runtime. Both axes travel together because every
                  call site builds the bag from the same place."
  ([name out-dir mach-o spec]
   (assemble name out-dir mach-o spec {}))
  ([name ^java.io.File out-dir ^java.io.File mach-o spec opts]
  (let [app-dir   (io/file out-dir (str name ".app"))
        _         (when (.exists app-dir)
                    (doseq [f (reverse (file-seq app-dir))]
                      (.delete f)))
        _         (.mkdirs app-dir)
        bin-dst   (io/file app-dir name)
        plist-f   (io/file app-dir "Info.plist")
        pkginfo-f (io/file app-dir "PkgInfo")
        src-dir   (:src-dir opts)]
    (java.nio.file.Files/move
      (.toPath mach-o)
      (.toPath bin-dst)
      (into-array java.nio.file.CopyOption
                  [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
    (spit plist-f
          (plist-xml
            {:bundle-id       (or (:bundle-id opts)
                                  (derive-bundle-id name))
             :display-name    (or (:display-name opts) name)
             :executable-name name
             :min-os-version  (or (:min-os-version opts)
                                  (:min-os-version spec))
             :sdk-name        (:sdk spec)
             :platform-dtname (:platform-dtname spec)}))
    (spit pkginfo-f "APPL????")
    (copy-resources! (when src-dir (io/file src-dir)) app-dir name)
    app-dir)))

(defn adhoc-codesign!
  "Ad-hoc sign the bundle with the host codesign tool. Simulator
   builds accept ad-hoc (`-s -`); device builds route through
   `dev-codesign!`. Returns {:exit :out :err} from the codesign
   subprocess."
  [^java.io.File app-dir]
  (shell/sh "codesign" "--force" "--sign" "-" (.getPath app-dir)))

(defn- codesign-failure [err]
  {:exit 1 :out "" :err err})

(defn dev-codesign!
  "Device codesign. Embeds the supplied .mobileprovision as
   `<app-dir>/embedded.mobileprovision`, then calls codesign with
   the dev identity and the entitlements plist.

   `creds` is `{:identity :profile :entitlements}`. Missing or
   nonexistent values surface as a non-zero result the caller
   handles the same way as adhoc failure. Returns
   `{:exit :out :err}` matching `adhoc-codesign!` so one caller
   handles both shapes."
  [^java.io.File app-dir {:keys [identity profile entitlements]}]
  (let [missing (cond-> []
                  (str/blank? identity)
                  (conj "CXC_IOS_CODESIGN_IDENTITY")
                  (str/blank? profile)
                  (conj "CXC_IOS_PROVISIONING_PROFILE")
                  (str/blank? entitlements)
                  (conj "CXC_IOS_ENTITLEMENTS"))]
    (cond
      (seq missing)
      (codesign-failure (str "dev codesign for :ios-device needs these env vars: "
                             (str/join ", " missing)))
      (not (.exists (io/file profile)))
      (codesign-failure (str "provisioning profile not found: " profile))
      (not (.exists (io/file entitlements)))
      (codesign-failure (str "entitlements plist not found: " entitlements))
      :else
      (do (copy-file (io/file profile)
                     (io/file app-dir "embedded.mobileprovision"))
          (shell/sh "codesign" "--force"
                    "--sign" identity
                    "--entitlements" entitlements
                    "--timestamp=none"
                    (.getPath app-dir))))))

(defn codesign!
  "Codesign dispatch. One owner for the `(:codesign spec)` case.
   `creds` is the `{:identity :profile :entitlements}` map the
   `:dev` arm hands to `dev-codesign!`; `:adhoc`, `:none`, and
   `nil` ignore it. Returns `{:exit :out :err}` for every mode
   so build callers propagate failure uniformly. Any value
   outside the domain `#{nil :none :adhoc :dev}` raises so a
   target-spec typo surfaces at the boundary instead of silently
   producing an unsigned bundle."
  [^java.io.File app-dir spec creds]
  (case (:codesign spec)
    (nil :none) {:exit 0 :out "" :err ""}
    :adhoc      (adhoc-codesign! app-dir)
    :dev        (dev-codesign! app-dir creds)
    (throw (ex-info (str "unknown codesign mode: " (pr-str (:codesign spec)))
                    {:kind :unknown-codesign-mode
                     :mode (:codesign spec)}))))
