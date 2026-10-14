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

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Edge-to-edge helpers: status bar, display cutout, navigation bar and keyboard. */
final class SystemBars {

    private SystemBars() {}

    private static final int TYPES = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();

    /** Pads the view by the insets of the requested edges, keeping its own padding. */
    static void pad(View view, boolean top, boolean bottom, boolean withKeyboard) {
        final int left = view.getPaddingLeft();
        final int topPadding = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottomPadding = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(TYPES);
            int bottomInset = bottom ? bars.bottom : 0;
            if (withKeyboard) {
                bottomInset = Math.max(bottomInset, windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            }
            v.setPadding(left + bars.left, topPadding + (top ? bars.top : 0), right + bars.right, bottomPadding + bottomInset);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }
}
