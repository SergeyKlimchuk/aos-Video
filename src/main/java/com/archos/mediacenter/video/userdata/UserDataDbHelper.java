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

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Local, app-private storage for the phone UI user data. It is independent from the MediaLib
 * video index: nothing here is ever filled from online catalogs.
 */
public class UserDataDbHelper extends SQLiteOpenHelper {

    public static final String DATABASE_NAME = "nova_user_data.db";
    private static final int DATABASE_VERSION = 1;

    static final String T_SOURCES = "sources";
    static final String T_FAVORITES = "favorites";
    static final String T_RECENTS = "recents";
    static final String T_PINNED = "pinned";
    static final String T_MEDIA_INFO = "media_info";

    public UserDataDbHelper(Context context) {
        this(context, DATABASE_NAME);
    }

    /** name == null creates an in-memory database (tests). */
    public UserDataDbHelper(Context context, String name) {
        super(context, name, null, DATABASE_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(false);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_SOURCES + " ("
                + "id TEXT PRIMARY KEY, "
                + "name TEXT NOT NULL, "
                + "protocol TEXT NOT NULL, "
                + "host TEXT NOT NULL, "
                + "port INTEGER NOT NULL DEFAULT 0, "
                + "share TEXT, "
                + "root_path TEXT, "
                + "username TEXT, "
                + "domain TEXT, "
                + "secret BLOB, "
                + "ftp_secure INTEGER NOT NULL DEFAULT 0, "
                + "host_key TEXT, "
                + "status INTEGER NOT NULL DEFAULT 0, "
                + "status_time INTEGER NOT NULL DEFAULT 0, "
                + "created_at INTEGER NOT NULL DEFAULT 0, "
                + "position INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE " + T_FAVORITES + " ("
                + "source_id TEXT NOT NULL, "
                + "path TEXT NOT NULL, "
                + "size INTEGER NOT NULL DEFAULT -1, "
                + "added_at INTEGER NOT NULL, "
                + "PRIMARY KEY (source_id, path))");
        db.execSQL("CREATE TABLE " + T_RECENTS + " ("
                + "source_id TEXT NOT NULL, "
                + "path TEXT NOT NULL, "
                + "size INTEGER NOT NULL DEFAULT -1, "
                + "position INTEGER NOT NULL DEFAULT 0, "
                + "duration INTEGER NOT NULL DEFAULT -1, "
                + "completed INTEGER NOT NULL DEFAULT 0, "
                + "last_played INTEGER NOT NULL, "
                + "PRIMARY KEY (source_id, path))");
        db.execSQL("CREATE TABLE " + T_PINNED + " ("
                + "id INTEGER PRIMARY KEY, "
                + "source_id TEXT NOT NULL, "
                + "path TEXT NOT NULL, "
                + "label TEXT NOT NULL, "
                + "position INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE (source_id, path))");
        // Technical cache (duration read from the file itself); safe to wipe with the thumbnails.
        db.execSQL("CREATE TABLE " + T_MEDIA_INFO + " ("
                + "source_id TEXT NOT NULL, "
                + "path TEXT NOT NULL, "
                + "size INTEGER NOT NULL DEFAULT -1, "
                + "modified INTEGER NOT NULL DEFAULT -1, "
                + "duration INTEGER NOT NULL DEFAULT -1, "
                + "PRIMARY KEY (source_id, path))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // first version
    }
}
