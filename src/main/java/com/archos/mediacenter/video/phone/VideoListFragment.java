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
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.FavoriteVideo;
import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.RecentVideo;
import com.archos.mediacenter.video.userdata.UserDataStore;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** "Recently played" (real playbacks only) or "Favorites" (user marked) list of Home. */
public class VideoListFragment extends Fragment implements PhoneListAdapter.Callbacks, UserDataStore.Listener {

    static final int MODE_RECENT = 0;
    static final int MODE_FAVORITES = 1;

    private static final String ARG_MODE = "mode";
    private static final int MENU_OPEN_LOCATION = 1;
    private static final int MENU_START_OVER = 2;
    private static final int MENU_REMOVE = 3;

    private int mMode;
    private RecyclerView mList;
    private PhoneListAdapter mAdapter;
    private UserDataStore mStore;

    static VideoListFragment newInstance(int mode) {
        VideoListFragment f = new VideoListFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_MODE, mode);
        f.setArguments(args);
        return f;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_phone_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mMode = requireArguments().getInt(ARG_MODE);
        mStore = UserDataStore.get(requireContext());
        mList = view.findViewById(R.id.list);
        mList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mList.addItemDecoration(new PhoneListAdapter.RowDivider(requireContext()));
        mAdapter = new PhoneListAdapter(requireContext(), this, this);
        mList.setAdapter(mAdapter);
        refresh();
    }

    @Override
    public void onStart() {
        super.onStart();
        mStore.addListener(this);
        refresh();
    }

    @Override
    public void onStop() {
        mStore.removeListener(this);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        ThumbnailLoader.get(requireContext()).cancel(this);
        super.onDestroyView();
    }

    @Override
    public void onUserDataChanged(int changes) {
        if ((changes & (UserDataStore.CHANGED_FAVORITES | UserDataStore.CHANGED_RECENTS | UserDataStore.CHANGED_SOURCES)) != 0) {
            refresh();
        }
    }

    void scrollToTop() {
        if (mList != null) mList.smoothScrollToPosition(0);
    }

    private void refresh() {
        if (getView() == null) return;
        Context context = requireContext();
        Map<String, NetworkSource> sources = new HashMap<>();
        for (NetworkSource s : mStore.getSources()) sources.put(s.id, s);
        long now = System.currentTimeMillis();
        List<ListItems.Item> items = new ArrayList<>();
        if (mMode == MODE_RECENT) {
            for (RecentVideo r : mStore.getRecents()) {
                NetworkSource source = sources.get(r.ref.sourceId);
                if (source == null) continue;
                String meta = Formats.join(Formats.size(context, r.size), Formats.playedAt(context, r.lastPlayedAt));
                items.add(new ListItems.VideoItem(r.ref, source, r.ref.getName(), r.size, -1, r.durationMs, meta,
                        r.getProgress(), mStore.isFavorite(r.ref), isUnavailable(source, now)));
            }
        } else {
            for (FavoriteVideo f : mStore.getFavorites()) {
                NetworkSource source = sources.get(f.ref.sourceId);
                if (source == null) continue;
                RecentVideo recent = mStore.getRecent(f.ref);
                long size = f.size > 0 ? f.size : (recent != null ? recent.size : -1);
                String meta = Formats.join(Formats.size(context, size), Formats.addedAt(context, f.addedAt));
                items.add(new ListItems.VideoItem(f.ref, source, f.ref.getName(), size, -1, mStore.getKnownDuration(f.ref),
                        meta, recent != null ? recent.getProgress() : -1, true, isUnavailable(source, now)));
            }
        }
        int count = items.size();
        if (count > 0) {
            String title = getString(mMode == MODE_RECENT ? R.string.phone_recent_header : R.string.phone_favorites_header);
            items.add(0, new ListItems.Header(title, getResources().getQuantityString(R.plurals.phone_video_count, count, count)));
        }
        mAdapter.submitList(items);
        bindEmptyState(count == 0);
    }

    private static boolean isUnavailable(NetworkSource source, long now) {
        return source.getDisplayStatus(now) == NetworkSource.Status.UNAVAILABLE;
    }

    private void bindEmptyState(boolean empty) {
        View root = requireView();
        PhoneUi.setEmptyVisible(root, empty);
        mList.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) return;
        boolean hasSources = mStore.hasSources();
        int action = hasSources ? R.string.phone_open_sources : R.string.phone_add_source;
        View.OnClickListener onAction = v -> {
            PhoneMainActivity main = PhoneUi.main(this);
            if (main == null) return;
            if (hasSources) main.showSources(PhonePrefs.getSourcesTab(requireContext()));
            else main.editSource(null, true);
        };
        if (mMode == MODE_RECENT) {
            PhoneUi.bindEmpty(root, R.drawable.ic_phone_history, getString(R.string.phone_recent_empty), null, action, onAction);
        } else {
            PhoneUi.bindEmpty(root, R.drawable.ic_phone_heart_outline, getString(R.string.phone_favorites_empty),
                    getString(R.string.phone_favorites_empty_hint), action, onAction);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Row callbacks
    // ------------------------------------------------------------------------------------------

    @Override
    public void onVideoClick(ListItems.VideoItem item) {
        VideoLauncher.play(requireActivity(), item.source, item.ref, item.size, false, true);
    }

    @Override
    public void onHeartClick(ListItems.VideoItem item) {
        if (mMode == MODE_FAVORITES) {
            removeFavoriteWithUndo(item);
        } else {
            mStore.toggleFavorite(item.ref, item.size);
        }
    }

    private void removeFavoriteWithUndo(ListItems.VideoItem item) {
        final FavoriteVideo removed = mStore.removeFavorite(item.ref);
        if (removed == null) return;
        Snackbar bar = PhoneUi.snackbar(this, getString(R.string.phone_favorite_removed));
        if (bar == null) return;
        bar.setAction(R.string.phone_undo, v -> mStore.restoreFavorite(removed)).show();
    }

    @Override
    public boolean hasVideoMenu() {
        return true;
    }

    @Override
    public void onVideoMenu(ListItems.VideoItem item, View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        Menu menu = popup.getMenu();
        menu.add(Menu.NONE, MENU_OPEN_LOCATION, 0, R.string.phone_menu_open_location);
        menu.add(Menu.NONE, MENU_START_OVER, 1, R.string.phone_menu_start_over);
        menu.add(Menu.NONE, MENU_REMOVE, 2, mMode == MODE_RECENT ? R.string.phone_menu_remove_history : R.string.phone_menu_remove_favorite);
        popup.setOnMenuItemClickListener(menuItem -> {
            switch (menuItem.getItemId()) {
                case MENU_OPEN_LOCATION:
                    PhoneMainActivity main = PhoneUi.main(this);
                    if (main != null) main.push(BrowserFragment.browse(item.ref.sourceId, item.ref.getParentPath()));
                    return true;
                case MENU_START_OVER:
                    VideoLauncher.play(requireActivity(), item.source, item.ref, item.size, true, true);
                    return true;
                case MENU_REMOVE:
                    if (mMode == MODE_RECENT) {
                        // the file on the server and the favorites are untouched
                        mStore.removeRecent(item.ref);
                        PhoneUi.showSnackbar(this, getString(R.string.phone_removed_from_history));
                    } else {
                        removeFavoriteWithUndo(item);
                    }
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    @Override
    public void onFolderClick(ListItems.FolderItem item) {
        // no folders in these lists
    }

    @Override
    public void onFolderMenu(ListItems.FolderItem item, View anchor) {
        // no folders in these lists
    }

    @Override
    public void onHeaderAction(ListItems.Header header) {
        // headers have no action here
    }
}
