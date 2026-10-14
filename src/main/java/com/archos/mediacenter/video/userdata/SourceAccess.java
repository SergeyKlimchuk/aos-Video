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
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.archos.filecorelibrary.MetaFile2;
import com.archos.filecorelibrary.MetaFile2Factory;
import com.archos.filecorelibrary.RawLister;
import com.archos.filecorelibrary.RawListerFactory;
import com.archos.filecorelibrary.samba.NetworkCredentialsDatabase;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Network access for the phone UI on top of the existing FileCoreLibrary clients (jcifs/SMBJ,
 * commons-net, JSch/SSHJ). Credentials are handed to FileCoreLibrary as in-memory ("temporary")
 * credentials only, so they never end up in its legacy Blowfish-encrypted table.
 */
public final class SourceAccess {

    private static final Logger log = LoggerFactory.getLogger(SourceAccess.class);

    /** A verified SFTP host key is checked again after this delay. */
    private static final long HOST_KEY_RECHECK_MS = 10 * 60 * 1000L;
    private static final int HOST_KEY_TIMEOUT_MS = 10_000;

    private static final AtomicInteger sThreadCount = new AtomicInteger();
    /** Bounded: never more than a few simultaneous connections. */
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(3,
            r -> new Thread(r, "nova-source-" + sThreadCount.incrementAndGet()));
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final Map<String, Long> sVerifiedHostKeys = new ConcurrentHashMap<>();

    public interface Callback<T> {
        void onResult(T result, NetworkError error);
    }

    /** Thrown when the SFTP server presents a key the user has not accepted yet. */
    public static final class HostKeyException extends Exception {
        public final String fingerprint;
        public final boolean changed;

        HostKeyException(String fingerprint, boolean changed) {
            super(changed ? "host key changed" : "host key not trusted yet");
            this.fingerprint = fingerprint;
            this.changed = changed;
        }
    }

    private SourceAccess() {}

    // ------------------------------------------------------------------------------------------
    // Credentials
    // ------------------------------------------------------------------------------------------

    public static void registerCredentials(NetworkSource source, String password) {
        String user = source.username == null ? "" : source.username;
        String domain = source.protocol == SourceProtocol.SMB && source.domain != null ? source.domain : "";
        NetworkCredentialsDatabase db = NetworkCredentialsDatabase.getInstance();
        String key = source.getCredentialKey();
        db.addCredential(new NetworkCredentialsDatabase.Credential(user, password == null ? "" : password, key, domain, true));
        if (source.protocol == SourceProtocol.SMB) {
            // share enumeration happens on the host itself: register it unless another entry exists
            String hostKey = source.getScheme() + "://" + source.getAuthority() + "/";
            if (!hostKey.equals(key) && db.getCredential(hostKey) == null) {
                db.addCredential(new NetworkCredentialsDatabase.Credential(user, password == null ? "" : password, hostKey, domain, true));
            }
        }
    }

    /** Called at startup, in the background: makes every saved source usable by the player too. */
    public static void registerAll(Context context) {
        UserDataStore store = UserDataStore.get(context);
        for (NetworkSource s : store.getSources()) {
            registerCredentials(s, store.getPassword(s));
        }
    }

    // ------------------------------------------------------------------------------------------
    // SFTP host key
    // ------------------------------------------------------------------------------------------

    /** Reads the server host key fingerprint (SHA256) without authenticating. */
    public static String fetchHostKeyFingerprint(String host, int port) throws JSchException {
        final JSch jsch = new JSch();
        final HostKey[] seen = new HostKey[1];
        jsch.setHostKeyRepository(new HostKeyRepository() {
            @Override
            public int check(String h, byte[] key) {
                try {
                    seen[0] = new HostKey(h, key);
                } catch (JSchException e) {
                    log.warn("fetchHostKeyFingerprint: unsupported key", e);
                }
                return NOT_INCLUDED;
            }

            @Override public void add(HostKey hostkey, UserInfo ui) {}
            @Override public void remove(String h, String type) {}
            @Override public void remove(String h, String type, byte[] key) {}
            @Override public String getKnownHostsRepositoryID() { return "nova"; }
            @Override public HostKey[] getHostKey() { return new HostKey[0]; }
            @Override public HostKey[] getHostKey(String h, String type) { return new HostKey[0]; }
        });
        Session session = jsch.getSession("nova", host, port);
        session.setConfig("StrictHostKeyChecking", "yes");
        try {
            session.connect(HOST_KEY_TIMEOUT_MS);
        } catch (JSchException e) {
            // expected: the key is rejected on purpose once it has been captured
            if (seen[0] == null) throw e;
        } finally {
            session.disconnect();
        }
        if (seen[0] == null) throw new JSchException("no host key received");
        return seen[0].getType() + " " + seen[0].getFingerPrint(jsch);
    }

    /**
     * SFTP only: compares the presented host key with the trusted one. FileCoreLibrary itself
     * accepts any key, so this check runs before the app opens an SFTP source.
     */
    public static void verifyHostKey(NetworkSource source) throws Exception {
        if (source.protocol != SourceProtocol.SFTP) return;
        Long verifiedAt = sVerifiedHostKeys.get(source.id);
        if (source.hostKeyFingerprint != null && verifiedAt != null
                && System.currentTimeMillis() - verifiedAt < HOST_KEY_RECHECK_MS) {
            return;
        }
        String fingerprint = fetchHostKeyFingerprint(source.host.trim(), source.getEffectivePort());
        if (source.hostKeyFingerprint == null) throw new HostKeyException(fingerprint, false);
        if (!source.hostKeyFingerprint.equals(fingerprint)) throw new HostKeyException(fingerprint, true);
        sVerifiedHostKeys.put(source.id, System.currentTimeMillis());
    }

