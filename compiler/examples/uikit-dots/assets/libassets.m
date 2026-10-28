// libassets.m — NSBundle resource byte-length probe.
//
// Splits `path` into base + extension so NSBundle's lookup API can
// resolve either a flat Resources/foo.png or a nested
// Resources/sfx/boom.wav layout. Returns the file length as int32
// (truncated above 2GB, which no sample asset will hit) or -1 if
// the resource isn't bundled.

#import <Foundation/Foundation.h>

int cxc_assets_read_bytes_count(const char *path) {
    if (!path) return -1;
    NSString *p = [NSString stringWithUTF8String:path];
    NSString *base = [p stringByDeletingPathExtension];
    NSString *ext  = [p pathExtension];
    NSString *full = [[NSBundle mainBundle] pathForResource:base ofType:ext];
    if (!full) return -1;
    NSError *err = nil;
    NSDictionary *attrs = [[NSFileManager defaultManager]
        attributesOfItemAtPath:full error:&err];
    if (err) return -1;
    return (int)[attrs[NSFileSize] longLongValue];
}
