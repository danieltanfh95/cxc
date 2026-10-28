# ios-hello

The smallest useful iOS Simulator demo written in Corvid. Compiles
to a code-signed `.app` bundle, boots in the iOS Simulator, shows
the cxc-generated placeholder UI (system background + centered
`cxc / uikit` label), and logs lifecycle messages as UIApplication
fires each callback on the Main atom.

Zero Objective-C in the `.cxc` source. The `RuntimeShape.uikit`
field on the plugin manifest routes `main()` through a cxc-generated
`CxcAppDelegate` that forwards UIApplication callbacks into atom
messages (`did_launch`, `will_resign`, `did_background`,
`will_foreground`, `did_activate`, `will_terminate`). The atom
responds to the subset it cares about and ignores the rest.

## What this demonstrates

- `runtime_shape: RuntimeShape.uikit` on a `def plugin:` block —
  the whole UIKit integration carried by one line of declaration.
- Compile-time emission of UIApplicationMain + CxcAppDelegate.
- `.app` bundle assembly with Info.plist, PkgInfo, ad-hoc code-sign,
  all driven by `--target=ios-sim`. No Xcode project.
- Lifecycle messages flow as regular cxc arms. Writing a transition
  that fires on resume takes one line.

## Files

- [ios-hello.cxc](ios-hello.cxc): the demo, one plugin block + a
  main atom with six lifecycle arms.
- [build.sh](build.sh): compiles to `build/ios-hello.app`. Pass
  `--boot` to install and launch in the first available iOS
  Simulator device.

## Build

```bash
./build.sh                 # compile only
./build.sh --boot          # compile, install, launch, stream log
./build.sh --boot --device <UDID>   # pick a specific simulator
```

First boot needs an iOS Simulator runtime installed. If `xcrun
simctl list devices available` is empty, open Xcode → Settings →
Platforms and download iOS. One-time 7 GB download.

## Lifecycle output

After `./build.sh --boot`:

```
ios-hello: did_launch
ios-hello: did_activate
```

Press Cmd-Shift-H to send the app to background:

```
ios-hello: will_resign
ios-hello: did_background
```

Tap it in the app switcher to come back:

```
ios-hello: will_foreground
ios-hello: did_activate
```
