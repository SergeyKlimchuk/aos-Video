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
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

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
    /** Copy of the last touch event of the window: finds the finger of a long press. */
    private MotionEvent mLastTouch;
    /** The rest of a gesture that closed the preview is not delivered to the screen. */
    private boolean mSwallowGesture;
    /** Key held right now, if any, and the one whose long press opened the preview. */
    private int mHeldKey = KeyEvent.KEYCODE_UNKNOWN;
    private long mHeldKeyDownTime;
    private int mOpeningKey = KeyEvent.KEYCODE_UNKNOWN;
    private long mOpeningKeyDownTime;
    /** Release of a key that closed the preview, not delivered to the screen. */
    private int mSwallowKey = KeyEvent.KEYCODE_UNKNOWN;

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
        if (mLastTouch != null) {
            mLastTouch.recycle();
            mLastTouch = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------------------------------
    // Input while the preview is shown: nothing reaches the screen behind it
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) mSwallowGesture = false;
        if (mLastTouch != null) mLastTouch.recycle();
        mLastTouch = MotionEvent.obtain(ev);
        if (mPreview != null) {
            mPreview.onTouchEvent(ev);
            return true;
        }
        if (mSwallowGesture) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) mSwallowGesture = false;
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean isFingerDown() {
        if (mLastTouch == null) return false;
        int action = mLastTouch.getActionMasked();
        return action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            mHeldKey = code;
            mHeldKeyDownTime = event.getDownTime();
        } else if (action == KeyEvent.ACTION_UP && code == mHeldKey) {
            mHeldKey = KeyEvent.KEYCODE_UNKNOWN;
        }
        if (mPreview != null) {
            if (code == mOpeningKey && event.getDownTime() == mOpeningKeyDownTime) {
                // the key whose long press opened the card: its repeats go nowhere, its release
                // lets the row clear its pressed state (a long press is never followed by a click)
                if (action != KeyEvent.ACTION_UP) return true;
                mOpeningKey = KeyEvent.KEYCODE_UNKNOWN;
                return super.dispatchKeyEvent(event);
            }
            // Back closes the card (mBack); volume, media and other system keys keep working
            if (event.isSystem()) return super.dispatchKeyEvent(event);
            // any other key closes it and goes no further (Enter would play the row behind)
            if (action == KeyEvent.ACTION_DOWN) {
                mSwallowKey = code;
                dismissPreview();
            }
            return true;
        }
        if (code == mSwallowKey) {
            if (action == KeyEvent.ACTION_UP) mSwallowKey = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        // the wheel and the mouse buttons are not for the hidden list; hovering goes on to the
        // card (explore by touch), which keeps it from the screen behind
        if (mPreview != null) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_SCROLL || action == MotionEvent.ACTION_BUTTON_PRESS
                    || action == MotionEvent.ACTION_BUTTON_RELEASE) {
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(ev);
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
    void showPreview(ListItems.VideoItem item, View row, Bitmap placeholder) {
        if (item.source == null || isFinishing()) return;
        dismissPreview();
        int index = findRowPointer(row);
        boolean touch = index >= 0;
        int pointerId = touch ? mLastTouch.getPointerId(index) : -1;
        float x = touch ? mLastTouch.getX(index) : 0;
        float y = touch ? mLastTouch.getY(index) : 0;
        mOpeningKey = touch ? KeyEvent.KEYCODE_UNKNOWN : mHeldKey;
        mOpeningKeyDownTime = mHeldKeyDownTime;
        mPreview = VideoPreview.show(this, item, placeholder, touch, pointerId, x, y, preview -> {
            if (mPreview != preview) return;
            mPreview = null;
            // what is left of a gesture that closed the card never reaches the screen
            if (isFingerDown()) mSwallowGesture = true;
        });
        if (isFingerDown()) {
            // the row and its list stop tracking this gesture: no click, no scroll, no tab swipe
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, x, y, 0);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
    }

    /**
     * The finger of a long press: the one on the row (another may rest elsewhere), allowing the
     * drift a long press tolerates, or the only finger down. -1 without one.
     */
    private int findRowPointer(View row) {
        if (!isFingerDown() || row == null) return -1;
        MotionEvent last = mLastTouch;
        int[] location = new int[2];
        row.getLocationInWindow(location);
        float slop = 2f * ViewConfiguration.get(this).getScaledTouchSlop();
        int lifted = last.getActionMasked() == MotionEvent.ACTION_POINTER_UP ? last.getActionIndex() : -1;
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        int fingers = 0;
        int only = -1;
        for (int i = 0; i < last.getPointerCount(); i++) {
            if (i == lifted) continue;
            fingers++;
            only = i;
            float dx = Math.max(0, Math.max(location[0] - last.getX(i), last.getX(i) - (location[0] + row.getWidth())));
            float dy = Math.max(0, Math.max(location[1] - last.getY(i), last.getY(i) - (location[1] + row.getHeight())));
            if (dx <= slop && dy <= slop && dx * dx + dy * dy < bestDistance) {
                best = i;
                bestDistance = dx * dx + dy * dy;
            }
        }
        return best >= 0 ? best : fingers == 1 ? only : -1;
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
