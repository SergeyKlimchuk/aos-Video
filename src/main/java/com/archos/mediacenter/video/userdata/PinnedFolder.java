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

package com.archos.mediacenter.video.userdata;

/** A Quick access shortcut: a link to a folder, never a copy of its content. */
public final class PinnedFolder {
    public final long id;
    public final MediaRef ref;
    public final String label;
    public final int position;

    public PinnedFolder(long id, MediaRef ref, String label, int position) {
        this.id = id;
        this.ref = ref;
        this.label = label;
        this.position = position;
    }

    PinnedFolder withLabel(String newLabel) {
        return new PinnedFolder(id, ref, newLabel, position);
    }

    PinnedFolder withPosition(int newPosition) {
        return new PinnedFolder(id, ref, label, newPosition);
    }
}
