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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Sources, favorites, playback history and Quick access shortcuts of the phone UI.
 *
 * <p>Everything is cached in memory (the tables are small) so lists and hearts update instantly;
 * the database is written on a single background thread, in call order. Listeners are notified on
 * the main thread, which keeps every screen and the player in sync.
 */
public final class UserDataStore {

    private static final Logger log = LoggerFactory.getLogger(UserDataStore.class);

    public static final int CHANGED_SOURCES = 1;
    public static final int CHANGED_FAVORITES = 1 << 1;
    public static final int CHANGED_RECENTS = 1 << 2;
    public static final int CHANGED_PINNED = 1 << 3;
    public static final int CHANGED_ALL = CHANGED_SOURCES | CHANGED_FAVORITES | CHANGED_RECENTS | CHANGED_PINNED;

    /** Oldest history entries beyond this count are dropped. */
    static final int MAX_RECENTS = 300;

    public interface Listener {
        void onUserDataChanged(int changes);
    }

    /** Linked records, shown before a source is deleted. */
    public static final class Usage {
        public final int favorites;
        public final int recents;
        public final int pinned;

        Usage(int favorites, int recents, int pinned) {
            this.favorites = favorites;
            this.recents = recents;
            this.pinned = pinned;
        }

        public boolean isEmpty() {
            return favorites == 0 && recents == 0 && pinned == 0;
        }
    }

    private static volatile UserDataStore sInstance;

    private final SQLiteOpenHelper mHelper;
    private final SecretCodec mCodec;
    private final Executor mIo;
    private final Executor mNotifier;
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();

    private boolean mLoaded;
    private final LinkedHashMap<String, NetworkSource> mSources = new LinkedHashMap<>();
    private final HashMap<MediaRef, FavoriteVideo> mFavorites = new HashMap<>();
    private final HashMap<MediaRef, RecentVideo> mRecents = new HashMap<>();
    private final ArrayList<PinnedFolder> mPinned = new ArrayList<>();
    private final HashMap<MediaRef, MediaInfo> mMediaInfo = new HashMap<>();
    private long mNextPinnedId = 1;

    public static UserDataStore get(Context context) {
        UserDataStore store = sInstance;
        if (store == null) {
            synchronized (UserDataStore.class) {
                store = sInstance;
                if (store == null) {
                    Context app = context.getApplicationContext();
                    final Handler main = new Handler(Looper.getMainLooper());
                    store = new UserDataStore(new UserDataDbHelper(app), new KeystoreSecretCodec(),
                            Executors.newSingleThreadExecutor(r -> new Thread(r, "nova-userdata")),
                            main::post);
                    sInstance = store;
                }
            }
        }
        return store;
    }

    UserDataStore(SQLiteOpenHelper helper, SecretCodec codec, Executor io, Executor notifier) {
        mHelper = helper;
        mCodec = codec;
        mIo = io;
        mNotifier = notifier;
    }

    /** Loads the cache in the background so the first screen does not wait for the disk. */
    public void preloadAsync() {
        mIo.execute(this::ensureLoaded);
    }

