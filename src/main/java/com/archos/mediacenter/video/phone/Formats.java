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
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.format.Formatter;

import com.archos.mediacenter.video.R;

import java.util.Calendar;
import java.util.Locale;

/** Size, duration and date labels of the lists ("1.4 GB · Today, 18:24"). */
final class Formats {

    static final String SEPARATOR = " · ";

    private Formats() {}

    static String size(Context context, long bytes) {
        return bytes > 0 ? Formatter.formatShortFileSize(context, bytes) : null;
    }

    /** "1:42:07" / "48:21", or null when unknown (no fake time is displayed). */
    static String duration(long durationMs) {
        return durationMs > 0 ? DateUtils.formatElapsedTime(durationMs / 1000) : null;
    }

    /** Preview time codes, always with hours: "00:52:08", "01:42:07". */
    static String timecode(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** 0 = today, 1 = yesterday, -1 otherwise. */
    private static int dayDistance(long time) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        if (sameDay(now, then)) return 0;
        now.add(Calendar.DAY_OF_YEAR, -1);
        return sameDay(now, then) ? 1 : -1;
    }

    private static String shortDate(Context context, long time) {
        return DateUtils.formatDateTime(context, time,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR);
    }

    /** "Today, 18:24", "Yesterday, 14:08", "12 Oct, 21:17". */
    static String playedAt(Context context, long time) {
        String clock = DateFormat.getTimeFormat(context).format(time);
        switch (dayDistance(time)) {
            case 0:
                return context.getString(R.string.phone_today_at, clock);
            case 1:
                return context.getString(R.string.phone_yesterday_at, clock);
            default:
                return shortDate(context, time) + ", " + clock;
        }
    }

    /** "Added today", "Added yesterday", "Added 11 Oct". */
    static String addedAt(Context context, long time) {
        switch (dayDistance(time)) {
            case 0:
                return context.getString(R.string.phone_added_today);
            case 1:
                return context.getString(R.string.phone_added_yesterday);
            default:
                return context.getString(R.string.phone_added_on, shortDate(context, time));
        }
    }

    static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : null;
    }

    static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(SEPARATOR);
            sb.append(p);
        }
        return sb.toString();
    }
}
