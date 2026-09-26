// mechanism_B.m — Prototype B: runtime-hook negotiation.
//
// The plugin dylib REGISTERS a hook at load time via
// __attribute__((constructor)). The compiler-emitted main() calls a
// well-known symbol (_cxc_runtime_prep) after prelude init, before
// its default tail. If a plugin registered a hook, prep does
// [NSApp run] (never returns). If no plugin registered, prep is a
// no-op and main falls through to dispatch_main().
//
// Compile:
//   clang -fobjc-arc mechanism_B.m -framework Cocoa -o mechanism_B
//
// Everything lives in one file for legibility. In a landed version:
//   • The `runtime_shape` selection code (the __attribute__((constructor))
//     that assigns to g_runtime_hook) would live in the AppKit plugin
//     dylib (libappkit_hook.dylib).
//   • The emit_main-like tail (`_cxc_runtime_prep()` then
//     dispatch_main()) would be what the Corvid compiler emits
//     for every program — the "always-emit-hook-check" shape.
//   • User's .cxc source declares nothing; the plugin's presence alone
//     redirects main to NSApp run.

#import <Cocoa/Cocoa.h>
#include <stdio.h>

// ---------------------------------------------------------------------------
// What the Corvid runtime would provide.
//
// A global function pointer, initially NULL. The compiler emits a
// call to `_cxc_runtime_prep` before the tail of main(). If a plugin
// has registered a hook, the runtime invokes it; the hook may run its
// own event loop and never return. Otherwise, main falls through to
// dispatch_main.
static void (*g_runtime_hook)(void) = NULL;

void _cxc_runtime_prep(void) {
    if (g_runtime_hook) g_runtime_hook();
}

// ---------------------------------------------------------------------------
// What the AppKit plugin would provide (via libappkit_hook.dylib).
//
// A constructor that runs at dylib load time and installs itself as
// the runtime hook. The user's .cxc source never mentions this — the
// mere act of linking the plugin dylib redirects main.
static void plugin_run(void);

__attribute__((constructor))
static void plugin_register_hook(void) {
    g_runtime_hook = plugin_run;
    printf("[B] plugin constructor ran; hook registered\n");
}

@interface HelloView : NSView
@end
@implementation HelloView
- (BOOL)isFlipped { return YES; }
- (void)drawRect:(NSRect)dirty {
    [[NSColor colorWithSRGBRed:0.09 green:0.10 blue:0.13 alpha:1] setFill];
    NSRectFill(self.bounds);
    [[NSColor colorWithSRGBRed:0.35 green:0.51 blue:0.78 alpha:1] setFill];
    NSRectFill(NSMakeRect(40, 40, 200, 80));
    [[NSColor colorWithSRGBRed:0.78 green:0.35 blue:0.35 alpha:1] setFill];
    NSRectFill(NSMakeRect(260, 40, 200, 80));
    NSDictionary* a = @{
        NSFontAttributeName: [NSFont systemFontOfSize:22],
        NSForegroundColorAttributeName: [NSColor whiteColor],
    };
    [@"mechanism B: runtime hook"
     drawAtPoint:NSMakePoint(40, 160) withAttributes:a];
    NSDictionary* b = @{
        NSFontAttributeName: [NSFont systemFontOfSize:14],
        NSForegroundColorAttributeName: [NSColor colorWithWhite:0.55 alpha:1],
    };
    [@"plugin ctor registered hook; main() called _cxc_runtime_prep()"
     drawAtPoint:NSMakePoint(40, 200) withAttributes:b];
}
@end

static void plugin_run(void) {
    printf("[B] plugin_run: [NSApp run] — hook is now the event loop\n");
    NSRect frame = NSMakeRect(200, 200, 720, 480);
    NSWindow* w = [[NSWindow alloc]
        initWithContentRect:frame
                  styleMask:(NSWindowStyleMaskTitled
                            | NSWindowStyleMaskClosable
                            | NSWindowStyleMaskResizable)
                    backing:NSBackingStoreBuffered
                      defer:NO];
    w.title = @"mechanism B — runtime hook";
    w.releasedWhenClosed = NO;
    w.contentView = [[HelloView alloc] initWithFrame:frame];
    NSScreen* s = [NSScreen screens][0];
    NSRect sf = s.visibleFrame;
    [w setFrameOrigin:NSMakePoint(
        sf.origin.x + (sf.size.width  - 720) / 2,
        sf.origin.y + (sf.size.height - 480) / 2)];
    [w makeKeyAndOrderFront:nil];
    [NSApplication sharedApplication];
    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
    [NSApp activateIgnoringOtherApps:YES];
    [NSApp run];
}

// ---------------------------------------------------------------------------
// What the Corvid compiler would emit for main().
//
// The `_cxc_runtime_prep()` call is the "hook slot". If prep never
// returns (because a plugin registered), the following dispatch_main
// is dead code. If no plugin registered, prep is a no-op and
// dispatch_main is the default fallback.

int main(int argc, const char* argv[]) {
    @autoreleasepool {
        // (bootstrap: atom construction goes here in the real compiler)
        _cxc_runtime_prep();
        printf("[B] no runtime hook took over; falling through to dispatch_main\n");
        dispatch_main();
    }
    return 0;
}
