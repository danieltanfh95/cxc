#!/usr/bin/env bb
;; cxc-repl — client for the in-binary REPL socket.
;;
;; A cxc binary compiled with the standard pipeline opens
;; /tmp/cxc-repl-<pid>.sock when launched with CXC_REPL=1 in its
;; environment. This script speaks the line-based protocol:
;;
;;   ping
;;   atoms
;;   read <atom-id> <obs-or-field-name>
;;   send <atom-id> <selector>
;;   chaos <rate>            ; 0.0 .. 1.0, drop fraction of async sends
;;
;; Usage:
;;   cxc-repl <pid> <op> [args...]     ; one-shot
;;   cxc-repl <pid>                    ; interactive — reads stdin lines
;;   cxc-repl auto <op> [args...]      ; picks the newest /tmp/cxc-repl-*.sock
;;
;; Examples:
;;   ./my-binary &                     ; (CXC_REPL already in env)
;;   cxc-repl $! ping
;;   cxc-repl $! atoms
;;   cxc-repl auto read 0x104936060 n
;;   cxc-repl auto send 0x104936060 bump
;;   cxc-repl auto chaos 0.1
;;
;; Return: the socket response on stdout, exit 0. Error on stderr with
;; exit 1 for missing socket / connection failure.

(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(import [java.net UnixDomainSocketAddress StandardProtocolFamily]
        [java.nio.channels SocketChannel]
        [java.nio ByteBuffer]
        [java.nio.charset StandardCharsets])

(defn- resolve-sock-path [pid]
  (cond
    (= pid "auto")
    ;; fs/glob skips sockets on some platforms; shell out to a plain
    ;; directory walk so AF_UNIX nodes show up.
    (->> (fs/list-dir "/tmp")
         (filter #(re-matches #"cxc-repl-\d+\.sock" (str (fs/file-name %))))
         (sort-by #(.toMillis ^java.nio.file.attribute.FileTime
                              (fs/last-modified-time %)))
         last
         str)

    (re-matches #"\d+" pid)
    (str "/tmp/cxc-repl-" pid ".sock")

    :else
    pid))

(defn- send-op [sock-path op args]
  (let [msg (if (seq args)
              (str op " " (str/join " " args) "\n")
              (str op "\n"))
        addr (UnixDomainSocketAddress/of sock-path)
        ch   (SocketChannel/open StandardProtocolFamily/UNIX)]
    (try
      (.connect ch addr)
      (.write ch (ByteBuffer/wrap (.getBytes msg StandardCharsets/UTF_8)))
      (.shutdownOutput ch)
      (let [buf (ByteBuffer/allocate 65536)
            sb  (StringBuilder.)]
        (loop []
          (.clear buf)
          (let [n (.read ch buf)]
            (when (pos? n)
              (.flip buf)
              (.append sb
                       (String. (.array buf) 0 (.limit buf)
                                StandardCharsets/UTF_8))
              (recur))))
        (str/trim-newline (.toString sb)))
      (finally (.close ch)))))

(defn- die! [msg]
  (binding [*out* *err*] (println msg))
  (System/exit 1))

(let [[pid op & args] *command-line-args*]
  (when-not pid
    (die! (str "usage: cxc-repl <pid|auto|/path/to/sock> <op> [args...]\n"
               "       cxc-repl <pid|auto|/path>                    (interactive)")))
  (let [sock-path (resolve-sock-path pid)]
    (when (or (nil? sock-path) (str/blank? sock-path))
      (die! (str "no cxc-repl socket found for " pid
                 " (is the binary running with CXC_REPL=1?)")))
    (when-not (fs/exists? sock-path)
      (die! (str "socket missing: " sock-path)))
    (if op
      (println (send-op sock-path op args))
      (do
        (println (str "cxc-repl connected: " sock-path "  (ctrl-d to exit)"))
        (loop []
          (print "cxc> ") (flush)
          (when-let [line (read-line)]
            (when (seq (str/trim line))
              (let [[o & as] (str/split (str/trim line) #"\s+")]
                (try
                  (println (send-op sock-path o as))
                  (catch Exception e
                    (binding [*out* *err*]
                      (println (str "error: " (.getMessage e))))))))
            (recur)))))))
