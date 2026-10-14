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
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.os.BundleCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.archos.filecorelibrary.MetaFile2;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.MediaRef;
import com.archos.mediacenter.video.userdata.NetworkError;
import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.PinnedFolder;
import com.archos.mediacenter.video.userdata.SourceAccess;
import com.archos.mediacenter.video.userdata.UserDataStore;
import com.archos.mediaprovider.ArchosMediaFile;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * Network file browser: folders first, then playable videos only (subtitles and other files
 * stay hidden but remain available to the player).
 *
 * <p>Back goes up one level within the current scenario only: from a pinned folder it returns to
 * Quick access, never to the parents of the shortcut; going above is an explicit action.
 */
public class BrowserFragment extends Fragment implements PhoneListAdapter.Callbacks, BackHandler, UserDataStore.Listener {

    /** Fragment result asking Quick access to show the source chooser again. */
    static final String RESULT_CHOOSE_SOURCE_AGAIN = "phone_choose_source_again";

    private static final int MODE_BROWSE = 0;
    private static final int MODE_PINNED = 1;
    private static final int MODE_PICK = 2;

    private static final String ARG_MODE = "mode";
    private static final String ARG_SOURCE = "source";
    private static final String ARG_PATH = "path";
    private static final String ARG_PINNED = "pinned";
    private static final String ARG_CHOOSER_ON_EXIT = "chooser_on_exit";
    private static final String STATE_STACK = "stack";
    private static final String STATE_SCROLL = "scroll";

    private static final long PROGRESS_DELAY_MS = 250;
    /** No listing ever spins forever: after this delay the server is reported as not answering. */
    private static final long LIST_TIMEOUT_MS = 45_000;

    private static final int MENU_PARENT = 1;
    private static final int MENU_REFRESH = 2;
    private static final int MENU_FOLDER_OPEN = 3;
    private static final int MENU_FOLDER_PIN = 4;

    private int mMode;
    private NetworkSource mSource;
    private PinnedFolder mPinnedShortcut;
    private UserDataStore mStore;

    /** Folder levels of this scenario; the last one is displayed. */
    private ArrayList<String> mStack = new ArrayList<>();
    private ArrayList<Parcelable> mScroll = new ArrayList<>();
    private final Map<String, List<MetaFile2>> mListings = new HashMap<>();
    private List<MetaFile2> mFiles;

    private RecyclerView mList;
    private PhoneListAdapter mAdapter;
    private TextView mTitle;
    private TextView mBreadcrumb;
    private MaterialButton mPinButton;
    private View mProgress;
    private Future<?> mTask;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mShowProgress = () -> {
        if (mProgress != null) mProgress.setVisibility(View.VISIBLE);
    };
    private final Runnable mTimeout = this::onListTimeout;

    static BrowserFragment browse(String sourceId, String path) {
        return create(MODE_BROWSE, sourceId, path, -1, false);
    }

    static BrowserFragment pinned(long pinnedId) {
        return create(MODE_PINNED, null, null, pinnedId, false);
    }

    /** Folder picker for "Add folder" of Quick access. */
    static BrowserFragment pick(String sourceId, boolean chooserOnExit) {
        return create(MODE_PICK, sourceId, null, -1, chooserOnExit);
    }

