// viewerd.m — standalone Cocoa viewer for a directory of PNG frames.
//
// Compile:
//   clang -fobjc-arc viewerd.m -framework Cocoa -o viewerd
//
// Usage:
//   viewerd [--dir /tmp/dota2-frames] [--fps 4] [--mode auto|step]
//
// Controls (window in focus):
//   space       toggle play / pause
//   left/right  step one frame (also stops auto)
//   r           re-scan the frames dir
//   escape      quit
//
// Data contract (see cocoa-viewer README): the dir contains files
// named f0.png ... fN.png. Names sort numerically, not
// lexicographically. Frames may be any size; each is aspect-fit into
// the window with black bars.
//
// Why a separate binary: the sim binary uses Corvid's dispatch_main()
// runtime which can't safely re-enter AppKit. This process has its
// own NSApplication + CFRunLoop and never touches Corvid.

#import <Cocoa/Cocoa.h>

typedef enum { MODE_AUTO, MODE_STEP } ViewerMode;

static NSString*  g_dir  = @"/tmp/dota2-frames";
static double     g_fps  = 4.0;
static ViewerMode g_mode = MODE_AUTO;

@interface ViewerView : NSView
@property (nonatomic, strong) NSArray<NSString*>* paths;
@property (nonatomic, assign) NSInteger index;
@property (nonatomic, strong) NSImage* image;
@property (nonatomic, strong) NSTimer* timer;
- (void)reload;
- (void)advanceBy:(NSInteger)delta;
- (void)togglePlay;
- (void)startTimer;
- (void)stopTimer;
@end

// Numeric-suffix comparator: "f10.png" > "f2.png" > "f1.png".
// Falls back to lexicographic if a name doesn't match.
static NSInteger frame_num(NSString* name) {
    NSString* base = name.lastPathComponent.stringByDeletingPathExtension;
    if (![base hasPrefix:@"f"]) return NSIntegerMax;
    NSString* digits = [base substringFromIndex:1];
    NSScanner* s = [NSScanner scannerWithString:digits];
    NSInteger n = 0;
    if (![s scanInteger:&n] || !s.isAtEnd) return NSIntegerMax;
    return n;
}

@implementation ViewerView

- (BOOL)acceptsFirstResponder { return YES; }
- (BOOL)isOpaque              { return YES; }

- (void)drawRect:(NSRect)dirtyRect {
    [[NSColor blackColor] setFill];
    NSRectFill(self.bounds);
    if (self.image) {
        NSSize sz = self.image.size;
        CGFloat sx = self.bounds.size.width  / sz.width;
        CGFloat sy = self.bounds.size.height / sz.height;
        CGFloat s  = sx < sy ? sx : sy;
        NSSize out = NSMakeSize(sz.width * s, sz.height * s);
        NSRect dst = NSMakeRect((self.bounds.size.width  - out.width)  / 2,
                                (self.bounds.size.height - out.height) / 2,
                                out.width, out.height);
        [self.image drawInRect:dst
                      fromRect:NSZeroRect
                     operation:NSCompositingOperationSourceOver
                      fraction:1.0
                respectFlipped:YES
                         hints:nil];
    }
    NSString* hud = [NSString stringWithFormat:@"%ld / %lu  %@",
                     (long)(self.index + 1),
                     (unsigned long)self.paths.count,
                     self.timer ? @"play" : @"pause"];
    NSDictionary* attrs = @{
        NSFontAttributeName: [NSFont monospacedSystemFontOfSize:11
                                                         weight:NSFontWeightRegular],
        NSForegroundColorAttributeName: [NSColor whiteColor],
    };
    [hud drawAtPoint:NSMakePoint(8, 6) withAttributes:attrs];
}

- (void)loadCurrent {
    if (self.paths.count == 0) { self.image = nil; return; }
    if (self.index < 0 || self.index >= (NSInteger)self.paths.count) self.index = 0;
    self.image = [[NSImage alloc] initWithContentsOfFile:self.paths[self.index]];
    self.needsDisplay = YES;
}

- (void)reload {
    NSError* err = nil;
    NSArray<NSString*>* names =
        [[NSFileManager defaultManager] contentsOfDirectoryAtPath:g_dir error:&err];
    if (!names) {
        NSLog(@"viewerd: cannot read %@: %@", g_dir, err.localizedDescription);
        self.paths = @[];
        self.image = nil;
        self.needsDisplay = YES;
        return;
    }
    NSMutableArray<NSString*>* full = [NSMutableArray new];
    for (NSString* n in names) {
        if (![n hasSuffix:@".png"]) continue;
        [full addObject:[g_dir stringByAppendingPathComponent:n]];
    }
    [full sortUsingComparator:^NSComparisonResult(NSString* a, NSString* b) {
        NSInteger ai = frame_num(a), bi = frame_num(b);
        if (ai != bi) return ai < bi ? NSOrderedAscending : NSOrderedDescending;
        return [a compare:b];
    }];
    self.paths = full;
    [self loadCurrent];
}

- (void)advanceBy:(NSInteger)delta {
    NSInteger n = (NSInteger)self.paths.count;
    if (n == 0) return;
    self.index = ((self.index + delta) % n + n) % n;
    [self loadCurrent];
}

