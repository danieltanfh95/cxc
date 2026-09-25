// libcocoa_viewer.m — minimal Cocoa wrapper for Corvid FFI drawing.
//
// Compile:
//   clang -dynamiclib libcocoa_viewer.m -framework Cocoa -o libcocoa_viewer.dylib
//
// FFI surface (C-callable):
//   void cv_open(int w, int h, const char* title);
//   void cv_clear(int r, int g, int b);
//   void cv_rect(int x, int y, int w, int h, int r, int g, int b);
//   void cv_text(int x, int y, const char* s, int size, int r, int g, int b);
//   void cv_line(int x1, int y1, int x2, int y2, int r, int g, int b);
//   void cv_present(void);
//   void cv_run(void);
//
// Coordinates: top-left origin (Cocoa native is bottom-left; the view
// flips its coord space so callers can pass screen-natural values).
// Colors: 0-255 per channel.

#import <Cocoa/Cocoa.h>

typedef enum {
    OP_CLEAR = 0,
    OP_RECT = 1,
    OP_TEXT = 2,
    OP_LINE = 3,
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
    NSGraphicsContext* ctx = [NSGraphicsContext currentContext];
    for (DrawOp* op in _ops) {
        NSColor* color = [NSColor colorWithSRGBRed:op->r/255.0
                                            green:op->g/255.0
                                             blue:op->b/255.0
                                            alpha:1.0];
        switch (op->kind) {
            case OP_CLEAR: {
                [color setFill];
                NSRectFill(self.bounds);
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
    (void)ctx;
}

@end

static NSApplication* g_app = nil;
static NSWindow* g_window = nil;
static CanvasView* g_view = nil;
static NSMutableArray<DrawOp*>* g_pending = nil;

static void ensure_app(void) {
    if (g_app) return;
    g_app = [NSApplication sharedApplication];
    [g_app setActivationPolicy:NSApplicationActivationPolicyRegular];
    g_pending = [NSMutableArray new];
}

void cv_open(int w, int h, const char* title) {
    @autoreleasepool {
        ensure_app();
        NSRect frame = NSMakeRect(100, 100, w, h);
        NSUInteger style = NSWindowStyleMaskTitled
                         | NSWindowStyleMaskClosable
                         | NSWindowStyleMaskMiniaturizable
                         | NSWindowStyleMaskResizable;
        g_window = [[NSWindow alloc] initWithContentRect:frame
                                               styleMask:style
                                                 backing:NSBackingStoreBuffered
                                                   defer:NO];
        NSString* nsTitle = title
            ? [NSString stringWithUTF8String:title]
            : @"Corvid Cocoa Viewer";
        [g_window setTitle:nsTitle];
        g_view = [[CanvasView alloc] initWithFrame:NSMakeRect(0, 0, w, h)];
        [g_window setContentView:g_view];
        [g_window makeKeyAndOrderFront:nil];
        [g_app activateIgnoringOtherApps:YES];
    }
}

void cv_clear(int r, int g, int b) {
    ensure_app();
    [g_pending removeAllObjects];
    DrawOp* op = [DrawOp new];
    op->kind = OP_CLEAR;
    op->r = r; op->g = g; op->b = b;
    [g_pending addObject:op];
}

void cv_rect(int x, int y, int w, int h, int r, int g, int b) {
    ensure_app();
    DrawOp* op = [DrawOp new];
    op->kind = OP_RECT;
    op->x = x; op->y = y; op->w = w; op->h = h;
    op->r = r; op->g = g; op->b = b;
    [g_pending addObject:op];
}

void cv_line(int x1, int y1, int x2, int y2, int r, int g, int b) {
    ensure_app();
    DrawOp* op = [DrawOp new];
    op->kind = OP_LINE;
    op->x = x1; op->y = y1;
    op->w = x2; op->h = y2;
    op->r = r; op->g = g; op->b = b;
    [g_pending addObject:op];
}

void cv_text(int x, int y, const char* s, int size, int r, int g, int b) {
    ensure_app();
    DrawOp* op = [DrawOp new];
    op->kind = OP_TEXT;
    op->x = x; op->y = y;
    op->size = size > 0 ? size : 12;
    op->text = s ? [NSString stringWithUTF8String:s] : @"";
    op->r = r; op->g = g; op->b = b;
    [g_pending addObject:op];
}

void cv_present(void) {
    if (!g_view) return;
    g_view.ops = [g_pending mutableCopy];
    [g_view setNeedsDisplay:YES];
    NSEvent* ev;
    while ((ev = [g_app nextEventMatchingMask:NSEventMaskAny
                                    untilDate:[NSDate distantPast]
                                       inMode:NSDefaultRunLoopMode
                                      dequeue:YES])) {
        [g_app sendEvent:ev];
    }
    [g_view displayIfNeeded];
}

void cv_run(void) {
    if (!g_app) return;
    [g_app run];
}
