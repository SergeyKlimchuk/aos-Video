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
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.archos.mediacenter.video.R;
import com.google.android.material.tabs.TabLayout;

/** Sources: network connections and Quick access shortcuts (not a library section). */
public class SourcesFragment extends Fragment {

    static final int TAB_CONNECTIONS = 0;
    static final int TAB_QUICK_ACCESS = 1;

    private static final String[] TAGS = {"connections", "quick_access"};

    private TabLayout mTabs;
    private ImageButton mAdd;
    private int mSelected;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_phone_sources, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mSelected = PhonePrefs.getSourcesTab(requireContext()) == TAB_QUICK_ACCESS ? TAB_QUICK_ACCESS : TAB_CONNECTIONS;
        FragmentManager fm = getChildFragmentManager();
        if (fm.findFragmentByTag(TAGS[0]) == null) {
            fm.beginTransaction()
                    .add(R.id.sources_content, ConnectionsFragment.newInstance(false), TAGS[0])
                    .add(R.id.sources_content, new QuickAccessFragment(), TAGS[1])
                    .commitNow();
        }
        mAdd = view.findViewById(R.id.sources_add);
        mAdd.setOnClickListener(v -> {
            if (mSelected == TAB_CONNECTIONS) {
                PhoneMainActivity main = PhoneUi.main(this);
                if (main != null) main.editSource(null, false);
            } else {
                Fragment f = getChildFragmentManager().findFragmentByTag(TAGS[TAB_QUICK_ACCESS]);
                if (f instanceof QuickAccessFragment) ((QuickAccessFragment) f).addFolder();
            }
        });
        mTabs = view.findViewById(R.id.sources_tabs);
        mTabs.addTab(mTabs.newTab().setText(R.string.phone_tab_connections));
        mTabs.addTab(mTabs.newTab().setText(R.string.phone_tab_quick_access));
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
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    void selectInnerTab(int index) {
        if (mTabs == null) {
            PhonePrefs.setSourcesTab(requireContext(), index);
            return;
        }
        TabLayout.Tab tab = mTabs.getTabAt(index);
        if (tab != null) tab.select();
    }

    private void showChild(int index) {
        mSelected = index;
        PhonePrefs.setSourcesTab(requireContext(), index);
        mAdd.setContentDescription(getString(index == TAB_CONNECTIONS ? R.string.phone_add_source : R.string.phone_add_folder));
        FragmentManager fm = getChildFragmentManager();
        Fragment shown = fm.findFragmentByTag(TAGS[index]);
        Fragment hidden = fm.findFragmentByTag(TAGS[1 - index]);
        if (shown == null || hidden == null) return;
        fm.beginTransaction().show(shown).hide(hidden).commitNow();
    }
}
