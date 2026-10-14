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

/**
 * Single automatic resume rule used by every list of the phone UI (no "resume / start over"
 * dialog):
 * <ul>
 * <li>less than 30 s watched: start from 0;</li>
 * <li>finished, or within the last 60 s of a long video: start from 0;</li>
 * <li>short videos (under 5 min) use relative thresholds instead: 10% to start resuming,
 *     95% to count as finished;</li>
 * <li>otherwise resume at the saved position.</li>
 * </ul>
 */
public final class ResumePolicy {

    static final long MIN_RESUME_MS = 30_000L;
    static final long END_WINDOW_MS = 60_000L;
    static final long SHORT_VIDEO_MS = 5 * 60_000L;
    static final float SHORT_MIN_RATIO = 0.10f;
    static final float COMPLETED_RATIO = 0.95f;

    private ResumePolicy() {}

    /** Whether a position counts as having watched the video to the end. */
    public static boolean isCompleted(long positionMs, long durationMs) {
        if (durationMs <= 0 || positionMs < 0) return false;
        if (positionMs >= (long) (durationMs * COMPLETED_RATIO)) return true;
        return durationMs >= SHORT_VIDEO_MS && durationMs - positionMs <= END_WINDOW_MS;
    }

    /** Position (ms) the player must start from, 0 meaning the beginning. */
    public static long startPosition(long positionMs, long durationMs, boolean completed) {
        if (completed || positionMs <= 0) return 0;
        if (durationMs > 0) {
            if (isCompleted(positionMs, durationMs)) return 0;
            long minResume = durationMs < SHORT_VIDEO_MS
                    ? Math.min(MIN_RESUME_MS, (long) (durationMs * SHORT_MIN_RATIO))
                    : MIN_RESUME_MS;
            return positionMs < minResume ? 0 : positionMs;
        }
        // Unknown duration: only the absolute start threshold can be applied.
        return positionMs < MIN_RESUME_MS ? 0 : positionMs;
    }

    public static long startPosition(RecentVideo recent) {
        return recent == null ? 0 : startPosition(recent.positionMs, recent.durationMs, recent.completed);
    }
}
