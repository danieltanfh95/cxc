(ns cxc.util.hash
  "Hex-rendered digest helpers. SHA-256 UTF-8 was copy-pasted into
   build.clj and incremental.clj with identical bodies — centralised
   here so cache key schemes agree bit-for-bit.")

(defn sha256-hex ^String [^String s]
  (let [md  (java.security.MessageDigest/getInstance "SHA-256")
        _   (.update md (.getBytes s "UTF-8"))
        raw (.digest md)]
    (apply str (map #(format "%02x" %) raw))))
