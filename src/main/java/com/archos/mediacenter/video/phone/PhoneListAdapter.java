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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import android.content.res.ColorStateList;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.SourceAccess;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/** Video lists (recent, favorites) and folder listings share these rows. */
final class PhoneListAdapter extends ListAdapter<ListItems.Item, RecyclerView.ViewHolder> {

    static final int TYPE_HEADER = 0;
    static final int TYPE_VIDEO = 1;
    static final int TYPE_FOLDER = 2;

    interface Callbacks {
        void onVideoClick(ListItems.VideoItem item);

        void onHeartClick(ListItems.VideoItem item);

        /** The "more" button; only shown when {@link #hasVideoMenu()}. */
        void onVideoMenu(ListItems.VideoItem item, View anchor);

        /** Long press: hold-to-preview. The placeholder is the row thumbnail, may be null. */
        void onVideoPreview(ListItems.VideoItem item, View row, Bitmap placeholder);

        boolean hasVideoMenu();

        void onFolderClick(ListItems.FolderItem item);

        void onFolderMenu(ListItems.FolderItem item, View anchor);

        void onHeaderAction(ListItems.Header header);
    }

    private static final DiffUtil.ItemCallback<ListItems.Item> DIFF = new DiffUtil.ItemCallback<ListItems.Item>() {
        @Override
        public boolean areItemsTheSame(@NonNull ListItems.Item a, @NonNull ListItems.Item b) {
            return a.id().equals(b.id());
        }

        @Override
        public boolean areContentsTheSame(@NonNull ListItems.Item a, @NonNull ListItems.Item b) {
            return a.equals(b);
        }
    };

    private final Callbacks mCallbacks;
    private final ThumbnailLoader mThumbnails;
    private final Object mOwner;

    PhoneListAdapter(Context context, Callbacks callbacks, Object thumbnailOwner) {
        super(DIFF);
        mCallbacks = callbacks;
        mThumbnails = ThumbnailLoader.get(context);
        mOwner = thumbnailOwner;
    }

