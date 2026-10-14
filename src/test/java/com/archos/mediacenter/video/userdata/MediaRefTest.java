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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MediaRefTest {

    @Test
    public void normalizesPaths() {
        assertEquals("/", MediaRef.normalizePath(null));
        assertEquals("/", MediaRef.normalizePath(""));
        assertEquals("/", MediaRef.normalizePath("//"));
        assertEquals("/Video/Movies", MediaRef.normalizePath("Video//Movies/"));
    }

    @Test
    public void sameFileIsRecognizedWhateverTheEntryPoint() {
        // history, favorites, browser and pinned folders all build the ref from the same pieces
        MediaRef fromBrowser = new MediaRef("nas", "/Video/Movies/a.mkv");
        MediaRef fromPinned = new MediaRef("nas", MediaRef.child("/Video/Movies/", "a.mkv"));
        assertEquals(fromBrowser, fromPinned);
        assertEquals(fromBrowser.hashCode(), fromPinned.hashCode());
    }

    @Test
    public void sameNameInAnotherFolderOrSourceIsAnotherFile() {
        assertNotEquals(new MediaRef("nas", "/a/x.mkv"), new MediaRef("nas", "/b/x.mkv"));
        assertNotEquals(new MediaRef("nas", "/a/x.mkv"), new MediaRef("ftp", "/a/x.mkv"));
    }

    @Test
    public void parentsAndNames() {
        assertEquals("/Video", MediaRef.parentOf("/Video/Movies"));
        assertEquals("/", MediaRef.parentOf("/Video"));
        assertEquals("/", MediaRef.parentOf("/"));
        assertEquals("Movies", MediaRef.nameOf("/Video/Movies/"));
    }

    @Test
    public void withinRootIsSegmentAware() {
        assertTrue(MediaRef.isWithin("/Video/Movies/4K", "/Video/Movies"));
        assertTrue(MediaRef.isWithin("/Video/Movies", "/Video/Movies"));
        assertFalse(MediaRef.isWithin("/Video/MoviesOld", "/Video/Movies"));
        assertTrue(MediaRef.isWithin("/anything", "/"));
    }
}
