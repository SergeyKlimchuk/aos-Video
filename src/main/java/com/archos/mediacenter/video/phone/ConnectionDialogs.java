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
import android.content.Context;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.MediaRef;
import com.archos.mediacenter.video.userdata.NetworkError;
import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.SourceAccess;
import com.archos.mediacenter.video.userdata.UserDataStore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Connection failures as the user can act on them: retry, fix the connection settings or
 * confirm an SFTP server key. Records are never deleted because of a failure.
 */
final class ConnectionDialogs {

    private ConnectionDialogs() {}

    static String describe(Context context, NetworkError error) {
        return context.getString(error == null ? R.string.phone_error_unknown : error.kind.messageRes);
    }

    /**
     * @param file the file being opened, null when a folder or the source itself failed
     * @param retry run again after "Retry" or after the server key was trusted
     */
    static void show(Activity activity, NetworkSource source, NetworkError error, MediaRef file, Runnable retry) {
        if (activity == null || activity.isFinishing()) return;
        if (error.kind == NetworkError.Kind.HOST_KEY && error.cause instanceof SourceAccess.HostKeyException) {
            confirmHostKey(activity, source, (SourceAccess.HostKeyException) error.cause, retry);
            return;
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        if (error.kind == NetworkError.Kind.NOT_FOUND && file != null) {
            builder.setTitle(R.string.phone_file_not_found_title)
                    .setMessage(activity.getString(R.string.phone_file_not_found_message, file.getName()))
                    .setPositiveButton(android.R.string.ok, null);
        } else if (error.kind == NetworkError.Kind.AUTH) {
            builder.setTitle(activity.getString(R.string.phone_cannot_connect_title, source.name))
                    .setMessage(describe(activity, error))
                    .setPositiveButton(R.string.phone_menu_edit_connection, (d, w) -> editSource(activity, source))
                    .setNegativeButton(android.R.string.cancel, null);
        } else {
            builder.setTitle(activity.getString(R.string.phone_cannot_connect_title, source.name))
                    .setMessage(describe(activity, error))
                    .setPositiveButton(R.string.phone_retry, (d, w) -> {
                        if (retry != null) retry.run();
                    })
                    .setNeutralButton(R.string.phone_connection_settings, (d, w) -> editSource(activity, source))
                    .setNegativeButton(android.R.string.cancel, null);
        }
        builder.show();
    }

    static void editSource(Activity activity, NetworkSource source) {
        if (activity instanceof PhoneMainActivity) {
            ((PhoneMainActivity) activity).editSource(source.id, false);
        } else {
            activity.startActivity(SourceEditActivity.intent(activity, source.id));
        }
    }

    /**
     * First connection: show the fingerprint and let the user trust it. Changed key: block, warn
     * and require an explicit decision; never trust silently.
     */
    static void confirmHostKey(Activity activity, NetworkSource source, SourceAccess.HostKeyException e, Runnable onTrusted) {
        String server = source.getAuthority();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(e.changed ? R.string.phone_hostkey_changed_title : R.string.phone_hostkey_new_title)
                .setMessage(activity.getString(e.changed ? R.string.phone_hostkey_changed_message : R.string.phone_hostkey_new_message,
                        server, e.fingerprint))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(e.changed ? R.string.phone_hostkey_trust_new : R.string.phone_hostkey_trust, (d, w) -> {
                    UserDataStore store = UserDataStore.get(activity);
                    if (store.getSource(source.id) != null) store.setHostKeyFingerprint(source.id, e.fingerprint);
                    source.hostKeyFingerprint = e.fingerprint;
                    SourceAccess.markHostKeyTrusted(source.id);
                    if (onTrusted != null) onTrusted.run();
                });
        builder.show();
    }
}
