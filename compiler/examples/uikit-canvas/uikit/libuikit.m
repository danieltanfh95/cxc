// uikit-canvas / uikit shim.
//
// Backs the symbols declared in uikit.cxc's `foreign "cxc_uikit":`
// block. cxc compile's directory mode discovers this .m and passes
// it to clang alongside the generated .m, so the final mach-o has
// both the atom message dispatch AND these UIKit wrappers.

#import <UIKit/UIKit.h>

static UIColor *color_rgb(int r, int g, int b) {
    return [UIColor colorWithRed:((CGFloat)r)/255.0
                           green:((CGFloat)g)/255.0
                            blue:((CGFloat)b)/255.0
                           alpha:1.0];
}

int cxc_uikit_set_window_background(id window, int r, int g, int b) {
    UIWindow *w = (UIWindow *)window;
    UIColor *color = color_rgb(r, g, b);
    w.backgroundColor = color;
    if (w.rootViewController) {
        w.rootViewController.view.backgroundColor = color;
    }
    return 0;
}

int cxc_uikit_set_root_label(id window, const char *text, int r, int g, int b) {
    UIWindow *w = (UIWindow *)window;
    UIViewController *vc = w.rootViewController;
    if (!vc) {
        return -1;
    }
    // Clear any previous subviews so repeat calls don't stack labels.
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
