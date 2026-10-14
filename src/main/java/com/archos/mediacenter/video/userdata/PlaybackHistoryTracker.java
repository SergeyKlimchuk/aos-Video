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

package com.archos.mediacenter.video.userdata;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feeds the phone UI history from PlayerService. An entry is created only once playback really
 * started (a file that fails to open never appears in the history) and then refreshed
 * periodically, on pause, on exit and at the end.
 */
public final class PlaybackHistoryTracker {

    private static final Logger log = LoggerFactory.getLogger(PlaybackHistoryTracker.class);

    public static final String EXTRA_SOURCE_ID = "nova_source_id";
    public static final String EXTRA_MEDIA_PATH = "nova_media_path";
    public static final String EXTRA_FILE_SIZE = "nova_file_size";

    /** Interval of the periodic position save while playing. */
    public static final long SAVE_INTERVAL_MS = 5000;

    private final Context mContext;
    private MediaRef mRef;
    private long mSize = -1;
    private boolean mStarted;

    public PlaybackHistoryTracker(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Called for every player command. Extras from the phone UI identify the file directly; other
     * launches are matched against the configured sources by URI.
     */
    public void begin(Intent intent, Uri uri, boolean continuingSession) {
        MediaRef ref = resolve(intent, uri);
        if (continuingSession && ref != null && ref.equals(mRef)) return;
        mRef = ref;
        mSize = ref != null && intent != null ? intent.getLongExtra(EXTRA_FILE_SIZE, -1) : -1;
        mStarted = false;
        if (log.isDebugEnabled()) log.debug("begin: tracked={}", mRef != null);
    }

    private MediaRef resolve(Intent intent, Uri uri) {
        if (uri == null) return null;
        UserDataStore store = UserDataStore.get(mContext);
        String sourceId = intent != null ? intent.getStringExtra(EXTRA_SOURCE_ID) : null;
        String path = intent != null ? intent.getStringExtra(EXTRA_MEDIA_PATH) : null;
        if (sourceId != null && path != null) {
            NetworkSource source = store.getSource(sourceId);
            // auto-next reuses the intent with a new URI: only trust extras describing this URI
            if (source != null && MediaRef.normalizePath(path).equals(NetworkSource.pathOf(uri))) {
                return new MediaRef(sourceId, path);
            }
        }
        return store.findRefForUri(uri);
    }

    public MediaRef getRef() {
        return mRef;
    }

    public boolean isTracking() {
        return mStarted && mRef != null;
    }

    /** First real playback of the session: from now on the history is updated. */
    public void onPlaybackStarted() {
        if (mRef != null) mStarted = true;
    }

    public void update(long positionMs, long durationMs, boolean completed) {
        if (!isTracking()) return;
        boolean done = completed || ResumePolicy.isCompleted(positionMs, durationMs);
        UserDataStore.get(mContext).recordPlayback(mRef, mSize, done ? Math.max(positionMs, durationMs) : positionMs,
                durationMs, done);
    }

    public void end() {
        mStarted = false;
    }
}
