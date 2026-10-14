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
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MenuItem;
import android.view.MotionEvent;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
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

    // Hold-to-preview: the finger that made the long press keeps driving the card
    private VideoPreview mPreview;
    private boolean mTouchDown;
    private int mTouchPointerId;
    private float mTouchX;
    private float mTouchY;
    /** The rest of a gesture that closed the preview is not delivered to the screen. */
    private boolean mSwallowGesture;

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
            if (mPreview != null) {
                mPreview.dismiss();
                return;
            }
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

    @Override
    protected void onPause() {
        dismissPreview();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) dismissPreview();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        dismissPreview();
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onDestroy() {
        dismissPreview();
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mSwallowGesture = false;
            mTouchDown = true;
            mTouchPointerId = ev.getPointerId(0);
        }
        if (mTouchDown) {
            int index = ev.findPointerIndex(mTouchPointerId);
            if (index >= 0) {
                mTouchX = ev.getX(index);
                mTouchY = ev.getY(index);
            }
        }
        boolean ends = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (ends) mTouchDown = false;
        if (mPreview != null) {
            // the card takes the whole gesture; the screen behind sees none of it
            mPreview.onTouchEvent(ev);
            if (mPreview == null && !ends) mSwallowGesture = true;
            return true;
        }
        if (mSwallowGesture) {
            if (ends) mSwallowGesture = false;
            return true;
        }
        return super.dispatchTouchEvent(ev);
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

    /**
     * Hold-to-preview of a video row, called from its long press: the finger still down drives
     * the card and lifting it closes the card. Nothing is played, the history is untouched.
     */
    void showPreview(ListItems.VideoItem item, Bitmap placeholder) {
        if (item.source == null || isFinishing()) return;
        dismissPreview();
        boolean touch = mTouchDown;
        mPreview = VideoPreview.show(this, item, placeholder, touch, mTouchPointerId, mTouchX, mTouchY, preview -> {
            if (mPreview == preview) mPreview = null;
        });
        if (touch) {
            // the row and its list stop tracking this gesture: no click, no scroll, no tab swipe
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, mTouchX, mTouchY, 0);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
    }

    void dismissPreview() {
        if (mPreview != null) mPreview.dismiss();
    }

    @VisibleForTesting
    VideoPreview getPreview() {
        return mPreview;
    }

    /** Opens the source form; a null id creates a new source. */
    void editSource(String sourceId, boolean showSourcesAfterSave) {
        mShowSourcesAfterSave = showSourcesAfterSave;
        mSourceForm.launch(SourceEditActivity.intent(this, sourceId));
    }
}