    private static BrowserFragment create(int mode, String sourceId, String path, long pinnedId, boolean chooserOnExit) {
        BrowserFragment f = new BrowserFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_MODE, mode);
        args.putString(ARG_SOURCE, sourceId);
        args.putString(ARG_PATH, path);
        args.putLong(ARG_PINNED, pinnedId);
        args.putBoolean(ARG_CHOOSER_ON_EXIT, chooserOnExit);
        f.setArguments(args);
        return f;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_phone_browser, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mStore = UserDataStore.get(requireContext());
        Bundle args = requireArguments();
        mMode = args.getInt(ARG_MODE);
        String startPath;
        if (mMode == MODE_PINNED) {
            mPinnedShortcut = mStore.getPinned(args.getLong(ARG_PINNED));
            mSource = mPinnedShortcut != null ? mStore.getSource(mPinnedShortcut.ref.sourceId) : null;
            startPath = mPinnedShortcut != null ? mPinnedShortcut.ref.path : "/";
        } else {
            mSource = mStore.getSource(args.getString(ARG_SOURCE));
            startPath = args.getString(ARG_PATH);
            if (startPath == null && mSource != null) startPath = mSource.getRootPath();
        }
        if (mSource == null) {
            // the source or shortcut was removed meanwhile
            mHandler.post(this::close);
            return;
        }
        if (savedInstanceState != null && savedInstanceState.getStringArrayList(STATE_STACK) != null) {
            mStack = savedInstanceState.getStringArrayList(STATE_STACK);
            ArrayList<Parcelable> scroll = BundleCompat.getParcelableArrayList(savedInstanceState, STATE_SCROLL, Parcelable.class);
            if (scroll != null) mScroll = scroll;
        } else {
            mStack.add(MediaRef.normalizePath(startPath));
            mScroll.add(null);
        }
        while (mScroll.size() < mStack.size()) mScroll.add(null);

        View toolbar = view.findViewById(R.id.toolbar);
        mTitle = toolbar.findViewById(R.id.toolbar_title);
        toolbar.findViewById(R.id.toolbar_back).setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        ViewGroup actions = toolbar.findViewById(R.id.toolbar_actions);
        ImageButton more = (ImageButton) LayoutInflater.from(requireContext()).inflate(R.layout.view_phone_toolbar_action, actions, false);
        more.setImageResource(R.drawable.ic_phone_more_vert);
        more.setContentDescription(getString(R.string.phone_more_actions));
        more.setOnClickListener(v -> showFolderMenu(more));
        actions.addView(more);

