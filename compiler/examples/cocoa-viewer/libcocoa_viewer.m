// libcocoa_viewer.m — snapshot-only Cocoa wrapper for Corvid FFI drawing.
//
// Compile:
//   clang -dynamiclib libcocoa_viewer.m -framework Cocoa -o libcocoa_viewer.dylib
//
// FFI surface (C-callable):
//   void cv_open_offscreen(int w, int h);
//   void cv_clear(int r, int g, int b);
//   void cv_rect(int x, int y, int w, int h, int r, int g, int b);
//   void cv_text(int x, int y, const char* s, int size, int r, int g, int b);
//   void cv_line(int x1, int y1, int x2, int y2, int r, int g, int b);
//   int  cv_snapshot(const char* path);
//
// Coordinates: top-left origin. Colors: 0-255 per channel.
//
// Note on windowed modes: an earlier iteration exposed cv_open /
// cv_present / cv_next_key / cv_run so a Corvid sim could drive a
// live NSWindow. That path is not workable in-process with the
// current Corvid runtime. Corvid's generated `main()` calls
// `dispatch_main()` which pumps the main queue but never runs a
// CFRunLoop, and its SINGLE_MAIN_QUEUE runtime routes atom sync
// sends inline on the caller's thread — typically a libdispatch
// worker, not the main thread. Every hop back to main fails:
//   • inline AppKit: NSInternalInconsistencyException (AppKit checks
//     the running thread, not queue ownership).
//   • dispatch_sync(main_queue): libdispatch traps because we
//     transitively own main queue via the target chain.
//   • dispatch_async + semaphore: deadlocks — main queue can't drain
//     while a caller-chain block is still executing.
//   • performSelectorOnMainThread: NSDestinationInvalidException
//     because main thread has no NSRunLoop.
// The dylib is therefore snapshot-only: draw ops accumulate in
// memory and cv_snapshot renders them offscreen to a PNG. The
// `watch` shell wrapper launches Preview.app on the frames dir —
// Preview supports arrow-key stepping between images.

#import <Cocoa/Cocoa.h>

typedef enum {
    OP_CLEAR = 0,
    OP_RECT  = 1,
    OP_TEXT  = 2,
    OP_LINE  = 3,
} DrawOpKind;

@interface DrawOp : NSObject {
@public
    DrawOpKind kind;
    int x, y, w, h;
    int r, g, b;
    NSString* text;
    int size;
}
@end
@implementation DrawOp
@end

static int g_width  = 720;
static int g_height = 480;
static NSMutableArray<DrawOp*>* g_pending = nil;

static void ensure_pending(void) {
    if (!g_pending) g_pending = [NSMutableArray new];
}

// Paints a stream of DrawOp's into the current NSGraphicsContext.
// Called from CanvasView's drawRect via cacheDisplayInRect.
static void paint_ops(NSArray<DrawOp*>* ops, NSRect bounds) {
    for (DrawOp* op in ops) {
        NSColor* color = [NSColor colorWithSRGBRed:op->r/255.0
                                            green:op->g/255.0
                                             blue:op->b/255.0
                                            alpha:1.0];
        switch (op->kind) {
            case OP_CLEAR: {
                [color setFill];
                NSRectFill(bounds);
                break;
            }
            case OP_RECT: {
                [color setFill];
                NSRectFill(NSMakeRect(op->x, op->y, op->w, op->h));
                break;
            }
            case OP_LINE: {
                [color setStroke];
                NSBezierPath* path = [NSBezierPath bezierPath];
                [path setLineWidth:1.5];
                [path moveToPoint:NSMakePoint(op->x + 0.5, op->y + 0.5)];
                [path lineToPoint:NSMakePoint(op->w + 0.5, op->h + 0.5)];
                [path stroke];
                break;
            }
            case OP_TEXT: {
                NSDictionary* attrs = @{
                    NSFontAttributeName: [NSFont systemFontOfSize:op->size],
                    NSForegroundColorAttributeName: color,
                };
                [op->text drawAtPoint:NSMakePoint(op->x, op->y)
                       withAttributes:attrs];
                break;
            }
        }
    }
}

// A flipped-coordinate view that just replays draw ops. Only used as
// the target of cacheDisplayInRect for snapshot rendering — never
// attached to a live window.
@interface CanvasView : NSView
@property (nonatomic, strong) NSMutableArray<DrawOp*>* ops;
@end

@implementation CanvasView
- (BOOL)isFlipped { return YES; }
- (instancetype)initWithFrame:(NSRect)frame {
    if ((self = [super initWithFrame:frame])) {
        _ops = [NSMutableArray new];
    }
    return self;
}
- (void)drawRect:(NSRect)dirtyRect {
    paint_ops(_ops, self.bounds);
}
@end

// Remembers dimensions for cv_snapshot without opening a window.
// Headless — no NSApp, no CFRunLoop.
void cv_open_offscreen(int w, int h) {
    g_width = w; g_height = h;
    ensure_pending();
}

void cv_clear(int r, int g, int b) {
    ensure_pending();
    [g_pending removeAllObjects];
    DrawOp* op = [DrawOp new];
    op->kind = OP_CLEAR;
    op->r = r; op->g = g; op->b = b;
    [g_pending addObject:op];
}

void cv_rect(int x, int y, int w, int h, int r, int g, int b) {
    ensure_pending();
    DrawOp* op = [DrawOp new];
    op->kind = OP_RECT;
    op->x = x; op->y = y; op->w = w; op->h = h;
    op->r = r; op->g = g; op->b = b;
    [g_pending addObject:op];
}

void cv_line(int x1, int y1, int x2, int y2, int r, int g, int b) {
    ensure_pending();
    DrawOp* op = [DrawOp new];
    op->kind = OP_LINE;
    op->x = x1; op->y = y1;
    op->w = x2; op->h = y2;
    op->r = r; op->g = g; op->b = b;
    [g_pending addObject:op];
}

void cv_text(int x, int y, const char* s, int size, int r, int g, int b) {
    ensure_pending();
    DrawOp* op = [DrawOp new];
    op->kind = OP_TEXT;
    op->x = x; op->y = y;
    op->size = size > 0 ? size : 12;
    op->text = s ? [NSString stringWithUTF8String:s] : @"";
    op->r = r; op->g = g; op->b = b;
    [g_pending addObject:op];
}

// Renders pending ops to a top-left-origin PNG at `path`. Reuses a
// fresh CanvasView (isFlipped=YES) so text baselines match the
// bottom-left NSBitmapImageRep native orientation — a raw CTM flip
// would invert text.
int cv_snapshot(const char* path) {
    if (!path) return 0;
    ensure_pending();
    @autoreleasepool {
        NSRect frame = NSMakeRect(0, 0, g_width, g_height);
        CanvasView* view = [[CanvasView alloc] initWithFrame:frame];
        view.ops = [g_pending mutableCopy];
        NSBitmapImageRep* rep =
            [view bitmapImageRepForCachingDisplayInRect:frame];
        [view cacheDisplayInRect:frame toBitmapImageRep:rep];
        NSData* png = [rep representationUsingType:NSBitmapImageFileTypePNG
                                        properties:@{}];
        NSString* nsPath = [NSString stringWithUTF8String:path];
        BOOL ok = [png writeToFile:nsPath atomically:YES];
        return ok ? 1 : 0;
    }
}
