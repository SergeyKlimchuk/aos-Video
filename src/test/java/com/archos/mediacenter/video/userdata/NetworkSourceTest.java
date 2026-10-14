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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class NetworkSourceTest {

    private static NetworkSource smb() {
        NetworkSource s = NetworkSource.create(SourceProtocol.SMB);
        s.host = "192.168.1.10";
        s.share = "Video";
        s.rootPath = "/Movies";
        s.username = "sergey";
        return s;
    }

    @Test
    public void smbRootIncludesShareAndStartFolder() {
        NetworkSource s = smb();
        assertEquals("/Video/Movies", s.getRootPath());
        assertEquals("smb://192.168.1.10/Video/Movies/", s.buildUri(s.getRootPath(), true).toString());
        assertEquals("smb://192.168.1.10/Video/", s.getCredentialKey());
    }

    @Test
    public void uriKeepsLiteralCharactersButEscapesDelimiters() {
        NetworkSource s = smb();
        Uri uri = s.buildUri("/Video/Movies/Film #1 (2020)?.mkv", false);
        assertEquals("smb://192.168.1.10/Video/Movies/Film %231 (2020)%3F.mkv", uri.toString());
        // the canonical path survives the round trip through the URI
        assertEquals("/Video/Movies/Film #1 (2020)?.mkv", NetworkSource.pathOf(uri));
    }

    @Test
    public void nonDefaultPortIsPartOfTheAuthority() {
        NetworkSource s = NetworkSource.create(SourceProtocol.SFTP);
        s.host = "media.example.org";
        s.port = 2222;
        s.rootPath = "/home/videos";
        assertEquals("sftp://media.example.org:2222/home/videos/", s.buildUri(s.getRootPath(), true).toString());
        s.port = 22;
        assertEquals("sftp://media.example.org/", s.getCredentialKey());
    }

    @Test
    public void ftpsSchemeWhenEncrypted() {
        NetworkSource s = NetworkSource.create(SourceProtocol.FTP);
        s.host = "192.168.1.21";
        s.ftpSecure = true;
        assertEquals("ftps://192.168.1.21/videos/", s.buildUri("/videos", true).toString());
    }

    @Test
    public void matchesEquivalentSchemesAndPorts() {
        NetworkSource s = smb();
        assertTrue(s.matches(Uri.parse("smb://192.168.1.10/Video/a.mkv")));
        assertTrue(s.matches(Uri.parse("smbj://192.168.1.10/Video/a.mkv")));
        assertFalse(s.matches(Uri.parse("smb://192.168.1.11/Video/a.mkv")));
        assertFalse(s.matches(Uri.parse("ftp://192.168.1.10/Video/a.mkv")));
    }

    @Test
    public void credentialsNeverAppearInIdentifiers() {
        NetworkSource s = smb();
        String uri = s.buildUri("/Video/a.mkv", false).toString();
        assertFalse(uri.contains("sergey"));
        assertFalse(s.toString().contains("sergey"));
    }

    @Test
    public void staleStatusIsShownAsNotChecked() {
        NetworkSource s = smb();
        s.status = NetworkSource.Status.AVAILABLE;
        s.statusTime = 1_000;
        assertEquals(NetworkSource.Status.AVAILABLE, s.getDisplayStatus(2_000));
        assertEquals(NetworkSource.Status.UNKNOWN, s.getDisplayStatus(1_000 + NetworkSource.STATUS_VALIDITY_MS + 1));
    }
}
