// harness_smoke.m — standalone ObjC harness that uses libappkit's FFI
// exactly as a Corvid-emitted binary would. Verifies the dylib works
// before wiring it up through the compiler.
//
// Compile:
//   clang harness_smoke.m -L. -lappkit -Wl,-rpath,. -framework Cocoa \
//         -o harness_smoke
// Run:
//   ./harness_smoke

#import <Cocoa/Cocoa.h>
#include <stdio.h>

extern void ak_prep(void);
extern int  ak_window_open(int w, int h, const char* title);
extern void ak_clear(int r, int g, int b);
extern void ak_rect(int x, int y, int w, int h, int r, int g, int b);
extern void ak_text(int x, int y, const char* s, int size, int r, int g, int b);
extern void ak_present(void);
extern int  ak_run(void);
extern void ak_quit(void);
extern void ak_subscribe_key(void (*cb)(int, const char*));
extern void ak_subscribe_close(void (*cb)(void));

static int  g_counter = 0;

static void redraw(void) {
    ak_clear(24, 26, 32);
    ak_rect(20, 20, 200, 80, 90, 130, 200);
    ak_rect(240, 20, 200, 80, 200, 90, 90);
    char buf[64];
    snprintf(buf, sizeof(buf), "keys pressed: %d", g_counter);
    ak_text(20, 130, buf, 18, 220, 220, 220);
    ak_text(20, 160, "press any key, close to quit", 12, 140, 140, 140);
    ak_present();
}

static void on_key(int code, const char* chars) {
    printf("keyDown code=%d chars=%s\n", code, chars ? chars : "");
    fflush(stdout);
    g_counter++;
    if (code == 53 /* escape */) { ak_quit(); return; }
    redraw();
}

static void on_close(void) {
    printf("window closed\n");
    fflush(stdout);
}

int main(int argc, char* argv[]) {
    @autoreleasepool {
        ak_prep();
        ak_window_open(720, 480, "appkit smoke");
        ak_subscribe_key(on_key);
        ak_subscribe_close(on_close);
        redraw();
        return ak_run();
    }
}