    public void addListener(Listener listener) {
        mListeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    private void notifyChanged(final int changes) {
        mNotifier.execute(() -> {
            for (Listener l : mListeners) l.onUserDataChanged(changes);
        });
    }

    // ------------------------------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------------------------------

    private synchronized void ensureLoaded() {
        if (mLoaded) return;
        SQLiteDatabase db = mHelper.getWritableDatabase();
        try (Cursor c = db.query(UserDataDbHelper.T_SOURCES, null, null, null, null, null, "position, created_at")) {
            while (c.moveToNext()) {
                NetworkSource s = new NetworkSource();
                s.id = str(c, "id");
                s.name = str(c, "name");
                s.protocol = SourceProtocol.fromId(str(c, "protocol"));
                if (s.protocol == null) continue;
                s.host = str(c, "host");
                s.port = integer(c, "port");
                s.share = str(c, "share");
                s.rootPath = str(c, "root_path");
                s.username = str(c, "username");
                s.domain = str(c, "domain");
                s.secret = c.getBlob(c.getColumnIndexOrThrow("secret"));
                s.ftpSecure = integer(c, "ftp_secure") != 0;
                s.hostKeyFingerprint = str(c, "host_key");
                s.status = NetworkSource.Status.fromCode(integer(c, "status"));
                s.statusTime = lng(c, "status_time");
                s.createdAt = lng(c, "created_at");
                s.position = integer(c, "position");
                mSources.put(s.id, s);
            }
        }
        try (Cursor c = db.query(UserDataDbHelper.T_FAVORITES, null, null, null, null, null, null)) {
            while (c.moveToNext()) {
                MediaRef ref = new MediaRef(str(c, "source_id"), str(c, "path"));
                mFavorites.put(ref, new FavoriteVideo(ref, lng(c, "size"), lng(c, "added_at")));
            }
        }
        try (Cursor c = db.query(UserDataDbHelper.T_RECENTS, null, null, null, null, null, null)) {
            while (c.moveToNext()) {
                MediaRef ref = new MediaRef(str(c, "source_id"), str(c, "path"));
                mRecents.put(ref, new RecentVideo(ref, lng(c, "size"), lng(c, "position"),
                        lng(c, "duration"), integer(c, "completed") != 0, lng(c, "last_played")));
            }
        }
        try (Cursor c = db.query(UserDataDbHelper.T_PINNED, null, null, null, null, null, "position, id")) {
            while (c.moveToNext()) {
                long id = lng(c, "id");
                mPinned.add(new PinnedFolder(id, new MediaRef(str(c, "source_id"), str(c, "path")),
                        str(c, "label"), integer(c, "position")));
                mNextPinnedId = Math.max(mNextPinnedId, id + 1);
            }
        }
        try (Cursor c = db.query(UserDataDbHelper.T_MEDIA_INFO, null, null, null, null, null, null)) {
            while (c.moveToNext()) {
                mMediaInfo.put(new MediaRef(str(c, "source_id"), str(c, "path")),
                        new MediaInfo(lng(c, "size"), lng(c, "modified"), lng(c, "duration")));
            }
        }
        mLoaded = true;
        if (log.isDebugEnabled()) log.debug("ensureLoaded: {} sources, {} favorites, {} recents, {} pinned",
                mSources.size(), mFavorites.size(), mRecents.size(), mPinned.size());
    }

    private static String str(Cursor c, String column) {
        return c.getString(c.getColumnIndexOrThrow(column));
    }

    private static int integer(Cursor c, String column) {
        return c.getInt(c.getColumnIndexOrThrow(column));
    }

    private static long lng(Cursor c, String column) {
        return c.getLong(c.getColumnIndexOrThrow(column));
    }

    private void write(final DbWrite op) {
        mIo.execute(() -> {
            try {
                SQLiteDatabase db = mHelper.getWritableDatabase();
                db.beginTransaction();
                try {
                    op.run(db);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } catch (RuntimeException e) {
                log.error("write: failed to persist user data", e);
            }
        });
    }

    private interface DbWrite {
        void run(SQLiteDatabase db);
    }

    private static String[] key(MediaRef ref) {
        return new String[]{ref.sourceId, ref.path};
    }

    private static final String WHERE_REF = "source_id = ? AND path = ?";

    // ------------------------------------------------------------------------------------------
    // Sources
    // ------------------------------------------------------------------------------------------

    public synchronized List<NetworkSource> getSources() {
        ensureLoaded();
        List<NetworkSource> list = new ArrayList<>(mSources.size());
        for (NetworkSource s : mSources.values()) list.add(s.copy());
        Collections.sort(list, (a, b) -> a.position != b.position
                ? Integer.compare(a.position, b.position) : Long.compare(a.createdAt, b.createdAt));
        return list;
    }

    public synchronized NetworkSource getSource(String id) {
        ensureLoaded();
        NetworkSource s = id == null ? null : mSources.get(id);
        return s == null ? null : s.copy();
    }

    public synchronized boolean hasSources() {
        ensureLoaded();
        return !mSources.isEmpty();
    }

    /**
     * Inserts or updates a source, keeping its id. A null password keeps the stored one; an empty
     * one removes it.
     */
    public void saveSource(NetworkSource source, String newPassword) throws Exception {
        final NetworkSource s = source.copy();
        if (newPassword != null) {
            s.secret = newPassword.isEmpty() ? null : mCodec.encrypt(newPassword);
        }
        synchronized (this) {
            ensureLoaded();
            NetworkSource previous = mSources.get(s.id);
            if (previous == null) {
                int max = -1;
                for (NetworkSource other : mSources.values()) max = Math.max(max, other.position);
                s.position = max + 1;
                if (s.createdAt == 0) s.createdAt = System.currentTimeMillis();
            } else {
                s.position = previous.position;
                s.createdAt = previous.createdAt;
                if (newPassword == null) s.secret = previous.secret;
            }
            mSources.put(s.id, s);
            source.secret = s.secret;
            source.position = s.position;
            source.createdAt = s.createdAt;
        }
        final ContentValues values = sourceValues(s);
        write(db -> db.insertWithOnConflict(UserDataDbHelper.T_SOURCES, null, values, SQLiteDatabase.CONFLICT_REPLACE));
        notifyChanged(CHANGED_SOURCES);
    }

    private static ContentValues sourceValues(NetworkSource s) {
        ContentValues v = new ContentValues();
        v.put("id", s.id);
        v.put("name", s.name);
        v.put("protocol", s.protocol.id());
        v.put("host", s.host);
        v.put("port", s.port);
        v.put("share", s.share);
        v.put("root_path", s.rootPath);
        v.put("username", s.username);
        v.put("domain", s.domain);
        v.put("secret", s.secret);
        v.put("ftp_secure", s.ftpSecure ? 1 : 0);
        v.put("host_key", s.hostKeyFingerprint);
        v.put("status", s.status == NetworkSource.Status.CHECKING ? NetworkSource.Status.UNKNOWN.code : s.status.code);
        v.put("status_time", s.statusTime);
        v.put("created_at", s.createdAt);
        v.put("position", s.position);
        return v;
    }

    /** Decrypted password, or null when none is stored or it cannot be decrypted. */
    public String getPassword(NetworkSource source) {
        byte[] secret;
        synchronized (this) {
            ensureLoaded();
            NetworkSource s = mSources.get(source.id);
            secret = s != null ? s.secret : source.secret;
        }
        if (secret == null || secret.length == 0) return null;
        try {
            return mCodec.decrypt(secret);
        } catch (Exception e) {
            // never log the secret itself
            log.warn("getPassword: cannot decrypt password of source {}", source.id);
            return null;
        }
    }

    /** CHECKING is kept in memory only; other states are persisted with their time. */
    public void setStatus(String sourceId, NetworkSource.Status status) {
        final long now = System.currentTimeMillis();
        synchronized (this) {
            ensureLoaded();
            NetworkSource s = mSources.get(sourceId);
            if (s == null) return;
            s.status = status;
            if (status != NetworkSource.Status.CHECKING) s.statusTime = now;
        }
        if (status != NetworkSource.Status.CHECKING) {
            final ContentValues v = new ContentValues();
            v.put("status", status.code);
            v.put("status_time", now);
            write(db -> db.update(UserDataDbHelper.T_SOURCES, v, "id = ?", new String[]{sourceId}));
        }
        notifyChanged(CHANGED_SOURCES);
    }

    public void setHostKeyFingerprint(String sourceId, String fingerprint) {
        synchronized (this) {
            ensureLoaded();
            NetworkSource s = mSources.get(sourceId);
            if (s == null) return;
            s.hostKeyFingerprint = fingerprint;
        }
        final ContentValues v = new ContentValues();
        v.put("host_key", fingerprint);
        write(db -> db.update(UserDataDbHelper.T_SOURCES, v, "id = ?", new String[]{sourceId}));
        notifyChanged(CHANGED_SOURCES);
    }

    public synchronized Usage getUsage(String sourceId) {
        ensureLoaded();
        int favorites = 0, recents = 0, pinned = 0;
        for (MediaRef ref : mFavorites.keySet()) if (ref.sourceId.equals(sourceId)) favorites++;
        for (MediaRef ref : mRecents.keySet()) if (ref.sourceId.equals(sourceId)) recents++;
        for (PinnedFolder p : mPinned) if (p.ref.sourceId.equals(sourceId)) pinned++;
        return new Usage(favorites, recents, pinned);
    }

    /** Explicit, user-confirmed deletion: the source and every local record linked to it. */
    public void deleteSource(final String sourceId) {
        synchronized (this) {
            ensureLoaded();
            mSources.remove(sourceId);
            mFavorites.keySet().removeIf(ref -> ref.sourceId.equals(sourceId));
            mRecents.keySet().removeIf(ref -> ref.sourceId.equals(sourceId));
            mMediaInfo.keySet().removeIf(ref -> ref.sourceId.equals(sourceId));
            mPinned.removeIf(p -> p.ref.sourceId.equals(sourceId));
        }
        final String[] args = {sourceId};
        write(db -> {
            db.delete(UserDataDbHelper.T_SOURCES, "id = ?", args);
            db.delete(UserDataDbHelper.T_FAVORITES, "source_id = ?", args);
            db.delete(UserDataDbHelper.T_RECENTS, "source_id = ?", args);
            db.delete(UserDataDbHelper.T_PINNED, "source_id = ?", args);
            db.delete(UserDataDbHelper.T_MEDIA_INFO, "source_id = ?", args);
        });
        notifyChanged(CHANGED_ALL);
    }

    /** Resolves a URI played from elsewhere (intent, other UI) to a configured source. */
    public synchronized MediaRef findRefForUri(Uri uri) {
        ensureLoaded();
        if (uri == null) return null;
        String path = NetworkSource.pathOf(uri);
        NetworkSource best = null;
        int bestRootLength = -1;
        for (NetworkSource s : mSources.values()) {
            if (!s.matches(uri)) continue;
            String root = s.getRootPath();
            int rootLength = MediaRef.isWithin(path, root) ? root.length() : 0;
            if (rootLength > bestRootLength) {
                best = s;
                bestRootLength = rootLength;
            }
        }
        return best == null ? null : new MediaRef(best.id, path);
    }

    // ------------------------------------------------------------------------------------------
    // Favorites
    // ------------------------------------------------------------------------------------------

    public synchronized boolean isFavorite(MediaRef ref) {
        ensureLoaded();
        return ref != null && mFavorites.containsKey(ref);
    }

    /** Most recently added first. */
    public synchronized List<FavoriteVideo> getFavorites() {
        ensureLoaded();
        List<FavoriteVideo> list = new ArrayList<>(mFavorites.values());
        Collections.sort(list, (a, b) -> Long.compare(b.addedAt, a.addedAt));
        return list;
    }

    /** Adds the video, keeping a single entry per file; returns the stored favorite. */
    public FavoriteVideo addFavorite(MediaRef ref, long size) {
        FavoriteVideo existing;
        synchronized (this) {
            ensureLoaded();
            existing = mFavorites.get(ref);
        }
        if (existing != null) return existing;
        return restoreFavorite(new FavoriteVideo(ref, size, System.currentTimeMillis()));
    }

    /** Puts back a favorite (undo), keeping its original date and therefore its position. */
    public FavoriteVideo restoreFavorite(final FavoriteVideo favorite) {
        synchronized (this) {
            ensureLoaded();
            mFavorites.put(favorite.ref, favorite);
        }
        final ContentValues v = new ContentValues();
        v.put("source_id", favorite.ref.sourceId);
        v.put("path", favorite.ref.path);
        v.put("size", favorite.size);
        v.put("added_at", favorite.addedAt);
        write(db -> db.insertWithOnConflict(UserDataDbHelper.T_FAVORITES, null, v, SQLiteDatabase.CONFLICT_REPLACE));
        notifyChanged(CHANGED_FAVORITES);
        return favorite;
    }

    /** Returns the removed favorite (for undo), or null if the video was not a favorite. */
    public FavoriteVideo removeFavorite(final MediaRef ref) {
        FavoriteVideo removed;
        synchronized (this) {
            ensureLoaded();
            removed = mFavorites.remove(ref);
        }
        if (removed == null) return null;
        write(db -> db.delete(UserDataDbHelper.T_FAVORITES, WHERE_REF, key(ref)));
        notifyChanged(CHANGED_FAVORITES);
        return removed;
    }

    /** Returns the new state. */
    public boolean toggleFavorite(MediaRef ref, long size) {
        if (isFavorite(ref)) {
            removeFavorite(ref);
            return false;
        }
        addFavorite(ref, size);
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // Playback history
    // ------------------------------------------------------------------------------------------

    /** Most recently played first. */
    public synchronized List<RecentVideo> getRecents() {
        ensureLoaded();
        List<RecentVideo> list = new ArrayList<>(mRecents.values());
        Collections.sort(list, (a, b) -> Long.compare(b.lastPlayedAt, a.lastPlayedAt));
        return list;
    }

    public synchronized RecentVideo getRecent(MediaRef ref) {
        ensureLoaded();
        return ref == null ? null : mRecents.get(ref);
    }

    /** Creates or refreshes the history entry; unknown size or duration keep the previous value. */
    public RecentVideo recordPlayback(MediaRef ref, long size, long positionMs, long durationMs, boolean completed) {
        final RecentVideo entry;
        final List<MediaRef> pruned = new ArrayList<>();
        synchronized (this) {
            ensureLoaded();
            RecentVideo previous = mRecents.get(ref);
            long s = size >= 0 ? size : (previous != null ? previous.size : -1);
            long d = durationMs > 0 ? durationMs : (previous != null ? previous.durationMs : -1);
            if (d <= 0) {
                MediaInfo known = mMediaInfo.get(ref);
                if (known != null && known.durationMs > 0) d = known.durationMs;
            }
            entry = new RecentVideo(ref, s, Math.max(0, positionMs), d, completed, System.currentTimeMillis());
            mRecents.put(ref, entry);
            if (mRecents.size() > MAX_RECENTS) {
                List<RecentVideo> sorted = new ArrayList<>(mRecents.values());
                Collections.sort(sorted, (a, b) -> Long.compare(b.lastPlayedAt, a.lastPlayedAt));
                for (int i = MAX_RECENTS; i < sorted.size(); i++) {
                    MediaRef old = sorted.get(i).ref;
                    mRecents.remove(old);
                    pruned.add(old);
                }
            }
        }
        final ContentValues v = new ContentValues();
        v.put("source_id", ref.sourceId);
        v.put("path", ref.path);
        v.put("size", entry.size);
        v.put("position", entry.positionMs);
        v.put("duration", entry.durationMs);
        v.put("completed", entry.completed ? 1 : 0);
        v.put("last_played", entry.lastPlayedAt);
        write(db -> {
            db.insertWithOnConflict(UserDataDbHelper.T_RECENTS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
            for (MediaRef old : pruned) db.delete(UserDataDbHelper.T_RECENTS, WHERE_REF, key(old));
        });
        notifyChanged(CHANGED_RECENTS);
        return entry;
    }

    /** Removes the entry from the history only; the file and the favorites are untouched. */
    public RecentVideo removeRecent(final MediaRef ref) {
        RecentVideo removed;
        synchronized (this) {
            ensureLoaded();
            removed = mRecents.remove(ref);
        }
        if (removed == null) return null;
        write(db -> db.delete(UserDataDbHelper.T_RECENTS, WHERE_REF, key(ref)));
        notifyChanged(CHANGED_RECENTS);
        return removed;
    }

    // ------------------------------------------------------------------------------------------
    // Quick access
    // ------------------------------------------------------------------------------------------

    public synchronized List<PinnedFolder> getPinned() {
        ensureLoaded();
        return new ArrayList<>(mPinned);
    }

    public synchronized PinnedFolder findPinned(MediaRef ref) {
        ensureLoaded();
        for (PinnedFolder p : mPinned) if (p.ref.equals(ref)) return p;
        return null;
    }

    public synchronized PinnedFolder getPinned(long id) {
        ensureLoaded();
        for (PinnedFolder p : mPinned) if (p.id == id) return p;
        return null;
    }

    /** Returns the new shortcut, or null when this folder of this source is already pinned. */
    public PinnedFolder addPinned(MediaRef ref, String label) {
        final PinnedFolder pinned;
        synchronized (this) {
            ensureLoaded();
            for (PinnedFolder p : mPinned) if (p.ref.equals(ref)) return null;
            int position = mPinned.isEmpty() ? 0 : mPinned.get(mPinned.size() - 1).position + 1;
            pinned = new PinnedFolder(mNextPinnedId++, ref, label, position);
            mPinned.add(pinned);
        }
        final ContentValues v = new ContentValues();
        v.put("id", pinned.id);
        v.put("source_id", ref.sourceId);
        v.put("path", ref.path);
        v.put("label", label);
        v.put("position", pinned.position);
        write(db -> db.insertWithOnConflict(UserDataDbHelper.T_PINNED, null, v, SQLiteDatabase.CONFLICT_REPLACE));
        notifyChanged(CHANGED_PINNED);
        return pinned;
    }

    /** Renames the shortcut only, never the folder on the server. */
    public void renamePinned(final long id, final String label) {
        synchronized (this) {
            ensureLoaded();
            boolean found = false;
            for (int i = 0; i < mPinned.size(); i++) {
                if (mPinned.get(i).id == id) {
                    mPinned.set(i, mPinned.get(i).withLabel(label));
                    found = true;
                }
            }
            if (!found) return;
        }
        final ContentValues v = new ContentValues();
        v.put("label", label);
        write(db -> db.update(UserDataDbHelper.T_PINNED, v, "id = ?", new String[]{String.valueOf(id)}));
        notifyChanged(CHANGED_PINNED);
    }

    public PinnedFolder removePinned(final long id) {
        PinnedFolder removed = null;
        synchronized (this) {
            ensureLoaded();
            for (Iterator<PinnedFolder> it = mPinned.iterator(); it.hasNext(); ) {
                PinnedFolder p = it.next();
                if (p.id == id) {
                    removed = p;
                    it.remove();
                }
            }
        }
        if (removed == null) return null;
        write(db -> db.delete(UserDataDbHelper.T_PINNED, "id = ?", new String[]{String.valueOf(id)}));
        notifyChanged(CHANGED_PINNED);
        return removed;
    }

    /** Persists a manual order; ids missing from the list keep their relative order at the end. */
    public void reorderPinned(List<Long> orderedIds) {
        final List<PinnedFolder> updated = new ArrayList<>();
        synchronized (this) {
            ensureLoaded();
            Map<Long, PinnedFolder> byId = new LinkedHashMap<>();
            for (PinnedFolder p : mPinned) byId.put(p.id, p);
            List<PinnedFolder> ordered = new ArrayList<>();
            for (Long id : orderedIds) {
                PinnedFolder p = byId.remove(id);
                if (p != null) ordered.add(p);
            }
            ordered.addAll(byId.values());
            mPinned.clear();
            for (int i = 0; i < ordered.size(); i++) {
                PinnedFolder p = ordered.get(i).withPosition(i);
                mPinned.add(p);
                updated.add(p);
            }
        }
        write(db -> {
            for (PinnedFolder p : updated) {
                ContentValues v = new ContentValues();
                v.put("position", p.position);
                db.update(UserDataDbHelper.T_PINNED, v, "id = ?", new String[]{String.valueOf(p.id)});
            }
        });
        notifyChanged(CHANGED_PINNED);
    }

    // ------------------------------------------------------------------------------------------
    // Technical media info (local cache, not user data)
    // ------------------------------------------------------------------------------------------

    /** Technical data read from the file itself (never from online catalogs). */
    public static final class MediaInfo {
        public final long size;
        public final long modified;
        public final long durationMs;

        public MediaInfo(long size, long modified, long durationMs) {
            this.size = size;
            this.modified = modified;
            this.durationMs = durationMs;
        }
    }

    public synchronized MediaInfo getMediaInfo(MediaRef ref) {
        ensureLoaded();
        return ref == null ? null : mMediaInfo.get(ref);
    }

    /** Duration in ms known from playback or from the file itself, -1 otherwise. */
    public synchronized long getKnownDuration(MediaRef ref) {
        ensureLoaded();
        RecentVideo recent = mRecents.get(ref);
        if (recent != null && recent.durationMs > 0) return recent.durationMs;
        MediaInfo info = mMediaInfo.get(ref);
        return info == null ? -1 : info.durationMs;
    }

    public void putMediaInfo(MediaRef ref, long size, long modified, long durationMs) {
        final MediaInfo info = new MediaInfo(size, modified, durationMs);
        synchronized (this) {
            ensureLoaded();
            MediaInfo previous = mMediaInfo.put(ref, info);
            if (previous != null && previous.size == size && previous.modified == modified
                    && previous.durationMs == durationMs) {
                return;
            }
        }
        final ContentValues v = new ContentValues();
        v.put("source_id", ref.sourceId);
        v.put("path", ref.path);
        v.put("size", size);
        v.put("modified", modified);
        v.put("duration", durationMs);
        write(db -> db.insertWithOnConflict(UserDataDbHelper.T_MEDIA_INFO, null, v, SQLiteDatabase.CONFLICT_REPLACE));
    }

    /** Clears the technical cache only; favorites, history and progress are kept. */
    public void clearMediaInfo() {
        synchronized (this) {
            ensureLoaded();
            mMediaInfo.clear();
        }
        write(db -> db.delete(UserDataDbHelper.T_MEDIA_INFO, null, null));
    }

    /** Waits until queued writes are on disk (tests and process shutdown paths). */
    void flush() throws InterruptedException {
        final Object lock = new Object();
        final boolean[] done = {false};
        mIo.execute(() -> {
            synchronized (lock) {
                done[0] = true;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            while (!done[0]) lock.wait();
        }
    }
}
