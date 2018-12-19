(ns crux.memory
  (:import [java.nio ByteOrder ByteBuffer]
           [org.agrona DirectBuffer MutableDirectBuffer]
           org.agrona.concurrent.UnsafeBuffer))

(defprotocol MemoryRegion
  (->on-heap ^bytes [this])

  (->off-heap
    ^MutableDirectBuffer [this]
    ^MutableDirectBuffer [this ^MutableDirectBuffer to])

  (off-heap? [this])

  (capacity [this]))

(extend-protocol MemoryRegion
  (class (byte-array 0))
  (->on-heap [this]
    this)

  (->off-heap [this]
    (let [b (UnsafeBuffer. (ByteBuffer/allocateDirect (alength ^bytes this)))]
      (->off-heap this b)))

  (->off-heap [this ^MutableDirectBuffer to]
    (doto to
      (.putBytes 0 ^bytes this)))

  (off-heap? [this]
    false)

  (capacity [this]
    (alength ^bytes this))

  DirectBuffer
  (->on-heap [this]
    (if (and (.byteArray this)
             (= (.capacity this)
                (alength (.byteArray this))))
      (.byteArray this)
      (let [bytes (byte-array (.capacity this))]
        (.getBytes this 0 bytes)
        bytes)))

  (->off-heap [this]
    (if (off-heap? this)
      this
      (->off-heap this (UnsafeBuffer. (ByteBuffer/allocateDirect (alength ^bytes this))))))

  (->off-heap [this ^MutableDirectBuffer to]
    (doto to
      (.putBytes 0 this 0 (.capacity this))))

  (off-heap? [this]
    (or (some-> (.byteBuffer this) (.isDirect))
        (and (nil? (.byteArray this))
             (nil? (.byteBuffer this)))))

  (capacity [this]
    (.capacity this))

  ByteBuffer
  (->on-heap [this]
    (if (and (.hasArray this)
             (= (.remaining this)
                (alength (.array this))))
      (.array this)
     (doto (byte-array (.remaining this))
        (->> (.get this)))))

  (->off-heap [this]
    (if (.isDirect this)
      (UnsafeBuffer. this (.position this) (.remaining this))
      (->off-heap this (UnsafeBuffer. (ByteBuffer/allocateDirect (.remaining this))))))

  (->off-heap [this ^MutableDirectBuffer to]
    (doto to
      (.putBytes 0 this (.position this) (.remaining this))))

  (off-heap? [this]
    (.isDirect this))

  (capacity [this]
    (.remaining this)))
