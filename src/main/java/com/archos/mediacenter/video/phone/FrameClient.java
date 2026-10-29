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

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;

import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.SourceAccess;
import com.archos.mediacenter.video.userdata.UserDataStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client of {@link FrameService}: every frame of the phone UI is extracted there, never in the
 * app process, so a file that crashes the native decoder cannot take the app down.
 *
 * <ul>
 * <li>the service is bound on demand and unbound a minute after its last session closed;</li>
 * <li>a session whose process ended while it was idle (another file crashed it) opens its file
 *     again by itself;</li>
 * <li>a call during which the process died is tried again alone, with no other call in flight:
 *     when the process dies again, this file is the cause and {@link CrashException} says so.</li>
 * </ul>
 *
 * <p>Calls block: never on the main thread.</p>
 */
final class FrameClient {

    private static final Logger log = LoggerFactory.getLogger(FrameClient.class);

    /** Covers the restart delay the system applies to a service that just crashed. */
    private static final long CONNECT_TIMEOUT_MS = 20_000;
    private static final long IDLE_UNBIND_MS = 60_000;

    /** The extraction process died twice on this file, the second time with nothing else running. */
    static final class CrashException extends Exception {
        CrashException() {
            super("the frame extraction process crashed on this file");
        }
    }

    private interface Call<T> {
        T run(IFrameService service, int generation) throws RemoteException;
    }

    @SuppressLint("StaticFieldLeak") // holds the application context only
    private static volatile FrameClient sInstance;

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final AtomicInteger mIds = new AtomicInteger();
    private final Runnable mUnbind = this::unbindIfIdle;

    private final Object mLock = new Object();
    // guarded by mLock
    private IFrameService mService;
    /** Incremented with each connection: a new process has none of the earlier retrievers. */
    private int mGeneration;
    private boolean mBound;
    private int mSessions;
    private int mCalls;
    private boolean mAlone;
    private int mWaitingAlone;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (mLock) {
                mService = IFrameService.Stub.asInterface(binder);
                mGeneration++;
                mLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // the process ended: the system starts it again for this binding
            synchronized (mLock) {
                mService = null;
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // this binding will never connect again: a new one is made at the next call
            synchronized (mLock) {
                mService = null;
                unbindLocked();
                mLock.notifyAll();
            }
        }

        @Override
        public void onNullBinding(ComponentName name) {
            onBindingDied(name);
        }
    };

    static FrameClient get(Context context) {
        FrameClient client = sInstance;
        if (client == null) {
            synchronized (FrameClient.class) {
                client = sInstance;
                if (client == null) {
                    client = new FrameClient(context.getApplicationContext());
                    sInstance = client;
                }
            }
        }
        return client;
    }

    private FrameClient(Context context) {
        mContext = context;
    }

    /**
     * A retriever on a file of this source (its URI, see {@link NetworkSource#buildUri}) in the
     * extraction process; nothing is connected yet.
     */
    Session open(NetworkSource source, String uri) {
        String password = UserDataStore.get(mContext).getPassword(source);
        return new Session(uri, SourceAccess.credentialsOf(source, password));
    }

    // ------------------------------------------------------------------------------------------
    // Connection (mLock held)
    // ------------------------------------------------------------------------------------------

    private IFrameService connectLocked() throws IOException, InterruptedException {
        long deadline = SystemClock.uptimeMillis() + CONNECT_TIMEOUT_MS;
        while (mService == null) {
            if (!mBound) {
                mBound = mContext.bindService(new Intent(mContext, FrameService.class), mConnection,
                        Context.BIND_AUTO_CREATE);
                if (!mBound) throw new IOException("frame service unavailable");
            }
            long left = deadline - SystemClock.uptimeMillis();
            if (left <= 0) throw new IOException("frame service not connected");
            mLock.wait(left);
        }
        return mService;
    }

    private void unbindLocked() {
        if (!mBound) return;
        mBound = false;
        try {
            mContext.unbindService(mConnection);
        } catch (IllegalArgumentException ignored) {
            // already unbound
        }
    }

