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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.archos.filecorelibrary.FileUtils;
import com.archos.filecorelibrary.MimeUtils;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.object.NonIndexedVideo;
import com.archos.mediacenter.video.player.PlayerActivity;
import com.archos.mediacenter.video.player.PlayerService;
import com.archos.mediacenter.video.userdata.MediaRef;
import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.PlaybackHistoryTracker;
import com.archos.mediacenter.video.userdata.ResumePolicy;
import com.archos.mediacenter.video.userdata.SourceAccess;
import com.archos.mediacenter.video.userdata.UserDataStore;
import com.archos.mediacenter.video.utils.ExternalPlayerResultListener;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.Future;

/**
 * One tap opens the full screen Nova player: no information card, no resume question. The
 * start position follows {@link ResumePolicy}.
 */
final class VideoLauncher {

    /** The "connecting" dialog only appears when the check is slow, to avoid flashing. */
    private static final long PROGRESS_DELAY_MS = 400;

    private VideoLauncher() {}

    /**
     * @param verify check that the server answers and the file still exists before opening the
     *               player (history and favorites); the browser has just listed the folder
     */
    static void play(final Activity activity, final NetworkSource source, final MediaRef ref, final long size,
                     final boolean fromStart, final boolean verify) {
        if (source == null) {
            Toast.makeText(activity, R.string.phone_error_unknown, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!verify) {
            launch(activity, source, ref, size, fromStart);
            return;
        }
        final Handler handler = new Handler(Looper.getMainLooper());
        final AlertDialog[] progress = new AlertDialog[1];
        final boolean[] cancelled = {false};
        final Future<?>[] task = new Future<?>[1];
        final Runnable showProgress = () -> {
            if (activity.isFinishing()) return;
            progress[0] = new MaterialAlertDialogBuilder(activity)
                    .setMessage(activity.getString(R.string.phone_connecting_to, source.name))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> {
                        cancelled[0] = true;
                        if (task[0] != null) task[0].cancel(true);
                    })
                    .setCancelable(false)
                    .show();
        };
        handler.postDelayed(showProgress, PROGRESS_DELAY_MS);
        task[0] = SourceAccess.stat(activity, source, ref, (file, error) -> {
            handler.removeCallbacks(showProgress);
            if (progress[0] != null && progress[0].isShowing()) progress[0].dismiss();
            if (cancelled[0] || activity.isFinishing()) return;
            if (error != null) {
                ConnectionDialogs.show(activity, source, error, ref,
                        () -> play(activity, source, ref, size, fromStart, true));
                return;
            }
            long length = file != null && file.length() > 0 ? file.length() : size;
            launch(activity, source, ref, length, fromStart);
        });
    }

    static void launch(Activity activity, NetworkSource source, MediaRef ref, long size, boolean fromStart) {
        long position = fromStart ? 0 : ResumePolicy.startPosition(UserDataStore.get(activity).getRecent(ref));
        Uri uri = source.buildUri(ref, false);
        String name = ref.getName();
        String extension = FileUtils.getExtension(name);
        String mimeType = extension != null ? MimeUtils.guessMimeTypeFromExtension(extension) : null;

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClass(activity, PlayerActivity.class);
        intent.putExtra(PlayerService.VIDEO, new NonIndexedVideo(uri, uri, name, null));
        intent.setDataAndType(uri, mimeType != null ? mimeType : "video/*");
        intent.putExtra(PlayerActivity.KEY_STREAMING_URI, uri);
        intent.putExtra(PlayerActivity.RESUME, PlayerService.RESUME_NO);
        intent.putExtra(PlayerActivity.VIDEO_PLAYER_LEGACY_EXTRA, false);
        intent.putExtra(PlayerService.PLAYLIST_ID, -1L);
        // an explicit position is applied by PlayerService without any dialog
        if (position > 0) {
            intent.putExtra(ExternalPlayerResultListener.ExternalPositionExtra.VLC_JUSTPLAYER_ACTION_EXTRA_position, (int) position);
        }
        intent.putExtra(PlaybackHistoryTracker.EXTRA_SOURCE_ID, ref.sourceId);
        intent.putExtra(PlaybackHistoryTracker.EXTRA_MEDIA_PATH, ref.path);
        intent.putExtra(PlaybackHistoryTracker.EXTRA_FILE_SIZE, size);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.no_application_to_open_file, Toast.LENGTH_SHORT).show();
        }
    }
}
