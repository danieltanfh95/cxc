// libprefs.m — NSUserDefaults-backed int store.
//
// Round-trip through objectForKey:/setInteger: so we can
// distinguish "no value stored" (fall back to default_value) from
// "stored zero".

#import <Foundation/Foundation.h>

int cxc_prefs_get_int(const char *key, int default_value) {
    if (!key) return default_value;
    NSString *k = [NSString stringWithUTF8String:key];
    NSUserDefaults *d = [NSUserDefaults standardUserDefaults];
    id obj = [d objectForKey:k];
    if (!obj) return default_value;
    return (int)[d integerForKey:k];
}

int cxc_prefs_set_int(const char *key, int value) {
    if (!key) return -1;
    NSString *k = [NSString stringWithUTF8String:key];
    [[NSUserDefaults standardUserDefaults] setInteger:value forKey:k];
    return 0;
}
