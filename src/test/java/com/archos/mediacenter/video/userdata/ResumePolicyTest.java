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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ResumePolicyTest {

    private static final long MIN = 60_000L;

    @Test
    public void startsFromZeroBelowThirtySeconds() {
        assertEquals(0, ResumePolicy.startPosition(29_000, 90 * MIN, false));
    }

    @Test
    public void resumesInTheMiddleOfALongVideo() {
        assertEquals(34 * MIN, ResumePolicy.startPosition(34 * MIN, 102 * MIN, false));
    }

    @Test
    public void restartsInTheLastMinuteOfALongVideo() {
        assertEquals(0, ResumePolicy.startPosition(90 * MIN - 45_000, 90 * MIN, false));
        assertTrue(ResumePolicy.isCompleted(90 * MIN - 45_000, 90 * MIN));
    }

    @Test
    public void restartsWhenMarkedCompleted() {
        assertEquals(0, ResumePolicy.startPosition(10 * MIN, 90 * MIN, true));
    }

    @Test
    public void shortVideoUsesRelativeThresholds() {
        // 2 minute clip: the 60 s end window would cover half of it, only 95% counts as finished
        long duration = 2 * MIN;
        assertFalse(ResumePolicy.isCompleted(70_000, duration));
        assertEquals(70_000, ResumePolicy.startPosition(70_000, duration, false));
        assertTrue(ResumePolicy.isCompleted(115_000, duration));
        assertEquals(0, ResumePolicy.startPosition(115_000, duration, false));
        // 10% of the clip is enough to resume
        assertEquals(15_000, ResumePolicy.startPosition(15_000, duration, false));
        assertEquals(0, ResumePolicy.startPosition(10_000, duration, false));
    }

    @Test
    public void unknownDurationOnlyUsesStartThreshold() {
        assertEquals(0, ResumePolicy.startPosition(20_000, -1, false));
        assertEquals(5 * MIN, ResumePolicy.startPosition(5 * MIN, -1, false));
        assertFalse(ResumePolicy.isCompleted(5 * MIN, -1));
    }
}
