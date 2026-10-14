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
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.archos.mediacenter.utils.trakt.Trakt;
import com.archos.mediascraper.AutoScrapeService;

/**
 * The application no longer enriches the library from online catalogs. The MediaLib scraper
 * and Trakt code stay in the dependency tree but are switched off through the preferences they
 * already honour: AutoScrapeService checks {@link AutoScrapeService#KEY_ENABLE_AUTO_SCRAP}
 * before every scraping pass, and Trakt only runs with an access token.
 * Network shares (SMB/FTP/SFTP) are not affected.
 */
public final class OnlineMetadataGuard {

    private OnlineMetadataGuard() {}

    public static void enforce(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        if (prefs.getBoolean(AutoScrapeService.KEY_ENABLE_AUTO_SCRAP, true)) {
            editor.putBoolean(AutoScrapeService.KEY_ENABLE_AUTO_SCRAP, false);
            changed = true;
        }
        if (prefs.getBoolean(Trakt.KEY_TRAKT_LIVE_SCROBBLING, true)) {
            editor.putBoolean(Trakt.KEY_TRAKT_LIVE_SCROBBLING, false);
            changed = true;
        }
        if (changed) editor.apply();
        if (Trakt.isTraktV2Enabled(context, prefs)) {
            // drop the local Trakt session so no background sync can start
            Trakt.wipePreferences(prefs, false);
        }
    }
}
