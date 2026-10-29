// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.archos.mediacenter.video.phone;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.MediaRef;
import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.UserDataStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Video thumbnails extracted from the files themselves (never online posters).
 *
 * <ul>
 * <li>memory then disk cache first; the disk key covers source, path, size and modification
 *     time so an edited file never shows a stale frame;</li>
 * <li>generation runs on two threads at most, newest request first (the rows on screen), and the
 *     requests of a screen are dropped when it is left;</li>
 * <li>frames are extracted in the frame process ({@link FrameClient}): a file that crashes the
 *     native decoder ends that process only, and is marked so it is not tried again;</li>
 * <li>a failure only means "show the default icon"; it is never an error of the video.</li>
 * </ul>
 */
public final class ThumbnailLoader {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailLoader.class);

    private static final String DIR = "phone_thumbnails";
    private static final long MAX_DISK_BYTES = 256L * 1024 * 1024;
    private static final int THUMB_WIDTH = 384;
    /** Empty file: the decoder crashes on this version of the video, it is not tried again. */
    private static final String CRASH_SUFFIX = ".crash";
    private static final String TMP_SUFFIX = ".tmp";

    public interface DurationListener {
        void onDuration(long durationMs);
    }

    @SuppressLint("StaticFieldLeak") // holds the application context only
    private static volatile ThumbnailLoader sInstance;

    private final Context mContext;
    private final File mDir;
    private final LruCache<String, Bitmap> mMemory;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor mExecutor;
    /** Guarded by itself: key -> views waiting for that key. */
    private final Map<String, List<Target>> mWaiting = new HashMap<>();
    private final Map<String, Future<?>> mTasks = new HashMap<>();
    private final Map<Object, Set<String>> mOwners = new HashMap<>();
    private final Set<String> mFailed = Collections.synchronizedSet(new HashSet<>());

    private static final class Target {
        final ImageView image;
        final View placeholder;
        final DurationListener listener;

        Target(ImageView image, View placeholder, DurationListener listener) {
            this.image = image;
            this.placeholder = placeholder;
            this.listener = listener;
        }
    }

    /** LIFO: the most recently requested rows are the visible ones. */
    private static final class LifoQueue extends LinkedBlockingDeque<Runnable> {
        @Override
        public boolean offer(Runnable r) {
            return super.offerFirst(r);
        }
    }

    public static ThumbnailLoader get(Context context) {
        ThumbnailLoader loader = sInstance;
        if (loader == null) {
            synchronized (ThumbnailLoader.class) {
                loader = sInstance;
                if (loader == null) {
                    loader = new ThumbnailLoader(context.getApplicationContext());
                    sInstance = loader;
                }
            }
        }
        return loader;
    }

    private ThumbnailLoader(Context context) {
        mContext = context;
        mDir = new File(context.getCacheDir(), DIR);
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 16);
        mMemory = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        mExecutor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LifoQueue(),
                r -> {
                    Thread t = new Thread(r, "nova-thumbs");
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });
        mExecutor.allowCoreThreadTimeOut(true);
    }

    static String cacheKey(MediaRef ref, long size, long modified) {
        String raw = ref.sourceId + "|" + ref.path + "|" + size + "|" + modified;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static void showPlaceholder(ImageView image, View placeholder) {
        image.setImageDrawable(null);
        if (placeholder != null) placeholder.setVisibility(View.VISIBLE);
    }

    private static void showBitmap(ImageView image, View placeholder, Bitmap bitmap) {
        image.setImageBitmap(bitmap);
        if (placeholder != null) placeholder.setVisibility(View.GONE);
    }

    /**
     * Displays the thumbnail of a video in a list row. Unknown size or modification time are
     * completed from what a previous listing recorded.
     *
     * @param generate false to use the cache only (unreachable server, unchecked SFTP key)
     */
    public void bind(ImageView image, View placeholder, NetworkSource source, MediaRef ref, long size, long modified,
                     boolean generate, DurationListener listener, Object owner) {
        if (source == null || !PhonePrefs.showThumbnails(mContext)) {
            image.setTag(R.id.phone_thumb_key, null);
            showPlaceholder(image, placeholder);
            return;
        }
        if (modified <= 0) {
            UserDataStore.MediaInfo info = UserDataStore.get(mContext).getMediaInfo(ref);
            if (info != null && (size <= 0 || info.size == size)) {
                size = info.size;
                modified = info.modified;
            }
        }
        final String key = cacheKey(ref, size, modified);
        if (key.equals(image.getTag(R.id.phone_thumb_key)) && image.getDrawable() != null) return;
        image.setTag(R.id.phone_thumb_key, key);
        Bitmap cached = mMemory.get(key);
        if (cached != null) {
            showBitmap(image, placeholder, cached);
            return;
        }
        showPlaceholder(image, placeholder);
        if (mFailed.contains(key)) return;

        final Target target = new Target(image, placeholder, listener);
        final long fSize = size;
        final long fModified = modified;
        synchronized (mWaiting) {
            List<Target> targets = mWaiting.get(key);
            if (targets == null) {
                targets = new ArrayList<>();
                mWaiting.put(key, targets);
            }
            // a recycled row must not be updated twice
            for (Iterator<Target> it = targets.iterator(); it.hasNext(); ) {
                if (it.next().image == image) it.remove();
            }
            targets.add(target);
            Set<String> keys = mOwners.get(owner);
            if (keys == null) {
                keys = new HashSet<>();
                mOwners.put(owner, keys);
            }
            keys.add(key);
            if (!mTasks.containsKey(key)) {
                mTasks.put(key, mExecutor.submit(() -> load(key, source, ref, fSize, fModified, generate)));
            }
        }
    }

    /** Drops the pending requests of a screen that is no longer displayed. */
    public void cancel(Object owner) {
        synchronized (mWaiting) {
            Set<String> keys = mOwners.remove(owner);
            if (keys == null) return;
            for (String key : keys) {
                mWaiting.remove(key);
                Future<?> task = mTasks.remove(key);
                // a running extraction is left to finish: interrupting native code is unsafe
                if (task != null) task.cancel(false);
            }
        }
    }

    private File file(String key) {
        return new File(mDir, key + ".jpg");
    }

    // ------------------------------------------------------------------------------------------
    // Hold-to-preview frames: same directory, same size limit, same "clear cache" as thumbnails
    // ------------------------------------------------------------------------------------------

    /**
     * Cache key of a video for its preview frames. An unknown modification time is completed
     * from what a previous listing recorded, as for the list thumbnails.
     */
    String previewKey(MediaRef ref, long size, long modified) {
        if (modified <= 0) {
            UserDataStore.MediaInfo info = UserDataStore.get(mContext).getMediaInfo(ref);
            if (info != null && (size <= 0 || info.size == size)) {
                size = info.size;
                modified = info.modified;
            }
        }
        return cacheKey(ref, size, modified) + "_p";
    }

    /** Cache file of one preview frame. */
    File previewFile(String previewKey, int count, int index) {
        return file(previewKey + count + "_" + index);
    }

    /** Cached preview frame, or null (call off the main thread). */
    Bitmap readPreviewFrame(File file) {
        if (!file.exists()) return null;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
        if (bitmap != null && !file.setLastModified(System.currentTimeMillis()) && log.isTraceEnabled()) {
            log.trace("readPreviewFrame: cannot touch {}", file);
        }
        return bitmap;
    }

    /** Stores a preview frame (call off the main thread). */
    void savePreviewFrame(File file, Bitmap bitmap) {
        save(file, bitmap);
    }

    private void load(String key, NetworkSource source, MediaRef ref, long size, long modified, boolean generate) {
        Bitmap bitmap = null;
        long duration = -1;
        boolean retryLater = false;
        try {
            File file = file(key);
            if (file.exists()) {
                bitmap = BitmapFactory.decodeFile(file.getPath());
                if (bitmap != null && !file.setLastModified(System.currentTimeMillis()) && log.isTraceEnabled()) {
                    log.trace("load: cannot touch {}", file);
                }
            }
            if (bitmap != null) {
                duration = UserDataStore.get(mContext).getKnownDuration(ref, size, modified);
            } else if (!generate) {
                retryLater = true;
            } else if (!crashMarker(key).exists()) {
                byte[] jpeg = null;
                // in the frame extraction process: a file that crashes the decoder ends that
                // process only (FrameClient), never the app
                try (FrameClient.Session session = FrameClient.get(mContext)
                        .open(source, source.buildUri(ref, false).toString())) {
                    if (session.open()) {
                        jpeg = session.frameAt(-1, THUMB_WIDTH);
                        duration = session.duration();
                    }
                } catch (FrameClient.CrashException e) {
                    // never tried again for this version of the file, until "Clear cache"
                    log.warn("load: {} crashes the decoder, no thumbnail", ref.getName());
                    markCrashing(key);
                } catch (IOException | InterruptedException e) {
                    // the extraction process could not be reached: not the file's fault
                    retryLater = true;
                }
                if (jpeg != null) {
                    bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    if (bitmap != null) write(file, jpeg);
                }
                if (duration > 0 || bitmap != null) {
                    UserDataStore.get(mContext).putMediaInfo(ref, size, modified, duration);
                }
            }
        } catch (Throwable t) {
            // a failed thumbnail only means the default icon
            if (log.isDebugEnabled()) log.debug("load: no frame for {} ({})", ref.getName(), t.getClass().getSimpleName());
        }
        if (bitmap != null) mMemory.put(key, bitmap);
        else if (!retryLater) mFailed.add(key);
        deliver(key, bitmap, duration);
    }

    private File crashMarker(String key) {
        return new File(mDir, key + CRASH_SUFFIX);
    }

    private void markCrashing(String key) {
        if (!mDir.exists() && !mDir.mkdirs()) return;
        try {
            if (!crashMarker(key).createNewFile() && log.isTraceEnabled()) log.trace("markCrashing: already marked");
        } catch (IOException e) {
            log.warn("markCrashing: cannot write marker", e);
        }
    }

    private void save(File file, Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        if (bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)) write(file, out.toByteArray());
    }

    /**
     * Writes a cached frame atomically: a reader (another preview of the same file) or a process
     * that ends meanwhile never leaves a truncated picture behind.
     */
    private void write(File file, byte[] jpeg) {
        if (!mDir.exists() && !mDir.mkdirs()) return;
        File tmp = null;
        try {
            tmp = File.createTempFile(file.getName(), TMP_SUFFIX, mDir);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(jpeg);
            }
            if (!tmp.renameTo(file)) throw new IOException("cannot rename " + tmp.getName());
            tmp = null;
        } catch (IOException e) {
            log.warn("write: cannot write thumbnail", e);
        } finally {
            if (tmp != null && !tmp.delete() && log.isTraceEnabled()) log.trace("write: cannot delete {}", tmp);
        }
        trim();
    }

    private void deliver(final String key, final Bitmap bitmap, final long duration) {
        final List<Target> targets;
        synchronized (mWaiting) {
            mTasks.remove(key);
            targets = mWaiting.remove(key);
        }
        if (targets == null) return;
        mMain.post(() -> {
            for (Target t : targets) {
                // the row may have been recycled for another video meanwhile
                if (!key.equals(t.image.getTag(R.id.phone_thumb_key))) continue;
                if (bitmap != null) showBitmap(t.image, t.placeholder, bitmap);
                if (duration > 0 && t.listener != null) t.listener.onDuration(duration);
            }
        });
    }

    /** Keeps the disk cache under its limit, oldest files first. */
    private synchronized void trim() {
        File[] files = mDir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_DISK_BYTES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_DISK_BYTES * 8 / 10) break;
            // crash markers weigh nothing; a file being written is renamed in a moment
            String name = f.getName();
            if (name.endsWith(CRASH_SUFFIX) || name.endsWith(TMP_SUFFIX)) continue;
            long length = f.length();
            if (f.delete()) total -= length;
        }
    }

    /** Disk usage of the thumbnail cache, in bytes (call off the main thread). */
    public long getCacheSize() {
        File[] files = mDir.listFiles();
        long total = 0;
        if (files != null) for (File f : files) total += f.length();
        return total;
    }

    /**
     * Deletes the cached frames and the technical media info. Favorites, history, positions,
     * sources and shortcuts are untouched. Returns the freed bytes (call off the main thread).
     */
    public long clearCache() {
        long freed = 0;
        synchronized (this) {
            File[] files = mDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    long length = f.length();
                    if (f.delete()) freed += length;
                }
            }
        }
        mMemory.evictAll();
        mFailed.clear();
        UserDataStore.get(mContext).clearMediaInfo();
        return freed;
    }
}
