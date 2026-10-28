// uikit-canvas / uikit shim.
//
// Backs the symbols declared in uikit.cxc's `foreign "cxc_uikit":`
// block. cxc compile's directory mode discovers this .m and passes
// it to clang alongside the generated .m, so the final mach-o has
// both the atom message dispatch AND these UIKit wrappers.
//
// Window state model: `cxc_uikit_init(window)` caches the window
// in a file-scoped static. Every paint operation reads from that
// cache so arms firing later (taps, timers) never need to re-thread
// the window into the plugin methods.

#import <UIKit/UIKit.h>
#import <objc/message.h>

// cxc emits this file-scoped pointer; UIApplicationMain populates it.
extern id cxc_main_atom;

static UIWindow *cxc_shim_window = nil;

static UIColor *color_rgb(int r, int g, int b) {
    return [UIColor colorWithRed:((CGFloat)r)/255.0
                           green:((CGFloat)g)/255.0
                            blue:((CGFloat)b)/255.0
                           alpha:1.0];
}

int cxc_uikit_init(id window) {
    cxc_shim_window = (UIWindow *)window;
    return 0;
}

int cxc_uikit_paint_background(int r, int g, int b) {
    if (!cxc_shim_window) return -1;
    UIColor *color = color_rgb(r, g, b);
    cxc_shim_window.backgroundColor = color;
    if (cxc_shim_window.rootViewController) {
        cxc_shim_window.rootViewController.view.backgroundColor = color;
    }
    return 0;
}

int cxc_uikit_paint_label(const char *text, int r, int g, int b) {
    if (!cxc_shim_window) return -1;
    UIViewController *vc = cxc_shim_window.rootViewController;
    if (!vc) return -2;
    for (UIView *v in [vc.view.subviews copy]) {
        [v removeFromSuperview];
    }
    UILabel *label = [[UILabel alloc] init];
    label.text = [NSString stringWithUTF8String:text];
    label.font = [UIFont systemFontOfSize:28 weight:UIFontWeightMedium];
    label.textColor = color_rgb(r, g, b);
    label.textAlignment = NSTextAlignmentCenter;
    label.translatesAutoresizingMaskIntoConstraints = NO;
    [vc.view addSubview:label];
    [NSLayoutConstraint activateConstraints:@[
        [label.centerXAnchor constraintEqualToAnchor:vc.view.centerXAnchor],
        [label.centerYAnchor constraintEqualToAnchor:vc.view.centerYAnchor]
    ]];
    return 0;
}

@interface CxcTapHandler : NSObject
@end
@implementation CxcTapHandler
- (void)handleTap:(UITapGestureRecognizer *)sender {
    CGPoint pt = [sender locationInView:sender.view];
    // cxc lowers `on_tap(x: Int32!, y: Int32!)` to this selector.
    SEL s_xy = @selector(on_tapWithx:y:);
    SEL s_bare = @selector(on_tap);
    if ([cxc_main_atom respondsToSelector:s_xy]) {
        int32_t x = (int32_t)pt.x;
        int32_t y = (int32_t)pt.y;
        ((void (*)(id, SEL, int32_t, int32_t))objc_msgSend)(
            cxc_main_atom, s_xy, x, y);
    } else if ([cxc_main_atom respondsToSelector:s_bare]) {
        ((void (*)(id, SEL))objc_msgSend)(cxc_main_atom, s_bare);
    }
}
@end

static CxcTapHandler *cxc_tap_handler = nil;

int cxc_uikit_install_tap_handler(void) {
    if (!cxc_shim_window || !cxc_shim_window.rootViewController) return -1;
    if (!cxc_tap_handler) cxc_tap_handler = [[CxcTapHandler alloc] init];
    UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc]
                                    initWithTarget:cxc_tap_handler
                                            action:@selector(handleTap:)];
    [cxc_shim_window.rootViewController.view addGestureRecognizer:tap];
    return 0;
}
