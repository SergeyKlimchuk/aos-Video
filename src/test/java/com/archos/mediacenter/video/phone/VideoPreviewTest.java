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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.MediaRef;
import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.SecretCodec;
import com.archos.mediacenter.video.userdata.SourceProtocol;
import com.archos.mediacenter.video.userdata.UserDataDbHelper;
import com.archos.mediacenter.video.userdata.UserDataStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** Hold-to-preview: card placement, frame extraction and the gesture that drives the card. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class VideoPreviewTest {

    private static final long DURATION = 6_127_000; // 01:42:07

    private static final SecretCodec PLAIN = new SecretCodec() {
        @Override
        public byte[] encrypt(String plain) {
            return plain.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String decrypt(byte[] cipher) {
            return new String(cipher, StandardCharsets.UTF_8);
        }
    };

    /** Frames of any time, extracted instantly. */
    private static final class FakeExtractor implements PreviewFrames.Extractor {
        final AtomicInteger frames;
        final boolean failOpen;
        final boolean noFrame;

        FakeExtractor(AtomicInteger frames, boolean failOpen, boolean noFrame) {
            this.frames = frames;
            this.failOpen = failOpen;
            this.noFrame = noFrame;
        }

        @Override
        public long open(String uri, boolean needDuration) throws Exception {
            if (failOpen) throw new IOException("unreachable");
            return needDuration ? DURATION : -1;
        }

        @Override
        public Bitmap frameAt(long timeMs) {
            frames.incrementAndGet();
            return noFrame ? null : Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
        }

        @Override
        public void close() {
        }
    }

    private static final class Recorder implements PreviewFrames.Listener {
        long duration = -1;
        final List<Integer> ready = new ArrayList<>();
        final List<Integer> failed = new ArrayList<>();
        int error;

        @Override
        public void onDuration(long durationMs) {
            duration = durationMs;
        }

        @Override
        public void onFrame(int index, Bitmap frame) {
            (frame != null ? ready : failed).add(index);
        }

        @Override
        public void onError(int message) {
            error = message;
        }
    }

    private Context mContext;
    private UserDataStore mStore;
    private PreviewFrames.ExtractorFactory mFactory;
    private Executor mExecutor;
    private final AtomicInteger mExtracted = new AtomicInteger();

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mStore = new UserDataStore(new UserDataDbHelper(mContext, null), PLAIN, Runnable::run, Runnable::run);
        UserDataStore.setInstanceForTest(mStore);
        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putBoolean(PhonePrefs.KEY_SHOW_THUMBNAILS, false)
                .putString(PhonePrefs.KEY_THEME_MODE, PhonePrefs.THEME_SYSTEM)
                .commit();
        mFactory = PreviewFrames.sFactory;
        mExecutor = PreviewFrames.sExecutor;
        // extraction runs synchronously; its results still go through the main looper
        PreviewFrames.sExecutor = Runnable::run;
        PreviewFrames.sFactory = context -> new FakeExtractor(mExtracted, false, false);
        ThumbnailLoader.get(mContext).clearCache();
    }

    @After
    public void tearDown() {
        PreviewFrames.sFactory = mFactory;
        PreviewFrames.sExecutor = mExecutor;
        UserDataStore.setInstanceForTest(null);
    }

    private NetworkSource addSource() throws Exception {
        NetworkSource s = NetworkSource.create(SourceProtocol.SMB);
        s.name = "Home NAS";
        s.host = "192.168.1.10";
        s.share = "Video";
        mStore.saveSource(s, "secret");
        return s;
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static MotionEvent touch(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, x, y, 0);
    }

    // ------------------------------------------------------------------------------------------
    // Pure logic
    // ------------------------------------------------------------------------------------------

    @Test
    public void framesAreSpreadOverTheWholeVideo() {
        assertEquals(DURATION / 40, PreviewFrames.timeOf(0, DURATION));
        assertEquals(DURATION * 39 / 40, PreviewFrames.timeOf(PreviewFrames.COUNT - 1, DURATION));
        for (int i = 1; i < PreviewFrames.COUNT; i++) {
            assertTrue(PreviewFrames.timeOf(i, DURATION) > PreviewFrames.timeOf(i - 1, DURATION));
        }
        assertEquals("01:42:07", Formats.timecode(DURATION));
        assertEquals("00:00:00", Formats.timecode(0));
    }

    @Test
    public void cardIsCentredUnlessTheFingerWouldCoverTheFrame() {
        // area 0..1000, card 600 high with its frame at 80..380 in the card: centred top is 200
        assertEquals(200, VideoPreview.placeCard(0, 1000, 600, 80, 380, Float.NaN, 30));
        // finger low on the screen: the centred frame already ends above it
        assertEquals(200, VideoPreview.placeCard(0, 1000, 600, 80, 380, 900, 30));
        // finger in the middle: the card moves up until the frame ends above the finger
        assertEquals(90, VideoPreview.placeCard(0, 1000, 600, 80, 380, 500, 30));
        // finger near the top: impossible, the card stays centred
        assertEquals(200, VideoPreview.placeCard(0, 1000, 600, 80, 380, 150, 30));
    }

    @Test
    public void scaleMapsPositionsToTheNearestFrame() {
        FrameScaleView scale = new FrameScaleView(mContext);
        scale.setCount(PreviewFrames.COUNT);
        scale.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        scale.layout(0, 0, 1000, scale.getMeasuredHeight());
        assertTrue(scale.getMeasuredHeight() > 0);
        assertEquals(0, scale.indexAt(-50));
        assertEquals(0, scale.indexAt(scale.dotX(0)));
        assertEquals(9, scale.indexAt(scale.dotX(9) + 1));
        assertEquals(PreviewFrames.COUNT - 1, scale.indexAt(scale.dotX(PreviewFrames.COUNT - 1)));
        assertEquals(PreviewFrames.COUNT - 1, scale.indexAt(5000));
    }

    // ------------------------------------------------------------------------------------------
    // Frame extraction
    // ------------------------------------------------------------------------------------------

    @Test
    public void extractsEveryFrameThenServesThemFromTheCache() throws Exception {
        NetworkSource source = addSource();
        MediaRef ref = new MediaRef(source.id, "/Video/Film.mkv");
        Recorder first = new Recorder();
        new PreviewFrames(mContext, source, ref, 1_000_000L, 1234L, -1, first).start();
        idle();
        assertEquals(DURATION, first.duration);
        assertEquals(PreviewFrames.COUNT, first.ready.size());
        assertEquals(PreviewFrames.COUNT, mExtracted.get());
        assertEquals(0, first.error);

        // known duration and cached frames: no connection at all
        PreviewFrames.sFactory = context -> {
            throw new AssertionError("no connection expected");
        };
        Recorder second = new Recorder();
        new PreviewFrames(mContext, source, ref, 1_000_000L, 1234L, DURATION, second).start();
        idle();
        assertEquals(DURATION, second.duration);
        assertEquals(PreviewFrames.COUNT, second.ready.size());
    }

    @Test
    public void unreachableServerReportsAnErrorInsteadOfFrames() throws Exception {
        NetworkSource source = addSource();
        PreviewFrames.sFactory = context -> new FakeExtractor(mExtracted, true, false);
        Recorder recorder = new Recorder();
        new PreviewFrames(mContext, source, new MediaRef(source.id, "/Video/Film.mkv"), 1L, 1L, DURATION, recorder).start();
        idle();
        assertTrue(recorder.ready.isEmpty());
        assertEquals(PreviewFrames.COUNT, recorder.failed.size());
        assertTrue(recorder.error != 0);
    }

    @Test
    public void undecodableFileStopsAfterAFewAttempts() throws Exception {
        NetworkSource source = addSource();
        PreviewFrames.sFactory = context -> new FakeExtractor(mExtracted, false, true);
        Recorder recorder = new Recorder();
        new PreviewFrames(mContext, source, new MediaRef(source.id, "/Video/Broken.mkv"), 1L, 1L, DURATION, recorder).start();
        idle();
        assertTrue(mExtracted.get() < PreviewFrames.COUNT);
        assertEquals(R.string.phone_preview_error_read, recorder.error);
        assertEquals(PreviewFrames.COUNT, recorder.failed.size());
    }

    @Test
    public void cancelledPreviewDeliversNothing() throws Exception {
        NetworkSource source = addSource();
        Recorder recorder = new Recorder();
        PreviewFrames frames = new PreviewFrames(mContext, source, new MediaRef(source.id, "/Video/Film.mkv"),
                1L, 1L, -1, recorder);
        frames.start();
        frames.cancel();
        idle();
        assertTrue(recorder.ready.isEmpty());
        assertEquals(-1, recorder.duration);
    }

    // ------------------------------------------------------------------------------------------
    // Gesture
    // ------------------------------------------------------------------------------------------

    private interface ActivityCheck {
        void run(PhoneMainActivity activity, View row) throws Exception;
    }

    private void withFavoriteRow(ActivityCheck check) throws Exception {
        NetworkSource source = addSource();
        mStore.addFavorite(new MediaRef(source.id, "/Video/Film.mkv"), 1_400_000_000L);
        try (ActivityScenario<PhoneMainActivity> scenario = ActivityScenario.launch(PhoneMainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    idle();
                    HomeFragment home = (HomeFragment) activity.getSupportFragmentManager()
                            .findFragmentByTag("phone_tab_0").getChildFragmentManager().findFragmentByTag("root");
                    home.selectTab(HomeFragment.TAB_FAVORITES);
                    idle();
                    RecyclerView list = (RecyclerView) findVisible(activity.getWindow().getDecorView(), R.id.list);
                    assertNotNull(list);
                    list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
                    list.layout(0, 0, 1080, 1920);
                    View title = findVisible(list, R.id.video_title);
                    assertNotNull(title);
                    View row = list.findContainingItemView(title);
                    check.run(activity, row);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static View findVisible(View root, int id) {
        if (root.getId() == id && root.isShown()) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findVisible(group.getChildAt(i), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void holdShowsFramesSlideChoosesReleaseCloses() throws Exception {
        withFavoriteRow((activity, row) -> {
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, 200, 400));
            assertTrue(row.performLongClick());
            idle();
            VideoPreview preview = activity.getPreview();
            assertNotNull(preview);
            View card = activity.findViewById(R.id.preview_card);
            assertNotNull(card);
            assertTrue(card.isShown());
            assertNotNull(((ImageView) activity.findViewById(R.id.preview_image)).getDrawable());
            assertEquals(Formats.timecode(PreviewFrames.timeOf(0, DURATION)),
                    ((TextView) activity.findViewById(R.id.preview_timecode)).getText().toString());
            assertEquals("01:42:07", ((TextView) activity.findViewById(R.id.preview_end)).getText().toString());

            // the finger rests: frames change by themselves
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VideoPreview.AUTO_ADVANCE_MS));
            assertEquals(1, preview.getSelected());

            // sliding to the far right picks the last frame, and frames stop changing by themselves
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_MOVE, 5000, 400));
            assertEquals(PreviewFrames.COUNT - 1, preview.getSelected());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3 * VideoPreview.AUTO_ADVANCE_MS));
            assertEquals(PreviewFrames.COUNT - 1, preview.getSelected());
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_MOVE, -100, 400));
            assertEquals(0, preview.getSelected());

            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_UP, -100, 400));
            assertNull(activity.getPreview());
            assertFalse(preview.isShowing());
            assertNull(activity.findViewById(R.id.preview_card));
            // a preview is not a playback
            assertNull(shadowOf(activity).getNextStartedActivity());
            assertTrue(mStore.getRecents().isEmpty());
        });
    }

    @Test
    public void previewWithoutFingerClosesOnNextTouchWithoutClickingThrough() throws Exception {
        withFavoriteRow((activity, row) -> {
            assertTrue(row.performLongClick());
            idle();
            assertNotNull(activity.getPreview());
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, 200, 400));
            assertNull(activity.getPreview());
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_UP, 200, 400));
            idle();
            assertNull(shadowOf(activity).getNextStartedActivity());
        });
    }

    @Test
    public void backClosesThePreview() throws Exception {
        withFavoriteRow((activity, row) -> {
            assertTrue(row.performLongClick());
            idle();
            assertNotNull(activity.getPreview());
            activity.getOnBackPressedDispatcher().onBackPressed();
            assertNull(activity.getPreview());
            assertNull(activity.findViewById(R.id.preview_card));
        });
    }
}
