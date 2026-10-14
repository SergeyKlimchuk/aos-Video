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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.MediaRef;
import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.SecretCodec;
import com.archos.mediacenter.video.userdata.SourceProtocol;
import com.archos.mediacenter.video.userdata.UserDataDbHelper;
import com.archos.mediacenter.video.userdata.UserDataStore;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;

/** Inflates the phone screens and checks the main interactions on an in-memory store. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class PhoneUiSmokeTest {

    private static final SecretCodec PLAIN = new SecretCodec() {
        @Override
        public byte[] encrypt(String plain) {
            return plain.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String decrypt(byte[] cipher) {
            return new String(cipher, StandardCharsets.UTF_8);
        }
    };

    private Context mContext;
    private UserDataStore mStore;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mStore = new UserDataStore(new UserDataDbHelper(mContext, null), PLAIN, Runnable::run, Runnable::run);
        UserDataStore.setInstanceForTest(mStore);
        // no frame extraction in unit tests
        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putBoolean(PhonePrefs.KEY_SHOW_THUMBNAILS, false)
                .putString(PhonePrefs.KEY_THEME_MODE, PhonePrefs.THEME_SYSTEM)
                .commit();
    }

    @After
    public void tearDown() {
        UserDataStore.setInstanceForTest(null);
    }

    private NetworkSource addSource() throws Exception {
        NetworkSource s = NetworkSource.create(SourceProtocol.SMB);
        s.name = "Home NAS";
        s.host = "192.168.1.10";
        s.share = "Video";
        mStore.saveSource(s, "secret");
        return s;
    }

    private static View findVisible(View root, int id) {
        if (root.getId() == id && root.isShown()) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findVisible(group.getChildAt(i), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void threeDestinationsAndEmptyHistoryOffersToAddASource() {
        try (ActivityScenario<PhoneMainActivity> scenario = ActivityScenario.launch(PhoneMainActivity.class)) {
            scenario.onActivity(activity -> {
                shadowOf(Looper.getMainLooper()).idle();
                BottomNavigationView nav = activity.findViewById(R.id.phone_bottom_nav);
                assertEquals(3, nav.getMenu().size());
                View root = activity.getWindow().getDecorView();
                TextView title = (TextView) findVisible(root, R.id.empty_title);
                assertNotNull(title);
                assertEquals(activity.getString(R.string.phone_recent_empty), title.getText().toString());
                TextView action = (TextView) findVisible(root, R.id.empty_action);
                assertEquals(activity.getString(R.string.phone_add_source), action.getText().toString());
            });
        }
    }

    @Test
    public void favoriteHeartRemovesTheRowWithoutPlaying() throws Exception {
        NetworkSource source = addSource();
        MediaRef film = new MediaRef(source.id, "/Video/Film.mkv");
        mStore.addFavorite(film, 1_400_000_000L);
        try (ActivityScenario<PhoneMainActivity> scenario = ActivityScenario.launch(PhoneMainActivity.class)) {
            scenario.onActivity(activity -> {
                shadowOf(Looper.getMainLooper()).idle();
                HomeFragment home = (HomeFragment) activity.getSupportFragmentManager()
                        .findFragmentByTag("phone_tab_0").getChildFragmentManager().findFragmentByTag("root");
                home.selectTab(HomeFragment.TAB_FAVORITES);
                shadowOf(Looper.getMainLooper()).idle();
                View root = activity.getWindow().getDecorView();
                RecyclerView list = (RecyclerView) findVisible(root, R.id.list);
                assertNotNull(list);
                list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
                list.layout(0, 0, 1080, 1920);
                assertEquals(2, list.getAdapter().getItemCount()); // header + row
                View heart = findVisible(list, R.id.video_heart);
                assertNotNull(heart);
                heart.performClick();
                shadowOf(Looper.getMainLooper()).idle();
                assertFalse(mStore.isFavorite(film));
                // no player was started by the heart
                assertEquals(null, shadowOf(activity).getNextStartedActivity());
            });
        }
    }

    @Test
    public void sourcesAndSettingsScreensInflate() throws Exception {
        addSource();
        try (ActivityScenario<PhoneMainActivity> scenario = ActivityScenario.launch(PhoneMainActivity.class)) {
            scenario.onActivity(activity -> {
                BottomNavigationView nav = activity.findViewById(R.id.phone_bottom_nav);
                nav.setSelectedItemId(R.id.phone_nav_sources);
                shadowOf(Looper.getMainLooper()).idle();
                View root = activity.getWindow().getDecorView();
                assertNotNull(findVisible(root, R.id.sources_tabs));
                nav.setSelectedItemId(R.id.phone_nav_settings);
                shadowOf(Looper.getMainLooper()).idle();
                ViewGroup themes = (ViewGroup) findVisible(root, R.id.settings_theme_options);
                assertNotNull(themes);
                assertEquals(3, themes.getChildCount());
            });
        }
    }

    @Test
    public void darkThemeIsAppliedToThePhoneUiOnly() {
        PhonePrefs.setThemeMode(mContext, PhonePrefs.THEME_DARK);
        try (ActivityScenario<PhoneMainActivity> scenario = ActivityScenario.launch(PhoneMainActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(AppCompatDelegate.MODE_NIGHT_YES, activity.getDelegate().getLocalNightMode());
                // the global mode (player overlay, TV interface) is left untouched
                assertEquals(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED, AppCompatDelegate.getDefaultNightMode());
            });
        }
    }

    @Test
    public void sourceFormInflatesForEachProtocol() {
        try (ActivityScenario<SourceEditActivity> scenario = ActivityScenario.launch(SourceEditActivity.class)) {
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.form_protocol_ftp).performClick();
                assertTrue(activity.findViewById(R.id.form_ftp_group).isShown());
                assertFalse(activity.findViewById(R.id.form_smb_group).isShown());
                activity.findViewById(R.id.form_protocol_sftp).performClick();
                assertTrue(activity.findViewById(R.id.form_sftp_key).isShown());
            });
        }
    }
}
