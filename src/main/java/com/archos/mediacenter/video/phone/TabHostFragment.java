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
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.archos.mediacenter.video.R;

/**
 * One bottom navigation destination with its own back stack. Screens are added on top of each
 * other (never replaced), so the previous one keeps its view and scroll position.
 */
public class TabHostFragment extends Fragment {

    private static final String ARG_ROOT = "root";
    private static final String TAG_ROOT = "root";

    static TabHostFragment newInstance(Class<? extends Fragment> root) {
        TabHostFragment f = new TabHostFragment();
        Bundle args = new Bundle();
        args.putString(ARG_ROOT, root.getName());
        f.setArguments(args);
        return f;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frame = new FrameLayout(inflater.getContext());
        frame.setId(R.id.phone_tab_content);
        return frame;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FragmentManager fm = getChildFragmentManager();
        if (fm.findFragmentByTag(TAG_ROOT) == null) {
            Fragment root = fm.getFragmentFactory().instantiate(requireContext().getClassLoader(),
                    requireArguments().getString(ARG_ROOT));
            fm.beginTransaction().add(R.id.phone_tab_content, root, TAG_ROOT).commitNow();
        }
    }

    Fragment getRoot() {
        return getChildFragmentManager().findFragmentByTag(TAG_ROOT);
    }

    /** The screen currently visible in this tab. */
    Fragment getTop() {
        return getChildFragmentManager().findFragmentById(R.id.phone_tab_content);
    }

    void push(Fragment fragment) {
        FragmentManager fm = getChildFragmentManager();
        Fragment top = getTop();
        androidx.fragment.app.FragmentTransaction t = fm.beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out,
                        android.R.anim.fade_in, android.R.anim.fade_out);
        if (top != null) t.hide(top);
        t.add(R.id.phone_tab_content, fragment).addToBackStack(null).commit();
    }

    /** @return false when already at the root of the tab. */
    boolean pop() {
        FragmentManager fm = getChildFragmentManager();
        if (fm.getBackStackEntryCount() == 0) return false;
        fm.popBackStack();
        return true;
    }

    void popToRoot() {
        FragmentManager fm = getChildFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStack(fm.getBackStackEntryAt(0).getId(), FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
    }

    boolean isAtRoot() {
        return getChildFragmentManager().getBackStackEntryCount() == 0;
    }
}
