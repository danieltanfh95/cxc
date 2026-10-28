// libaudio.m — AVAudioPlayer SFX cache.
//
// Keeps one AVAudioPlayer per registered name in an NSMutableDictionary.
// Target spec adds AVFoundation to the iOS framework list so this
// links out of the box.

#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>

static NSMutableDictionary<NSString *, AVAudioPlayer *> *cxc_audio_players = nil;

static NSMutableDictionary<NSString *, AVAudioPlayer *> *cxc_audio_cache(void) {
    if (!cxc_audio_players) cxc_audio_players = [NSMutableDictionary dictionary];
    return cxc_audio_players;
}

int cxc_audio_load(const char *name, const char *filename) {
    if (!name || !filename) return -1;
    NSString *n = [NSString stringWithUTF8String:name];
    NSString *fn = [NSString stringWithUTF8String:filename];
    NSString *base = [fn stringByDeletingPathExtension];
    NSString *ext  = [fn pathExtension];
    NSString *path = [[NSBundle mainBundle] pathForResource:base ofType:ext];
    if (!path) return -2;
    NSURL *url = [NSURL fileURLWithPath:path];
    NSError *err = nil;
    AVAudioPlayer *p = [[AVAudioPlayer alloc] initWithContentsOfURL:url error:&err];
    if (!p || err) return -3;
    [p prepareToPlay];
    cxc_audio_cache()[n] = p;
    return 0;
}

int cxc_audio_play(const char *name) {
    if (!name) return -1;
    NSString *n = [NSString stringWithUTF8String:name];
    AVAudioPlayer *p = cxc_audio_cache()[n];
    if (!p) return -2;
    p.currentTime = 0;
    [p play];
    return 0;
}
