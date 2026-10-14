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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.archos.mediacenter.video.R;
import com.google.android.material.tabs.TabLayout;

/** Home: exactly two library sections, Recently played and Favorites. */
public class HomeFragment extends Fragment {

    static final int TAB_RECENT = 0;
    static final int TAB_FAVORITES = 1;

    private static final String STATE_TAB = "tab";
    private static final String[] TAGS = {"recent", "favorites"};

    private TabLayout mTabs;
    private int mSelected = TAB_RECENT;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_phone_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) mSelected = savedInstanceState.getInt(STATE_TAB, TAB_RECENT);
        FragmentManager fm = getChildFragmentManager();
        if (fm.findFragmentByTag(TAGS[0]) == null) {
            fm.beginTransaction()
                    .add(R.id.home_content, VideoListFragment.newInstance(VideoListFragment.MODE_RECENT), TAGS[0])
                    .add(R.id.home_content, VideoListFragment.newInstance(VideoListFragment.MODE_FAVORITES), TAGS[1])
                    .commitNow();
        }
        mTabs = view.findViewById(R.id.home_tabs);
        mTabs.addTab(mTabs.newTab().setText(R.string.phone_tab_recent));
        mTabs.addTab(mTabs.newTab().setText(R.string.phone_tab_favorites));
        TabLayout.Tab tab = mTabs.getTabAt(mSelected);
        if (tab != null) tab.select();
        showChild(mSelected);
        mTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showChild(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                Fragment f = getChildFragmentManager().findFragmentByTag(TAGS[tab.getPosition()]);
                if (f instanceof VideoListFragment) ((VideoListFragment) f).scrollToTop();
            }
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, mSelected);
    }

    void selectTab(int index) {
        if (mTabs == null) {
            mSelected = index;
            return;
        }
        TabLayout.Tab tab = mTabs.getTabAt(index);
        if (tab != null) tab.select();
    }

    private void showChild(int index) {
        mSelected = index;
        FragmentManager fm = getChildFragmentManager();
        Fragment shown = fm.findFragmentByTag(TAGS[index]);
        Fragment hidden = fm.findFragmentByTag(TAGS[1 - index]);
        if (shown == null || hidden == null) return;
        fm.beginTransaction().show(shown).hide(hidden).commitNow();
    }
}
