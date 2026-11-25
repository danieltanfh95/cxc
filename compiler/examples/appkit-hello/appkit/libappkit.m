// libappkit.m — Corvid FFI over AppKit. Drawing primitives + window.
//
// The Corvid binary that loads this dylib runs `[NSApp run]` instead
// of `dispatch_main()` (via `runtime_shape: RuntimeShape.main_runloop`
// on the plugin manifest). AppKit's CFRunLoop drains
// `dispatch_get_main_queue()` as part of its event pump, so atoms
// that dispatch_async to main from worker threads still run.
//
// Compile:
//   clang -dynamiclib -fobjc-arc libappkit.m -framework Cocoa \
//         -install_name @rpath/libappkit.dylib -o libappkit.dylib
//
// Threading contract:
//   * ak_prep and ak_run MUST be called on the main thread.
//   * ak_window_open + all drawing calls are safe from any thread;
//     they marshal to main via dispatch_async internally.
//   * Draws accumulate in a per-window command buffer. ak_present
//     triggers setNeedsDisplay:; drawRect: replays.
//   * Callbacks (keyDown, close) fire on main.

#import <Cocoa/Cocoa.h>

typedef enum {
    OP_CLEAR      = 0,
    OP_RECT       = 1,
    OP_ROUND_RECT = 2,
    OP_LINE       = 3,
    OP_TEXT       = 4,
    OP_IMAGE      = 5,
    OP_GRADIENT   = 6,
} AkOpKind;

@interface AkOp : NSObject {
@public
    AkOpKind kind;
    int x, y, w, h;
    int r, g, b;
    int r2, g2, b2;    // second color for gradient
    CGFloat radius;
    CGFloat alpha;
    NSString* text;
    int text_size;
    BOOL text_bold;
    NSImage* image;
    CGFloat line_thickness;
}
@end
@implementation AkOp
@end

// Global state — single-window per process. Multi-window is
// deferred; the current landing focus is the runtime-shape mechanism.
static NSWindow*              g_window   = nil;
static NSMutableArray<AkOp*>* g_pending  = nil;
static NSMutableDictionary<NSString*, NSImage*>* g_image_cache = nil;

static void (*g_key_cb)(int, const char*)       = NULL;
static void (*g_close_cb)(void)                 = NULL;
static void (*g_mouse_down_cb)(int, int, int)   = NULL;
static void (*g_mouse_up_cb)(int, int, int)     = NULL;
static void (*g_tick_cb)(int)                   = NULL;
static NSTimer*    g_tick_timer                 = nil;
static NSTimeInterval g_tick_interval_s         = 0.016;

