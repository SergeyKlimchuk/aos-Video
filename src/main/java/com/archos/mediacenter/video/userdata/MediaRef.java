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

import java.util.Objects;

/**
 * Persistent identity of a file or folder: the source id plus the canonical (decoded) path on
 * the server. The path never contains the host, the port or any credential, so editing a source
 * address keeps favorites, history and shortcuts valid.
 */
public final class MediaRef {

    public final String sourceId;
    public final String path;

    public MediaRef(String sourceId, String path) {
        this.sourceId = Objects.requireNonNull(sourceId);
        this.path = normalizePath(path);
    }

    public String getName() {
        return nameOf(path);
    }

    public String getParentPath() {
        return parentOf(path);
    }

    /** "/a//b/" -> "/a/b"; null or empty -> "/". */
    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder(path.length() + 1);
        if (path.charAt(0) != '/') sb.append('/');
        char previous = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/' && previous == '/') continue;
            sb.append(c);
            previous = c;
        }
        if (sb.length() > 1 && sb.charAt(sb.length() - 1) == '/') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    public static String nameOf(String path) {
        String p = normalizePath(path);
        if ("/".equals(p)) return "/";
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /** Parent of "/a/b" is "/a"; the root is its own parent. */
    public static String parentOf(String path) {
        String p = normalizePath(path);
        int slash = p.lastIndexOf('/');
        return slash <= 0 ? "/" : p.substring(0, slash);
    }

    public static String child(String parent, String name) {
        String p = normalizePath(parent);
        return "/".equals(p) ? "/" + name : p + "/" + name;
    }

    /** True when path is root itself or lies below it. */
    public static boolean isWithin(String path, String root) {
        String p = normalizePath(path);
        String r = normalizePath(root);
        if ("/".equals(r) || p.equals(r)) return true;
        return p.startsWith(r + "/");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MediaRef)) return false;
        MediaRef other = (MediaRef) o;
        return sourceId.equals(other.sourceId) && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return 31 * sourceId.hashCode() + path.hashCode();
    }

    @Override
    public String toString() {
        return sourceId + ":" + path;
    }
}
