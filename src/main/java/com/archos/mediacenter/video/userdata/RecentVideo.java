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

/** An entry of the playback history: created only once playback really started. */
public final class RecentVideo {
    public final MediaRef ref;
    /** File size in bytes, -1 when unknown. */
    public final long size;
    /** Last position in milliseconds. */
    public final long positionMs;
    /** Duration in milliseconds, -1 when unknown. */
    public final long durationMs;
    public final boolean completed;
    public final long lastPlayedAt;

    public RecentVideo(MediaRef ref, long size, long positionMs, long durationMs, boolean completed, long lastPlayedAt) {
        this.ref = ref;
        this.size = size;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.completed = completed;
        this.lastPlayedAt = lastPlayedAt;
    }

    /** Progress in [0, 1], or -1 when it cannot be computed. */
    public float getProgress() {
        if (completed) return 1f;
        if (durationMs <= 0 || positionMs <= 0) return -1f;
        return Math.min(1f, (float) positionMs / (float) durationMs);
    }
}
