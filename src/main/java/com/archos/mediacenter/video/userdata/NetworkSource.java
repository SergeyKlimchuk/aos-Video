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

import android.net.Uri;

import com.archos.filecorelibrary.FileUtils;

import java.util.Locale;
import java.util.UUID;

/**
 * A configured SMB, FTP or SFTP connection. The id never changes, even when the name, address or
 * credentials are edited. The password is not part of this object: it is stored encrypted and
 * only decrypted on demand by {@link UserDataStore}.
 */
public final class NetworkSource {

    /** A status older than this is shown as "not checked". */
    public static final long STATUS_VALIDITY_MS = 30 * 60 * 1000L;

    public enum Status {
        UNKNOWN(0), AVAILABLE(1), UNAVAILABLE(2), CHECKING(3);

        public final int code;

        Status(int code) {
            this.code = code;
        }

        static Status fromCode(int code) {
            for (Status s : values()) if (s.code == code) return s;
            return UNKNOWN;
        }
    }

    public String id;
    public String name;
    public SourceProtocol protocol;
    public String host;
    /** 0 means the protocol default. */
    public int port;
    /** SMB only: the share that holds the videos. */
    public String share;
    /** Start folder, relative to the share for SMB. */
    public String rootPath;
    public String username;
    /** SMB only: domain or workgroup. */
    public String domain;
    /** FTP only: explicit TLS (ftps scheme). */
    public boolean ftpSecure;
    /** SFTP only: trusted host key fingerprint, null until the user accepted one. */
    public String hostKeyFingerprint;
    public Status status = Status.UNKNOWN;
    public long statusTime;
    public long createdAt;
    public int position;
    /** Encrypted password, owned by UserDataStore. */
    byte[] secret;

    public static NetworkSource create(SourceProtocol protocol) {
        NetworkSource s = new NetworkSource();
        s.id = UUID.randomUUID().toString();
        s.protocol = protocol;
        s.createdAt = System.currentTimeMillis();
        return s;
    }

    public NetworkSource copy() {
        NetworkSource s = new NetworkSource();
        s.id = id;
        s.name = name;
        s.protocol = protocol;
        s.host = host;
        s.port = port;
        s.share = share;
        s.rootPath = rootPath;
        s.username = username;
        s.domain = domain;
        s.ftpSecure = ftpSecure;
        s.hostKeyFingerprint = hostKeyFingerprint;
        s.status = status;
        s.statusTime = statusTime;
        s.createdAt = createdAt;
        s.position = position;
        s.secret = secret;
        return s;
    }

    public boolean hasPassword() {
        return secret != null && secret.length > 0;
    }

    public int getEffectivePort() {
        return port > 0 ? port : protocol.defaultPort;
    }

    public String getScheme() {
        return protocol == SourceProtocol.FTP && ftpSecure ? "ftps" : protocol.scheme;
    }

    /** host[:port], the port being omitted when it is the protocol default. */
    public String getAuthority() {
        String h = host == null ? "" : host.trim();
        if (h.indexOf(':') >= 0 && !h.startsWith("[")) h = "[" + h + "]"; // IPv6 literal
        return getEffectivePort() == protocol.defaultPort ? h : h + ":" + getEffectivePort();
    }

    /** Canonical path of the folder opened when the source is tapped. */
    public String getRootPath() {
        String start = MediaRef.normalizePath(rootPath);
        if (protocol == SourceProtocol.SMB) {
            String s = share == null ? "" : share.trim();
            while (s.startsWith("/")) s = s.substring(1);
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            if (s.isEmpty()) return start;
            return "/".equals(start) ? "/" + s : "/" + s + start;
        }
        return start;
    }

    /** Base used to register credentials: every URI of this source starts with it. */
    public String getCredentialKey() {
        if (protocol == SourceProtocol.SMB) {
            String rootShare = getRootPath();
            int second = rootShare.indexOf('/', 1);
            String sharePath = second > 0 ? rootShare.substring(0, second) : rootShare;
            return getScheme() + "://" + getAuthority() + ("/".equals(sharePath) ? "/" : sharePath + "/");
        }
        return getScheme() + "://" + getAuthority() + "/";
    }

    /**
     * Builds a URI the way FileCoreLibrary does: literal characters, only '#' and '?' escaped,
     * directories ending with '/' (required by jcifs).
     */
    public Uri buildUri(String path, boolean directory) {
        String p = MediaRef.normalizePath(path);
        StringBuilder sb = new StringBuilder();
        sb.append(getScheme()).append("://").append(getAuthority()).append(FileUtils.escapeUriPathDelimiters(p));
        if (directory && sb.charAt(sb.length() - 1) != '/') sb.append('/');
        return Uri.parse(sb.toString());
    }

    public Uri buildUri(MediaRef ref, boolean directory) {
        return buildUri(ref.path, directory);
    }

    public static String pathOf(Uri uri) {
        return MediaRef.normalizePath(uri == null ? null : uri.getPath());
    }

    /** True when the URI points to this server (scheme family, host and port). */
    public boolean matches(Uri uri) {
        if (uri == null || protocol != SourceProtocol.fromScheme(uri.getScheme())) return false;
        String uriHost = uri.getHost();
        if (uriHost == null || host == null) return false;
        String h = host.trim();
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (!uriHost.equalsIgnoreCase(h)) return false;
        int uriPort = uri.getPort() > 0 ? uri.getPort() : protocol.defaultPort;
        return uriPort == getEffectivePort();
    }

    /** Status shown to the user: stale results are reported as not checked. */
    public Status getDisplayStatus(long now) {
        if (status == Status.CHECKING || status == Status.UNKNOWN) return status;
        return now - statusTime > STATUS_VALIDITY_MS ? Status.UNKNOWN : status;
    }

    /** "192.168.1.10 / Video / Movies" style summary, without credentials. */
    public String describeLocation() {
        String root = getRootPath();
        StringBuilder sb = new StringBuilder(getAuthority());
        if (!"/".equals(root)) sb.append(root.replace("/", " / "));
        return sb.toString();
    }

    @Override
    public String toString() {
        // never include credentials
        return String.format(Locale.ROOT, "NetworkSource{%s %s %s}", id, protocol, getAuthority());
    }
}