    private void unbindIfIdle() {
        synchronized (mLock) {
            if (mSessions > 0 || mCalls > 0) return;
            mService = null;
            unbindLocked();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Calls run side by side, except one retried after a crash, which runs alone
    // ------------------------------------------------------------------------------------------

    private void enter(boolean alone) throws InterruptedException {
        synchronized (mLock) {
            if (alone) {
                mWaitingAlone++;
                try {
                    while (mAlone || mCalls > 0) mLock.wait();
                } finally {
                    mWaitingAlone--;
                }
                mAlone = true;
            } else {
                while (mAlone || mWaitingAlone > 0) mLock.wait();
            }
            mCalls++;
        }
    }

    private void exit(boolean alone) {
        synchronized (mLock) {
            mCalls--;
            if (alone) mAlone = false;
            mLock.notifyAll();
        }
    }

    /** One file opened in the extraction process. Calls come from one thread at a time. */
    final class Session implements AutoCloseable {
        private final int mId = mIds.incrementAndGet();
        private final String mUri;
        private final String[] mCredentials;
        /** Generation the remote retriever was opened in, -1 when it is not. */
        private volatile int mOpenedIn = -1;
        private volatile boolean mClosed;

        private Session(String uri, String[] credentials) {
            mUri = uri;
            mCredentials = credentials;
            synchronized (mLock) {
                mSessions++;
                mMain.removeCallbacks(mUnbind);
            }
        }

        /** False when the retriever cannot open the file. */
        boolean open() throws CrashException, IOException, InterruptedException {
            Boolean opened = call((service, generation) -> ensureOpen(service, generation));
            return opened != null && opened;
        }

        /** Duration read from the file in ms, -1 when unknown. */
        long duration() throws CrashException, IOException, InterruptedException {
            Long duration = call((service, generation) ->
                    ensureOpen(service, generation) ? service.duration(mId) : -1L);
            return duration != null ? duration : -1;
        }

        /** JPEG of the frame nearest to this time (ms, -1 for the usual thumbnail), or null. */
        byte[] frameAt(long timeMs, int maxWidth) throws CrashException, IOException, InterruptedException {
            return call((service, generation) ->
                    ensureOpen(service, generation) ? service.frameAt(mId, timeMs, maxWidth) : null);
        }

        private boolean ensureOpen(IFrameService service, int generation) throws RemoteException {
            if (mOpenedIn == generation) return true;
            // first call, or a new process since: it has no retriever for this file yet
            if (!service.open(mId, mUri, mCredentials)) return false;
            mOpenedIn = generation;
            return true;
        }

        /** Null once closed. */
        private <T> T call(Call<T> call) throws CrashException, IOException, InterruptedException {
            for (int attempt = 0; ; attempt++) {
                boolean alone = attempt > 0;
                enter(alone);
                IFrameService service = null;
                try {
                    if (mClosed) return null;
                    int generation;
                    synchronized (mLock) {
                        service = connectLocked();
                        generation = mGeneration;
                    }
                    T result = call.run(service, generation);
                    // closed while the remote retriever was being opened: release it as well
                    if (mClosed) service.close(mId);
                    return result;
                } catch (DeadObjectException e) {
                    synchronized (mLock) {
                        // unless the new process is already connected
                        if (mService == service) mService = null;
                    }
                    if (mClosed) return null;
                    if (alone) throw new CrashException();
                    // the process may have died because of another file: tried again alone
                    if (log.isDebugEnabled()) log.debug("call: frame process died, retrying alone");
                } catch (RemoteException e) {
                    // not a crash (transaction failure): nothing to blame the file for
                    throw new IOException(e);
                } finally {
                    exit(alone);
                }
            }
        }

        /** Idempotent, any thread except the main one: aborts a call in progress. */
        @Override
        public void close() {
            IFrameService service;
            synchronized (mLock) {
                if (mClosed) return;
                mClosed = true;
                service = mService;
                if (--mSessions == 0) mMain.postDelayed(mUnbind, IDLE_UNBIND_MS);
            }
            if (service == null || mOpenedIn < 0) return;
            try {
                service.close(mId);
            } catch (RemoteException ignored) {
                // the process is gone, and the retriever with it
            }
        }
    }
}
