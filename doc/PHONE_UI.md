# Phone UI (minimal network player)

The phone/tablet interface is a minimal network video player: three destinations,
one-tap playback, favorites, Quick access folders, no online metadata. The Android TV
interface (`leanback/`) is unchanged and still selected by `EntryActivity` on TV.

## Navigation

```
EntryActivity ── TV ──> MainActivityLeanback (unchanged)
      └── phone ──> phone.PhoneMainActivity (bottom navigation, one back stack per tab)
                     ├── Home      HomeFragment: Recently played | Favorites (VideoListFragment)
                     ├── Sources   SourcesFragment: Connections | Quick access
                     │               ConnectionsFragment, QuickAccessFragment
                     │               BrowserFragment (browse / pinned / pick modes)
                     │               SourceEditActivity (add / edit form)
                     └── Settings  PhoneSettingsFragment
Any video row ──> PlayerActivity (no information card, no resume dialog)
Long press on a video row ──> VideoPreview (card in the same window, no player)
```

Back rules:

- browser: one folder up within the current scenario; at its root, back to the screen
  that opened it (Connections, Quick access, Home for "Open location");
- pinned folder: Back never goes above the shortcut; "Parent folder" in the menu is the
  explicit way up and Back returns to the shortcut;
- folder picker: at its root, back to the source chooser; "Select this folder" saves the
  shortcut and returns to Quick access;
- root of Sources or Settings: back to Home; root of Home: standard Android behaviour.

## Data (`userdata/`)

`UserDataStore` keeps everything in `nova_user_data.db` (app private) with an in-memory
cache; listeners are notified on the main thread so lists, hearts and the player stay in
sync. It does not depend on the MediaLib video index.

| Table | Content |
| --- | --- |
| `sources` | permanent UUID, name, protocol (smb/ftp/sftp), host, port, share, start folder, user, domain, FTPS flag, trusted SFTP key, last status, order, encrypted password |
| `favorites` | source id + canonical path, size, date added |
| `recents` | source id + canonical path, size, position, duration, completed flag, last played |
| `pinned` | id, source id + canonical path, label, order (unique per source + path) |
| `media_info` | technical cache (size, modification time, duration read from the file) |

- A file is identified by **source id + canonical decoded path** (`MediaRef`): never by name
  only, never with host or credentials. Editing a source address keeps every record.
- Passwords are encrypted with an AES-GCM key of the Android Keystore
  (`KeystoreSecretCodec`) and handed to FileCoreLibrary as *temporary* (in-memory)
  credentials, so they never reach its legacy table.
- Deleting a source is the only operation that removes linked records, after an explicit
  confirmation listing the counts. An unreachable server never removes anything.

## Playback

- `VideoLauncher` starts `PlayerActivity` with `RESUME_NO` plus an explicit `position`
  extra computed by `ResumePolicy` (< 30 s: start; last 60 s or finished: start; clips under
  5 min use 10% / 95%). The player applies it without any dialog.
- History and favorites lists check the file first (`SourceAccess.stat`) to report
  "file not found", authentication or network errors; the browser plays directly.
- `PlaybackHistoryTracker` (owned by `PlayerService`) creates the history entry only after
  the first real `onPlay`, then saves every 5 s, on pause, on exit and at the end.
- The player overlay shows a heart (favorite toggle without interrupting playback) and a
  "Start over" item; the information card is hidden for phone UI launches.

## Hold-to-preview

A long press on any video row (Recently played, Favorites, folder browser) opens a "Quick
preview" card in the activity's own window; the row menu stays on the "more" button.

```
PhoneListAdapter (long press) ──> fragment.onVideoPreview ──> PhoneMainActivity.showPreview
PhoneMainActivity.dispatchTouchEvent ──> VideoPreview.onTouchEvent   (whole gesture while shown)
VideoPreview (card, blur, layout) <── PreviewFrames (extraction + cache) ──> MediaLib retriever
```

- `VideoPreview` adds a full-window layer to `android.R.id.content`, above anything raised in
  it (snackbars), and blurs everything behind it (`RenderEffect`, Android 12+; a dim scrim
  below), which is also hidden from accessibility. The card takes ~94% of the width inside the
  system bars; its frame follows the video aspect ratio within the height left (letterboxed
  beyond) and the card never leaves the window: short windows drop the hints, then the title,
  then the scale label. It is centred unless the finger would cover the frame: it then moves up
  so the frame ends above the finger.