    /**
     * Whether FileCoreLibrary may connect to this source directly (thumbnails): always for SMB
     * and FTP, only after the SFTP server key was checked in this session.
     */
    public static boolean canConnectDirectly(NetworkSource source) {
        if (source.protocol != SourceProtocol.SFTP) return true;
        return source.hostKeyFingerprint != null && sVerifiedHostKeys.containsKey(source.id);
    }

    /** Records an explicit user decision to trust the key. */
    public static void markHostKeyTrusted(String sourceId) {
        sVerifiedHostKeys.put(sourceId, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------------------------------
    // Operations (background thread, result on the main thread)
    // ------------------------------------------------------------------------------------------

    private interface Op<T> {
        T run() throws Exception;
    }

    private static <T> Future<?> submit(final Op<T> op, final Callback<T> callback) {
        return sExecutor.submit(() -> {
            T result = null;
            NetworkError error = null;
            try {
                result = op.run();
            } catch (HostKeyException e) {
                error = new NetworkError(NetworkError.Kind.HOST_KEY, e);
            } catch (Throwable t) {
                error = NetworkError.of(t);
                if (log.isDebugEnabled()) log.debug("operation failed: {} ({})", error.kind, t.getClass().getName());
            }
            if (Thread.currentThread().isInterrupted()) return;
            final T r = result;
            final NetworkError e = error;
            sMain.post(() -> callback.onResult(r, e));
        });
    }

    /** Lists a folder of a saved source and updates its availability status. */
    public static Future<?> list(final Context context, final NetworkSource source, final String path,
                                 final Callback<List<MetaFile2>> callback) {
        final UserDataStore store = UserDataStore.get(context);
        return submit(() -> {
            try {
                verifyHostKey(source);
                List<MetaFile2> files = listNow(source, path);
                store.setStatus(source.id, NetworkSource.Status.AVAILABLE);
                return files;
            } catch (Exception e) {
                if (!(e instanceof HostKeyException) && NetworkError.of(e).isSourceFailure()) {
                    store.setStatus(source.id, NetworkSource.Status.UNAVAILABLE);
                }
                throw e;
            }
        }, callback);
    }

    static List<MetaFile2> listNow(NetworkSource source, String path) throws Exception {
        Uri uri = source.buildUri(path, true);
        RawLister lister = RawListerFactory.getRawListerForUrl(uri);
        List<MetaFile2> files = lister.getFileList();
        if (files == null) throw new FileNotFoundException(path);
        return files;
    }

    /** "Check connection" from the list: updates the status shown on the card. */
    public static Future<?> check(final Context context, final NetworkSource source, final Callback<Void> callback) {
        final UserDataStore store = UserDataStore.get(context);
        store.setStatus(source.id, NetworkSource.Status.CHECKING);
        return list(context, source, source.getRootPath(), (result, error) -> {
            if (error != null && !error.isSourceFailure()) {
                // the server answered: it is reachable even if the start folder is not readable
                store.setStatus(source.id, NetworkSource.Status.AVAILABLE);
            } else if (error != null && error.kind == NetworkError.Kind.HOST_KEY) {
                store.setStatus(source.id, NetworkSource.Status.UNKNOWN);
            }
            callback.onResult(null, error);
        });
    }

    /**
     * Form test of an unsaved configuration: connects with the typed credentials and reads the
     * start folder. Nothing is saved.
     */
    public static Future<?> test(final NetworkSource draft, final String password, final Callback<List<MetaFile2>> callback) {
        return submit(() -> {
            verifyHostKey(draft);
            registerCredentials(draft, password);
            return listNow(draft, draft.getRootPath());
        }, callback);
    }

    /** Lists the SMB shares of a host (the share picker of the form). */
    public static Future<?> listShares(final NetworkSource draft, final String password, final Callback<List<String>> callback) {
        return submit(() -> {
            registerCredentials(draft, password);
            NetworkSource host = draft.copy();
            host.share = null;
            host.rootPath = "/";
            List<String> shares = new ArrayList<>();
            for (MetaFile2 f : listNow(host, "/")) {
                String name = f.getName();
                if (name == null || name.endsWith("$")) continue; // administrative shares
                shares.add(name);
            }
            Collections.sort(shares, String.CASE_INSENSITIVE_ORDER);
            return shares;
        }, callback);
    }

    /** Checks that a file still exists before playing it; returns its metadata. */
    public static Future<?> stat(final Context context, final NetworkSource source, final MediaRef ref,
                                 final Callback<MetaFile2> callback) {
        final UserDataStore store = UserDataStore.get(context);
        return submit(() -> {
            try {
                verifyHostKey(source);
                MetaFile2 file = MetaFile2Factory.getMetaFileForUrl(source.buildUri(ref, false));
                if (file == null) throw new FileNotFoundException(ref.path);
                store.setStatus(source.id, NetworkSource.Status.AVAILABLE);
                return file;
            } catch (Exception e) {
                NetworkError error = NetworkError.of(e);
                if (!(e instanceof HostKeyException) && error.isSourceFailure()) {
                    store.setStatus(source.id, NetworkSource.Status.UNAVAILABLE);
                } else if (error.kind == NetworkError.Kind.NOT_FOUND || error.kind == NetworkError.Kind.ACCESS_DENIED) {
                    store.setStatus(source.id, NetworkSource.Status.AVAILABLE);
                }
                throw e;
            }
        }, callback);
    }

    /** Runs an arbitrary blocking job on the network pool (thumbnails use their own pool). */
    public static <T> Future<?> run(final Op2<T> job, final Callback<T> callback) {
        return submit(job::run, callback);
    }

    public interface Op2<T> {
        T run() throws Exception;
    }
}
