(ns crux.io
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:import [java.nio.file Files FileVisitResult SimpleFileVisitor]
           [java.nio.file.attribute FileAttribute]
           [java.lang.ref ReferenceQueue PhantomReference]
           [java.util IdentityHashMap]
           [java.net ServerSocket]))

;; TODO: Replace with java.lang.ref.Cleaner in Java 9.
;; We currently still support Java 8.
(def ^:private ^ReferenceQueue reference-queue (ReferenceQueue.))
(def ^:private ^IdentityHashMap ref->cleanup (IdentityHashMap.))

(declare cleaner-future)
(def ^:private cleaner-future
  (do (when (and (bound? #'cleaner-future) cleaner-future)
        (future-cancel cleaner-future))
      (future
        (try
          (loop [ref (.remove reference-queue)]
            (try
              (when-let [cleanup (.remove ref->cleanup ref)]
                (cleanup))
              (catch Exception e
                (log/error "Error while running cleaner:" e)))
            (recur (.remove reference-queue)))
          (catch InterruptedException _)))))

(defn register-cleaner [object action]
  (.put ref->cleanup (PhantomReference. object reference-queue) action))

(defn free-port ^long []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(defn create-tmpdir ^java.io.File [dir-name]
  (.toFile (Files/createTempDirectory dir-name (make-array FileAttribute 0))))

(def file-deletion-visitor
  (proxy [SimpleFileVisitor] []
    (visitFile [file _]
      (Files/delete file)
      FileVisitResult/CONTINUE)

    (postVisitDirectory [dir _]
      (Files/delete dir)
      FileVisitResult/CONTINUE)))

(defn delete-dir [dir]
  (let [dir (io/file dir)]
    (when (.exists dir)
      (Files/walkFileTree (.toPath dir) file-deletion-visitor))))

(defn folder-size
  "Total size of a file or folder in bytes"
  [^java.io.File f]
  (cond
    (string? f) (folder-size (io/file f))
    (.isDirectory f) (apply + (map folder-size (.listFiles f)))
    :default (.length f)))

(def units {:KB 1000
            :MB 1000000
            :GB 1000000000
            :TB 1000000000000
            :PB 1000000000000000})

;; There are more elegant ways to do this but they'd require more imports.
(defn ->human-size
  "Converts byte units for human readability."
  ([bytes]
   (if (< bytes 1000)
     (str bytes " B")
     (let [unit (last (filter #(>= bytes (% units)) (keys units)))]
       (->human-size bytes unit))))

  ([bytes unit]
   (as-> bytes b
     (/ b (unit units))
     (double b)
     (format "%.3f" b)
     (str b " " (name unit)))))

(defn folder-human-size [f]
  (->human-size (folder-size f)))
