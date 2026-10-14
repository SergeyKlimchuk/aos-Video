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

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.PinnedFolder;
import com.archos.mediacenter.video.userdata.UserDataStore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Quick access: shortcuts to network folders at any depth. They are links only: nothing is
 * copied or moved, and removing one never touches the server.
 */
public class QuickAccessFragment extends Fragment implements UserDataStore.Listener {

    private static final int MENU_OPEN = 1;
    private static final int MENU_RENAME = 2;
    private static final int MENU_REMOVE = 3;

    private UserDataStore mStore;
    private RecyclerView mGrid;
    private final Adapter mAdapter = new Adapter();
    private boolean mDragging;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_phone_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mStore = UserDataStore.get(requireContext());
        mGrid = view.findViewById(R.id.list);
        int horizontal = getResources().getDimensionPixelSize(R.dimen.nova_space_s);
        mGrid.setPadding(horizontal, mGrid.getPaddingTop(), horizontal, mGrid.getPaddingBottom());
        GridLayoutManager layout = new GridLayoutManager(requireContext(), 2);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mAdapter.getItemViewType(position) == Adapter.TYPE_PINNED ? 1 : 2;
            }
        });
        mGrid.setLayoutManager(layout);
        mGrid.setAdapter(mAdapter);
        new ItemTouchHelper(new ReorderCallback()).attachToRecyclerView(mGrid);
        PhoneUi.bindEmpty(view, R.drawable.ic_phone_pin_outline, getString(R.string.phone_pinned_empty),
                getString(R.string.phone_pinned_empty_hint), R.string.phone_add_folder, v -> addFolder());
        // the folder picker asks for the source list again when the user backs out of its root
        requireActivity().getSupportFragmentManager().setFragmentResultListener(BrowserFragment.RESULT_CHOOSE_SOURCE_AGAIN,
                getViewLifecycleOwner(), (key, result) -> addFolder());
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
    public void onUserDataChanged(int changes) {
        if ((changes & (UserDataStore.CHANGED_PINNED | UserDataStore.CHANGED_SOURCES)) != 0 && !mDragging) refresh();
    }

    private void refresh() {
        View view = getView();
        if (view == null) return;
        Map<String, NetworkSource> sources = new HashMap<>();
        for (NetworkSource s : mStore.getSources()) sources.put(s.id, s);
        List<PinnedFolder> pinned = new ArrayList<>();
        for (PinnedFolder p : mStore.getPinned()) if (sources.containsKey(p.ref.sourceId)) pinned.add(p);
        mAdapter.set(pinned, sources);
        PhoneUi.setEmptyVisible(view, pinned.isEmpty());
        mGrid.setVisibility(pinned.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** "Add folder": choose the source, then the folder in the browser picker. */
    void addFolder() {
        if (!isAdded()) return;
        final List<NetworkSource> sources = mStore.getSources();
        PhoneMainActivity main = PhoneUi.main(this);
        if (main == null) return;
        if (sources.isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.phone_no_sources_for_pin)
                    .setPositiveButton(R.string.phone_add_source, (d, w) -> main.editSource(null, false))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        if (sources.size() == 1) {
            main.push(BrowserFragment.pick(sources.get(0).id, false));
            return;
        }
        CharSequence[] names = new CharSequence[sources.size()];
        for (int i = 0; i < names.length; i++) names[i] = SourceVisuals.title(requireContext(), sources.get(i));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.phone_choose_source)
                .setItems(names, (d, which) -> main.push(BrowserFragment.pick(sources.get(which).id, true)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void open(PinnedFolder pinned) {
        PhoneMainActivity main = PhoneUi.main(this);
        if (main != null) main.push(BrowserFragment.pinned(pinned.id));
    }

    private void showMenu(PinnedFolder pinned, View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        Menu menu = popup.getMenu();
        menu.add(Menu.NONE, MENU_OPEN, 0, R.string.phone_menu_open);
        menu.add(Menu.NONE, MENU_RENAME, 1, R.string.phone_menu_rename);
        menu.add(Menu.NONE, MENU_REMOVE, 2, R.string.phone_menu_unpin);
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_OPEN:
                    open(pinned);
                    return true;
                case MENU_RENAME:
                    PhoneUi.promptText(requireActivity(), R.string.phone_rename_shortcut, pinned.label,
                            label -> mStore.renamePinned(pinned.id, label));
                    return true;
                case MENU_REMOVE:
                    mStore.removePinned(pinned.id);
                    PhoneUi.showSnackbar(this, getString(R.string.phone_folder_unpinned));
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    /** Long press and drag a card to change the order; the order is saved on drop. */
    private final class ReorderCallback extends ItemTouchHelper.SimpleCallback {
        ReorderCallback() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.START | ItemTouchHelper.END, 0);
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == Adapter.TYPE_PINNED ? super.getMovementFlags(recyclerView, holder) : 0;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
            if (to.getItemViewType() != Adapter.TYPE_PINNED) return false;
            mDragging = true;
            mAdapter.move(from.getBindingAdapterPosition(), to.getBindingAdapterPosition());
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {}

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
            super.clearView(recyclerView, holder);
            if (mDragging) {
                mDragging = false;
                mStore.reorderPinned(mAdapter.orderedIds());
            }
        }
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        static final int TYPE_HEADER = 0;
        static final int TYPE_PINNED = 1;
        static final int TYPE_FOOTER = 2;
        private final List<PinnedFolder> mPinned = new ArrayList<>();
        private Map<String, NetworkSource> mSources = new HashMap<>();

        @SuppressLint("NotifyDataSetChanged") // small grid rebuilt from the store
        void set(List<PinnedFolder> pinned, Map<String, NetworkSource> sources) {
            mPinned.clear();
            mPinned.addAll(pinned);
            mSources = sources;
            notifyDataSetChanged();
        }

        void move(int from, int to) {
            int a = from - 1;
            int b = to - 1;
            if (a < 0 || b < 0 || a >= mPinned.size() || b >= mPinned.size()) return;
            Collections.swap(mPinned, a, b);
            notifyItemMoved(from, to);
        }

        List<Long> orderedIds() {
            List<Long> ids = new ArrayList<>();
            for (PinnedFolder p : mPinned) ids.add(p.id);
            return ids;
        }

        @Override
        public int getItemCount() {
            return mPinned.isEmpty() ? 0 : mPinned.size() + 2;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return TYPE_HEADER;
            if (position == mPinned.size() + 1) return TYPE_FOOTER;
            return TYPE_PINNED;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            int layout = viewType == TYPE_HEADER ? R.layout.item_phone_section_header
                    : viewType == TYPE_FOOTER ? R.layout.item_phone_footer_text : R.layout.item_phone_pinned;
            return new RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            View v = holder.itemView;
            int type = getItemViewType(position);
            if (type == TYPE_HEADER) {
                ((TextView) v.findViewById(R.id.header_title)).setText(R.string.phone_pinned_header);
                v.findViewById(R.id.header_trailing).setVisibility(View.GONE);
                com.google.android.material.button.MaterialButton action = v.findViewById(R.id.header_action);
                action.setVisibility(View.VISIBLE);
                action.setText(R.string.phone_add);
                action.setIconResource(R.drawable.ic_phone_add);
                action.setOnClickListener(b -> addFolder());
                return;
            }
            if (type == TYPE_FOOTER) {
                ((TextView) v).setText(R.string.phone_pinned_hint);
                return;
            }
            PinnedFolder pinned = mPinned.get(position - 1);
            NetworkSource source = mSources.get(pinned.ref.sourceId);
            SourceVisuals.tint(v.findViewById(R.id.pinned_tile), SourceVisuals.folderColor(position - 1));
            ((TextView) v.findViewById(R.id.pinned_label)).setText(pinned.label);
            ((TextView) v.findViewById(R.id.pinned_path)).setText(
                    Formats.join(source != null ? source.protocol.label : null, pinned.ref.path));
            TextView sourceLine = v.findViewById(R.id.pinned_source);
            boolean unavailable = source != null
                    && source.getDisplayStatus(System.currentTimeMillis()) == NetworkSource.Status.UNAVAILABLE;
            sourceLine.setText(unavailable ? getString(R.string.phone_source_unavailable) : (source != null ? source.name : ""));
            sourceLine.setTextColor(ContextCompat.getColor(v.getContext(), unavailable ? R.color.nova_error : R.color.nova_text_secondary));
            v.setOnClickListener(c -> open(pinned));
            View menu = v.findViewById(R.id.pinned_menu);
            menu.setOnClickListener(c -> showMenu(pinned, menu));
            v.setContentDescription(pinned.label + ", " + pinned.ref.path);
        }
    }
}
