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

import com.archos.filecorelibrary.AuthenticationException;
import com.archos.mediacenter.video.R;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

import javax.net.ssl.SSLException;

import jcifs.smb.NtStatus;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbException;

/**
 * Turns protocol specific exceptions into the few cases the user can act upon. A protocol
 * incompatibility is never reported as a wrong password, and a decoding problem is never
 * reported as a network one (the player reports those itself).
 */
public final class NetworkError {

    public enum Kind {
        AUTH(R.string.phone_error_auth),
        ACCESS_DENIED(R.string.phone_error_access_denied),
        NOT_FOUND(R.string.phone_error_not_found),
        UNREACHABLE(R.string.phone_error_unreachable),
        PROTOCOL(R.string.phone_error_protocol),
        HOST_KEY(R.string.phone_error_host_key),
        UNKNOWN(R.string.phone_error_unknown);

        public final int messageRes;

        Kind(int messageRes) {
            this.messageRes = messageRes;
        }
    }

    public final Kind kind;
    public final Throwable cause;

    public NetworkError(Kind kind, Throwable cause) {
        this.kind = kind;
        this.cause = cause;
    }

    public static NetworkError of(Throwable t) {
        return new NetworkError(classify(t), t);
    }

    /** True when the server itself could not be reached or refused us (source status). */
    public boolean isSourceFailure() {
        return kind == Kind.UNREACHABLE || kind == Kind.AUTH || kind == Kind.PROTOCOL || kind == Kind.HOST_KEY;
    }

    public static Kind classify(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            Kind kind = classifyOne(c);
            if (kind != null) return kind;
        }
        return Kind.UNKNOWN;
    }

    private static Kind classifyOne(Throwable c) {
        if (c instanceof AuthenticationException || c instanceof SmbAuthException) return Kind.AUTH;
        if (c instanceof SmbException) {
            switch (((SmbException) c).getNtStatus()) {
                case NtStatus.NT_STATUS_LOGON_FAILURE:
                case NtStatus.NT_STATUS_WRONG_PASSWORD:
                    return Kind.AUTH;
                case NtStatus.NT_STATUS_ACCESS_DENIED:
                case NtStatus.NT_STATUS_NETWORK_ACCESS_DENIED:
                    return Kind.ACCESS_DENIED;
                case NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND:
                case NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND:
                case NtStatus.NT_STATUS_BAD_NETWORK_NAME:
                case NtStatus.NT_STATUS_NOT_FOUND:
                    return Kind.NOT_FOUND;
                default:
                    break;
            }
        }
        if (c instanceof SftpException) {
            int id = ((SftpException) c).id;
            if (id == 2) return Kind.NOT_FOUND;       // SSH_FX_NO_SUCH_FILE
            if (id == 3) return Kind.ACCESS_DENIED;   // SSH_FX_PERMISSION_DENIED
        }
        String message = c.getMessage() == null ? "" : c.getMessage().toLowerCase(Locale.ROOT);
        if (c instanceof JSchException) {
            if (message.contains("auth fail") || message.contains("auth cancel") || message.contains("userauth")) return Kind.AUTH;
            if (message.contains("hostkey") || message.contains("host key")) return Kind.HOST_KEY;
            if (message.contains("timeout") || message.contains("connection refused")) return Kind.UNREACHABLE;
            if (message.contains("algorithm negotiation") || message.contains("invalid server")) return Kind.PROTOCOL;
        }
        if (c instanceof UnknownHostException || c instanceof ConnectException
                || c instanceof NoRouteToHostException || c instanceof PortUnreachableException
                || c instanceof SocketTimeoutException) {
            return Kind.UNREACHABLE;
        }
        if (c instanceof FileNotFoundException) return Kind.NOT_FOUND;
        if (c instanceof SSLException) return Kind.PROTOCOL;
        // Libraries that are not on the application compile classpath are matched by name.
        String name = c.getClass().getName();
        if (name.startsWith("org.apache.commons.net.")) return Kind.PROTOCOL;
        if (name.startsWith("net.schmizz.sshj.userauth.")) return Kind.AUTH;
        if (name.startsWith("net.schmizz.sshj.transport.TransportException")) return Kind.PROTOCOL;
        if (name.startsWith("com.hierynomus.")) {
            if (message.contains("logon_failure") || message.contains("wrong_password")) return Kind.AUTH;
            if (message.contains("access_denied")) return Kind.ACCESS_DENIED;
            if (message.contains("not_found") || message.contains("bad_network_name")) return Kind.NOT_FOUND;
        }
        return null;
    }
}
