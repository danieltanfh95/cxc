// mechanism_C.m — Prototype C: weak-linked provider symbol.
//
// The compiler-emitted main() unconditionally references a weakly-
// linked symbol `_Cxc_main_provider`. When the plugin dylib is
// linked, the strong definition inside the dylib overrides the weak
// null, and main() defers to it. When no plugin is linked, the weak
// symbol resolves to NULL and main() falls through to dispatch_main.
//
// Compile:
//   clang -fobjc-arc mechanism_C.m -framework Cocoa -o mechanism_C
//
// Everything lives in one file for legibility. In a landed version:
//   • The strong definition of `_Cxc_main_provider` would live in the
//     AppKit plugin dylib (libappkit_provider.dylib).
//   • The weak forward-declaration is what the Corvid compiler emits
//     for every program (in the emit-main prelude).
//   • User's .cxc source declares nothing; the linker decides.

#import <Cocoa/Cocoa.h>
#include <stdio.h>

// ---------------------------------------------------------------------------
// What the Corvid runtime / compiler emit would include.
//
// Weak forward declaration. If nothing defines this strongly at link
// time, the symbol resolves to NULL. If the AppKit plugin dylib is
// linked, its strong definition wins.
extern void _Cxc_main_provider(void) __attribute__((weak));

// ---------------------------------------------------------------------------
// What the AppKit plugin dylib would provide.
//
// A strong definition of `_Cxc_main_provider`. Its presence in the
// linked binary is the runtime-shape signal. No constructor needed;
// no runtime pointer assignment; the linker binds statically.

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
    [@"mechanism C: weak link"
     drawAtPoint:NSMakePoint(40, 160) withAttributes:a];
    NSDictionary* b = @{
        NSFontAttributeName: [NSFont systemFontOfSize:14],
        NSForegroundColorAttributeName: [NSColor colorWithWhite:0.55 alpha:1],
    };
    [@"plugin provided strong _Cxc_main_provider; main() saw non-NULL"
     drawAtPoint:NSMakePoint(40, 200) withAttributes:b];
}
@end

// STRONG definition. When linked into the binary, main() sees non-NULL.
void _Cxc_main_provider(void) {
    printf("[C] _Cxc_main_provider: strong symbol won; [NSApp run]\n");
    NSRect frame = NSMakeRect(200, 200, 720, 480);
    NSWindow* w = [[NSWindow alloc]
        initWithContentRect:frame
                  styleMask:(NSWindowStyleMaskTitled
                            | NSWindowStyleMaskClosable
                            | NSWindowStyleMaskResizable)
                    backing:NSBackingStoreBuffered
                      defer:NO];
    w.title = @"mechanism C — weak-link";
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
// The weak-link check is the "opt-in" mechanism. If the symbol
// resolved to NULL, main() falls through to dispatch_main. If it
// resolved to a real function, main() defers.

int main(int argc, const char* argv[]) {
    @autoreleasepool {
        // (bootstrap: atom construction goes here in the real compiler)
        if (_Cxc_main_provider) {
            _Cxc_main_provider();
            return 0;   /* provider owns the exit path */
        }
        printf("[C] no _Cxc_main_provider linked; falling through to dispatch_main\n");
        dispatch_main();
    }
    return 0;
}
