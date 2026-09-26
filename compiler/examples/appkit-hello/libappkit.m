// libappkit.m — Corvid FFI over AppKit.
//
// This dylib is the plugin that a Corvid program uses to open a
// native window, draw into it, and receive input events. It is
// designed to be loaded by a Corvid binary whose main() runs
// `[NSApp run]` instead of `dispatch_main()`; that's the only
// runtime prerequisite. Atoms then touch AppKit APIs via
// dispatch_async(dispatch_get_main_queue(), ^{ ... }) — the AppKit
// run loop drains the main dispatch queue.
//
// Compile:
//   clang -dynamiclib libappkit.m -framework Cocoa \
//         -install_name @rpath/libappkit.dylib -o libappkit.dylib
//
// FFI surface (C-callable):
//   void ak_prep(void);                              // register app + set activation policy
//   int  ak_window_open(int w, int h, const char* title);
//   void ak_clear(int r, int g, int b);
//   void ak_rect(int x, int y, int w, int h, int r, int g, int b);
//   void ak_text(int x, int y, const char* s, int size, int r, int g, int b);
//   void ak_present(void);                            // trigger a redraw
//   int  ak_run(void);                                // [NSApp run]; returns on quit
//   void ak_quit(void);                               // ask app to terminate
//
//   void ak_subscribe_key(void (*cb)(int keycode, const char* chars));
//   void ak_subscribe_close(void (*cb)(void));
//
// Threading contract:
//   - ak_prep and ak_run MUST be called on the main thread.
//   - ak_window_open / ak_rect / ak_text / ak_clear / ak_present are safe
//     from any thread; they marshal to main via dispatch_async internally.
//   - Callbacks fire on the main thread. The caller is responsible for
//     hopping to an atom queue if it wants atom semantics.
//
// Draw model: draws accumulate in a command buffer. ak_present triggers
// setNeedsDisplay:; drawRect: replays the accumulated commands. ak_clear
// truncates the buffer to just a clear op.

#import <Cocoa/Cocoa.h>

typedef enum {
    OP_CLEAR = 0,
    OP_RECT  = 1,
    OP_TEXT  = 2,
} AkOpKind;

@interface AkOp : NSObject {
@public
    AkOpKind kind;
    int x, y, w, h;
    int r, g, b;
    NSString* text;
    int size;
}
@end
@implementation AkOp
@end

// Global state — this dylib assumes single window per process. That's
// the R1 shape; multi-window is a follow-on decision.
static NSWindow*              g_window  = nil;
static NSMutableArray<AkOp*>* g_pending = nil;

static void (*g_key_cb)(int, const char*)   = NULL;
static void (*g_close_cb)(void)              = NULL;

static void ensure_pending(void) {
    if (!g_pending) g_pending = [NSMutableArray new];
}

@interface AkView : NSView
@property (nonatomic, strong) NSMutableArray<AkOp*>* ops;
@end

@implementation AkView
- (BOOL)isFlipped { return YES; }
- (BOOL)acceptsFirstResponder { return YES; }
- (instancetype)initWithFrame:(NSRect)f {
    if ((self = [super initWithFrame:f])) {
        _ops = [NSMutableArray new];
    }
    return self;
}
- (void)drawRect:(NSRect)dirty {
    for (AkOp* op in self.ops) {
        NSColor* c = [NSColor colorWithSRGBRed:op->r/255.0
                                         green:op->g/255.0
                                          blue:op->b/255.0
                                         alpha:1.0];
        switch (op->kind) {
            case OP_CLEAR:
                [c setFill];
                NSRectFill(self.bounds);
                break;
            case OP_RECT:
                [c setFill];
                NSRectFill(NSMakeRect(op->x, op->y, op->w, op->h));
                break;
            case OP_TEXT: {
                NSDictionary* attrs = @{
                    NSFontAttributeName: [NSFont systemFontOfSize:op->size],
                    NSForegroundColorAttributeName: c,
                };
                [op->text drawAtPoint:NSMakePoint(op->x, op->y)
                       withAttributes:attrs];
                break;
            }
        }
    }
}
- (void)keyDown:(NSEvent*)e {
    if (!g_key_cb) return;
    NSString* chars = e.charactersIgnoringModifiers ?: @"";
    g_key_cb((int)e.keyCode, chars.UTF8String);
}
@end

