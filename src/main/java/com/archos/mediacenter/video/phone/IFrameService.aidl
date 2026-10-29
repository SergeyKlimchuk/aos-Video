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

/**
 * Frame extraction of the phone UI, in the ":frames" process (FrameService). Sessions are numbered
 * by the client, one retriever each; frames come back as JPEG.
 */
interface IFrameService {
    /** Opens a retriever on the file, credentials from SourceAccess.credentialsOf; false when it cannot. */
    boolean open(int session, String uri, in String[] credentials);

    /** Duration read from the file in ms, -1 when unknown. */
    long duration(int session);

    /** JPEG of the frame nearest to this time (ms, -1 for the usual thumbnail), at most maxWidth wide, or null. */
    byte[] frameAt(int session, long timeMs, int maxWidth);

    /** Releases the retriever, also while frameAt runs (the extraction is aborted). */
    void close(int session);
}
