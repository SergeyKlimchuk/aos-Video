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
import android.view.InputDevice;
import android.view.KeyEvent;
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
        /** Time of a frame that cannot be decoded, -1 for none. */
        final long brokenTime;

        FakeExtractor(AtomicInteger frames, boolean failOpen, boolean noFrame) {
            this(frames, failOpen, noFrame, -1);
        }

        FakeExtractor(AtomicInteger frames, boolean failOpen, boolean noFrame, long brokenTime) {
            this.frames = frames;
            this.failOpen = failOpen;
            this.noFrame = noFrame;
            this.brokenTime = brokenTime;
        }

        @Override
        public long open(String uri, boolean needDuration) throws Exception {
            if (failOpen) throw new IOException("unreachable");
            return needDuration ? DURATION : -1;
        }

        @Override
        public Bitmap frameAt(long timeMs) {
            frames.incrementAndGet();
            if (noFrame || timeMs == brokenTime) return null;
            return Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
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
    private PreviewFrames.Probe mProbe;
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
        mProbe = PreviewFrames.sProbe;
        mExecutor = PreviewFrames.sExecutor;
        PreviewFrames.sProbe = (context, source, ref) -> {
        };
        // extraction runs synchronously; its results still go through the main looper
        PreviewFrames.sExecutor = Runnable::run;
        PreviewFrames.sFactory = context -> new FakeExtractor(mExtracted, false, false);
        ThumbnailLoader.get(mContext).clearCache();
    }

    @After
    public void tearDown() {
        PreviewFrames.sFactory = mFactory;
        PreviewFrames.sProbe = mProbe;
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

    /** Several fingers, pointer ids 0..n-1. */
    private static MotionEvent touches(int action, float[]... points) {
        int n = points.length;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = points[i][0];
            coords[i].y = points[i][1];
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, n, properties, coords, 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    /** Centre of a view in window coordinates. */
    private static float[] centre(View view) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        return new float[]{location[0] + view.getWidth() / 2f, location[1] + view.getHeight() / 2f};
    }

    private static KeyEvent key(int action, int code, long downTime, int repeat) {
        return new KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, repeat);
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
    public void unreachableFileIsReportedWithItsOwnReasonBeforeAnyConnection() throws Exception {
        NetworkSource source = addSource();
        PreviewFrames.sProbe = (context, s, ref) -> {
            throw new java.io.FileNotFoundException(ref.path);
        };
        PreviewFrames.sFactory = context -> {
            throw new AssertionError("no retriever once the file is known to be missing");
        };
        Recorder recorder = new Recorder();
        new PreviewFrames(mContext, source, new MediaRef(source.id, "/Video/Gone.mkv"), 1L, 1L, -1, recorder).start();
        idle();
        assertEquals(PreviewFrames.COUNT, recorder.failed.size());
        assertEquals(com.archos.mediacenter.video.userdata.NetworkError.Kind.NOT_FOUND.messageRes, recorder.error);
    }

    @Test
    public void cachedFramesDoNotHideAServerThatStoppedAnswering() throws Exception {
        NetworkSource source = addSource();
        MediaRef ref = new MediaRef(source.id, "/Video/Film.mkv");
        new PreviewFrames(mContext, source, ref, 1_000_000L, 1234L, DURATION, new Recorder()).start();
        idle();
        // only the first five frames stay cached
        ThumbnailLoader cache = ThumbnailLoader.get(mContext);
        String key = cache.previewKey(ref, 1_000_000L, 1234L);
        for (int i = 5; i < PreviewFrames.COUNT; i++) {
            assertTrue(cache.previewFile(key, PreviewFrames.COUNT, i).delete());
        }
        mExtracted.set(0);
        PreviewFrames.sFactory = context -> new FakeExtractor(mExtracted, false, true);
        Recorder recorder = new Recorder();
        new PreviewFrames(mContext, source, ref, 1_000_000L, 1234L, DURATION, recorder).start();
        idle();
        assertEquals(5, recorder.ready.size());
        assertEquals(PreviewFrames.COUNT - 5, recorder.failed.size());
        // three misses in a row, plus the frame the other connection was already reading: not fifteen
        assertTrue("extractions: " + mExtracted.get(), mExtracted.get() <= 4);
    }

    @Test
    public void durationFoundOnceIsKeptForTheNextPreview() throws Exception {
        NetworkSource source = addSource();
        MediaRef ref = new MediaRef(source.id, "/Video/Film.mkv");
        new PreviewFrames(mContext, source, ref, 1_000_000L, 1234L, -1, new Recorder()).start();
        idle();
        assertEquals(DURATION, mStore.getKnownDuration(ref));
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
            float[] finger = centre(row);
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, finger[0], finger[1]));
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
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_MOVE, 5000, finger[1]));
            assertEquals(PreviewFrames.COUNT - 1, preview.getSelected());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3 * VideoPreview.AUTO_ADVANCE_MS));
            assertEquals(PreviewFrames.COUNT - 1, preview.getSelected());
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_MOVE, -100, finger[1]));
            assertEquals(0, preview.getSelected());

            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_UP, -100, finger[1]));
            assertNull(activity.getPreview());
            assertFalse(preview.isShowing());
            assertNull(activity.findViewById(R.id.preview_card));
            // a preview is not a playback
            assertNull(shadowOf(activity).getNextStartedActivity());
            assertTrue(mStore.getRecents().isEmpty());
        });
    }

    @Test
    public void previewWithoutFingerClosesOnTapWithoutClickingThrough() throws Exception {
        withFavoriteRow((activity, row) -> {
            assertTrue(row.performLongClick());
            idle();
            assertNotNull(activity.getPreview());
            float[] tap = centre(row);
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, tap[0], tap[1]));
            assertNotNull(activity.getPreview());
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_UP, tap[0], tap[1]));
            assertNull(activity.getPreview());
            idle();
            assertNull(shadowOf(activity).getNextStartedActivity());
        });
    }

    @Test
    public void edgeSwipeForBackOnlyClosesThePreview() throws Exception {
        withFavoriteRow((activity, row) -> {
            assertTrue(row.performLongClick());
            idle();
            // the system takes the edge swipe: the window sees a down then a cancel, then Back
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, 1, 400));
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_CANCEL, 1, 400));
            assertNotNull(activity.getPreview());
            activity.getOnBackPressedDispatcher().onBackPressed();
            assertNull(activity.getPreview());
            // Back closed the card only: the Home screen is still there
            assertFalse(activity.isFinishing());
        });
    }

    @Test
    public void keysCloseThePreviewWithoutReachingTheListBehind() throws Exception {
        withFavoriteRow((activity, row) -> {
            // keyboard long press of Enter on the row
            long down = SystemClock.uptimeMillis();
            activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, down, 0));
            assertTrue(row.performLongClick());
            idle();
            assertNotNull(activity.getPreview());
            // auto repeat and release of that same key keep the card open
            activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, down, 1));
            activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, down, 0));
            assertNotNull(activity.getPreview());
            // the next Enter closes it and is not a click on the row behind
            long next = SystemClock.uptimeMillis();
            activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, next, 0));
            assertNull(activity.getPreview());
            activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, next, 0));
            idle();
            assertNull(shadowOf(activity).getNextStartedActivity());
        });
    }

    @Test
    public void theFingerOnTheRowDrivesThePreviewNotAnotherOne() throws Exception {
        withFavoriteRow((activity, row) -> {
            float[] onRow = centre(row);
            float[] elsewhere = {onRow[0], Math.max(1, onRow[1] - row.getHeight() * 2)};
            activity.dispatchTouchEvent(touches(MotionEvent.ACTION_DOWN, elsewhere));
            activity.dispatchTouchEvent(touches(MotionEvent.ACTION_POINTER_DOWN
                    | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), elsewhere, onRow));
            assertTrue(row.performLongClick());
            idle();
            VideoPreview preview = activity.getPreview();
            assertNotNull(preview);
            // the second finger slides: it picks the frame
            activity.dispatchTouchEvent(touches(MotionEvent.ACTION_MOVE, elsewhere, new float[]{5000, onRow[1]}));
            assertEquals(PreviewFrames.COUNT - 1, preview.getSelected());
            // lifting it closes the card although the first finger stays
            activity.dispatchTouchEvent(touches(MotionEvent.ACTION_POINTER_UP
                    | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), elsewhere, new float[]{5000, onRow[1]}));
            assertNull(activity.getPreview());
            // the rest of that gesture does not reach the list
            activity.dispatchTouchEvent(touches(MotionEvent.ACTION_UP, elsewhere));
            idle();
            assertNull(shadowOf(activity).getNextStartedActivity());
        });
    }

    @Test
    public void undecodableFrameIsNotShownAsAnotherOne() throws Exception {
        int broken = 3;
        PreviewFrames.sFactory = context -> new FakeExtractor(mExtracted, false, false,
                PreviewFrames.timeOf(broken, DURATION));
        withFavoriteRow((activity, row) -> {
            float[] finger = centre(row);
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, finger[0], finger[1]));
            assertTrue(row.performLongClick());
            idle();
            FrameScaleView scale = activity.findViewById(R.id.preview_scale);
            int[] location = new int[2];
            scale.getLocationInWindow(location);
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_MOVE, location[0] + scale.dotX(broken), finger[1]));
            assertEquals(broken, activity.getPreview().getSelected());
            assertNull(((ImageView) activity.findViewById(R.id.preview_image)).getDrawable());
            TextView message = activity.findViewById(R.id.preview_message);
            assertEquals(View.VISIBLE, message.getVisibility());
            assertEquals(activity.getString(R.string.phone_preview_frame_unavailable), message.getText().toString());
            // the next frame is shown again
            activity.dispatchTouchEvent(touch(MotionEvent.ACTION_MOVE, location[0] + scale.dotX(broken + 1), finger[1]));
            assertNotNull(((ImageView) activity.findViewById(R.id.preview_image)).getDrawable());
            assertEquals(View.GONE, message.getVisibility());
        });
    }

    @Test
    @Config(qualifiers = "w640dp-h300dp-land")
    public void cardStaysInsideAShortWindowAtFullWidth() throws Exception {
        withFavoriteRow((activity, row) -> {
            assertTrue(row.performLongClick());
            idle();
            View card = activity.findViewById(R.id.preview_card);
            View overlay = (View) card.getParent();
            assertTrue(overlay.getHeight() > 0);
            assertTrue(card.getTop() >= 0);
            assertTrue(card.getBottom() <= overlay.getHeight());
            float fraction = (float) card.getWidth() / overlay.getWidth();
            assertTrue("card width " + fraction, fraction > 0.9f && fraction < 0.96f);
            // the scale is never the part that is dropped
            assertTrue(activity.findViewById(R.id.preview_scale).isShown());
            assertTrue(activity.findViewById(R.id.preview_frame).getHeight() > 0);
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
