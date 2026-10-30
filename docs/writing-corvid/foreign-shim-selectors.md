# Foreign shim selectors · how `respondsToSelector:` lines up with the atom

## Problem

A `foreign "lib":` plugin's ObjC shim often wants to call back into
the main atom. The idiomatic pattern on `cxc_main_atom` is

```objc
SEL sel = @selector(on_tapWithx:y:);
if ([cxc_main_atom respondsToSelector:sel]) {
    // ...
}
```

The selector name here is the result of cxc's lowering convention. The
shim encodes a string the compiler produces. There is no header the
shim can include to pick up the names from the atom declaration, so a
mangling change in the compiler silently makes every shim probe return
`NO` and the user's taps / draws / ticks stop firing with no
diagnostic.

Until the compiler ships a generated selector header, treat this as a
documented seam the shim author reads before writing probes.

## Convention

Given a `.cxc` arm like

```corvid
playing <: on_tap(x: Int32!, y: Int32!) -> playing
    then:
        ...
```

the generated ObjC selector is `on_tapWithx:y:` and the full method
signature is

```objc
- (void)on_tapWithx:(int)x y:(int)y;
```

Rules:

- The method name is the atom-side verb (`on_tap`) verbatim.
- For a zero-arg form (`on_tick -> running`), the selector is bare:
  `@selector(on_tick)`.
- For a one-or-more-arg form, the first arg's label is appended as
  `With<label>`, then every subsequent arg appends as `<label>:`.
- Arg labels keep their cxc spelling. Underscores stay underscores
  (`dt_millis`, not `dtMillis`), so `on_tick(dt_millis: Int32!)`
  becomes `@selector(on_tickWithdt_millis:)`.
- Int32 widens to `int` at the ObjC boundary. Int64 widens to
  `long long`. Any value-typed arg's representation mirrors the atom
  env field's representation. Pointer-typed args (`Any!`, atom
  references) stay `id`.

## What this means for a plugin author

A shim that wants to call a cxc arm must spell the selector the way
the lowering emits it. Writing `on_tap(x:y:)` instead of
`on_tapWithx:y:` returns a silent `NO`.

Three live shims that follow this convention:

- `compiler/examples/uikit-canvas/uikit/libuikit.m` — `on_tapWithx:y:`,
  `on_tickWithdt_millis:`.
- `compiler/examples/uikit-dots/canvas/libcanvas.m` —
  `on_touch_beginWithid:x:y:`, `on_touch_moveWithid:x:y:`,
  `on_touch_endWithid:x:y:`.

Every selector in those files is the output of the rules above
applied to the arm's `then:` signature in the same project's `.cxc`.

## Future replacement

When a second shim-heavy plugin lands (fourth site, roughly), the
compiler should emit a `cxc_shim_selectors.h` alongside the `.m`,
exposing mnemonic macros:

```c
#define CXC_SEL_ON_TAP         @selector(on_tapWithx:y:)
#define CXC_SEL_ON_TOUCH_BEGIN @selector(on_touch_beginWithid:x:y:)
```

The shim includes the header and stops hard-coding the mangling. The
header is derivable from the atom declarations the compiler already
has in hand, so there is no new source of truth to maintain.

Until that header exists, this doc is the seam-spec. If the mangling
in `cxc.emit` changes, update this file and every shim at the same
time.