static void ensure_state(void) {
    if (!g_pending)     g_pending     = [NSMutableArray new];
    if (!g_image_cache) g_image_cache = [NSMutableDictionary new];
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
        NSColor* c1 = [NSColor colorWithSRGBRed:op->r/255.0
                                          green:op->g/255.0
                                           blue:op->b/255.0
                                          alpha:op->alpha];
        switch (op->kind) {
            case OP_CLEAR: {
                [c1 setFill];
                NSRectFill(self.bounds);
                break;
            }
            case OP_RECT: {
                [c1 setFill];
                NSRectFill(NSMakeRect(op->x, op->y, op->w, op->h));
                break;
            }
            case OP_ROUND_RECT: {
                NSBezierPath* p = [NSBezierPath
                    bezierPathWithRoundedRect:NSMakeRect(op->x, op->y, op->w, op->h)
                                       xRadius:op->radius
                                       yRadius:op->radius];
                [c1 setFill];
                [p fill];
                break;
            }
            case OP_LINE: {
                NSBezierPath* p = [NSBezierPath bezierPath];
                [p setLineWidth:op->line_thickness];
                [p moveToPoint:NSMakePoint(op->x + 0.5, op->y + 0.5)];
                [p lineToPoint:NSMakePoint(op->w + 0.5, op->h + 0.5)];
                [c1 setStroke];
                [p stroke];
                break;
            }
            case OP_GRADIENT: {
                NSColor* c2 = [NSColor colorWithSRGBRed:op->r2/255.0
                                                  green:op->g2/255.0
                                                   blue:op->b2/255.0
                                                  alpha:op->alpha];
                NSGradient* gr = [[NSGradient alloc]
                    initWithStartingColor:c1 endingColor:c2];
                [gr drawInRect:NSMakeRect(op->x, op->y, op->w, op->h)
                         angle:-90];
                break;
            }
            case OP_TEXT: {
                NSFont* font = op->text_bold
                    ? [NSFont boldSystemFontOfSize:op->text_size]
                    : [NSFont systemFontOfSize:op->text_size];
                NSDictionary* attrs = @{
                    NSFontAttributeName: font,
                    NSForegroundColorAttributeName: c1,
                };
                [op->text drawAtPoint:NSMakePoint(op->x, op->y)
                       withAttributes:attrs];
                break;
            }
            case OP_IMAGE: {
                if (op->image) {
                    NSRect dst = NSMakeRect(op->x, op->y, op->w, op->h);
                    [op->image drawInRect:dst
                                 fromRect:NSZeroRect
                                operation:NSCompositingOperationSourceOver
                                 fraction:op->alpha
                           respectFlipped:YES
                                    hints:nil];
                }
                break;
            }
        }
    }
}
@end

static AkView* g_view = nil;

// Marshal a block onto main. Safe to call from any thread.
static void on_main(dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

@interface AkAppDelegate : NSObject <NSApplicationDelegate, NSWindowDelegate>
@end
@implementation AkAppDelegate
- (void)windowWillClose:(NSNotification*)n {
    if (g_close_cb) g_close_cb();
    [NSApp terminate:nil];
}
- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication*)app {
    return YES;
}
@end

@interface AkKeyView : AkView
@end
@implementation AkKeyView
- (void)keyDown:(NSEvent*)e {
    if (!g_key_cb) return;
    NSString* chars = e.charactersIgnoringModifiers ?: @"";
    g_key_cb((int)e.keyCode, chars.UTF8String);
}
- (void)mouseDown:(NSEvent*)e {
    if (!g_mouse_down_cb) return;
    NSPoint p = [self convertPoint:e.locationInWindow fromView:nil];
    g_mouse_down_cb((int)p.x, (int)p.y, (int)e.buttonNumber);
}
- (void)mouseUp:(NSEvent*)e {
    if (!g_mouse_up_cb) return;
    NSPoint p = [self convertPoint:e.locationInWindow fromView:nil];
    g_mouse_up_cb((int)p.x, (int)p.y, (int)e.buttonNumber);
}
- (void)rightMouseDown:(NSEvent*)e {
    if (!g_mouse_down_cb) return;
    NSPoint p = [self convertPoint:e.locationInWindow fromView:nil];
    g_mouse_down_cb((int)p.x, (int)p.y, (int)e.buttonNumber);
}
- (void)rightMouseUp:(NSEvent*)e {
    if (!g_mouse_up_cb) return;
    NSPoint p = [self convertPoint:e.locationInWindow fromView:nil];
    g_mouse_up_cb((int)p.x, (int)p.y, (int)e.buttonNumber);
}
@end

// Public FFI --------------------------------------------------------

void ak_prep(void) {
    on_main(^{
        ensure_state();
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
        static AkAppDelegate* d = nil;
        if (!d) { d = [AkAppDelegate new]; [NSApp setDelegate:d]; }
    });
}