- (void)tick:(NSTimer*)t { [self advanceBy:+1]; }

- (void)startTimer {
    if (self.timer) return;
    NSTimer* t = [NSTimer timerWithTimeInterval:1.0 / g_fps
                                         target:self
                                       selector:@selector(tick:)
                                       userInfo:nil
                                        repeats:YES];
    [[NSRunLoop mainRunLoop] addTimer:t forMode:NSRunLoopCommonModes];
    self.timer = t;
    self.needsDisplay = YES;
}

- (void)stopTimer {
    [self.timer invalidate];
    self.timer = nil;
    self.needsDisplay = YES;
}

- (void)togglePlay {
    if (self.timer) [self stopTimer]; else [self startTimer];
}

- (void)keyDown:(NSEvent*)e {
    NSString* chars = e.charactersIgnoringModifiers;
    unichar c = chars.length ? [chars characterAtIndex:0] : 0;
    switch (c) {
        case NSLeftArrowFunctionKey:
            [self stopTimer]; [self advanceBy:-1]; return;
        case NSRightArrowFunctionKey:
            [self stopTimer]; [self advanceBy:+1]; return;
        case ' ':
            [self togglePlay]; return;
        case 'r':
            [self reload]; return;
        case 27: /* ESC */
            [NSApp terminate:nil]; return;
        default:
            [super keyDown:e];
    }
}

@end

@interface AppDelegate : NSObject <NSApplicationDelegate, NSWindowDelegate>
@property (nonatomic, strong) NSWindow*   window;
@property (nonatomic, strong) ViewerView* view;
@end

@implementation AppDelegate

- (void)applicationDidFinishLaunching:(NSNotification*)n {
    NSRect frame = NSMakeRect(200, 200, 720, 480);
    NSWindowStyleMask mask = NSWindowStyleMaskTitled
                           | NSWindowStyleMaskClosable
                           | NSWindowStyleMaskMiniaturizable
                           | NSWindowStyleMaskResizable;
    self.window = [[NSWindow alloc] initWithContentRect:frame
                                              styleMask:mask
                                                backing:NSBackingStoreBuffered
                                                  defer:NO];
    self.window.title = [@"viewerd — " stringByAppendingString:g_dir];
    self.window.releasedWhenClosed = NO;
    self.window.delegate = self;

    self.view = [[ViewerView alloc] initWithFrame:self.window.contentView.bounds];
    self.view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    self.window.contentView = self.view;

    self.window.initialFirstResponder = self.view;
    [self.window makeFirstResponder:self.view];
    // Place on the primary (menubar) screen. NSScreen.mainScreen is
    // "the screen containing the key window", which is unpredictable
    // at launch on multi-display setups. NSScreen.screens[0] is
    // documented as the screen with the menubar.
    NSArray<NSScreen*>* screens = [NSScreen screens];
    if (screens.count > 0) {
        NSScreen* screen = screens[0];
        NSRect sf = screen.visibleFrame;
        NSRect wf = self.window.frame;
        NSPoint origin = NSMakePoint(sf.origin.x + (sf.size.width  - wf.size.width)  / 2,
                                     sf.origin.y + (sf.size.height - wf.size.height) / 2);
        NSLog(@"viewerd: primary screen visibleFrame=(%.0f,%.0f %.0fx%.0f), placing at (%.0f,%.0f)",
              sf.origin.x, sf.origin.y, sf.size.width, sf.size.height, origin.x, origin.y);
        [self.window setFrameOrigin:origin];
    }
    [self.window makeKeyAndOrderFront:nil];

    [self.view reload];
    if (g_mode == MODE_AUTO) [self.view startTimer];

    [NSApp activateIgnoringOtherApps:YES];
}

- (void)windowWillClose:(NSNotification*)n {
    [self.view stopTimer];
    [NSApp terminate:nil];
}

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication*)app {
    return YES;
}

@end

static void parse_args(int argc, const char* const argv[]) {
    for (int i = 1; i < argc; i++) {
        const char* a = argv[i];
        if (strcmp(a, "--dir") == 0 && i + 1 < argc) {
            g_dir = [NSString stringWithUTF8String:argv[++i]];
        } else if (strcmp(a, "--fps") == 0 && i + 1 < argc) {
            double v = atof(argv[++i]);
            if (v > 0) g_fps = v;
        } else if (strcmp(a, "--mode") == 0 && i + 1 < argc) {
            const char* m = argv[++i];
            g_mode = (strcmp(m, "step") == 0) ? MODE_STEP : MODE_AUTO;
        } else if (strcmp(a, "-h") == 0 || strcmp(a, "--help") == 0) {
            fprintf(stderr,
                    "usage: viewerd [--dir <path>] [--fps <n>] [--mode auto|step]\n");
            exit(0);
        }
    }
}

int main(int argc, const char* const argv[]) {
    @autoreleasepool {
        parse_args(argc, argv);
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
        AppDelegate* d = [AppDelegate new];
        [NSApp setDelegate:d];
        [NSApp run];
    }
    return 0;
}