        mBreadcrumb = view.findViewById(R.id.browser_breadcrumb);
        ((TextView) view.findViewById(R.id.browser_source)).setText(
                Formats.join(mSource.protocol.label, mSource.getAuthority()));
        ((ImageView) view.findViewById(R.id.browser_source_icon)).setImageResource(SourceVisuals.icon(mSource.protocol));
        mPinButton = view.findViewById(R.id.browser_pin);
        mPinButton.setOnClickListener(v -> togglePinCurrent());
        if (mMode == MODE_PICK) {
            mPinButton.setVisibility(View.GONE);
            view.findViewById(R.id.browser_pick_bar).setVisibility(View.VISIBLE);
            view.findViewById(R.id.browser_pick).setOnClickListener(v -> pickCurrent());
        }
        mProgress = view.findViewById(R.id.browser_progress);
        mList = view.findViewById(R.id.list);
        mList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mList.addItemDecoration(new PhoneListAdapter.RowDivider(requireContext()));
        mAdapter = new PhoneListAdapter(requireContext(), this, this);
        mList.setAdapter(mAdapter);
        load(false);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mStore != null) mStore.addListener(this);
    }

    @Override
    public void onStop() {
        if (mStore != null) mStore.removeListener(this);
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        saveScroll();
        outState.putStringArrayList(STATE_STACK, mStack);
        outState.putParcelableArrayList(STATE_SCROLL, mScroll);
    }

    @Override
    public void onDestroyView() {
        cancelTask();
        ThumbnailLoader.get(requireContext()).cancel(this);
        super.onDestroyView();
    }

    @Override
    public void onUserDataChanged(int changes) {
        if (mSource == null || mStack.isEmpty()) return;
        if ((changes & UserDataStore.CHANGED_SOURCES) != 0) {
            NetworkSource updated = mStore.getSource(mSource.id);
            if (updated == null) {
                close();
                return;
            }
            mSource = updated;
        }
        if ((changes & (UserDataStore.CHANGED_FAVORITES | UserDataStore.CHANGED_PINNED | UserDataStore.CHANGED_RECENTS)) != 0) {
            render();
        }
    }

    private String currentPath() {
        return mStack.get(mStack.size() - 1);
    }

    private void close() {
        PhoneMainActivity main = PhoneUi.main(this);
        if (main != null && isAdded()) main.pop();
    }

    // ------------------------------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean onBackPressed() {
        if (mSource == null) return false;
        if (mStack.size() > 1) {
            cancelTask();
            ThumbnailLoader.get(requireContext()).cancel(this);
            mStack.remove(mStack.size() - 1);
            mScroll.remove(mScroll.size() - 1);
            load(false);
            return true;
        }
        if (mMode == MODE_PICK && requireArguments().getBoolean(ARG_CHOOSER_ON_EXIT)) {
            // leaving the root of the picker goes back to the choice of the source
            final FragmentActivity activity = requireActivity();
            mHandler.post(() -> activity.getSupportFragmentManager()
                    .setFragmentResult(RESULT_CHOOSE_SOURCE_AGAIN, new Bundle()));
        }
        return false;
    }

    private void openFolder(String path) {
        saveScroll();
        cancelTask();
        ThumbnailLoader.get(requireContext()).cancel(this);
        mStack.add(MediaRef.normalizePath(path));
        mScroll.add(null);
        load(false);
    }

    private void saveScroll() {
        if (mList == null || mList.getLayoutManager() == null || mScroll.isEmpty()) return;
        mScroll.set(mScroll.size() - 1, mList.getLayoutManager().onSaveInstanceState());
    }

    private void showFolderMenu(View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        Menu menu = popup.getMenu();
        if (!"/".equals(currentPath())) menu.add(Menu.NONE, MENU_PARENT, 0, R.string.phone_parent_folder);
        menu.add(Menu.NONE, MENU_REFRESH, 1, R.string.phone_refresh);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_PARENT) {
                // explicit move above the scenario root; Back returns here
                openFolder(MediaRef.parentOf(currentPath()));
                return true;
            }
            if (item.getItemId() == MENU_REFRESH) {
                load(true);
                return true;
            }
            return false;
        });
        popup.show();
    }

    // ------------------------------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------------------------------

    private void cancelTask() {
        mHandler.removeCallbacks(mShowProgress);
        mHandler.removeCallbacks(mTimeout);
        if (mTask != null) {
            mTask.cancel(true);
            mTask = null;
        }
    }

    private void load(boolean force) {
        updateHeader();
        final String path = currentPath();
        List<MetaFile2> cached = force ? null : mListings.get(path);
        if (cached != null) {
            mFiles = cached;
            render();
            restoreScroll();
            return;
        }
        cancelTask();
        mFiles = null;
        mAdapter.submitList(Collections.emptyList());
        PhoneUi.setEmptyVisible(requireView(), false);
        mHandler.postDelayed(mShowProgress, PROGRESS_DELAY_MS);
        mHandler.postDelayed(mTimeout, LIST_TIMEOUT_MS);
        mTask = SourceAccess.list(requireContext(), mSource, path, (files, error) -> {
            if (getView() == null || !path.equals(currentPath())) return;
            mHandler.removeCallbacks(mShowProgress);
            mHandler.removeCallbacks(mTimeout);
            mProgress.setVisibility(View.GONE);
            mTask = null;
            if (error != null) {
                showError(error);
                return;
            }
            mListings.put(path, files);
            mFiles = files;
            render();
            restoreScroll();
        });
    }

    private void restoreScroll() {
        Parcelable state = mScroll.isEmpty() ? null : mScroll.get(mScroll.size() - 1);
        if (state != null && mList.getLayoutManager() != null) {
            mList.post(() -> mList.getLayoutManager().onRestoreInstanceState(state));
        }
    }

    private void onListTimeout() {
        if (mTask == null || getView() == null) return;
        cancelTask();
        mProgress.setVisibility(View.GONE);
        showErrorView(getString(R.string.phone_error_timeout), false);
    }

    private void showError(NetworkError error) {
        if (error.kind == NetworkError.Kind.HOST_KEY && error.cause instanceof SourceAccess.HostKeyException) {
            showErrorView(ConnectionDialogs.describe(requireContext(), error), false);
            ConnectionDialogs.confirmHostKey(requireActivity(), mSource, (SourceAccess.HostKeyException) error.cause, () -> load(true));
            return;
        }
        boolean settings = error.kind == NetworkError.Kind.AUTH || error.kind == NetworkError.Kind.UNREACHABLE
                || error.kind == NetworkError.Kind.PROTOCOL;
        showErrorView(ConnectionDialogs.describe(requireContext(), error), settings);
    }

    /** No automatic deletion of anything: only retry or go to the connection settings. */
    private void showErrorView(String message, boolean withSettings) {
        View root = requireView();
        mAdapter.submitList(Collections.emptyList());
        PhoneUi.bindEmpty(root, R.drawable.ic_phone_cloud_off, message, null, R.string.phone_retry, v -> load(true));
        MaterialButton secondary = root.findViewById(R.id.empty_secondary_action);
        if (withSettings) {
            secondary.setVisibility(View.VISIBLE);
            secondary.setText(R.string.phone_connection_settings);
            secondary.setOnClickListener(v -> ConnectionDialogs.editSource(requireActivity(), mSource));
        } else {
            secondary.setVisibility(View.GONE);
        }
        PhoneUi.setEmptyVisible(root, true);
    }

    private static boolean isVideo(MetaFile2 file) {
        String name = file.getName();
        if (name == null || name.startsWith(".")) return false;
        String mime = file.getMimeType();
        if (mime != null && mime.startsWith("video/")) return true;
        return ArchosMediaFile.isVideoFileType(ArchosMediaFile.getSimpleFileType(name));
    }

    /** Natural order: "Episode 2" before "Episode 10". */
    static final Comparator<String> NATURAL = (a, b) -> {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    };

    private void render() {
        if (getView() == null || mSource == null || mStack.isEmpty()) return;
        updateHeader();
        if (mFiles == null) return;
        Context context = requireContext();
        String path = currentPath();
        List<MetaFile2> folders = new ArrayList<>();
        List<MetaFile2> videos = new ArrayList<>();
        for (MetaFile2 f : mFiles) {
            if (f.isDirectory()) {
                if (f.getName() != null && !f.getName().startsWith(".")) folders.add(f);
            } else if (isVideo(f)) {
                videos.add(f);
            }
        }
        Comparator<MetaFile2> byName = (x, y) -> NATURAL.compare(x.getName(), y.getName());
        Collections.sort(folders, byName);
        Collections.sort(videos, byName);
        List<ListItems.Item> items = new ArrayList<>();
        for (MetaFile2 f : folders) {
            MediaRef ref = new MediaRef(mSource.id, MediaRef.child(path, f.getName()));
            items.add(new ListItems.FolderItem(ref, f.getName(), mStore.findPinned(ref) != null));
        }
        if (!videos.isEmpty()) items.add(new ListItems.Header(getString(R.string.phone_video_files), null));
        for (MetaFile2 f : videos) {
            MediaRef ref = new MediaRef(mSource.id, MediaRef.child(path, f.getName()));
            long size = f.length();
            String meta = Formats.join(Formats.size(context, size), Formats.extension(f.getName()));
            com.archos.mediacenter.video.userdata.RecentVideo recent = mStore.getRecent(ref);
            items.add(new ListItems.VideoItem(ref, mSource, f.getName(), size, f.lastModified(), mStore.getKnownDuration(ref),
                    meta, recent != null ? recent.getProgress() : -1, mStore.isFavorite(ref), false));
        }
        mAdapter.submitList(items);
        boolean empty = items.isEmpty();
        if (empty) {
            PhoneUi.bindEmpty(requireView(), R.drawable.ic_phone_folder, getString(R.string.phone_folder_empty), null, 0, null);
            requireView().<View>findViewById(R.id.empty_secondary_action).setVisibility(View.GONE);
        }
        PhoneUi.setEmptyVisible(requireView(), empty);
    }

    private void updateHeader() {
        String path = currentPath();
        String title;
        if (mMode == MODE_PINNED && mStack.size() == 1 && mPinnedShortcut != null) {
            title = mPinnedShortcut.label;
        } else if (path.equals(mSource.getRootPath()) || "/".equals(path)) {
            title = mSource.name;
        } else {
            title = MediaRef.nameOf(path);
        }
        mTitle.setText(mMode == MODE_PICK && mStack.size() == 1 ? getString(R.string.phone_pick_folder_title) + " · " + title : title);
        StringBuilder crumbs = new StringBuilder(mSource.name);
        if (!"/".equals(path)) crumbs.append(path.replace("/", " / "));
        mBreadcrumb.setText(crumbs);
        if (mMode != MODE_PICK) {
            boolean pinned = mStore.findPinned(new MediaRef(mSource.id, path)) != null;
            mPinButton.setText(pinned ? R.string.phone_unpin_folder : R.string.phone_pin_folder);
            mPinButton.setIconResource(pinned ? R.drawable.ic_phone_pin : R.drawable.ic_phone_pin_outline);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pinning
    // ------------------------------------------------------------------------------------------

    private String defaultLabel(String path) {
        return "/".equals(path) || path.equals(mSource.getRootPath()) ? mSource.name : MediaRef.nameOf(path);
    }

    private void togglePinCurrent() {
        togglePin(new MediaRef(mSource.id, currentPath()));
    }

    private void togglePin(MediaRef ref) {
        PinnedFolder existing = mStore.findPinned(ref);
        if (existing != null) {
            mStore.removePinned(existing.id);
            PhoneUi.showSnackbar(this, getString(R.string.phone_folder_unpinned));
            return;
        }
        PhoneUi.promptText(requireActivity(), R.string.phone_pin_folder, defaultLabel(ref.path), label -> {
            if (mStore.addPinned(ref, label) != null) PhoneUi.showSnackbar(this, getString(R.string.phone_folder_pinned));
        });
    }

    /** Picker confirmation: name the shortcut, save it and return to Quick access. */
    private void pickCurrent() {
        MediaRef ref = new MediaRef(mSource.id, currentPath());
        PinnedFolder existing = mStore.findPinned(ref);
        if (existing != null) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(getString(R.string.phone_already_pinned, existing.label))
                    .setPositiveButton(android.R.string.ok, (d, w) -> close())
                    .setNeutralButton(R.string.phone_menu_rename, (d, w) -> PhoneUi.promptText(requireActivity(),
                            R.string.phone_rename_shortcut, existing.label, label -> {
                                mStore.renamePinned(existing.id, label);
                                close();
                            }))
                    .show();
            return;
        }
        PhoneUi.promptText(requireActivity(), R.string.phone_pin_folder, defaultLabel(ref.path), label -> {
            mStore.addPinned(ref, label);
            close();
        });
    }

    // ------------------------------------------------------------------------------------------
    // Row callbacks
    // ------------------------------------------------------------------------------------------

    @Override
    public void onVideoClick(ListItems.VideoItem item) {
        // the folder has just been listed: open the player right away
        VideoLauncher.play(requireActivity(), mSource, item.ref, item.size, false, false);
    }

    @Override
    public void onHeartClick(ListItems.VideoItem item) {
        mStore.toggleFavorite(item.ref, item.size);
    }

    @Override
    public boolean hasVideoMenu() {
        return false;
    }

    @Override
    public void onVideoMenu(ListItems.VideoItem item, View anchor) {
        // no menu on browser rows
    }

    @Override
    public void onVideoPreview(ListItems.VideoItem item, Bitmap placeholder) {
        PhoneMainActivity main = PhoneUi.main(this);
        if (main != null) main.showPreview(item, placeholder);
    }

    @Override
    public void onFolderClick(ListItems.FolderItem item) {
        openFolder(item.ref.path);
    }

    @Override
    public void onFolderMenu(ListItems.FolderItem item, View anchor) {
        if (mMode == MODE_PICK) return;
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenu().add(Menu.NONE, MENU_FOLDER_OPEN, 0, R.string.phone_menu_open);
        popup.getMenu().add(Menu.NONE, MENU_FOLDER_PIN, 1, item.pinned ? R.string.phone_menu_unpin : R.string.phone_menu_pin);
        popup.setOnMenuItemClickListener(menuItem -> {
            if (menuItem.getItemId() == MENU_FOLDER_OPEN) {
                openFolder(item.ref.path);
                return true;
            }
            if (menuItem.getItemId() == MENU_FOLDER_PIN) {
                togglePin(item.ref);
                return true;
            }
            return false;
        });
        popup.show();
    }

    @Override
    public void onHeaderAction(ListItems.Header header) {
        // section headers have no action in the browser
    }
}