static AkView* g_view = nil;

// Marshal a block onto main. Safe to call from any thread. If we're
// already on main we run inline to avoid deferring visible work by
// one run-loop turn.
static void on_main(dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

@interface AppDelegate : NSObject <NSApplicationDelegate, NSWindowDelegate>
@end
@implementation AppDelegate
- (void)windowWillClose:(NSNotification*)n {
    if (g_close_cb) g_close_cb();
}
- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication*)app {
    return YES;
}
@end

// Public FFI --------------------------------------------------------

void ak_prep(void) {
    on_main(^{
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
        static AppDelegate* d = nil;
        if (!d) { d = [AppDelegate new]; [NSApp setDelegate:d]; }
    });
}

int ak_window_open(int w, int h, const char* title) {
    __block int ok = 1;
    on_main(^{
        NSRect frame = NSMakeRect(200, 200, w, h);
        NSWindowStyleMask mask = NSWindowStyleMaskTitled
                               | NSWindowStyleMaskClosable
                               | NSWindowStyleMaskResizable
                               | NSWindowStyleMaskMiniaturizable;
        g_window = [[NSWindow alloc] initWithContentRect:frame
                                               styleMask:mask
                                                 backing:NSBackingStoreBuffered
                                                   defer:NO];
        g_window.title = title ? [NSString stringWithUTF8String:title]
                                : @"appkit";
        g_window.releasedWhenClosed = NO;
        g_window.delegate = (id<NSWindowDelegate>)[NSApp delegate];

        g_view = [[AkView alloc] initWithFrame:frame];
        g_view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        g_window.contentView = g_view;

        g_window.initialFirstResponder = g_view;
        [g_window makeFirstResponder:g_view];

        NSScreen* screen = [NSScreen screens].count > 0
                         ? [NSScreen screens][0]
                         : nil;
        if (screen) {
            NSRect sf = screen.visibleFrame;
            NSRect wf = g_window.frame;
            NSPoint origin = NSMakePoint(sf.origin.x + (sf.size.width - wf.size.width) / 2,
                                         sf.origin.y + (sf.size.height - wf.size.height) / 2);
            [g_window setFrameOrigin:origin];
        }
        [g_window makeKeyAndOrderFront:nil];
        [NSApp activateIgnoringOtherApps:YES];
    });
    return ok;
}

void ak_clear(int r, int g, int b) {
    on_main(^{
        ensure_pending();
        [g_pending removeAllObjects];
        AkOp* op = [AkOp new];
        op->kind = OP_CLEAR;
        op->r = r; op->g = g; op->b = b;
        [g_pending addObject:op];
    });
}

void ak_rect(int x, int y, int w, int h, int r, int g, int b) {
    on_main(^{
        ensure_pending();
        AkOp* op = [AkOp new];
        op->kind = OP_RECT;
        op->x = x; op->y = y; op->w = w; op->h = h;
        op->r = r; op->g = g; op->b = b;
        [g_pending addObject:op];
    });
}

void ak_text(int x, int y, const char* s, int size, int r, int g, int b) {
    NSString* str = s ? [NSString stringWithUTF8String:s] : @"";
    on_main(^{
        ensure_pending();
        AkOp* op = [AkOp new];
        op->kind = OP_TEXT;
        op->x = x; op->y = y;
        op->size = size > 0 ? size : 12;
        op->text = str;
        op->r = r; op->g = g; op->b = b;
        [g_pending addObject:op];
    });
}

void ak_present(void) {
    on_main(^{
        if (!g_view) return;
        g_view.ops = [g_pending mutableCopy];
        [g_view setNeedsDisplay:YES];
    });
}

int ak_run(void) {
    // Must run on main. AppKit will trap if not.
    if (![NSThread isMainThread]) {
        fprintf(stderr, "ak_run: called off-main; refusing\n");
        return -1;
    }
    [NSApp run];
    return 0;
}

void ak_quit(void) {
    on_main(^{ [NSApp terminate:nil]; });
}

void ak_subscribe_key(void (*cb)(int, const char*)) {
    g_key_cb = cb;
}

void ak_subscribe_close(void (*cb)(void)) {
    g_close_cb = cb;
}