- The finger on the long-pressed row keeps driving it: at rest the frames change every 700 ms
  (only to frames already extracted); past the touch slop horizontally, the frame under the
  finger's x position is shown until release. Lifting that finger, a cancelled gesture, Back,
  pause or focus loss close it at once; the rest of the gesture never reaches the list. Without
  a finger (accessibility action, keyboard) the frames change by themselves; a tap, a key or
  Back closes it. While it is shown, no touch, key, wheel or hover event reaches the screen
  behind; system keys (volume, media) keep working.
- A frame that cannot be decoded is reported as such, never replaced by another frame under
  its time code; while a frame loads, the previous one is dimmed under a spinner.
- `PreviewFrames` extracts 20 frames, each in the middle of an equal part of the video, with
  two connections, nearest-needed frame first. Before anything is shown, even from the cache,
  the file is looked up through FileCoreLibrary (`SourceAccess.statNow`, SMB and FTP; SFTP only
  gets its key checked, FileCoreLibrary misreports its other failures): the retriever reports an
  unreachable server, bad credentials or a missing file only as missing frames after its
  timeouts. Frames that could not be obtained show their reason on the card. Frames
  are cached next to the thumbnails (same 256 MB limit, same "Clear cache") and the duration
  found goes to the media info cache, so a cached video needs only that lookup. Three failed
  extractions in a row (six once some succeeded) give up the remaining frames; closing the card
  releases the retrievers, which aborts the native extraction.
- Files changed outside the app (the lookup decides):

  | On the server | Card |
  | --- | --- |
  | moved, renamed, deleted, a folder in its place | "file not found", no cached frame |
  | no longer readable (permissions) | "access denied", no cached frame |
  | replaced or modified (size or time differs) | new frames, duration read again |
  | damaged | "cannot be read" after a few frames, or at once if it crashes the decoder |
  | server unreachable, bad credentials | cached frames, the reason for the others |

  A duration is only reused for the same size and modification time
  (`UserDataStore.getKnownDuration(ref, size, modified)`): the retriever answers a time beyond
  the end of a shorter file with its default frame, which would show under wrong time codes.
- Nothing is played and nothing is written to the history or the positions.

## Frame extraction process

The native retriever (avos/FFmpeg) can crash on a damaged or unusual file. The legacy UI only
runs it in `:scanner`; the phone UI runs it in `:frames` (`FrameService`, AIDL `IFrameService`),
never in the app process, for the list thumbnails and the preview card alike.

- `FrameClient` binds on demand and unbinds a minute after its last session. Each session is
  one retriever on one file; frames come back as JPEG (no large bitmap over Binder).
  Credentials go with each file (`SourceAccess.credentialsOf`), so an edited password applies
  in `:frames` at once.
- When the process dies during a call, the call is tried again alone (no other call in
  flight): a second death blames that file (`CrashException`). Sessions that were idle open
  their file again. A thumbnail that crashes the decoder gets an empty `.crash` marker next to
  the cached frames: the default icon is shown and the file is not tried again until it
  changes (new cache key) or "Clear cache". The preview card says "cannot be read".
- Cached frames are written to a temporary file and renamed, never left truncated.

## Online metadata

`OnlineMetadataGuard` switches off TMDb scraping (`enable_auto_scrap_key`, honoured by
every `AutoScrapeService` pass) and Trakt (local session dropped) at startup. The scraper
and Trakt preference categories are hidden. MediaLib keeps the libraries; nothing calls
them anymore. Thumbnails are frames extracted from the files (`ThumbnailLoader`).

## Known limitations

- SFTP: FileCoreLibrary accepts any host key. The app checks the key itself
  (`SourceAccess.verifyHostKey`, trust on first use, blocking dialog when it changes) before
  browsing or playing from the phone UI, but a full fix belongs in FileCoreLibrary.
- Credentials saved by the legacy UI remain in FileCoreLibrary's own table.
- IntroDB (opt-in intro skipping) only queries scraped videos, which no longer occur for
  new files.
