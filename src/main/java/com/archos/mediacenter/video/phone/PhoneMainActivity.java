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
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.archos.mediacenter.video.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Phone UI shell: exactly three destinations (Home, Sources, Settings), each with its own back
 * stack. Videos open the full screen player directly from any list.
 */
public class PhoneMainActivity extends PhoneBaseActivity {

    public static final String ACTION_SHOW_RECENT = "com.archos.mediacenter.video.phone.SHOW_RECENT";
    public static final String ACTION_SHOW_FAVORITES = "com.archos.mediacenter.video.phone.SHOW_FAVORITES";
    public static final String ACTION_SHOW_SOURCES = "com.archos.mediacenter.video.phone.SHOW_SOURCES";

    static final int TAB_HOME = 0;
    static final int TAB_SOURCES = 1;
    static final int TAB_SETTINGS = 2;

    private static final String STATE_TAB = "tab";
    private static final int[] MENU_IDS = {R.id.phone_nav_home, R.id.phone_nav_sources, R.id.phone_nav_settings};
    @SuppressWarnings("unchecked")
    private static final Class<? extends Fragment>[] ROOTS = new Class[]{
            HomeFragment.class, SourcesFragment.class, PhoneSettingsFragment.class};

    private final TabHostFragment[] mTabs = new TabHostFragment[3];
    private int mCurrent = TAB_HOME;
    private BottomNavigationView mNav;
    private boolean mShowSourcesAfterSave;
    /** Launch intent handled once the tab screens exist (onStart). */
    private Intent mPendingIntent;

    private final ActivityResultLauncher<Intent> mSourceForm = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && mShowSourcesAfterSave) {
                    showSources(SourcesFragment.TAB_CONNECTIONS);
                }
                mShowSourcesAfterSave = false;
            });

    private final OnBackPressedCallback mBack = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            TabHostFragment tab = mTabs[mCurrent];
            Fragment top = tab.getTop();
            if (top instanceof BackHandler && ((BackHandler) top).onBackPressed()) return;
            if (tab.pop()) return;
            if (mCurrent != TAB_HOME) {
                selectTab(TAB_HOME);
                return;
            }
            // root of the app: standard Android behaviour
            setEnabled(false);
            getOnBackPressedDispatcher().onBackPressed();
            setEnabled(true);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone_main);
        SystemBars.pad(findViewById(R.id.phone_tabs_container), true, false, false);

        mNav = findViewById(R.id.phone_bottom_nav);
        mNav.inflateMenu(R.menu.phone_bottom_nav);

        FragmentManager fm = getSupportFragmentManager();
        if (savedInstanceState == null) {
            FragmentTransaction t = fm.beginTransaction();
            for (int i = 0; i < mTabs.length; i++) {
                mTabs[i] = TabHostFragment.newInstance(ROOTS[i]);
                t.add(R.id.phone_tabs_container, mTabs[i], tag(i));
                if (i != TAB_HOME) t.hide(mTabs[i]);
            }
            t.commitNow();
        } else {
            for (int i = 0; i < mTabs.length; i++) mTabs[i] = (TabHostFragment) fm.findFragmentByTag(tag(i));
            mCurrent = savedInstanceState.getInt(STATE_TAB, TAB_HOME);
        }
        mNav.setSelectedItemId(MENU_IDS[mCurrent]);
        mNav.setOnItemSelectedListener(this::onNavItemSelected);
        mNav.setOnItemReselectedListener(item -> mTabs[indexOf(item)].popToRoot());
        getOnBackPressedDispatcher().addCallback(this, mBack);

        if (savedInstanceState == null) mPendingIntent = getIntent();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mPendingIntent != null) {
            Intent intent = mPendingIntent;
            mPendingIntent = null;
            handleIntent(intent);
        }
    }

    private static String tag(int index) {
        return "phone_tab_" + index;
    }

    private static int indexOf(MenuItem item) {
        for (int i = 0; i < MENU_IDS.length; i++) if (MENU_IDS[i] == item.getItemId()) return i;
        return TAB_HOME;
    }

    private boolean onNavItemSelected(MenuItem item) {
        showTab(indexOf(item));
        return true;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, mCurrent);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // the tab screens already exist here
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_SHOW_RECENT.equals(action) || ACTION_SHOW_FAVORITES.equals(action)) {
            selectTab(TAB_HOME);
            mTabs[TAB_HOME].popToRoot();
            HomeFragment home = (HomeFragment) mTabs[TAB_HOME].getRoot();
            if (home != null) home.selectTab(ACTION_SHOW_FAVORITES.equals(action) ? HomeFragment.TAB_FAVORITES : HomeFragment.TAB_RECENT);
        } else if (ACTION_SHOW_SOURCES.equals(action)) {
            showSources(PhonePrefs.getSourcesTab(this));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Navigation used by the screens
    // ------------------------------------------------------------------------------------------

    void selectTab(int index) {
        if (mNav.getSelectedItemId() != MENU_IDS[index]) {
            mNav.setSelectedItemId(MENU_IDS[index]); // triggers showTab
        } else {
            showTab(index);
        }
    }

    private void showTab(int index) {
        if (index == mCurrent && mTabs[index].isVisible()) return;
        FragmentTransaction t = getSupportFragmentManager().beginTransaction();
        for (int i = 0; i < mTabs.length; i++) {
            if (i == index) t.show(mTabs[i]);
            else t.hide(mTabs[i]);
        }
        t.commitNow();
        mCurrent = index;
    }

    /** Opens a screen on top of the current destination. */
    void push(Fragment fragment) {
        mTabs[mCurrent].push(fragment);
    }

    /** Closes the screen on top of the current destination. */
    void pop() {
        mTabs[mCurrent].pop();
    }

    void showSources(int innerTab) {
        selectTab(TAB_SOURCES);
        mTabs[TAB_SOURCES].popToRoot();
        SourcesFragment sources = (SourcesFragment) mTabs[TAB_SOURCES].getRoot();
        if (sources != null) sources.selectInnerTab(innerTab);
    }

    /** Opens the source form; a null id creates a new source. */
    void editSource(String sourceId, boolean showSourcesAfterSave) {
        mShowSourcesAfterSave = showSourcesAfterSave;
        mSourceForm.launch(SourceEditActivity.intent(this, sourceId));
    }
}
