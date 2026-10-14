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

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.MediaRef;
import com.archos.mediacenter.video.userdata.NetworkError;
import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.SourceAccess;
import com.archos.mediacenter.video.userdata.UserDataStore;
import com.archos.medialib.IMediaMetadataRetriever;
import com.archos.medialib.MediaFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Extracts the frames of the hold-to-preview card in the background.
 *
 * <ul>
 * <li>{@link #COUNT} frames, each in the middle of an equal part of the video: neither the black
 *     first frame nor the very end;</li>
 * <li>the file is looked up first: the retriever reports an unreachable server or a missing file
 *     only as missing frames after its timeouts, FileCoreLibrary tells why at once;</li>
 * <li>frames extracted once come back from the thumbnail disk cache, without any connection when
 *     the duration is already known;</li>
 * <li>{@link #CONNECTIONS} connections at most, each with its own retriever; the frame nearest to
 *     what the card needs next is always extracted first;</li>
 * <li>{@link #cancel()} aborts the extraction in progress (the native retriever supports a release
 *     from another thread). Only the technical caches are written (frames, duration): no history,
 *     no position.</li>
 * </ul>
 */
final class PreviewFrames {

    private static final Logger log = LoggerFactory.getLogger(PreviewFrames.class);

    static final int COUNT = 20;
    private static final int CONNECTIONS = 2;
    /** The Android retriever (fallback) returns full resolution frames. */
    private static final int MAX_FRAME_WIDTH = 960;
    /** Failed extractions in a row after which the remaining frames are given up. */
    private static final int MAX_MISSES = 3;
    /** Same once some frames could be extracted: a few positions may be undecodable. */
    private static final int MAX_MISSES_AFTER_SUCCESS = 6;

    private static final int PENDING = 0;
    private static final int LOADING = 1;
    private static final int DONE = 2;
    private static final int FAILED = 3;

    interface Listener {
        void onDuration(long durationMs);

        /** A null frame means this position could not be decoded. */
        void onFrame(int index, Bitmap frame);

        /** Nothing at all can be shown for this video. */
        void onError(@StringRes int message);
    }

    /** One connection to the file. */
    interface Extractor {
        /** Opens the file; returns its duration in ms when asked for, -1 otherwise or when unknown. */
        long open(String uri, boolean needDuration) throws Exception;

        /** The frame nearest to this time, or null. */
        Bitmap frameAt(long timeMs);

        /** Idempotent; may be called from any thread, also while {@link #frameAt} runs. */
        void close();
    }

    interface ExtractorFactory {
        Extractor create(Context context);
    }

    /** Checks that the file can be reached; throws the reason otherwise. */
    interface Probe {
        void check(Context context, NetworkSource source, MediaRef ref) throws Exception;
    }

    @VisibleForTesting
    static ExtractorFactory sFactory = RetrieverExtractor::new;

    @VisibleForTesting
    static Probe sProbe = SourceAccess::statNow;

    @VisibleForTesting
    static Executor sExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "nova-preview");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final Context mContext;
    private final NetworkSource mSource;
    private final MediaRef mRef;
    private final Listener mListener;
    private final ThumbnailLoader mCache;
    private final String mCacheKey;
    private final long mSize;
    private final long mModified;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private final Object mLock = new Object();
    // guarded by mLock
    private final int[] mState = new int[COUNT];
    private final List<Extractor> mOpen = new ArrayList<>();
    private int mFocus;
    private boolean mForward = true;
    private long mDuration;
    private int mDelivered;
    private int mFinished;
    private int mExtracted;
    private int mMisses;
    private boolean mOthersStarted;
    private boolean mCancelled;

    PreviewFrames(Context context, NetworkSource source, MediaRef ref, long size, long modified, long knownDurationMs,
                  Listener listener) {
        mContext = context.getApplicationContext();
        mSource = source;
        mRef = ref;
        mListener = listener;
        mCache = ThumbnailLoader.get(mContext);
        // an unknown modification time is completed as for the list thumbnails
        UserDataStore.MediaInfo info = modified > 0 ? null : UserDataStore.get(mContext).getMediaInfo(ref);
        if (info != null && (size <= 0 || info.size == size)) {
            size = info.size;
            modified = info.modified;
        }
        mSize = size;
        mModified = modified;
        mCacheKey = mCache.previewKey(ref, size, modified);
        mDuration = knownDurationMs > 0 ? knownDurationMs : -1;
    }

    /** Time of a frame: the middle of the index-th of {@link #COUNT} equal parts. */
    static long timeOf(int index, long durationMs) {
        return durationMs * (2L * index + 1) / (2L * COUNT);
    }

    void start() {
        long duration;
        synchronized (mLock) {
            duration = mDuration;
        }
        // a known duration is shown at once, before any connection
        if (duration > 0) mListener.onDuration(duration);
        sExecutor.execute(() -> run(true));
    }

    /**
     * The frame the card needs next: frames are extracted from there, forward (frames changing
     * by themselves) or nearest first (finger on the scale).
     */
    void setFocus(int index, boolean forward) {
        synchronized (mLock) {
            mFocus = Math.max(0, Math.min(COUNT - 1, index));
            mForward = forward;
        }
    }

    /** Stops everything; no callback is delivered afterwards. */
    void cancel() {
        List<Extractor> open;
        synchronized (mLock) {
            if (mCancelled) return;
            mCancelled = true;
            open = new ArrayList<>(mOpen);
        }
        mMain.removeCallbacksAndMessages(null);
        // releasing waits for the native call in progress: never on the main thread
        for (Extractor e : open) sExecutor.execute(e::close);
    }

    private boolean isCancelled() {
        synchronized (mLock) {
            return mCancelled;
        }
    }

    /**
     * One connection. The first one starts without any when the duration is known (the frames
     * may all be cached), finds the duration otherwise, then starts the other ones; all take the
     * next needed frame until none is left.
     */
    private void run(boolean first) {
        Extractor extractor = null;
        int index = -1;
        try {
            long duration;
            synchronized (mLock) {
                duration = mDuration;
            }
            if (!first || duration <= 0) {
                extractor = open(first);
                if (extractor == null) return;
                long fileDuration = extractor.open(uri(), duration <= 0);
                if (duration <= 0) {
                    duration = fileDuration;
                    synchronized (mLock) {
                        mDuration = duration;
                    }
                    if (duration <= 0) {
                        abort(-1, R.string.phone_preview_error_read);
                        return;
                    }
                    // technical cache: the next preview of this file needs no connection
                    UserDataStore.get(mContext).putMediaInfo(mRef, mSize, mModified, duration);
                    final long d = duration;
                    post(() -> mListener.onDuration(d));
                }
                if (first) startOthers();
            }
            while ((index = next()) >= 0) {
                File file = mCache.previewFile(mCacheKey, COUNT, index);
                Bitmap frame = mCache.readPreviewFrame(file);
                if (frame == null) {
                    if (extractor == null) {
                        extractor = open(first);
                        if (extractor == null) return;
                        extractor.open(uri(), false);
                        startOthers();
                    }
                    frame = fit(extractor.frameAt(timeOf(index, duration)));
                    if (frame != null && !isCancelled()) mCache.savePreviewFrame(file, frame);
                    if (giveUp(frame != null)) {
                        abort(index, R.string.phone_preview_error_read);
                        return;
                    }
                }
                deliver(index, frame);
                index = -1;
            }
        } catch (SourceAccess.HostKeyException e) {
            if (first) abort(index, R.string.phone_preview_error_host_key);
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("run: {} ({})", mRef.getName(), e.getClass().getSimpleName());
            if (first) abort(index, NetworkError.of(e).kind.messageRes);
            // a helper connection that cannot open simply leaves the frames to the others
            else if (index >= 0) deliver(index, null);
        } finally {
            if (extractor != null) {
                synchronized (mLock) {
                    mOpen.remove(extractor);
                }
                extractor.close();
            }
        }
    }

    /**
     * True when extractions keep failing (frames read from the cache do not count): the file
     * cannot be decoded or the server stopped answering, and every other frame would wait for
     * the same timeout.
     */
    private boolean giveUp(boolean extracted) {
        synchronized (mLock) {
            if (extracted) {
                mExtracted++;
                mMisses = 0;
                return false;
            }
            return ++mMisses >= (mExtracted == 0 ? MAX_MISSES : MAX_MISSES_AFTER_SUCCESS);
        }
    }

    private void startOthers() {
        synchronized (mLock) {
            if (mCancelled || mOthersStarted) return;
            mOthersStarted = true;
        }
        for (int i = 1; i < CONNECTIONS; i++) sExecutor.execute(() -> run(false));
    }

    /**
     * A new connection, or null when the preview is already closed.
     *
     * @param probe look the file up first (also checks the SFTP key): an unreachable server, bad
     *              credentials or a missing file then fail at once with their own message
     */
    private Extractor open(boolean probe) throws Exception {
        if (probe) sProbe.check(mContext, mSource, mRef);
        // FileCoreLibrary accepts any SFTP key: check it like every other connection of the app
        else if (!SourceAccess.canConnectDirectly(mSource)) SourceAccess.verifyHostKey(mSource);
        Extractor extractor = sFactory.create(mContext);
        synchronized (mLock) {
            if (!mCancelled) {
                mOpen.add(extractor);
                return extractor;
            }
        }
        extractor.close();
        return null;
    }

    private String uri() {
        return mSource.buildUri(mRef, false).toString();
    }

    /** Next frame to extract, or -1 when none is left. */
    private int next() {
        synchronized (mLock) {
            if (mCancelled) return -1;
            for (int d = 0; d < 2 * COUNT; d++) {
                int i;
                if (mForward) {
                    if (d >= COUNT) break;
                    i = (mFocus + d) % COUNT;
                } else {
                    int step = (d + 1) / 2;
                    i = d % 2 == 1 ? mFocus + step : mFocus - step;
                    if (i < 0 || i >= COUNT) continue;
                }
                if (mState[i] == PENDING) {
                    mState[i] = LOADING;
                    return i;
                }
            }
            return -1;
        }
    }

    private void deliver(final int index, final Bitmap frame) {
        final boolean nothing;
        synchronized (mLock) {
            if (mCancelled) return;
            mState[index] = frame != null ? DONE : FAILED;
            if (frame != null) mDelivered++;
            mFinished++;
            nothing = mFinished == COUNT && mDelivered == 0;
        }
        post(() -> {
            mListener.onFrame(index, frame);
            if (nothing) mListener.onError(R.string.phone_preview_error_frames);
        });
    }

    /** The file cannot be read: every frame not extracted yet fails. */
    private void abort(int current, @StringRes final int message) {
        final List<Integer> failed = new ArrayList<>();
        final boolean nothing;
        synchronized (mLock) {
            if (mCancelled) return;
            for (int i = 0; i < COUNT; i++) {
                if (mState[i] == PENDING || i == current) {
                    mState[i] = FAILED;
                    mFinished++;
                    failed.add(i);
                }
            }
            nothing = mDelivered == 0;
        }
        post(() -> {
            for (int i : failed) mListener.onFrame(i, null);
            if (nothing) mListener.onError(message);
        });
    }

    private void post(final Runnable r) {
        mMain.post(() -> {
            if (!isCancelled()) r.run();
        });
    }

    private static Bitmap fit(Bitmap frame) {
        if (frame == null || frame.getWidth() <= MAX_FRAME_WIDTH) return frame;
        int height = Math.max(1, frame.getHeight() * MAX_FRAME_WIDTH / frame.getWidth());
        Bitmap scaled = Bitmap.createScaledBitmap(frame, MAX_FRAME_WIDTH, height, true);
        if (scaled != frame) frame.recycle();
        return scaled;
    }

    /** MediaLib retriever: avos when available (any container, network streams), Android otherwise. */
    private static final class RetrieverExtractor implements Extractor {
        private final IMediaMetadataRetriever mRetriever;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        RetrieverExtractor(Context context) {
            mRetriever = MediaFactory.createMetadataRetriever(context);
        }

        @Override
        public long open(String uri, boolean needDuration) {
            // reads only what the demuxer needs, never the whole file
            mRetriever.setDataSource(uri);
            if (!needDuration) return -1;
            String d = mRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            try {
                return d != null ? Long.parseLong(d.trim()) : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        @Override
        public Bitmap frameAt(long timeMs) {
            try {
                return mRetriever.getFrameAtTime(timeMs * 1000, IMediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public void close() {
            if (!mClosed.compareAndSet(false, true)) return;
            try {
                mRetriever.release();
            } catch (RuntimeException | IOException ignored) {
                // nothing to clean up
            }
        }
    }
}
