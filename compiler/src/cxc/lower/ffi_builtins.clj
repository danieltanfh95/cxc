(ns cxc.lower.ffi-builtins
  "Compiler-recognized FFI builtin table.

   Prelude-authored bodies (real Clock/Random/Console capabilities) call
   these names; lowering intercepts them via `ffi-builtin?` and emits raw
   C/ObjC via each entry's `:emit-c` fn.

   Retirement pending — this registry is a compiler-invented mechanism
   (audit F/K7) parallel to the spec's `foreign library` blocks (§1.14)
   consumed via the `Extern` capability atom (§1.12). Real machinery
   requires prelude directory tree + name resolution + capability
   provisioning (Phase D). Until then, prelude bodies use this hatch.

   Table shape (per entry): {:return-kw <type-kw for infer-type>
                             :return-type-node <optional raw type node>
                             :arity <int>
                             :emit-c <fn [lowered-args] → C string>}")

(def ffi-builtins
  "Map from builtin name to metadata. Public because M1 splits the
   consumers (infer/lower) across sibling namespaces."
  {"native_millis_now"
   ;; Wall-clock milliseconds since Unix epoch, as Int (int64_t).
   ;; The prior cast to `int` (32-bit) truncated silently for any
   ;; timestamp beyond ~24 days after epoch, so real clock reads
   ;; returned garbage. int64_t holds the whole value.
   {:return-kw :int
    :arity     0
    :emit-c    (fn [_args]
                 "((int64_t)([[NSDate date] timeIntervalSince1970] * 1000.0))")}
   "native_random_u32"
   {:return-kw :int
    :arity     0
    :emit-c    (fn [_args]
                 "((int)(arc4random() & 0x7FFFFFFF))")}
   "native_console_write"
   ;; Layer 6 Step 5: Console.write(msg) writes to stdout, returns
   ;; the number of bytes written. Wraps printf's %s to route
   ;; through the same underlying fd as `print` — subsequent output
   ;; from either path interleaves correctly.
   ;;
   ;; F65: msg is NSString *; extract UTF8 bytes at the print site.
   {:return-kw :int
    :arity     1
    :emit-c    (fn [args]
                 (str "printf(\"%s\", [" (first args) " UTF8String])"))}
   "native_console_readln"
   ;; 2026-09-03 — read one line from stdin. Trailing newline is
   ;; stripped. Returns nil on EOF or read error (empty stdin, EOF
   ;; without terminator, or IO failure). Uses a 4 KiB stack buffer;
   ;; longer lines are truncated at the first fgets boundary
   ;; (subsequent readln calls return the tail). String/stdio
   ;; symbols reach through Foundation's transitive includes.
   {:return-kw :string
    :arity     0
    :emit-c    (fn [_args]
                 (str "({ char __buf[4096]; NSString *__r = nil; "
                      "if (fgets(__buf, sizeof(__buf), stdin)) { "
                      "size_t __n = strlen(__buf); "
                      "if (__n > 0 && __buf[__n-1] == '\\n') __buf[__n-1] = '\\0'; "
                      "__r = [NSString stringWithUTF8String:__buf]; } "
                      "__r; })"))}
   "native_random_below"
   {:return-kw :int
    :arity     1
    :emit-c    (fn [args]
                 (str "((int)arc4random_uniform((uint32_t)("
                      (first args) ")))"))}
   "native_fs_read_text"
   ;; 2d (2026-09-02) — read a UTF-8 file into a String. Returns
   ;; `nil` on failure (missing file, permission denied, non-UTF-8
   ;; bytes). Foundation call: `+stringWithContentsOfFile:encoding:
   ;; error:` with nil error out-param (we don't surface NSError
   ;; details in the MVP). Callers check with `exists` first or
   ;; branch on nil to decide whether to raise IOError. When the
   ;; input NSString is nil (shouldn't happen from cxc code but
   ;; harmless to guard), returns nil.
   {:return-kw :string
    :arity     1
    :emit-c    (fn [args]
                 (str "((" (first args) ") ? "
                      "[NSString stringWithContentsOfFile:" (first args)
                      " encoding:NSUTF8StringEncoding error:nil] : nil)"))}
   "native_fs_write_text"
   ;; 2d (2026-09-02) — write a String to a UTF-8 file, atomically.
   ;; Returns Int status: 0 on success, -1 on failure (path unwritable,
   ;; disk full, permission denied). Foundation call:
   ;; `-writeToFile:atomically:encoding:error:` with nil error out-
   ;; param. Callers can raise IOError on -1.
   {:return-kw :int
    :arity     2
    :emit-c    (fn [args]
                 (let [path    (first args)
                       content (second args)]
                   (str "((" content " && " path " && "
                        "[" content " writeToFile:" path
                        " atomically:YES encoding:NSUTF8StringEncoding error:nil]) "
                        "? 0 : -1)")))}
   "native_subprocess_run"
   ;; 2e (2026-09-02) — run a shell command and capture combined
   ;; stdout+stderr as a String. Uses libc `popen(cmd, "r")` with
   ;; the `2>&1` redirect appended so a single pipe carries both
   ;; streams. Returns nil on popen failure (fork/exec problem);
   ;; returns the collected output string otherwise. Exit code is
   ;; NOT surfaced at MVP — callers get combined output only. When
   ;; exit-code discrimination is needed, migrate to a
   ;; `ProcResult`-returning native (needs a wider FFI marshal for
   ;; composite return types, or a prefix-encoded String consumers
   ;; parse; both deferred). The `2>&1` shell redirect is a MVP
   ;; simplification — real subprocess separates stderr via NSTask
   ;; + NSPipe; migrate when that FFI is available.
   {:return-kw :string
    :arity     1
    :emit-c    (fn [args]
                 (str "({ NSString *__c = " (first args) "; "
                      "NSString *__r = nil; "
                      "if (__c) { "
                      "NSString *__cmd2 = [__c stringByAppendingString:@\" 2>&1\"]; "
                      "FILE *__p = popen([__cmd2 UTF8String], \"r\"); "
                      "if (__p) { "
                      "NSMutableString *__out = [NSMutableString string]; "
                      "char __buf[4096]; "
                      "while (fgets(__buf, sizeof(__buf), __p)) { "
                      "[__out appendString:[NSString stringWithUTF8String:__buf]]; "
                      "} "
                      "pclose(__p); "
                      "__r = (NSString *)__out; } } "
                      "__r; })"))}
   "native_args_length"
   ;; 2h (2026-09-03) — argc as an Int. Reads from Darwin's
   ;; `*_NSGetArgc()` accessor; `<crt_externs.h>` is auto-included
   ;; whenever Foundation is included (see base-includes).
   {:return-kw :int
    :arity     0
    :emit-c    (fn [_args]
                 "((int)(*_NSGetArgc()))")}
   "native_args_get_or"
   ;; 2h (2026-09-03) — argv[index] as String, or the caller's
   ;; fallback if index is out of range. Darwin argc/argv accessed
   ;; via `*_NSGetArgc()` / `*_NSGetArgv()`; the argv entries are
   ;; `char *` C-strings which we wrap via `+stringWithUTF8String:`
   ;; (NULL-tolerant with an outer nil guard). Negative indices
   ;; return the fallback (unsigned cast would wrap silently, so
   ;; we branch).
   {:return-kw :string
    :arity     2
    :emit-c    (fn [args]
                 (let [idx      (first args)
                       fallback (second args)]
                   (str "({ int __i = (int)(" idx "); "
                        "int __ac = *_NSGetArgc(); "
                        "char **__av = *_NSGetArgv(); "
                        "NSString *__fb = " fallback "; "
                        "NSString *__r = __fb; "
                        "if (__i >= 0 && __i < __ac && __av[__i]) { "
                        "__r = [NSString stringWithUTF8String:__av[__i]]; "
                        "} "
                        "__r; })")))}
   "native_json_encode_str"
   ;; 4 (2026-09-03) — take a raw String, produce a JSON-encoded
   ;; String literal WITH surrounding quotes. `NSJSONSerialization`
   ;; only encodes top-level arrays/dicts by default, so we wrap
   ;; the input in a single-element array `[s]`, encode that, and
   ;; strip the `[...]` wrapper. Handles all mandatory escapes
   ;; (backslash, quote, control chars, non-BMP surrogates) per
   ;; RFC 8259. Nil-tolerant: nil input yields "null" (JSON null
   ;; literal, no quotes) so consumers can still concat safely.
   {:return-kw :string
    :arity     1
    :emit-c    (fn [args]
                 (str "({ NSString *__s = " (first args) "; "
                      "NSString *__r = nil; "
                      "if (__s == nil) { __r = @\"null\"; } "
                      "else { NSArray *__arr = @[__s]; "
                      "NSError *__err = nil; "
                      "NSData *__d = [NSJSONSerialization dataWithJSONObject:__arr "
                      "options:(NSJSONWritingOptions)0 error:&__err]; "
                      "if (__d && !__err) { "
                      "NSString *__wrapped = [[NSString alloc] initWithData:__d "
                      "encoding:NSUTF8StringEncoding]; "
                      "if (__wrapped.length >= 2) { "
                      "__r = [__wrapped substringWithRange:"
                      "NSMakeRange(1, __wrapped.length - 2)]; } "
                      "} "
                      "if (__r == nil) __r = @\"null\"; } "
                      "__r; })"))}
   "native_json_field_int_or"
   ;; 2026-09-03 — parse `json` as a top-level object and return
   ;; the Int value at `key`, or `fallback` if the JSON is
   ;; malformed, the top-level isn't an object, the key is
   ;; missing, or the value isn't a JSON number. NSNumber's
   ;; `longLongValue` handles both integers and floats (floats
   ;; truncate); if that's the wrong policy for a caller,
   ;; layering a `field_number` (Float) sibling stays trivial.
   {:return-kw :int
    :arity     3
    :emit-c    (fn [args]
                 (let [json-arg (first args)
                       key-arg  (second args)
                       fb-arg   (nth args 2)]
                   (str "({ NSString *__j = " json-arg "; "
                        "NSString *__k = " key-arg "; "
                        "int64_t __r = (int64_t)(" fb-arg "); "
                        "if (__j && __k) { "
                        "NSData *__d = [__j dataUsingEncoding:NSUTF8StringEncoding]; "
                        "NSError *__err = nil; "
                        "id __o = [NSJSONSerialization JSONObjectWithData:__d "
                        "options:(NSJSONReadingOptions)0 error:&__err]; "
                        "if (!__err && [__o isKindOfClass:[NSDictionary class]]) { "
                        "id __v = [(NSDictionary *)__o objectForKey:__k]; "
                        "if ([__v isKindOfClass:[NSNumber class]]) { "
                        "__r = [(NSNumber *)__v longLongValue]; } "
                        "} "
                        "} "
                        "__r; })")))}
   "native_json_field_int_or_path"
   ;; 2026-09-03 — same dot-path walk as field_string_path, but
   ;; returns the terminal number as Int (via NSNumber
   ;; longLongValue). Any break in the walk, or a terminal that
   ;; isn't an NSNumber, returns the caller's fallback. Common
   ;; case: `Json.field_int_or_path(claude_resp, "usage.input_tokens", 0)`.
   {:return-kw :int
    :arity     3
    :emit-c    (fn [args]
                 (let [json-arg (first args)
                       path-arg (second args)
                       fb-arg   (nth args 2)]
                   (str "({ NSString *__j = " json-arg "; "
                        "NSString *__p = " path-arg "; "
                        "int64_t __r = (int64_t)(" fb-arg "); "
                        "if (__j && __p) { "
                        "NSData *__d = [__j dataUsingEncoding:NSUTF8StringEncoding]; "
                        "NSError *__err = nil; "
                        "id __cur = [NSJSONSerialization JSONObjectWithData:__d "
                        "options:(NSJSONReadingOptions)0 error:&__err]; "
                        "if (!__err && __cur) { "
                        "NSArray *__parts = [__p componentsSeparatedByString:@\".\"]; "
                        "for (NSString *__seg in __parts) { "
                        "if (__cur == nil) break; "
                        "NSScanner *__sc = [NSScanner scannerWithString:__seg]; "
                        "NSInteger __idx = 0; "
                        "if ([__sc scanInteger:&__idx] && __sc.isAtEnd) { "
                        "if ([__cur isKindOfClass:[NSArray class]] && "
                        "__idx >= 0 && (NSUInteger)__idx < [(NSArray *)__cur count]) { "
                        "__cur = [(NSArray *)__cur objectAtIndex:(NSUInteger)__idx]; "
                        "} else { __cur = nil; } "
                        "} else if ([__cur isKindOfClass:[NSDictionary class]]) { "
                        "__cur = [(NSDictionary *)__cur objectForKey:__seg]; "
                        "} else { __cur = nil; } "
                        "} "
                        "if ([__cur isKindOfClass:[NSNumber class]]) { "
                        "__r = [(NSNumber *)__cur longLongValue]; } "
                        "} "
                        "} "
                        "__r; })")))}
   "native_json_field_string_path"
   ;; 2026-09-03 — walk a dot-separated path through nested
   ;; NSDictionary / NSArray from JSON and return the terminal
   ;; String value (or nil if the path breaks, the JSON is
   ;; malformed, or the terminal isn't a String). Numeric path
   ;; segments index into NSArray; non-numeric segments look up
   ;; NSDictionary keys. Enables clean Claude Messages API
   ;; extraction: `Json.field_string_path(resp, "content.0.text")`
   ;; returns the assistant's reply text without the manual
   ;; string surgery hello-agent-mini used before.
   {:return-kw :string
    :arity     2
    :emit-c    (fn [args]
                 (let [json-arg (first args)
                       path-arg (second args)]
                   (str "({ NSString *__j = " json-arg "; "
                        "NSString *__p = " path-arg "; "
                        "NSString *__r = nil; "
                        "if (__j && __p) { "
                        "NSData *__d = [__j dataUsingEncoding:NSUTF8StringEncoding]; "
                        "NSError *__err = nil; "
                        "id __cur = [NSJSONSerialization JSONObjectWithData:__d "
                        "options:(NSJSONReadingOptions)0 error:&__err]; "
                        "if (!__err && __cur) { "
                        "NSArray *__parts = [__p componentsSeparatedByString:@\".\"]; "
                        "for (NSString *__seg in __parts) { "
                        "if (__cur == nil) break; "
                        "NSScanner *__sc = [NSScanner scannerWithString:__seg]; "
                        "NSInteger __idx = 0; "
                        "if ([__sc scanInteger:&__idx] && __sc.isAtEnd) { "
                        "if ([__cur isKindOfClass:[NSArray class]] && "
                        "__idx >= 0 && (NSUInteger)__idx < [(NSArray *)__cur count]) { "
                        "__cur = [(NSArray *)__cur objectAtIndex:(NSUInteger)__idx]; "
                        "} else { __cur = nil; } "
                        "} else if ([__cur isKindOfClass:[NSDictionary class]]) { "
                        "__cur = [(NSDictionary *)__cur objectForKey:__seg]; "
                        "} else { __cur = nil; } "
                        "} "
                        "if ([__cur isKindOfClass:[NSString class]]) { "
                        "__r = (NSString *)__cur; } "
                        "} "
                        "} "
                        "__r; })")))}
   "native_json_field_string"
   ;; 4 (2026-09-03) — parse `json` as a top-level JSON object,
   ;; read the value at `key`, and return it as a String. Returns
   ;; nil when: the JSON is invalid, the top-level isn't an object,
   ;; the key is missing, or the value at the key isn't a String.
   ;; Numeric/bool/null/nested values return nil at MVP; callers
   ;; wanting those shapes need dedicated intrinsics (deferred).
   {:return-kw :string
    :arity     2
    :emit-c    (fn [args]
                 (let [json-arg (first args)
                       key-arg  (second args)]
                   (str "({ NSString *__j = " json-arg "; "
                        "NSString *__k = " key-arg "; "
                        "NSString *__r = nil; "
                        "if (__j && __k) { "
                        "NSData *__d = [__j dataUsingEncoding:NSUTF8StringEncoding]; "
                        "NSError *__err = nil; "
                        "id __o = [NSJSONSerialization JSONObjectWithData:__d "
                        "options:(NSJSONReadingOptions)0 error:&__err]; "
                        "if (!__err && [__o isKindOfClass:[NSDictionary class]]) { "
                        "id __v = [(NSDictionary *)__o objectForKey:__k]; "
                        "if ([__v isKindOfClass:[NSString class]]) { "
                        "__r = (NSString *)__v; } "
                        "} "
                        "} "
                        "__r; })")))}
   "native_http_get"
   ;; 5 (2026-09-03) — synchronous HTTP GET. Body returned as
   ;; UTF-8 String, nil on failure (network error, non-UTF-8 body,
   ;; nil URL). Headers passed as a JSON object String (parsed
   ;; via NSJSONSerialization) — users build with Json.encode_str
   ;; + concat, or an empty object `"{}"` for none. Async by
   ;; nature of NSURLSession; MVP blocks on a semaphore for a
   ;; sync surface.
   {:return-kw :string
    :arity     2
    :emit-c    (fn [args]
                 (let [url-arg     (first args)
                       headers-arg (second args)]
                   (str "({ NSString *__u = " url-arg "; "
                        "NSString *__h = " headers-arg "; "
                        "__block NSString *__r = nil; "
                        "if (__u) { "
                        "NSURL *__url = [NSURL URLWithString:__u]; "
                        "if (__url) { "
                        "NSMutableURLRequest *__req = [NSMutableURLRequest requestWithURL:__url]; "
                        "__req.HTTPMethod = @\"GET\"; "
                        "if (__h) { "
                        "NSData *__hd = [__h dataUsingEncoding:NSUTF8StringEncoding]; "
                        "NSError *__he = nil; "
                        "id __ho = [NSJSONSerialization JSONObjectWithData:__hd "
                        "options:(NSJSONReadingOptions)0 error:&__he]; "
                        "if (!__he && [__ho isKindOfClass:[NSDictionary class]]) { "
                        "[(NSDictionary *)__ho enumerateKeysAndObjectsUsingBlock:"
                        "^(id __hk, id __hv, BOOL *__stop) { "
                        "if ([__hk isKindOfClass:[NSString class]] && "
                        "[__hv isKindOfClass:[NSString class]]) { "
                        "[__req setValue:(NSString *)__hv forHTTPHeaderField:(NSString *)__hk]; "
                        "} }]; "
                        "} } "
                        "dispatch_semaphore_t __sem = dispatch_semaphore_create(0); "
                        "NSURLSessionDataTask *__task = [[NSURLSession sharedSession] "
                        "dataTaskWithRequest:__req completionHandler:"
                        "^(NSData *__data, NSURLResponse *__resp, NSError *__err) { "
                        "if (!__err && __data) { "
                        "__r = [[NSString alloc] initWithData:__data "
                        "encoding:NSUTF8StringEncoding]; "
                        "} "
                        "dispatch_semaphore_signal(__sem); }]; "
                        "[__task resume]; "
                        "dispatch_semaphore_wait(__sem, DISPATCH_TIME_FOREVER); "
                        "} } "
                        "__r; })")))}
   "native_http_post"
   ;; 5 (2026-09-03) — synchronous HTTP POST with a String body.
   ;; Body is UTF-8 encoded and sent as-is (caller sets
   ;; Content-Type in headers if it matters — JSON APIs typically
   ;; want `Content-Type: application/json`). Same nil-return
   ;; failure surface as `native_http_get`.
   {:return-kw :string
    :arity     3
    :emit-c    (fn [args]
                 (let [url-arg     (first args)
                       headers-arg (second args)
                       body-arg    (nth args 2)]
                   (str "({ NSString *__u = " url-arg "; "
                        "NSString *__h = " headers-arg "; "
                        "NSString *__b = " body-arg "; "
                        "__block NSString *__r = nil; "
                        "if (__u) { "
                        "NSURL *__url = [NSURL URLWithString:__u]; "
                        "if (__url) { "
                        "NSMutableURLRequest *__req = [NSMutableURLRequest requestWithURL:__url]; "
                        "__req.HTTPMethod = @\"POST\"; "
                        "if (__h) { "
                        "NSData *__hd = [__h dataUsingEncoding:NSUTF8StringEncoding]; "
                        "NSError *__he = nil; "
                        "id __ho = [NSJSONSerialization JSONObjectWithData:__hd "
                        "options:(NSJSONReadingOptions)0 error:&__he]; "
                        "if (!__he && [__ho isKindOfClass:[NSDictionary class]]) { "
                        "[(NSDictionary *)__ho enumerateKeysAndObjectsUsingBlock:"
                        "^(id __hk, id __hv, BOOL *__stop) { "
                        "if ([__hk isKindOfClass:[NSString class]] && "
                        "[__hv isKindOfClass:[NSString class]]) { "
                        "[__req setValue:(NSString *)__hv forHTTPHeaderField:(NSString *)__hk]; "
                        "} }]; "
                        "} } "
                        "if (__b) { "
                        "__req.HTTPBody = [__b dataUsingEncoding:NSUTF8StringEncoding]; "
                        "} "
                        "dispatch_semaphore_t __sem = dispatch_semaphore_create(0); "
                        "NSURLSessionDataTask *__task = [[NSURLSession sharedSession] "
                        "dataTaskWithRequest:__req completionHandler:"
                        "^(NSData *__data, NSURLResponse *__resp, NSError *__err) { "
                        "if (!__err && __data) { "
                        "__r = [[NSString alloc] initWithData:__data "
                        "encoding:NSUTF8StringEncoding]; "
                        "} "
                        "dispatch_semaphore_signal(__sem); }]; "
                        "[__task resume]; "
                        "dispatch_semaphore_wait(__sem, DISPATCH_TIME_FOREVER); "
                        "} } "
                        "__r; })")))}
   "native_http_get_envelope"
   ;; 2026-09-03 — GET returning `{"status": N, "body": "..."}` as
   ;; JSON String. Users parse via Json.field_int_or / field_string.
   ;; nil return when: nil URL, invalid URL, transport error before
   ;; a status was received (DNS fail, connection refused, etc.).
   ;; Non-2xx status codes still return an envelope — the "error"
   ;; concept here is transport-level, not HTTP-status-level.
   {:return-kw :string
    :arity     2
    :emit-c    (fn [args]
                 (let [url-arg     (first args)
                       headers-arg (second args)]
                   (str "({ NSString *__u = " url-arg "; "
                        "NSString *__h = " headers-arg "; "
                        "__block NSString *__envelope = nil; "
                        "if (__u) { "
                        "NSURL *__url = [NSURL URLWithString:__u]; "
                        "if (__url) { "
                        "NSMutableURLRequest *__req = [NSMutableURLRequest requestWithURL:__url]; "
                        "__req.HTTPMethod = @\"GET\"; "
                        "if (__h) { "
                        "NSData *__hd = [__h dataUsingEncoding:NSUTF8StringEncoding]; "
                        "NSError *__he = nil; "
                        "id __ho = [NSJSONSerialization JSONObjectWithData:__hd "
                        "options:(NSJSONReadingOptions)0 error:&__he]; "
                        "if (!__he && [__ho isKindOfClass:[NSDictionary class]]) { "
                        "[(NSDictionary *)__ho enumerateKeysAndObjectsUsingBlock:"
                        "^(id __hk, id __hv, BOOL *__stop) { "
                        "if ([__hk isKindOfClass:[NSString class]] && "
                        "[__hv isKindOfClass:[NSString class]]) { "
                        "[__req setValue:(NSString *)__hv forHTTPHeaderField:(NSString *)__hk]; "
                        "} }]; "
                        "} } "
                        "dispatch_semaphore_t __sem = dispatch_semaphore_create(0); "
                        "NSURLSessionDataTask *__task = [[NSURLSession sharedSession] "
                        "dataTaskWithRequest:__req completionHandler:"
                        "^(NSData *__data, NSURLResponse *__resp, NSError *__err) { "
                        "if (!__err && __resp && "
                        "[__resp isKindOfClass:[NSHTTPURLResponse class]]) { "
                        "NSInteger __status = [(NSHTTPURLResponse *)__resp statusCode]; "
                        "NSString *__body = (__data ? "
                        "[[NSString alloc] initWithData:__data encoding:NSUTF8StringEncoding] "
                        ": @\"\"); "
                        "if (__body == nil) __body = @\"\"; "
                        "NSDictionary *__env = @{@\"status\": @(__status), @\"body\": __body}; "
                        "NSError *__ee = nil; "
                        "NSData *__ed = [NSJSONSerialization dataWithJSONObject:__env "
                        "options:(NSJSONWritingOptions)0 error:&__ee]; "
                        "if (__ed && !__ee) { "
                        "__envelope = [[NSString alloc] initWithData:__ed "
                        "encoding:NSUTF8StringEncoding]; } "
                        "} "
                        "dispatch_semaphore_signal(__sem); }]; "
                        "[__task resume]; "
                        "dispatch_semaphore_wait(__sem, DISPATCH_TIME_FOREVER); "
                        "} } "
                        "__envelope; })")))}
   "native_http_post_envelope"
   ;; 2026-09-03 — POST returning `{"status": N, "body": "..."}` as
   ;; JSON String. Same shape as native_http_get_envelope; takes
   ;; a UTF-8 body String appended as HTTPBody.
   {:return-kw :string
    :arity     3
    :emit-c    (fn [args]
                 (let [url-arg     (first args)
                       headers-arg (second args)
                       body-arg    (nth args 2)]
                   (str "({ NSString *__u = " url-arg "; "
                        "NSString *__h = " headers-arg "; "
                        "NSString *__b = " body-arg "; "
                        "__block NSString *__envelope = nil; "
                        "if (__u) { "
                        "NSURL *__url = [NSURL URLWithString:__u]; "
                        "if (__url) { "
                        "NSMutableURLRequest *__req = [NSMutableURLRequest requestWithURL:__url]; "
                        "__req.HTTPMethod = @\"POST\"; "
                        "if (__h) { "
                        "NSData *__hd = [__h dataUsingEncoding:NSUTF8StringEncoding]; "
                        "NSError *__he = nil; "
                        "id __ho = [NSJSONSerialization JSONObjectWithData:__hd "
                        "options:(NSJSONReadingOptions)0 error:&__he]; "
                        "if (!__he && [__ho isKindOfClass:[NSDictionary class]]) { "
                        "[(NSDictionary *)__ho enumerateKeysAndObjectsUsingBlock:"
                        "^(id __hk, id __hv, BOOL *__stop) { "
                        "if ([__hk isKindOfClass:[NSString class]] && "
                        "[__hv isKindOfClass:[NSString class]]) { "
                        "[__req setValue:(NSString *)__hv forHTTPHeaderField:(NSString *)__hk]; "
                        "} }]; "
                        "} } "
                        "if (__b) { "
                        "__req.HTTPBody = [__b dataUsingEncoding:NSUTF8StringEncoding]; "
                        "} "
                        "dispatch_semaphore_t __sem = dispatch_semaphore_create(0); "
                        "NSURLSessionDataTask *__task = [[NSURLSession sharedSession] "
                        "dataTaskWithRequest:__req completionHandler:"
                        "^(NSData *__data, NSURLResponse *__resp, NSError *__err) { "
                        "if (!__err && __resp && "
                        "[__resp isKindOfClass:[NSHTTPURLResponse class]]) { "
                        "NSInteger __status = [(NSHTTPURLResponse *)__resp statusCode]; "
                        "NSString *__body = (__data ? "
                        "[[NSString alloc] initWithData:__data encoding:NSUTF8StringEncoding] "
                        ": @\"\"); "
                        "if (__body == nil) __body = @\"\"; "
                        "NSDictionary *__env = @{@\"status\": @(__status), @\"body\": __body}; "
                        "NSError *__ee = nil; "
                        "NSData *__ed = [NSJSONSerialization dataWithJSONObject:__env "
                        "options:(NSJSONWritingOptions)0 error:&__ee]; "
                        "if (__ed && !__ee) { "
                        "__envelope = [[NSString alloc] initWithData:__ed "
                        "encoding:NSUTF8StringEncoding]; } "
                        "} "
                        "dispatch_semaphore_signal(__sem); }]; "
                        "[__task resume]; "
                        "dispatch_semaphore_wait(__sem, DISPATCH_TIME_FOREVER); "
                        "} } "
                        "__envelope; })")))}
   "native_env_set"
   ;; 2026-09-03 — set an environment variable in the current process
   ;; (overwrite existing value). Returns 0 on success, -1 on failure
   ;; (out-of-memory or invalid name). libc `setenv(name, value, 1)`;
   ;; nil-guards on both args (either nil → no-op → returns -1).
   {:return-kw :int
    :arity     2
    :emit-c    (fn [args]
                 (let [name  (first args)
                       value (second args)]
                   (str "({ NSString *__n = " name "; "
                        "NSString *__v = " value "; "
                        "int __r = -1; "
                        "if (__n && __v) { "
                        "__r = setenv([__n UTF8String], [__v UTF8String], 1); "
                        "} "
                        "(int64_t)__r; })")))}
   "native_time_sleep_ms"
   ;; 2026-09-03 — suspend the current thread for `ms` milliseconds.
   ;; libc `usleep(ms * 1000)`. Negative or zero → no-op. Truncates
   ;; long sleeps to the useconds_t max (uint32) per usleep's contract;
   ;; use for retry backoff, throttling, small delays only. Not for
   ;; long-running waits (use dispatch/GCD for that once scheduler
   ;; primitives land).
   {:return-kw :int
    :arity     1
    :emit-c    (fn [args]
                 (str "({ int64_t __ms = (int64_t)(" (first args) "); "
                      "if (__ms > 0) { "
                      "useconds_t __us = (useconds_t)((__ms > 4000000) ? "
                      "4000000000u : (uint64_t)__ms * 1000u); "
                      "usleep(__us); } "
                      "(int64_t)0; })"))}
   "native_env_get_or_default"
   ;; 2g (2026-09-02) — read an environment variable, defaulting on
   ;; miss. libc `getenv(name)` returns `const char *`; when the var
   ;; is unset, getenv returns NULL and we fall back to the caller's
   ;; default. Also nil-guards the name (nil name → default). No
   ;; distinct "get" (returning nullable String) is exposed at MVP —
   ;; callers who want nil-on-miss can pass an empty String default
   ;; and check for empty; nullable-String surface arrives when
   ;; hello-agent needs it.
   {:return-kw :string
    :arity     2
    :emit-c    (fn [args]
                 (let [name    (first args)
                       default (second args)]
                   (str "({ NSString *__n = " name "; "
                        "NSString *__d = " default "; "
                        "NSString *__r = __d; "
                        "if (__n) { "
                        "const char *__v = getenv([__n UTF8String]); "
                        "if (__v) __r = [NSString stringWithUTF8String:__v]; "
                        "} "
                        "__r; })")))}
   "native_fs_exists"
   ;; 2d (2026-09-02) — check whether a path resolves to any
   ;; filesystem entry (file, directory, symlink). Returns Int:
   ;; 1 if present, 0 if absent. Foundation call:
   ;; `-[NSFileManager fileExistsAtPath:]` on the default manager.
   ;; Note: this is a snapshot; TOCTOU-safe callers should perform
   ;; the actual read/write and handle failure directly rather than
   ;; check-then-act.
   {:return-kw :int
    :arity     1
    :emit-c    (fn [args]
                 (str "((" (first args) " && "
                      "[[NSFileManager defaultManager] fileExistsAtPath:"
                      (first args) "]) ? 1 : 0)"))}
   "native_fs_mkdirs"
   ;; 2026-09-03 — create a directory, plus any missing intermediate
   ;; directories on the way (mkdir -p). Foundation:
   ;; `-[NSFileManager createDirectoryAtPath:withIntermediateDirectories:
   ;; attributes:error:]` with intermediates=YES. Returns 0 on success,
   ;; -1 on failure. Nil path → -1.
   {:return-kw :int
    :arity     1
    :emit-c    (fn [args]
                 (str "({ NSString *__p = " (first args) "; "
                      "int64_t __r = -1; "
                      "if (__p) { NSError *__e = nil; "
                      "BOOL __ok = [[NSFileManager defaultManager] "
                      "createDirectoryAtPath:__p "
                      "withIntermediateDirectories:YES "
                      "attributes:nil error:&__e]; "
                      "__r = (__ok && !__e) ? 0 : -1; } "
                      "__r; })"))}
   "native_fs_remove"
   ;; 2026-09-03 — delete a file or directory tree. Foundation:
   ;; `-[NSFileManager removeItemAtPath:error:]` (recursive for
   ;; directories). Returns 0 on success, -1 on failure. Nil path
   ;; → -1. Absent path → -1 (does not silently succeed). Callers
   ;; who want "remove-if-exists" should `exists` check first.
   {:return-kw :int
    :arity     1
    :emit-c    (fn [args]
                 (str "({ NSString *__p = " (first args) "; "
                      "int64_t __r = -1; "
                      "if (__p) { NSError *__e = nil; "
                      "BOOL __ok = [[NSFileManager defaultManager] "
                      "removeItemAtPath:__p error:&__e]; "
                      "__r = (__ok && !__e) ? 0 : -1; } "
                      "__r; })"))}
   "native_clock_every"
   ;; Layer 5c Step 8/10 — real method-returned Stream. Called from
   ;; Clock's `every(rate_ms: Int) -> Stream` method body; creates a
   ;; CxcStream carrying subscribers plus a dispatch_source_t periodic
   ;; timer whose event handler builds a fresh `_Cxc_Instant` and
   ;; fans it to every subscriber via `objc_msgSend` cast. macOS
   ;; libdispatch quirk: `dispatch_source_t` timers targeting the main
   ;; queue don't fire under `dispatch_main()`, so the timer lives on
   ;; a dedicated serial queue.
   ;;
   ;; Payload shape (`t: Instant`) matches the registration in
   ;; `method-stream-payload-shapes` for `[_Cxc_Clock every]`. Selector
   ;; on the subscriber side is
   ;; `cxc_stream_every_from__Cxc_ClockWithsender:t:` — receiver-side
   ;; method synthesis in `lower-receive-glyph-transitions` builds the
   ;; matching signature. The fanout bypasses CxcStream's generic
   ;; `-cxc_emit` because that method assumes a fixed (id, id)
   ;; signature; typed payload needs a per-payload-shape cast, which
   ;; we specialize inline here.
   ;;
   ;; Lifecycle: the CxcStream owns the timer via its teardown block.
   ;; The subscriber owns the CxcStream (see :raw-stream-subscribe in
   ;; emit — cxc_own_resource(self, stream) at subscription site).
   ;; When the subscriber -> end, its resources drop the stream →
   ;; ARC dealloc → -cxc_end fires teardown → timer cancelled → the
   ;; CxcTimerHandle deallocs → queue released. Clock (the source)
   ;; is just a factory here; it holds no reference to the stream
   ;; and its own lifecycle is independent. Retires the earlier
   ;; CFBridgingRetain leaks and unblocks per-subscriber lifetime
   ;; (a Clock with a short internal beat count doesn't kill a
   ;; subscriber's every-stream anymore).
   ;;
   ;; The event handler holds a strong ref to the CxcStream — that
   ;; ref lives inside the timer's captured block. The
   ;; CxcTimerHandle in the teardown-captured `__cxc_h` closure keeps
   ;; the timer + queue alive as long as the stream lives. Cycle:
   ;; stream → teardown block → handle → (no back-ref to stream).
   ;; No retain cycle; when subscriber drops stream, stream deallocs
   ;; → teardown runs → handle released → block destroyed → strong
   ;; stream ref inside block also released (already dealloc'd, so
   ;; no-op).
   {:return-kw {:atom "CxcStream"}
    ;; F67 L4b — parametric return-type node consulted by the receive-glyph
    ;; :method-stream resolver so observables wrapping this native inherit the
    ;; `Stream of T` structure without an explicit `-> T` annotation on the
    ;; observable header. Post-prelude-rename shape (Stream → CxcStream,
    ;; Instant → _Cxc_Instant); the ffi table stays in the same naming
    ;; convention as its C emission.
    :return-type-node {:node :type-of :outer "CxcStream"
                       :inner {:node :type-ref :name "Instant"}}
    :arity     2
    :emit-c    (fn [args]
                 (let [src  (first args)
                       rate (second args)]
                   (str "({ "
                        "id __cxc_src = " src "; "
                        "int64_t __cxc_ms = (int64_t)(" rate "); "
                        "dispatch_queue_t __cxc_q = "
                        "dispatch_queue_create(\"cxc.every\", DISPATCH_QUEUE_SERIAL); "
                        "dispatch_source_t __cxc_t = "
                        "dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, "
                        "__cxc_q); "
                        "dispatch_source_set_timer(__cxc_t, "
                        "dispatch_time(DISPATCH_TIME_NOW, __cxc_ms * NSEC_PER_MSEC), "
                        "__cxc_ms * NSEC_PER_MSEC, 0); "
                        "CxcStream *__cxc_s = "
                        "[[CxcStream alloc] initWithSelector:"
                        "@\"cxc_stream_every_from__Cxc_ClockWithsender:value:\" "
                        "source:__cxc_src]; "
                        ;; Weak ref inside the event handler so the
                        ;; timer's block doesn't keep the stream
                        ;; alive after subscribers drop it. Without
                        ;; the weak, stream → handle → block → strong
                        ;; stream is a real cycle.
                        "__weak CxcStream *__cxc_ws = __cxc_s; "
                        "dispatch_source_set_event_handler(__cxc_t, ^{ "
                        "CxcStream *__cxc_ss = __cxc_ws; "
                        "if (__cxc_ss == nil) return; "
                        ;; Build a fresh Instant per fire — matches
                        ;; the receive-side `{t: Instant}` type. Layer
                        ;; 5 keeps Instant.millis as int (see prelude
                        ;; Instant record); a wider integral type
                        ;; requires plumbing all the way through.
                        "_Cxc_Instant __cxc_inst = { .millis = "
                        "(int)([[NSDate date] timeIntervalSince1970] * 1000) }; "
                        "SEL __cxc_sel = sel_registerName("
                        "\"cxc_stream_every_from__Cxc_ClockWithsender:value:\"); "
                        "for (id sub in __cxc_ss.subscribers) { "
                        ;; Hop to subscriber via cxc_send_async so
                        ;; the msg lands on the subscriber's own_queue
                        ;; — same delivery model as generic cxc_emit.
                        "cxc_send_async(sub, ^{ "
                        "((void (*)(id, SEL, id, _Cxc_Instant))objc_msgSend)"
                        "(sub, __cxc_sel, __cxc_src, __cxc_inst); "
                        "}); "
                        "} "
                        "}); "
                        "dispatch_resume(__cxc_t); "
                        "CxcTimerHandle *__cxc_h = [[CxcTimerHandle alloc] init]; "
                        "__cxc_h.source = __cxc_t; "
                        "__cxc_h.queue = __cxc_q; "
                        "__cxc_s.teardown = ^{ (void)__cxc_h; }; "
                        "__cxc_s; })")))}})

(defn ffi-builtin? [name] (contains? ffi-builtins name))
