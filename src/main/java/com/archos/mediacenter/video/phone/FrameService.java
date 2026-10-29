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

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.IBinder;

import com.archos.filecorelibrary.jcifs.JcifsUtils;
import com.archos.filecorelibrary.smbj.SmbjUtils;
import com.archos.mediacenter.video.userdata.SourceAccess;
import com.archos.medialib.IMediaMetadataRetriever;
import com.archos.medialib.MediaFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the native retriever of the phone UI (list thumbnails, hold-to-preview) in its own process,
 * ":frames", like the legacy UI does in ":scanner": a damaged or unusual file can crash the
 * decoder, which then ends this process only. {@link FrameClient} finds out which file did it.
 *
 * <p>Every call runs on a binder thread; a session may be closed while one of its calls runs,
 * which aborts the extraction.</p>
 */
public final class FrameService extends Service {

    private static final Logger log = LoggerFactory.getLogger(FrameService.class);

    private static final int JPEG_QUALITY = 82;

    private final Map<Integer, IMediaMetadataRetriever> mSessions = new ConcurrentHashMap<>();

    private final IFrameService.Stub mBinder = new IFrameService.Stub() {
        @Override
        public boolean open(int session, String uri, String[] credentials) {
            // CustomApplication creates the SMB clients on a thread of its own: a first file may
            // arrive before (no-op afterwards)
            JcifsUtils.getInstance(FrameService.this);
            SmbjUtils.getInstance(FrameService.this);
            // the credentials of this process may be older than an edit made in the app
            SourceAccess.registerCredentials(credentials);
            IMediaMetadataRetriever retriever;
            try {
                retriever = MediaFactory.createMetadataRetriever(FrameService.this);
            } catch (Throwable t) {
                log.warn("open: no retriever ({})", t.getClass().getSimpleName());
                return false;
            }
            release(mSessions.put(session, retriever));
            try {
                // reads only what the demuxer needs, never the whole file
                retriever.setDataSource(uri);
                return true;
            } catch (Throwable t) {
                if (log.isDebugEnabled()) log.debug("open: cannot open ({})", t.getClass().getSimpleName());
                // unless close() came first and released it already
                if (mSessions.remove(session, retriever)) release(retriever);
                return false;
            }
        }

        @Override
        public long duration(int session) {
            IMediaMetadataRetriever retriever = mSessions.get(session);
            if (retriever == null) return -1;
            try {
                String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long duration = d != null ? Long.parseLong(d.trim()) : -1;
                return duration > 0 ? duration : -1;
            } catch (Throwable t) {
                return -1;
            }
        }

        @Override
        public byte[] frameAt(int session, long timeMs, int maxWidth) {
            IMediaMetadataRetriever retriever = mSessions.get(session);
            if (retriever == null) return null;
            Bitmap frame = null;
            try {
                frame = timeMs < 0 ? retriever.getFrameAtTime(-1)
                        : retriever.getFrameAtTime(timeMs * 1000, IMediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) return null;
                frame = scale(frame, maxWidth);
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                return frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) ? out.toByteArray() : null;
            } catch (Throwable t) {
                // undecodable position, or the session closed meanwhile
                return null;
            } finally {
                if (frame != null) frame.recycle();
            }
        }

        @Override
        public void close(int session) {
            release(mSessions.remove(session));
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        for (Integer session : new ArrayList<>(mSessions.keySet())) release(mSessions.remove(session));
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    /** The Android retriever (fallback) returns full resolution frames. */
    private static Bitmap scale(Bitmap frame, int maxWidth) {
        if (maxWidth <= 0 || frame.getWidth() <= maxWidth) return frame;
        int height = Math.max(1, frame.getHeight() * maxWidth / frame.getWidth());
        Bitmap scaled = Bitmap.createScaledBitmap(frame, maxWidth, height, true);
        if (scaled != frame) frame.recycle();
        return scaled;
    }

    /** Idempotent; may run while a call of the session is in progress. */
    private static void release(IMediaMetadataRetriever retriever) {
        if (retriever == null) return;
        try {
            retriever.release();
        } catch (RuntimeException | IOException ignored) {
            // nothing to clean up
        }
    }
}
