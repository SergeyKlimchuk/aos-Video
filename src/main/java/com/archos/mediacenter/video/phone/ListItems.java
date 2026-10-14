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

import com.archos.mediacenter.video.userdata.MediaRef;
import com.archos.mediacenter.video.userdata.NetworkSource;

import java.util.Objects;

/** Rows shared by the video lists and the file browser. */
final class ListItems {

    private ListItems() {}

    abstract static class Item {
        abstract String id();

        /** Content equality, used by DiffUtil to rebind only the rows that changed. */
        @Override
        public abstract boolean equals(Object o);

        @Override
        public abstract int hashCode();
    }

    static final class Header extends Item {
        final String title;
        final String trailing;
        /** 0 for no action button. */
        final int actionText;
        final int actionIcon;

        Header(String title, String trailing) {
            this(title, trailing, 0, 0);
        }

        Header(String title, String trailing, int actionText, int actionIcon) {
            this.title = title;
            this.trailing = trailing;
            this.actionText = actionText;
            this.actionIcon = actionIcon;
        }

        @Override
        String id() {
            return "header:" + title;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Header)) return false;
            Header h = (Header) o;
            return Objects.equals(title, h.title) && Objects.equals(trailing, h.trailing) && actionText == h.actionText;
        }

        @Override
        public int hashCode() {
            return Objects.hash(title, trailing, actionText);
        }
    }

    static final class VideoItem extends Item {
        final MediaRef ref;
        final NetworkSource source;
        final String title;
        final long size;
        /** Modification time when known (browser), -1 otherwise. */
        final long modified;
        final long durationMs;
        final String meta;
        /** [0..1], or -1 when there is no progress to show. */
        final float progress;
        final boolean favorite;
        final boolean unavailable;

        VideoItem(MediaRef ref, NetworkSource source, String title, long size, long modified, long durationMs,
                  String meta, float progress, boolean favorite, boolean unavailable) {
            this.ref = ref;
            this.source = source;
            this.title = title;
            this.size = size;
            this.modified = modified;
            this.durationMs = durationMs;
            this.meta = meta;
            this.progress = progress;
            this.favorite = favorite;
            this.unavailable = unavailable;
        }

        @Override
        String id() {
            return "video:" + ref;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof VideoItem)) return false;
            VideoItem v = (VideoItem) o;
            return ref.equals(v.ref) && Objects.equals(title, v.title) && size == v.size && modified == v.modified
                    && durationMs == v.durationMs && Objects.equals(meta, v.meta) && progress == v.progress
                    && favorite == v.favorite && unavailable == v.unavailable;
        }

        @Override
        public int hashCode() {
            return ref.hashCode();
        }
    }

    static final class FolderItem extends Item {
        final MediaRef ref;
        final String name;
        final boolean pinned;

        FolderItem(MediaRef ref, String name, boolean pinned) {
            this.ref = ref;
            this.name = name;
            this.pinned = pinned;
        }

        @Override
        String id() {
            return "folder:" + ref;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof FolderItem)) return false;
            FolderItem f = (FolderItem) o;
            return ref.equals(f.ref) && Objects.equals(name, f.name) && pinned == f.pinned;
        }

        @Override
        public int hashCode() {
            return ref.hashCode();
        }
    }
}
