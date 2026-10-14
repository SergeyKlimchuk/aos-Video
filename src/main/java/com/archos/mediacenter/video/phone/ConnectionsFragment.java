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
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.SourceAccess;
import com.archos.mediacenter.video.userdata.UserDataStore;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/** Network connections (SMB, FTP, SFTP) with their availability status. */
public class ConnectionsFragment extends Fragment implements UserDataStore.Listener {

    private static final String ARG_STANDALONE = "standalone";
    private static final int MENU_OPEN = 1;
    private static final int MENU_EDIT = 2;
    private static final int MENU_CHECK = 3;
    private static final int MENU_DELETE = 4;

    private UserDataStore mStore;
    private RecyclerView mList;
    private final Adapter mAdapter = new Adapter();

    /** @param standalone shown on its own (from Settings) with a back button */
    static ConnectionsFragment newInstance(boolean standalone) {
        ConnectionsFragment f = new ConnectionsFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_STANDALONE, standalone);
        f.setArguments(args);
        return f;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_phone_connections, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mStore = UserDataStore.get(requireContext());
        if (requireArguments().getBoolean(ARG_STANDALONE)) {
            View toolbar = view.findViewById(R.id.toolbar);
            toolbar.setVisibility(View.VISIBLE);
            ((TextView) toolbar.findViewById(R.id.toolbar_title)).setText(R.string.phone_settings_manage_sources);
            toolbar.findViewById(R.id.toolbar_back).setOnClickListener(v -> {
                PhoneMainActivity main = PhoneUi.main(this);
                if (main != null) main.pop();
            });
            ImageButton add = (ImageButton) LayoutInflater.from(requireContext())
                    .inflate(R.layout.view_phone_toolbar_action, (ViewGroup) toolbar.findViewById(R.id.toolbar_actions), false);
            add.setImageResource(R.drawable.ic_phone_add);
            add.setContentDescription(getString(R.string.phone_add_source));
            add.setOnClickListener(v -> addSource());
            ((ViewGroup) toolbar.findViewById(R.id.toolbar_actions)).addView(add);
        }
        mList = view.findViewById(R.id.list);
        mList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mList.setAdapter(mAdapter);
        PhoneUi.bindEmpty(view, R.drawable.ic_phone_server, getString(R.string.phone_sources_empty), null,
                R.string.phone_add_source, v -> addSource());
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
        if ((changes & UserDataStore.CHANGED_SOURCES) != 0) refresh();
    }

    private void refresh() {
        View view = getView();
        if (view == null) return;
        List<NetworkSource> sources = mStore.getSources();
        mAdapter.setSources(sources);
        PhoneUi.setEmptyVisible(view, sources.isEmpty());
        mList.setVisibility(sources.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void addSource() {
        PhoneMainActivity main = PhoneUi.main(this);
        if (main != null) main.editSource(null, false);
    }

    private void open(NetworkSource source) {
        PhoneMainActivity main = PhoneUi.main(this);
        if (main != null) main.push(BrowserFragment.browse(source.id, source.getRootPath()));
    }

    private void check(NetworkSource source) {
        SourceAccess.check(requireContext(), source, (result, error) -> {
            if (!isAdded()) return;
            if (error == null) {
                PhoneUi.showSnackbar(this, getString(R.string.phone_check_ok, source.name));
            } else {
                NetworkSource current = mStore.getSource(source.id);
                if (current != null) ConnectionDialogs.show(requireActivity(), current, error, null, () -> check(current));
            }
        });
    }

    private void confirmDelete(NetworkSource source) {
        UserDataStore.Usage usage = mStore.getUsage(source.id);
        String message = usage.isEmpty()
                ? getString(R.string.phone_delete_source_message_empty)
                : getString(R.string.phone_delete_source_message, usage.favorites, usage.recents, usage.pinned);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.phone_delete_source_title, source.name))
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.phone_delete, (d, w) -> mStore.deleteSource(source.id))
                .show();
    }

    private void showMenu(NetworkSource source, View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        Menu menu = popup.getMenu();
        menu.add(Menu.NONE, MENU_OPEN, 0, R.string.phone_menu_open);
        menu.add(Menu.NONE, MENU_EDIT, 1, R.string.phone_menu_edit_connection);
        menu.add(Menu.NONE, MENU_CHECK, 2, R.string.phone_menu_check_connection);
        menu.add(Menu.NONE, MENU_DELETE, 3, R.string.phone_menu_delete_connection);
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_OPEN:
                    open(source);
                    return true;
                case MENU_EDIT:
                    PhoneMainActivity main = PhoneUi.main(this);
                    if (main != null) main.editSource(source.id, false);
                    return true;
                case MENU_CHECK:
                    check(source);
                    return true;
                case MENU_DELETE:
                    confirmDelete(source);
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_SOURCE = 1;
        private static final int TYPE_ADD = 2;
        private final List<NetworkSource> mSources = new ArrayList<>();

        @SuppressWarnings("NotifyDataSetChanged") // small list, statuses change in place
        void setSources(List<NetworkSource> sources) {
            mSources.clear();
            mSources.addAll(sources);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mSources.isEmpty() ? 0 : mSources.size() + 2;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return TYPE_HEADER;
            if (position == mSources.size() + 1) return TYPE_ADD;
            return TYPE_SOURCE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            int layout = viewType == TYPE_HEADER ? R.layout.item_phone_section_header
                    : viewType == TYPE_ADD ? R.layout.item_phone_add_button : R.layout.item_phone_source;
            return new RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            View v = holder.itemView;
            int type = getItemViewType(position);
            if (type == TYPE_HEADER) {
                ((TextView) v.findViewById(R.id.header_title)).setText(R.string.phone_connections_header);
                TextView trailing = v.findViewById(R.id.header_trailing);
                trailing.setText(getResources().getQuantityString(R.plurals.phone_source_count, mSources.size(), mSources.size()));
                trailing.setVisibility(View.VISIBLE);
                return;
            }
            if (type == TYPE_ADD) {
                MaterialButton add = v.findViewById(R.id.add_button);
                add.setIconResource(R.drawable.ic_phone_add);
                add.setOnClickListener(b -> addSource());
                return;
            }
            NetworkSource source = mSources.get(position - 1);
            View tile = v.findViewById(R.id.source_tile);
            SourceVisuals.tint(tile, SourceVisuals.color(source.protocol));
            ImageView icon = v.findViewById(R.id.source_icon);
            icon.setImageDrawable(ContextCompat.getDrawable(v.getContext(), SourceVisuals.icon(source.protocol)));
            ((TextView) v.findViewById(R.id.source_name)).setText(SourceVisuals.title(v.getContext(), source));
            ((TextView) v.findViewById(R.id.source_location)).setText(source.describeLocation());
            SourceVisuals.bindStatus(v.findViewById(R.id.source_status_dot), v.findViewById(R.id.source_status), source);
            v.setOnClickListener(c -> open(source));
            View more = v.findViewById(R.id.source_more);
            more.setOnClickListener(c -> showMenu(source, more));
            v.setOnLongClickListener(c -> {
                showMenu(source, more);
                return true;
            });
        }
    }
}
