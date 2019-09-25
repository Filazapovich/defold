(ns editor.scene-async
  (:require [util.profiler :as profiler]
            [dynamo.graph :as g]
            [clj-async-profiler.core :as prof])
  (:import [com.jogamp.opengl GL2]
           [java.nio ByteBuffer ByteOrder]
           [javafx.scene.image PixelBuffer PixelFormat WritableImage]
           [javafx.util Callback]))

(set! *warn-on-reflection* true)

(defn- make-direct-buffer-backed-writable-image
  [width height]
  (let [buf (doto (ByteBuffer/allocateDirect (* width height 4)) (.order (ByteOrder/nativeOrder)))
        format (PixelFormat/getByteBgraPreInstance)
        pixelBuffer (PixelBuffer. width height buf format)]
    {:byte-buffer buf
     :pixel-buffer pixelBuffer
     :image (WritableImage. pixelBuffer)}))

(defn make-async-copy-state [width height]
  {:width width
   :height height
   :state :done
   :pbo 0
   :pbo-size nil
   :images [(make-direct-buffer-backed-writable-image width height)
            (make-direct-buffer-backed-writable-image width height)]
   :buffer-update-callback (reify Callback (call [_ _] nil))
   :current-image 0})

;; // Creating a PixelBuffer using BYTE_BGRA_PRE pixel format.
;; * ByteBuffer byteBuffer = ByteBuffer.allocateDirect(width * height * 4);
;; * PixelFormat<ByteBuffer> pixelFormat = PixelFormat.getByteBgraPreInstance();
;; * PixelBuffer<ByteBuffer> pixelBuffer = new PixelBuffer<>(width, height, byteBuffer, pixelFormat);
;; * Image img = new WritableImage(pixelBuffer);

(defn request-resize! [async-copy-state width height]
  (assoc async-copy-state :width width :height height))

(defn dispose! [{:keys [pbo]} ^GL2 gl]
  (.glDeleteBuffers gl 1 (int-array [pbo]) 0)
  nil)

(defmulti begin-read! (fn [async-copy-state ^GL2 gl] (:state async-copy-state)))
(defmulti finish-image! :state)

(defn image
  ^WritableImage [{:keys [current-image images] :as async-copy-state}]
  (:image (nth images current-image)))

(defn- lazy-init! [{:keys [^int pbo] :as async-copy-state} ^GL2 gl]
  (cond-> async-copy-state
          (zero? pbo)
          (assoc :pbo (let [pbos (int-array 1)]
                        (.glGenBuffers gl 1 pbos 0)
                        (first pbos)))))

(defn- bind-pbo! [async-copy-state ^GL2 gl]
  (.glBindBuffer gl GL2/GL_PIXEL_PACK_BUFFER (:pbo async-copy-state))
  async-copy-state)

(defn- unbind-pbo! [async-copy-state ^GL2 gl]
  (.glBindBuffer gl GL2/GL_PIXEL_PACK_BUFFER 0)
  async-copy-state)

(defn- resize-pbo! [{:keys [width height pbo-size] :as async-copy-state} ^GL2 gl]
  (profiler/profile "resize-pbo" -1
    (if (and (= width (:width pbo-size))
             (= height (:height pbo-size)))
      async-copy-state
      (let [data-size (* width height 4)]
        (.glBufferData gl GL2/GL_PIXEL_PACK_BUFFER data-size nil GL2/GL_STREAM_READ)
        (assoc async-copy-state :pbo-size {:width width :height height})))))

(defn- begin-read-pixels-to-pbo! [async-copy-state ^GL2 gl]
  (profiler/profile "fbo->pbo" -1
    ;; NOTE You have to know what you are doing if you want to change these values.
    ;; If it does not match the native format exactly, glReadPixels will take a lot more time.
    ;; The read will apparently happen asynchronously. glMapBuffer below will wait until the read completes.
    ;;(.glReadPixels gl 0 0 ^int (:width async-copy-state) ^int (:height async-copy-state) GL2/GL_BGRA GL2/GL_UNSIGNED_INT_8_8_8_8_REV 0)
    (let [image (get-in async-copy-state [:images (:current-image async-copy-state)])
          ^ByteBuffer bb (:byte-buffer image)
          ^Callback cb (:buffer-update-callback async-copy-state)
          ^PixelBuffer pb (:pixel-buffer image)]
      (.glReadPixels gl 0 0 ^int (:width async-copy-state) ^int (:height async-copy-state) GL2/GL_BGRA GL2/GL_UNSIGNED_INT_8_8_8_8_REV bb)
      (.updateBuffer pb cb))
    (assoc async-copy-state :state :reading)))

(defmethod begin-read! :done
  [async-copy-state ^GL2 gl]
  (-> async-copy-state
      (lazy-init! gl)
      (bind-pbo! gl)
      (resize-pbo! gl)
      (unbind-pbo! gl)
      (begin-read-pixels-to-pbo! gl)
      ))

(defmethod begin-read! :reading
  [async-copy-state ^GL2 gl]
  (begin-read! (finish-image! async-copy-state gl) gl))

(defmethod finish-image! :done
  [async-copy-state ^GL2 gl]
  async-copy-state)

(defn- next-image [async-copy-state]
  (update async-copy-state :current-image (fn [ix] (mod (inc ix) (count (:images async-copy-state))))))

(defn- resize-image-to-pbo! [{:keys [current-image pbo-size] :as async-copy-state}]
  (profiler/profile "resize-image" -1
    (let [image (get-in async-copy-state [:images current-image])
          writable-image ^WritableImage (:image image)
          {:keys [^int width ^int height]} pbo-size]
      (cond-> async-copy-state
              (or (not= (.getWidth writable-image) width)
                  (not= (.getHeight writable-image) height))
              (assoc-in [:images current-image] (make-direct-buffer-backed-writable-image width height))))))

;; NOTE You have to know what you are doing if you want to change this value.
;; If it does not match the native format exactly, image.getPixelWriter().setPixels(...) below will take a lot more time.
(def ^:private ^PixelFormat pixel-format (PixelFormat/getByteBgraPreInstance))

(defn- copy-pbo-to-image! [async-copy-state ^GL2 gl]
  (profiler/profile "pbo->image" -1
    #_(let [image (image async-copy-state)
          {:keys [^int width ^int height]} (:pbo-size async-copy-state)
          ;; glMapBuffer will wait until glReadPixels completes
          buffer ^ByteBuffer (.glMapBuffer gl GL2/GL_PIXEL_PACK_BUFFER GL2/GL_READ_ONLY)]
      (.. image getPixelWriter (setPixels 0 0 width height pixel-format buffer (* width 4)))
      (.glUnmapBuffer gl GL2/GL_PIXEL_PACK_BUFFER))
    (assoc async-copy-state :state :done)))

(defmethod finish-image! :reading
  [async-copy-state ^GL2 gl]
  (-> async-copy-state
      (next-image)
      (resize-image-to-pbo!)
      ;;(bind-pbo! gl)
      (copy-pbo-to-image! gl)
      ;;(unbind-pbo! gl)
      ))

(comment
  (require 'dev)
  (require 'prof)
  (require '[dynamo.graph :as g])
  (g/clear-system-cache!)
  (prof/profile-for 10 {:threads true})
  ;; TODO: Try old approach and see if we can swap the buffer in the image.
  ;; Perhaps we can trick it by making a mock ByteBuffer that does not contain
  ;; any data but just delegates to an internal one that we can swap :)
  )