int ak_window_open(int w, int h, const char* title) {
    on_main(^{
        ensure_state();
        NSRect frame = NSMakeRect(200, 200, w, h);
        NSWindowStyleMask mask = NSWindowStyleMaskTitled
                               | NSWindowStyleMaskClosable
                               | NSWindowStyleMaskResizable
                               | NSWindowStyleMaskMiniaturizable;
        g_window = [[NSWindow alloc] initWithContentRect:frame
                                               styleMask:mask
                                                 backing:NSBackingStoreBuffered
                                                   defer:NO];
        g_window.title = title
            ? [NSString stringWithUTF8String:title]
            : @"appkit";
        g_window.releasedWhenClosed = NO;
        g_window.delegate = (id<NSWindowDelegate>)[NSApp delegate];

        g_view = [[AkKeyView alloc] initWithFrame:frame];
        g_view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        g_window.contentView = g_view;

        g_window.initialFirstResponder = g_view;
        [g_window makeFirstResponder:g_view];

        // Center on the primary (menubar) screen; NSScreen.mainScreen
        // is unreliable at launch on multi-display setups.
        NSArray<NSScreen*>* screens = [NSScreen screens];
        if (screens.count > 0) {
            NSScreen* screen = screens[0];
            NSRect sf = screen.visibleFrame;
            NSRect wf = g_window.frame;
            NSPoint origin = NSMakePoint(
                sf.origin.x + (sf.size.width  - wf.size.width)  / 2,
                sf.origin.y + (sf.size.height - wf.size.height) / 2);
            [g_window setFrameOrigin:origin];
        }
        [g_window makeKeyAndOrderFront:nil];
        [NSApp activateIgnoringOtherApps:YES];
    });
    return 1;
}

void ak_clear(int r, int g, int b) {
    on_main(^{
        ensure_state();
        [g_pending removeAllObjects];
        AkOp* op = [AkOp new];
        op->kind = OP_CLEAR;
        op->r = r; op->g = g; op->b = b; op->alpha = 1.0;
        [g_pending addObject:op];
    });
}

void ak_gradient(int x, int y, int w, int h,
                 int r1, int g1, int b1,
                 int r2, int g2, int b2) {
    on_main(^{
        ensure_state();
        AkOp* op = [AkOp new];
        op->kind = OP_GRADIENT;
        op->x = x; op->y = y; op->w = w; op->h = h;
        op->r = r1; op->g = g1; op->b = b1;
        op->r2 = r2; op->g2 = g2; op->b2 = b2;
        op->alpha = 1.0;
        [g_pending addObject:op];
    });
}

void ak_rect(int x, int y, int w, int h, int r, int g, int b) {
    on_main(^{
        ensure_state();
        AkOp* op = [AkOp new];
        op->kind = OP_RECT;
        op->x = x; op->y = y; op->w = w; op->h = h;
        op->r = r; op->g = g; op->b = b; op->alpha = 1.0;
        [g_pending addObject:op];
    });
}

void ak_round_rect(int x, int y, int w, int h,
                   int radius, int r, int g, int b) {
    on_main(^{
        ensure_state();
        AkOp* op = [AkOp new];
        op->kind = OP_ROUND_RECT;
        op->x = x; op->y = y; op->w = w; op->h = h;
        op->radius = radius;
        op->r = r; op->g = g; op->b = b; op->alpha = 1.0;
        [g_pending addObject:op];
    });
}

void ak_line(int x1, int y1, int x2, int y2,
             int thickness, int r, int g, int b) {
    on_main(^{
        ensure_state();
        AkOp* op = [AkOp new];
        op->kind = OP_LINE;
        op->x = x1; op->y = y1; op->w = x2; op->h = y2;
        op->line_thickness = thickness > 0 ? thickness : 1;
        op->r = r; op->g = g; op->b = b; op->alpha = 1.0;
        [g_pending addObject:op];
    });
}

