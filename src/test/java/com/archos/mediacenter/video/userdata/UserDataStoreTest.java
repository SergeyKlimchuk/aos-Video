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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class UserDataStoreTest {

    private static final String DB = "user_data_test.db";

    /** Reversible fake: the test only checks that the plain text never reaches the database. */
    private static final SecretCodec CODEC = new SecretCodec() {
        @Override
        public byte[] encrypt(String plain) {
            byte[] b = plain.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < b.length; i++) b[i] ^= 0x5A;
            return b;
        }

        @Override
        public String decrypt(byte[] cipher) {
            byte[] b = cipher.clone();
            for (int i = 0; i < b.length; i++) b[i] ^= 0x5A;
            return new String(b, StandardCharsets.UTF_8);
        }
    };

    private Context mContext;
    private UserDataStore mStore;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mContext.deleteDatabase(DB);
        mStore = open();
    }

    @After
    public void tearDown() {
        mContext.deleteDatabase(DB);
    }

    private UserDataStore open() {
        return new UserDataStore(new UserDataDbHelper(mContext, DB), CODEC, Runnable::run, Runnable::run);
    }

    private NetworkSource saveSource(String host, String share) throws Exception {
        NetworkSource s = NetworkSource.create(SourceProtocol.SMB);
        s.name = "NAS " + host;
        s.host = host;
        s.share = share;
        s.username = "user";
        mStore.saveSource(s, "secret");
        return s;
    }

    @Test
    public void everythingSurvivesARestart() throws Exception {
        NetworkSource s = saveSource("192.168.1.10", "Video");
        MediaRef film = new MediaRef(s.id, "/Video/a.mkv");
        mStore.addFavorite(film, 1000);
        mStore.recordPlayback(film, 1000, 120_000, 3_600_000, false);
        mStore.addPinned(new MediaRef(s.id, "/Video/Series"), "Series");

        UserDataStore reopened = open();
        assertEquals(1, reopened.getSources().size());
        assertEquals("secret", reopened.getPassword(reopened.getSource(s.id)));
        assertTrue(reopened.isFavorite(film));
        RecentVideo recent = reopened.getRecent(film);
        assertNotNull(recent);
        assertEquals(120_000, recent.positionMs);
        assertEquals(3_600_000, recent.durationMs);
        assertEquals("Series", reopened.getPinned().get(0).label);
    }

    @Test
    public void passwordIsNeverStoredInClear() throws Exception {
        NetworkSource s = saveSource("192.168.1.10", "Video");
        NetworkSource stored = mStore.getSource(s.id);
        assertFalse(Arrays.equals("secret".getBytes(StandardCharsets.UTF_8), stored.secret));
        // editing without a new password keeps the stored one
        stored.name = "Renamed";
        mStore.saveSource(stored, null);
        assertEquals("secret", mStore.getPassword(mStore.getSource(s.id)));
        assertEquals(s.id, mStore.getSource(s.id).id);
    }

    @Test
    public void favoriteIsNeverDuplicatedAndUndoKeepsOrder() throws Exception {
        NetworkSource s = saveSource("192.168.1.10", "Video");
        MediaRef a = new MediaRef(s.id, "/Video/a.mkv");
        MediaRef b = new MediaRef(s.id, "/Video/b.mkv");
        mStore.addFavorite(a, 1);
        Thread.sleep(2);
        mStore.addFavorite(b, 1);
        mStore.addFavorite(a, 1);
        assertEquals(2, mStore.getFavorites().size());
        assertEquals(b, mStore.getFavorites().get(0).ref);

        FavoriteVideo removed = mStore.removeFavorite(b);
        assertEquals(1, mStore.getFavorites().size());
        mStore.restoreFavorite(removed);
        assertEquals(b, mStore.getFavorites().get(0).ref);
    }

    @Test
    public void favoritesDoNotDependOnHistory() throws Exception {
        NetworkSource s = saveSource("192.168.1.10", "Video");
        MediaRef a = new MediaRef(s.id, "/Video/a.mkv");
        assertTrue(mStore.toggleFavorite(a, 1));
        assertNull(mStore.getRecent(a));
        mStore.recordPlayback(a, 1, 50_000, 100_000, false);
        mStore.removeRecent(a);
        assertTrue(mStore.isFavorite(a));
        assertFalse(mStore.toggleFavorite(a, 1));
    }

    @Test
    public void historyIsSortedAndKeepsKnownValues() throws Exception {
        NetworkSource s = saveSource("192.168.1.10", "Video");
        MediaRef a = new MediaRef(s.id, "/Video/a.mkv");
        MediaRef b = new MediaRef(s.id, "/Video/b.mkv");
        mStore.recordPlayback(a, 500, 60_000, 600_000, false);
        Thread.sleep(2);
        mStore.recordPlayback(b, 700, 10_000, 600_000, false);
        assertEquals(b, mStore.getRecents().get(0).ref);
        Thread.sleep(2);
        // unknown size/duration (-1) must not erase what is already known
        mStore.recordPlayback(a, -1, 90_000, -1, false);
        RecentVideo recent = mStore.getRecents().get(0);
        assertEquals(a, recent.ref);
        assertEquals(500, recent.size);
        assertEquals(600_000, recent.durationMs);
        assertEquals(90_000, recent.positionMs);
    }

    @Test
    public void pinnedFoldersAreUniquePerSourceAndReorderable() throws Exception {
        NetworkSource nas = saveSource("192.168.1.10", "Video");
        NetworkSource other = saveSource("192.168.1.11", "Video");
        PinnedFolder series = mStore.addPinned(new MediaRef(nas.id, "/Video/Series"), "Series");
        assertNotNull(series);
        assertNull(mStore.addPinned(new MediaRef(nas.id, "/Video/Series/"), "Again"));
        // same path on another server is another folder
        PinnedFolder otherSeries = mStore.addPinned(new MediaRef(other.id, "/Video/Series"), "Series");
        assertNotNull(otherSeries);
        PinnedFolder nested = mStore.addPinned(new MediaRef(nas.id, "/Video/Series/Season 1"), "S1");
        assertNotNull(nested);

        mStore.renamePinned(series.id, "TV");
        mStore.reorderPinned(Arrays.asList(nested.id, series.id, otherSeries.id));

        List<String> labels = new ArrayList<>();
        for (PinnedFolder p : open().getPinned()) labels.add(p.label);
        assertArrayEquals(new String[]{"S1", "TV", "Series"}, labels.toArray(new String[0]));
    }

    @Test
    public void deletingASourceRemovesOnlyItsRecords() throws Exception {
        NetworkSource nas = saveSource("192.168.1.10", "Video");
        NetworkSource other = saveSource("192.168.1.11", "Video");
        MediaRef a = new MediaRef(nas.id, "/Video/a.mkv");
        MediaRef b = new MediaRef(other.id, "/Video/b.mkv");
        mStore.addFavorite(a, 1);
        mStore.addFavorite(b, 1);
        mStore.recordPlayback(a, 1, 1, 10, false);
        mStore.addPinned(new MediaRef(nas.id, "/Video"), "Video");

        UserDataStore.Usage usage = mStore.getUsage(nas.id);
        assertEquals(1, usage.favorites);
        assertEquals(1, usage.recents);
        assertEquals(1, usage.pinned);

        mStore.deleteSource(nas.id);
        UserDataStore reopened = open();
        assertNull(reopened.getSource(nas.id));
        assertFalse(reopened.isFavorite(a));
        assertTrue(reopened.isFavorite(b));
        assertTrue(reopened.getRecents().isEmpty());
        assertTrue(reopened.getPinned().isEmpty());
    }

    @Test
    public void statusChangesDoNotTouchUserData() throws Exception {
        NetworkSource nas = saveSource("192.168.1.10", "Video");
        MediaRef a = new MediaRef(nas.id, "/Video/a.mkv");
        mStore.addFavorite(a, 1);
        mStore.setStatus(nas.id, NetworkSource.Status.UNAVAILABLE);
        assertTrue(mStore.isFavorite(a));
        assertEquals(NetworkSource.Status.UNAVAILABLE, open().getSource(nas.id).status);
    }

    @Test
    public void uriIsResolvedToTheMostSpecificSource() throws Exception {
        NetworkSource whole = saveSource("192.168.1.10", "");
        NetworkSource video = saveSource("192.168.1.10", "Video");
        MediaRef ref = mStore.findRefForUri(Uri.parse("smb://192.168.1.10/Video/Movies/a b.mkv"));
        assertNotNull(ref);
        assertEquals(video.id, ref.sourceId);
        assertEquals("/Video/Movies/a b.mkv", ref.path);
        MediaRef music = mStore.findRefForUri(Uri.parse("smb://192.168.1.10/Music/x.mkv"));
        assertEquals(whole.id, music.sourceId);
        assertNull(mStore.findRefForUri(Uri.parse("smb://10.0.0.1/Video/a.mkv")));
    }

    @Test
    public void listenersAreNotifiedOfChanges() throws Exception {
        NetworkSource nas = saveSource("192.168.1.10", "Video");
        final int[] changes = {0};
        mStore.addListener(c -> changes[0] |= c);
        mStore.addFavorite(new MediaRef(nas.id, "/Video/a.mkv"), 1);
        assertEquals(UserDataStore.CHANGED_FAVORITES, changes[0] & UserDataStore.CHANGED_FAVORITES);
    }
}
