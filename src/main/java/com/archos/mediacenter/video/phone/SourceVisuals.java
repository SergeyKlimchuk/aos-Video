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
import android.content.res.ColorStateList;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.SourceProtocol;

/** Icons, colors and status labels of sources, shared by the cards and the browser. */
final class SourceVisuals {

    private static final int[] FOLDER_COLORS = {
            R.color.nova_tile_blue, R.color.nova_tile_purple, R.color.nova_tile_orange, R.color.nova_tile_green};

    private SourceVisuals() {}

    static int icon(SourceProtocol protocol) {
        switch (protocol) {
            case FTP:
                return R.drawable.ic_phone_folder;
            case SFTP:
                return R.drawable.ic_phone_shield;
            default:
                return R.drawable.ic_phone_server;
        }
    }

    static int color(SourceProtocol protocol) {
        switch (protocol) {
            case FTP:
                return R.color.nova_tile_green;
            case SFTP:
                return R.color.nova_tile_purple;
            default:
                return R.color.nova_tile_blue;
        }
    }

    static int folderColor(int position) {
        return FOLDER_COLORS[Math.abs(position) % FOLDER_COLORS.length];
    }

    static void tint(View tile, int colorRes) {
        ViewCompat.setBackgroundTintList(tile, ColorStateList.valueOf(ContextCompat.getColor(tile.getContext(), colorRes)));
    }

    /** "Home NAS · SMB" with the protocol in a smaller secondary style. */
    static CharSequence title(Context context, NetworkSource source) {
        SpannableStringBuilder sb = new SpannableStringBuilder(source.name);
        int start = sb.length();
        sb.append(Formats.SEPARATOR).append(source.protocol.label);
        sb.setSpan(new ForegroundColorSpan(ContextCompat.getColor(context, R.color.nova_text_secondary)), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(13, true), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    static void bindStatus(View dot, TextView label, NetworkSource source) {
        Context context = label.getContext();
        int color;
        int text;
        switch (source.getDisplayStatus(System.currentTimeMillis())) {
            case AVAILABLE:
                color = R.color.nova_status_ok;
                text = R.string.phone_status_available;
                break;
            case UNAVAILABLE:
                color = R.color.nova_status_error;
                text = R.string.phone_status_unavailable;
                break;
            case CHECKING:
                color = R.color.nova_accent;
                text = R.string.phone_status_checking;
                break;
            default:
                color = R.color.nova_status_unknown;
                text = R.string.phone_status_unknown;
                break;
        }
        tint(dot, color);
        label.setText(context.getString(text));
    }
}
