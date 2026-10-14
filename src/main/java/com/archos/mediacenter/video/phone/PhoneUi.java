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

import android.app.Activity;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.archos.mediacenter.video.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/** Small UI helpers shared by the phone screens. */
final class PhoneUi {

    private PhoneUi() {}

    interface TextCallback {
        void onText(String text);
    }

    static PhoneMainActivity main(Fragment fragment) {
        Activity activity = fragment.getActivity();
        return activity instanceof PhoneMainActivity ? (PhoneMainActivity) activity : null;
    }

    /** Short, non blocking notification above the bottom navigation. */
    static Snackbar snackbar(Fragment fragment, CharSequence text) {
        View root = fragment.getView();
        if (root == null) return null;
        Snackbar bar = Snackbar.make(root, text, Snackbar.LENGTH_LONG);
        Activity activity = fragment.getActivity();
        View nav = activity != null ? activity.findViewById(R.id.phone_bottom_nav) : null;
        if (nav != null) bar.setAnchorView(nav);
        return bar;
    }

    static void showSnackbar(Fragment fragment, CharSequence text) {
        Snackbar bar = snackbar(fragment, text);
        if (bar != null) bar.show();
    }

    /** Empty state: icon, message, optional hint and action. */
    static void bindEmpty(View root, @DrawableRes int icon, CharSequence title, CharSequence hint,
                          @StringRes int action, View.OnClickListener onAction) {
        View empty = root.findViewById(R.id.empty);
        ((ImageView) empty.findViewById(R.id.empty_icon)).setImageResource(icon);
        ((TextView) empty.findViewById(R.id.empty_title)).setText(title);
        TextView hintView = empty.findViewById(R.id.empty_hint);
        hintView.setText(hint);
        hintView.setVisibility(hint == null ? View.GONE : View.VISIBLE);
        MaterialButton button = empty.findViewById(R.id.empty_action);
        if (action != 0) {
            button.setText(action);
            button.setOnClickListener(onAction);
            button.setVisibility(View.VISIBLE);
        } else {
            button.setVisibility(View.GONE);
        }
    }

    static void setEmptyVisible(View root, boolean visible) {
        root.findViewById(R.id.empty).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Single line text prompt used to name or rename a shortcut. */
    static void promptText(Activity activity, @StringRes int title, String initial, TextCallback callback) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_phone_text, null);
        EditText input = view.findViewById(R.id.dialog_text);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(initial);
        input.setSelection(input.getText().length());
        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(view)
                .setPositiveButton(R.string.phone_save, (d, w) -> {
                    String text = input.getText().toString().trim();
                    callback.onText(text.isEmpty() ? initial : text);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        input.requestFocus();
    }
}
