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
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

/** Phone UI settings stored in the default preferences, next to the existing Nova ones. */
public final class PhonePrefs {

    public static final String KEY_THEME_MODE = "phone_theme_mode";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_SYSTEM = "system";

    public static final String KEY_SHOW_THUMBNAILS = "phone_show_thumbnails";
    private static final String KEY_SOURCES_TAB = "phone_sources_tab";

    private PhonePrefs() {}

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static String getThemeMode(Context context) {
        String mode = prefs(context).getString(KEY_THEME_MODE, THEME_SYSTEM);
        return THEME_LIGHT.equals(mode) || THEME_DARK.equals(mode) ? mode : THEME_SYSTEM;
    }

    public static void setThemeMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode).apply();
    }

    static int toNightMode(String mode) {
        if (THEME_LIGHT.equals(mode)) return AppCompatDelegate.MODE_NIGHT_NO;
        if (THEME_DARK.equals(mode)) return AppCompatDelegate.MODE_NIGHT_YES;
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    /**
     * Applies the theme to a phone UI activity only: the player overlay and the TV interface keep
     * their own themes. Changing it recreates the activity, which restores its state.
     */
    public static void applyTheme(AppCompatActivity activity) {
        int mode = toNightMode(getThemeMode(activity));
        if (activity.getDelegate().getLocalNightMode() != mode) {
            activity.getDelegate().setLocalNightMode(mode);
        }
    }

    public static boolean showThumbnails(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_THUMBNAILS, true);
    }

    public static void setShowThumbnails(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_SHOW_THUMBNAILS, show).apply();
    }

    /** Last inner tab of the Sources screen (0 = connections, 1 = quick access). */
    public static int getSourcesTab(Context context) {
        return prefs(context).getInt(KEY_SOURCES_TAB, 0);
    }

    public static void setSourcesTab(Context context, int tab) {
        prefs(context).edit().putInt(KEY_SOURCES_TAB, tab).apply();
    }
}
