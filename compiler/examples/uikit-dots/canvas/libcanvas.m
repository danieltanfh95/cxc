// libcanvas.m — bitmap-free drawing surface + multi-touch input.
//
// CxcCanvasView keeps a background color and an NSMutableArray of
// shape dicts; -drawRect: paints the background then iterates the
// shapes. Each shape is {x,y,radius,r,g,b}. Touches on the view
// forward on_touch_begin/move/end(id, x, y) to cxc_main_atom via
// typed objc_msgSend.
//
// The shape-list approach stays simple at a sample scale (hundreds
// of dots). For a game that needs tens of thousands of persistent
// shapes, replace with a CGBitmapContext-backed canvas later.

#import <UIKit/UIKit.h>
#import <objc/message.h>

extern id cxc_main_atom;

@interface CxcCanvasView : UIView
@property (nonatomic, strong) NSMutableArray *shapes;
@property (nonatomic) CGFloat clearR;
@property (nonatomic) CGFloat clearG;
@property (nonatomic) CGFloat clearB;
@end

@implementation CxcCanvasView

- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.multipleTouchEnabled = YES;
        self.shapes = [NSMutableArray array];
        self.clearR = 0; self.clearG = 0; self.clearB = 0;
    }
    return self;
}

- (void)drawRect:(CGRect)rect {
    CGContextRef ctx = UIGraphicsGetCurrentContext();
    if (!ctx) return;
    CGContextSetRGBFillColor(ctx, self.clearR, self.clearG, self.clearB, 1.0);
    CGContextFillRect(ctx, self.bounds);
    for (NSDictionary *s in self.shapes) {
        CGContextSetRGBFillColor(ctx,
            [s[@"r"] doubleValue] / 255.0,
            [s[@"g"] doubleValue] / 255.0,
            [s[@"b"] doubleValue] / 255.0, 1.0);
        CGFloat x = [s[@"x"] doubleValue];
        CGFloat y = [s[@"y"] doubleValue];
        CGFloat r = [s[@"radius"] doubleValue];
        CGContextFillEllipseInRect(ctx, CGRectMake(x - r, y - r, r * 2, r * 2));
    }
}

- (void)dispatchTouch:(SEL)sel touches:(NSSet<UITouch *> *)touches {
    for (UITouch *t in touches) {
        int32_t touchId = (int32_t)(((intptr_t)t) & 0x7fffffff);
        CGPoint pt = [t locationInView:self];
        int32_t x = (int32_t)pt.x;
        int32_t y = (int32_t)pt.y;
        if ([cxc_main_atom respondsToSelector:sel]) {
            ((void (*)(id, SEL, int32_t, int32_t, int32_t))objc_msgSend)(
                cxc_main_atom, sel, touchId, x, y);
        }
    }
}

- (void)touchesBegan:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [self dispatchTouch:@selector(on_touch_beginWithid:x:y:) touches:touches];
}
- (void)touchesMoved:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [self dispatchTouch:@selector(on_touch_moveWithid:x:y:) touches:touches];
}
- (void)touchesEnded:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [self dispatchTouch:@selector(on_touch_endWithid:x:y:) touches:touches];
}
- (void)touchesCancelled:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [self dispatchTouch:@selector(on_touch_endWithid:x:y:) touches:touches];
}
@end

static CxcCanvasView *cxc_canvas_view = nil;

int cxc_canvas_install(id window_id) {
    UIWindow *window = (UIWindow *)window_id;
    UIViewController *vc = window.rootViewController;
    if (!vc) return -1;
    CxcCanvasView *v = [[CxcCanvasView alloc] initWithFrame:vc.view.bounds];
    v.autoresizingMask = UIViewAutoresizingFlexibleWidth
                       | UIViewAutoresizingFlexibleHeight;
    cxc_canvas_view = v;
    for (UIView *sv in [vc.view.subviews copy]) [sv removeFromSuperview];
    [vc.view addSubview:v];
    return 0;
}

int cxc_canvas_clear(int r, int g, int b) {
    if (!cxc_canvas_view) return -1;
    cxc_canvas_view.clearR = r / 255.0;
    cxc_canvas_view.clearG = g / 255.0;
    cxc_canvas_view.clearB = b / 255.0;
    [cxc_canvas_view.shapes removeAllObjects];
    [cxc_canvas_view setNeedsDisplay];
    return 0;
}

int cxc_canvas_fill_circle(int x, int y, int radius, int r, int g, int b) {
    if (!cxc_canvas_view) return -1;
    [cxc_canvas_view.shapes addObject:@{
        @"x": @(x), @"y": @(y), @"radius": @(radius),
        @"r": @(r), @"g": @(g), @"b": @(b)
    }];
    [cxc_canvas_view setNeedsDisplay];
    return 0;
}