static void enqueue_text(int x, int y, const char* s, int size, BOOL bold,
                         int r, int g, int b) {
    NSString* str = s ? [NSString stringWithUTF8String:s] : @"";
    on_main(^{
        ensure_state();
        AkOp* op = [AkOp new];
        op->kind = OP_TEXT;
        op->x = x; op->y = y;
        op->text_size = size > 0 ? size : 12;
        op->text_bold = bold;
        op->text = str;
        op->r = r; op->g = g; op->b = b; op->alpha = 1.0;
        [g_pending addObject:op];
    });
}

void ak_text(int x, int y, const char* s, int size, int r, int g, int b) {
    enqueue_text(x, y, s, size, NO, r, g, b);
}

void ak_text_bold(int x, int y, const char* s, int size, int r, int g, int b) {
    enqueue_text(x, y, s, size, YES, r, g, b);
}

// Load-cache-draw an image. Absolute paths only. Missing images
// print once and become no-ops (drawing continues past).
void ak_image(int x, int y, int w, int h, const char* path) {
    if (!path) return;
    NSString* p = [NSString stringWithUTF8String:path];
    on_main(^{
        ensure_state();
        NSImage* img = g_image_cache[p];
        if (!img) {
            img = [[NSImage alloc] initWithContentsOfFile:p];
            if (!img) {
                fprintf(stderr, "ak_image: cannot load %s\n", path);
                g_image_cache[p] = (id)[NSNull null];
                return;
            }
            g_image_cache[p] = img;
        } else if ([(id)img isKindOfClass:[NSNull class]]) {
            return;
        }
        AkOp* op = [AkOp new];
        op->kind = OP_IMAGE;
        op->x = x; op->y = y; op->w = w; op->h = h;
        op->image = img;
        op->alpha = 1.0;
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

void ak_subscribe_mouse_down(void (*cb)(int, int, int)) {
    g_mouse_down_cb = cb;
}

void ak_subscribe_mouse_up(void (*cb)(int, int, int)) {
    g_mouse_up_cb = cb;
}

static void start_tick_timer(void) {
    if (g_tick_timer) {
        [g_tick_timer invalidate];
        g_tick_timer = nil;
    }
    if (!g_tick_cb) return;
    __block NSDate* last = [NSDate date];
    g_tick_timer = [NSTimer scheduledTimerWithTimeInterval:g_tick_interval_s
                                                   repeats:YES
                                                     block:^(NSTimer* t) {
        if (!g_tick_cb) return;
        NSDate* now = [NSDate date];
        int dt_ms = (int)([now timeIntervalSinceDate:last] * 1000.0);
        last = now;
        g_tick_cb(dt_ms);
    }];
    [[NSRunLoop mainRunLoop] addTimer:g_tick_timer
                              forMode:NSRunLoopCommonModes];
}

// Subscribe a tick callback. Fires at `g_tick_interval_s`-second
// cadence (default 16ms; adjust via ak_set_tick_interval). The
// callback receives dt_ms since the previous tick, so the game's
// time step is independent of the configured interval.
void ak_tick(void (*cb)(int)) {
    g_tick_cb = cb;
    on_main(^{ start_tick_timer(); });
}

int ak_set_tick_interval(int ms) {
    if (ms <= 0) return 0;
    g_tick_interval_s = ms / 1000.0;
    on_main(^{
        if (g_tick_cb) start_tick_timer();
    });
    return 1;
}

// BOARD-6 (2026-11-13) — synchronous sleep with a draw flush so
// mid-sim frames paint instead of coalescing into the final draw.
// Useful for cxc code that drives a simulation synchronously inside
// main() and wants a visible step. For interactive apps, prefer
// ak_tick (NSTimer-driven) so the main runloop stays responsive.
// Returns 1 on success, 0 on bad arg (cxc FFI needs a return type).
int ak_sleep(int ms) {
    if (ms <= 0) return 0;
    on_main(^{
        if (g_window) [[g_window contentView] setNeedsDisplay:YES];
        [[NSRunLoop currentRunLoop]
            runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0]];
    });
    usleep((useconds_t)ms * 1000);
    return 1;
}
