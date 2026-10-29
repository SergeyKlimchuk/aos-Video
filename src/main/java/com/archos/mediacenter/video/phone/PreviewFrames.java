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
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;

import com.archos.filecorelibrary.MetaFile2;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.MediaRef;
import com.archos.mediacenter.video.userdata.NetworkError;
import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.SourceAccess;
import com.archos.mediacenter.video.userdata.SourceProtocol;
import com.archos.mediacenter.video.userdata.UserDataStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Extracts the frames of the hold-to-preview card in the background.
 *
 * <ul>
 * <li>{@link #COUNT} frames, each in the middle of an equal part of the video: neither the black
 *     first frame nor the very end;</li>
 * <li>the file is looked up first (SMB, FTP), before anything comes from the cache: moved,
 *     renamed or deleted outside the app it is reported missing, replaced or modified it gets its
 *     own frames and duration. The retriever would report an unreachable server or a missing
 *     file only as missing frames after its timeouts; FileCoreLibrary tells why at once. SFTP
 *     only gets its key checked, FileCoreLibrary misreports its other failures;</li>
 * <li>frames extracted once come back from the thumbnail disk cache, without any other
 *     connection when the duration of this version of the file is known;</li>
 * <li>{@link #CONNECTIONS} connections at most, each with its own retriever in the frame process
 *     ({@link FrameClient}): a file that crashes the decoder never ends the app. The frame nearest
 *     to what the card needs next is always extracted first;</li>
 * <li>{@link #cancel()} aborts the extraction in progress. Only the technical caches are written
 *     (frames, duration): no history, no position.</li>
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
        /** May come again with another value when the file turns out to have changed. */
        void onDuration(long durationMs);

        /** A frame, or (frame null) the reason why there is none for this position. */
        void onFrame(int index, Bitmap frame, @StringRes int failure);

        /** Nothing at all can be shown for this video. */
        void onError(@StringRes int message);
    }

    /** One connection to the file. */
    interface Extractor {
        /** Opens the file; returns its duration in ms when asked for, -1 otherwise or when unknown. */
        long open(String uri, boolean needDuration) throws Exception;

        /** The frame nearest to this time, or null; {@link UnreadableException} for no frame at all. */
        Bitmap frameAt(long timeMs) throws Exception;

        /** Idempotent; may be called from any thread, also while {@link #frameAt} runs. */
        void close();
    }

    interface ExtractorFactory {
        Extractor create(Context context, NetworkSource source);
    }

    /** The file as the server describes it now. */
    static final class FileState {
        final long size;
        final long modified;

        FileState(long size, long modified) {
            this.size = size;
            this.modified = modified;
        }
    }

    /** Looks the file up: its state (null when unknown), or throws the reason it cannot be reached. */
    interface Probe {
        FileState check(Context context, NetworkSource source, MediaRef ref) throws Exception;
    }

    /** The file cannot be read, or crashes the decoder: no other frame is tried. */
    static final class UnreadableException extends Exception {
        UnreadableException() {
            super("unreadable file");
        }
    }

    @VisibleForTesting
    static ExtractorFactory sFactory = RemoteExtractor::new;

    @VisibleForTesting
    static Probe sProbe = (context, source, ref) -> {
        MetaFile2 file = SourceAccess.statNow(context, source, ref);
        return new FileState(file.length(), file.lastModified());
    };

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
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private final Object mLock = new Object();
    // guarded by mLock
    private final int[] mState = new int[COUNT];
    private final List<Extractor> mOpen = new ArrayList<>();
    /** Version of the file the frames belong to: listed or played first, then as looked up. */
    private long mSize;
    private long mModified;
    private String mCacheKey;
    /** Why the server could not be asked about the file: no connection is tried then. */
    private Exception mUnreachable;
    private int mFocus;
    private boolean mForward = true;
    private long mDuration;
    private int mDelivered;
    private int mFinished;
    private int mExtracted;
    private int mMisses;
    private boolean mOthersStarted;
    private boolean mCancelled;

    /**
     * @param size            size as listed or played, may be stale (history, favorites)
     * @param modified        modification time as listed, -1 when unknown
     * @param knownDurationMs duration known for the file, may belong to an earlier version of it
     */
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
        // releasing waits for the call in progress: never on the main thread
        for (Extractor e : open) sExecutor.execute(e::close);
    }

    private boolean isCancelled() {
        synchronized (mLock) {
            return mCancelled;
        }
    }

    /**
     * One connection. The first one looks the file up, then starts without any connection when
     * the duration is known (the frames may all be cached), finds the duration otherwise, then
     * starts the other ones; all take the next needed frame until none is left.
     */
    private void run(boolean first) {
        Extractor extractor = null;
        int index = -1;
        try {
            if (first) {
                checkFile();
                long known;
                synchronized (mLock) {
                    known = mDuration;
                }
                // known for this version of the file: shown before any frame
                if (known > 0) post(() -> mListener.onDuration(known));
            }
            long duration;
            String key;
            long size;
            long modified;
            synchronized (mLock) {
                duration = mDuration;
                key = mCacheKey;
                size = mSize;
                modified = mModified;
            }
            if (!first || duration <= 0) {
                extractor = open();
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
                    // technical cache: the next preview of this file needs no other connection
                    UserDataStore.get(mContext).putMediaInfo(mRef, size, modified, duration);
                    final long d = duration;
                    post(() -> mListener.onDuration(d));
                }
                if (first) startOthers();
            }
            while ((index = next()) >= 0) {
                File file = mCache.previewFile(key, COUNT, index);
                Bitmap frame = mCache.readPreviewFrame(file);
                if (frame == null) {
                    if (extractor == null) {
                        extractor = open();
                        if (extractor == null) return;
                        extractor.open(uri(), false);
                        startOthers();
                    }
                    frame = extractor.frameAt(timeOf(index, duration));
                    if (frame != null && !isCancelled()) mCache.savePreviewFrame(file, frame);
                    if (giveUp(frame != null)) {
                        abort(index, R.string.phone_preview_error_read);
                        return;
                    }
                }
                deliver(index, frame, R.string.phone_preview_frame_unavailable);
                index = -1;
            }
        } catch (UnreadableException e) {
            // cannot be opened, or crashed the decoder: the other frames would do the same
            abort(index, R.string.phone_preview_error_read);
        } catch (SourceAccess.HostKeyException e) {
            if (first) abort(index, R.string.phone_preview_error_host_key);
        } catch (Exception | OutOfMemoryError e) {
            if (log.isDebugEnabled()) log.debug("run: {} ({})", mRef.getName(), e.getClass().getSimpleName());
            int reason = e instanceof Exception ? NetworkError.of(e).kind.messageRes : R.string.phone_preview_error_read;
            if (first) abort(index, reason);
            // a helper connection that cannot open simply leaves the frames to the others
            else if (index >= 0) deliver(index, null, reason);
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
     * Looks the file up before anything is shown from the cache (SMB, FTP), since it may have
     * changed outside the app: gone (moved, renamed, deleted) or no longer readable, nothing of it
     * is shown; another version of it (replaced, modified) gets its own frames and its duration
     * read again. When the server cannot be asked, what is cached is still shown.
     */
    private void checkFile() throws Exception {
        // FileCoreLibrary misreports SFTP failures: only its key is checked, at the first connection
        if (mSource.protocol == SourceProtocol.SFTP) return;
        FileState state;
        try {
            state = sProbe.check(mContext, mSource, mRef);
        } catch (Exception e) {
            NetworkError.Kind kind = NetworkError.classify(e);
            if (kind == NetworkError.Kind.NOT_FOUND || kind == NetworkError.Kind.ACCESS_DENIED) throw e;
            synchronized (mLock) {
                mUnreachable = e;
            }
            return;
        }
        if (state == null) return;
        long known = UserDataStore.get(mContext).getKnownDuration(mRef, state.size, state.modified);
        synchronized (mLock) {
            if (state.size != mSize || state.modified != mModified) {
                mSize = state.size;
                mModified = state.modified;
                mCacheKey = mCache.previewKey(mRef, state.size, state.modified);
            }
            // a duration known beforehand may belong to an earlier version of the file
            mDuration = known > 0 ? known : -1;
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

    /** A new connection, or null when the preview is already closed. */
    private Extractor open() throws Exception {
        Exception unreachable;
        synchronized (mLock) {
            unreachable = mUnreachable;
        }
        // the server did not answer the lookup: the retriever would only wait for its timeouts
        if (unreachable != null) throw unreachable;
        // FileCoreLibrary accepts any SFTP key: check it like every other connection of the app
        if (!SourceAccess.canConnectDirectly(mSource)) SourceAccess.verifyHostKey(mSource);
        Extractor extractor = sFactory.create(mContext, mSource);
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

    private void deliver(final int index, final Bitmap frame, @StringRes final int failure) {
        final boolean nothing;
        synchronized (mLock) {
            if (mCancelled) return;
            mState[index] = frame != null ? DONE : FAILED;
            if (frame != null) mDelivered++;
            mFinished++;
            nothing = mFinished == COUNT && mDelivered == 0;
        }
        post(() -> {
            if (nothing) mListener.onError(R.string.phone_preview_error_frames);
            mListener.onFrame(index, frame, frame != null ? 0 : failure);
        });
    }

    /**
     * The file cannot be read: every frame not extracted yet fails with this reason, which is
     * also the error of the card when nothing at all could be shown.
     */
    private void abort(int current, @StringRes final int message) {
        final List<Integer> failed = new ArrayList<>();
        final boolean nothing;
        synchronized (mLock) {
            if (mCancelled) return;
            for (int i = 0; i < COUNT; i++) {
                if (mState[i] == PENDING || (i == current && mState[i] == LOADING)) {
                    mState[i] = FAILED;
                    mFinished++;
                    failed.add(i);
                }
            }
            nothing = mDelivered == 0;
        }
        post(() -> {
            if (nothing) mListener.onError(message);
            for (int i : failed) mListener.onFrame(i, null, message);
        });
    }

    private void post(final Runnable r) {
        mMain.post(() -> {
            if (!isCancelled()) r.run();
        });
    }

    /**
     * A retriever in the frame process: a file that crashes the decoder ends that process, never
     * the app ({@link FrameClient}). Frames arrive as JPEG, at most {@link #MAX_FRAME_WIDTH} wide.
     */
    private static final class RemoteExtractor implements Extractor {
        private final Context mContext;
        private final NetworkSource mSource;
        private volatile FrameClient.Session mSession;
        private volatile boolean mClosed;

        RemoteExtractor(Context context, NetworkSource source) {
            mContext = context;
            mSource = source;
        }

        @Override
        public long open(String uri, boolean needDuration) throws Exception {
            FrameClient.Session session = FrameClient.get(mContext).open(mSource, uri);
            mSession = session;
            // closed meanwhile
            if (mClosed) session.close();
            try {
                if (!session.open()) throw new UnreadableException();
                return needDuration ? session.duration() : -1;
            } catch (FrameClient.CrashException e) {
                throw new UnreadableException();
            }
        }

        @Override
        public Bitmap frameAt(long timeMs) throws Exception {
            FrameClient.Session session = mSession;
            if (session == null) return null;
            byte[] jpeg;
            try {
                jpeg = session.frameAt(timeMs, MAX_FRAME_WIDTH);
            } catch (FrameClient.CrashException e) {
                throw new UnreadableException();
            }
            return jpeg != null ? BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length) : null;
        }

        @Override
        public void close() {
            mClosed = true;
            FrameClient.Session session = mSession;
            if (session != null) session.close();
        }
    }
}