    @Override
    public int getItemViewType(int position) {
        ListItems.Item item = getItem(position);
        if (item instanceof ListItems.VideoItem) return TYPE_VIDEO;
        if (item instanceof ListItems.FolderItem) return TYPE_FOLDER;
        return TYPE_HEADER;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_VIDEO:
                return new VideoHolder(inflater.inflate(R.layout.item_phone_video, parent, false));
            case TYPE_FOLDER:
                return new FolderHolder(inflater.inflate(R.layout.item_phone_folder, parent, false));
            default:
                return new HeaderHolder(inflater.inflate(R.layout.item_phone_section_header, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ListItems.Item item = getItem(position);
        if (holder instanceof VideoHolder) ((VideoHolder) holder).bind((ListItems.VideoItem) item);
        else if (holder instanceof FolderHolder) ((FolderHolder) holder).bind((ListItems.FolderItem) item);
        else ((HeaderHolder) holder).bind((ListItems.Header) item);
    }

    private final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView trailing;
        final MaterialButton action;

        HeaderHolder(View v) {
            super(v);
            title = v.findViewById(R.id.header_title);
            trailing = v.findViewById(R.id.header_trailing);
            action = v.findViewById(R.id.header_action);
        }

        void bind(ListItems.Header header) {
            title.setText(header.title);
            trailing.setText(header.trailing);
            trailing.setVisibility(header.trailing == null ? View.GONE : View.VISIBLE);
            if (header.actionText != 0) {
                action.setVisibility(View.VISIBLE);
                action.setText(header.actionText);
                if (header.actionIcon != 0) action.setIconResource(header.actionIcon);
                action.setOnClickListener(v -> mCallbacks.onHeaderAction(header));
            } else {
                action.setVisibility(View.GONE);
            }
        }
    }

    private final class VideoHolder extends RecyclerView.ViewHolder {
        final View thumbFrame;
        final ImageView thumb;
        final ImageView thumbIcon;
        final TextView duration;
        final TextView title;
        final TextView meta;
        final TextView warning;
        final LinearProgressIndicator progress;
        final ImageButton heart;
        final ImageButton more;

        VideoHolder(View v) {
            super(v);
            thumbFrame = v.findViewById(R.id.video_thumb_frame);
            thumbFrame.setClipToOutline(true);
            thumb = v.findViewById(R.id.video_thumb);
            thumbIcon = v.findViewById(R.id.video_thumb_icon);
            duration = v.findViewById(R.id.video_duration);
            title = v.findViewById(R.id.video_title);
            meta = v.findViewById(R.id.video_meta);
            warning = v.findViewById(R.id.video_warning);
            progress = v.findViewById(R.id.video_progress);
            heart = v.findViewById(R.id.video_heart);
            more = v.findViewById(R.id.video_more);
        }

        void bind(ListItems.VideoItem item) {
            Context context = itemView.getContext();
            title.setText(item.title);
            meta.setText(item.meta);
            meta.setVisibility(item.meta == null || item.meta.isEmpty() ? View.GONE : View.VISIBLE);
            warning.setVisibility(item.unavailable ? View.VISIBLE : View.GONE);
            thumbFrame.setAlpha(item.unavailable ? 0.5f : 1f);
            setDuration(item.durationMs);
            if (item.progress >= 0) {
                progress.setVisibility(View.VISIBLE);
                progress.setProgressCompat(Math.round(item.progress * 1000), false);
            } else {
                progress.setVisibility(View.GONE);
            }
            heart.setImageResource(item.favorite ? R.drawable.ic_phone_heart : R.drawable.ic_phone_heart_outline);
            ImageViewCompat.setImageTintList(heart, ColorStateList.valueOf(ContextCompat.getColor(context,
                    item.favorite ? R.color.nova_heart : R.color.nova_text_secondary)));
            heart.setContentDescription(context.getString(item.favorite ? R.string.phone_menu_remove_favorite : R.string.phone_favorite_add));
            heart.setOnClickListener(v -> mCallbacks.onHeartClick(item));
            itemView.setOnClickListener(v -> mCallbacks.onVideoClick(item));
            if (mCallbacks.hasVideoMenu()) {
                more.setVisibility(View.VISIBLE);
                more.setOnClickListener(v -> mCallbacks.onVideoMenu(item, more));
            } else {
                more.setVisibility(View.GONE);
            }
            itemView.setOnLongClickListener(v -> {
                mCallbacks.onVideoPreview(item, itemView, thumbnailBitmap());
                return true;
            });
            ViewCompat.replaceAccessibilityAction(itemView, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                    context.getString(R.string.phone_preview_title), null);
            itemView.setContentDescription(item.meta == null ? item.title : item.title + ", " + item.meta);
            boolean generate = !item.unavailable && item.source != null && SourceAccess.canConnectDirectly(item.source);
            mThumbnails.bind(thumb, thumbIcon, item.source, item.ref, item.size, item.modified, generate,
                    durationMs -> {
                        if (item.durationMs <= 0) setDuration(durationMs);
                    }, mOwner);
        }

        private Bitmap thumbnailBitmap() {
            Drawable d = thumb.getDrawable();
            return d instanceof BitmapDrawable ? ((BitmapDrawable) d).getBitmap() : null;
        }

        private void setDuration(long durationMs) {
            String text = Formats.duration(durationMs);
            duration.setText(text);
            duration.setVisibility(text == null ? View.GONE : View.VISIBLE);
        }
    }

    private final class FolderHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final ImageView pinned;

        FolderHolder(View v) {
            super(v);
            name = v.findViewById(R.id.folder_name);
            pinned = v.findViewById(R.id.folder_pinned);
        }

        void bind(ListItems.FolderItem item) {
            name.setText(item.name);
            pinned.setVisibility(item.pinned ? View.VISIBLE : View.GONE);
            itemView.setOnClickListener(v -> mCallbacks.onFolderClick(item));
            itemView.setOnLongClickListener(v -> {
                mCallbacks.onFolderMenu(item, name);
                return true;
            });
        }
    }

    /** Inset separator below video and folder rows. */
    static final class RowDivider extends RecyclerView.ItemDecoration {
        private final Paint mPaint = new Paint();
        private final int mInset;
        private final int mHeight;

        RowDivider(Context context) {
            mPaint.setColor(ContextCompat.getColor(context, R.color.nova_divider));
            float density = context.getResources().getDisplayMetrics().density;
            mInset = Math.round(16 * density);
            mHeight = Math.max(1, Math.round(density));
        }

        private static boolean hasDivider(RecyclerView parent, View child) {
            RecyclerView.ViewHolder holder = parent.getChildViewHolder(child);
            int type = holder.getItemViewType();
            return type == TYPE_VIDEO || type == TYPE_FOLDER;
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            if (hasDivider(parent, view)) outRect.bottom = mHeight;
        }

        @Override
        public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (!hasDivider(parent, child)) continue;
                float top = child.getBottom() + child.getTranslationY();
                c.drawRect(parent.getPaddingLeft() + mInset, top, parent.getWidth() - parent.getPaddingRight() - mInset,
                        top + mHeight, mPaint);
            }
        }
    }
}
