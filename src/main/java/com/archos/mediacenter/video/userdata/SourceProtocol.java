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

import java.util.Locale;

/** The only network protocols offered by the phone UI. */
public enum SourceProtocol {
    SMB("smb", 445, "SMB"),
    FTP("ftp", 21, "FTP"),
    SFTP("sftp", 22, "SFTP");

    public final String scheme;
    public final int defaultPort;
    public final String label;

    SourceProtocol(String scheme, int defaultPort, String label) {
        this.scheme = scheme;
        this.defaultPort = defaultPort;
        this.label = label;
    }

    /** Stable value stored in the database. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SourceProtocol fromId(String id) {
        if (id == null) return null;
        for (SourceProtocol p : values()) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }

    /** Maps every scheme Nova may produce for a protocol (smbj, ftps, sshj) back to it. */
    public static SourceProtocol fromScheme(String scheme) {
        if (scheme == null) return null;
        switch (scheme.toLowerCase(Locale.ROOT)) {
            case "smb":
            case "smbj":
                return SMB;
            case "ftp":
            case "ftps":
                return FTP;
            case "sftp":
            case "sshj":
                return SFTP;
            default:
                return null;
        }
    }
}
