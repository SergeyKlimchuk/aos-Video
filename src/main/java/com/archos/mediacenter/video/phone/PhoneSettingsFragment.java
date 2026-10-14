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

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.utils.VideoPreferencesActivity;
import com.archos.mediacenter.video.utils.VideoPreferencesCommon;
import com.archos.mediacenter.video.utils.VideoPreferencesFragment;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings: theme, thumbnails, the playback options of the existing Nova preferences (same keys,
 * no duplicate mechanism), sources and thumbnail cache. Online catalog options are gone.
 */
public class PhoneSettingsFragment extends Fragment {

    private static final String[] THEME_MODES = {PhonePrefs.THEME_LIGHT, PhonePrefs.THEME_DARK, PhonePrefs.THEME_SYSTEM};
    private static final int[] THEME_LABELS = {R.string.phone_theme_light, R.string.phone_theme_dark, R.string.phone_theme_system};
    private static final int[] THEME_ICONS = {R.drawable.ic_phone_sun, R.drawable.ic_phone_moon, R.drawable.ic_phone_device};

    private static final ExecutorService sDisk = Executors.newSingleThreadExecutor(r -> new Thread(r, "nova-settings"));

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private SharedPreferences mPrefs;
    private TextView mCacheSummary;
    private TextView mDecodingSummary;
    private MaterialSwitch mDecodingSwitch;
    private TextView mAudioSummary;
    private TextView mSubtitleSummary;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_phone_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        bindThemeOptions(view.findViewById(R.id.settings_theme_options));

        MaterialSwitch thumbnails = view.findViewById(R.id.settings_thumbnails_switch);
        thumbnails.setChecked(PhonePrefs.showThumbnails(requireContext()));
        thumbnails.setOnCheckedChangeListener((b, checked) -> PhonePrefs.setShowThumbnails(requireContext(), checked));
        view.findViewById(R.id.settings_thumbnails_row).setOnClickListener(v -> thumbnails.toggle());

        ViewGroup playback = view.findViewById(R.id.settings_playback_rows);
        View decoding = addRow(playback, R.string.phone_settings_decoding, true);
        mDecodingSummary = decoding.findViewById(R.id.row_summary);
        mDecodingSwitch = decoding.findViewById(R.id.row_switch);
        decoding.findViewById(R.id.row_chevron).setVisibility(View.GONE);
        mDecodingSwitch.setVisibility(View.VISIBLE);
        mDecodingSwitch.setContentDescription(getString(R.string.phone_settings_decoding));
        decoding.setOnClickListener(v -> {
            if (mDecodingSwitch.isEnabled()) mDecodingSwitch.toggle();
            else openPlaybackPreferences();
        });
        View audio = addRow(playback, R.string.phone_settings_audio_language, true);
        mAudioSummary = audio.findViewById(R.id.row_summary);
        audio.setOnClickListener(v -> chooseLanguage(VideoPreferencesCommon.KEY_AUDIO_TRACK_FAV_LANG, R.string.phone_settings_audio_language));
        View subtitles = addRow(playback, R.string.phone_settings_subtitle_language, true);
        mSubtitleSummary = subtitles.findViewById(R.id.row_summary);
        subtitles.setOnClickListener(v -> chooseLanguage(VideoPreferencesCommon.KEY_SUBTITLES_FAV_LANG, R.string.phone_settings_subtitle_language));
        View more = addRow(playback, R.string.phone_settings_more_playback, false);
        ((TextView) more.findViewById(R.id.row_summary)).setText(R.string.phone_settings_more_playback_summary);
        more.setOnClickListener(v -> openPlaybackPreferences());

        ViewGroup data = view.findViewById(R.id.settings_data_rows);
        View sources = addRow(data, R.string.phone_settings_manage_sources, true);
        ((TextView) sources.findViewById(R.id.row_summary)).setText(R.string.phone_settings_manage_sources_summary);
        sources.setOnClickListener(v -> {
            PhoneMainActivity main = PhoneUi.main(this);
            if (main != null) main.push(ConnectionsFragment.newInstance(true));
        });
        View cache = addRow(data, R.string.phone_settings_thumb_cache, false);
        mCacheSummary = cache.findViewById(R.id.row_summary);
        cache.setOnClickListener(v -> confirmClearCache());
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshSummaries();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) refreshSummaries();
    }

    private View addRow(ViewGroup container, int title, boolean divider) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View row = inflater.inflate(R.layout.item_phone_setting_row, container, false);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        container.addView(row);
        if (divider) {
            View line = new View(requireContext());
            line.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.nova_card_stroke));
            container.addView(line, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, Math.round(getResources().getDisplayMetrics().density))));
        }
        return row;
    }

    // ------------------------------------------------------------------------------------------
    // Theme
    // ------------------------------------------------------------------------------------------

    private void bindThemeOptions(ViewGroup container) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        String current = PhonePrefs.getThemeMode(requireContext());
        int accent = ContextCompat.getColor(requireContext(), R.color.nova_accent);
        int text = ContextCompat.getColor(requireContext(), R.color.nova_text_secondary);
        int stroke = ContextCompat.getColor(requireContext(), R.color.nova_card_stroke);
        int thick = Math.round(2 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < THEME_MODES.length; i++) {
            final String mode = THEME_MODES[i];
            MaterialCardView card = (MaterialCardView) inflater.inflate(R.layout.item_phone_theme_option, container, false);
            boolean selected = mode.equals(current);
            TextView label = card.findViewById(R.id.theme_label);
            label.setText(THEME_LABELS[i]);
            label.setTextColor(selected ? accent : text);
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(0, THEME_ICONS[i], 0, 0);
            TextViewCompat.setCompoundDrawableTintList(label, ColorStateList.valueOf(selected ? accent : text));
            card.setChecked(selected);
            card.setStrokeColor(selected ? accent : stroke);
            card.setStrokeWidth(selected ? thick : thick / 2);
            card.setContentDescription(getString(THEME_LABELS[i]));
            card.setOnClickListener(v -> {
                if (mode.equals(PhonePrefs.getThemeMode(requireContext()))) return;
                PhonePrefs.setThemeMode(requireContext(), mode);
                // applied at once; the activity is recreated with its state
                PhonePrefs.applyTheme((AppCompatActivity) requireActivity());
            });
            container.addView(card);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Playback (existing Nova preference keys)
    // ------------------------------------------------------------------------------------------

    private void refreshSummaries() {
        if (getView() == null) return;
        String decChoice = mPrefs.getString(VideoPreferencesCommon.KEY_DEC_CHOICE, null);
        if (decChoice != null) {
            // advanced decoder choice made in the full playback settings
            String[] entries = getResources().getStringArray(R.array.dec_choice_entries);
            String[] values = getResources().getStringArray(R.array.dec_choice_entryvalues);
            String label = decChoice;
            for (int i = 0; i < values.length && i < entries.length; i++) if (values[i].equals(decChoice)) label = entries[i];
            mDecodingSwitch.setOnCheckedChangeListener(null);
            mDecodingSwitch.setChecked(!"1".equals(decChoice));
            mDecodingSwitch.setEnabled(false);
            mDecodingSummary.setText(label);
        } else {
            boolean hardware = !mPrefs.getBoolean(VideoPreferencesCommon.KEY_FORCE_SW, false);
            mDecodingSwitch.setOnCheckedChangeListener(null);
            mDecodingSwitch.setEnabled(true);
            mDecodingSwitch.setChecked(hardware);
            mDecodingSummary.setText(hardware ? R.string.phone_settings_decoding_on : R.string.phone_settings_decoding_off);
            mDecodingSwitch.setOnCheckedChangeListener((b, checked) -> {
                mPrefs.edit().putBoolean(VideoPreferencesCommon.KEY_FORCE_SW, !checked).apply();
                mDecodingSummary.setText(checked ? R.string.phone_settings_decoding_on : R.string.phone_settings_decoding_off);
            });
        }
        mAudioSummary.setText(languageLabel(VideoPreferencesCommon.KEY_AUDIO_TRACK_FAV_LANG));
        mSubtitleSummary.setText(languageLabel(VideoPreferencesCommon.KEY_SUBTITLES_FAV_LANG));
        refreshCacheSize();
    }

    private String languageLabel(String key) {
        List<String> entries = new ArrayList<>();
        List<String> values = new ArrayList<>();
        VideoPreferencesCommon.buildPlaybackLanguageList(requireContext(), entries, values);
        String value = mPrefs.getString(key, Locale.getDefault().getLanguage());
        int index = indexOf(values, value);
        return index >= 0 ? entries.get(index) : entries.get(0);
    }

    private static int indexOf(List<String> values, String value) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).equalsIgnoreCase(value)) return i;
        return -1;
    }

    private void chooseLanguage(String key, int title) {
        List<String> entries = new ArrayList<>();
        List<String> values = new ArrayList<>();
        VideoPreferencesCommon.buildPlaybackLanguageList(requireContext(), entries, values);
        int checked = Math.max(0, indexOf(values, mPrefs.getString(key, Locale.getDefault().getLanguage())));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setSingleChoiceItems(entries.toArray(new CharSequence[0]), checked, (d, which) -> {
                    mPrefs.edit().putString(key, values.get(which)).apply();
                    d.dismiss();
                    refreshSummaries();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openPlaybackPreferences() {
        Intent intent = new Intent(requireContext(), VideoPreferencesActivity.class);
        intent.putExtra(VideoPreferencesFragment.EXTRA_PLAYBACK_ONLY, true);
        startActivity(intent);
    }

    // ------------------------------------------------------------------------------------------
    // Thumbnail cache
    // ------------------------------------------------------------------------------------------

    private void refreshCacheSize() {
        final ThumbnailLoader loader = ThumbnailLoader.get(requireContext());
        sDisk.execute(() -> {
            final long size = loader.getCacheSize();
            mMain.post(() -> {
                if (mCacheSummary != null && isAdded()) {
                    mCacheSummary.setText(getString(R.string.phone_settings_thumb_cache_summary,
                            Formatter.formatShortFileSize(requireContext(), size)));
                }
            });
        });
    }

    private void confirmClearCache() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.phone_clear_cache_title)
                .setMessage(R.string.phone_clear_cache_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.phone_clear, (d, w) -> {
                    final ThumbnailLoader loader = ThumbnailLoader.get(requireContext());
                    sDisk.execute(() -> {
                        final long freed = loader.clearCache();
                        mMain.post(() -> {
                            if (!isAdded()) return;
                            PhoneUi.showSnackbar(this, getString(R.string.phone_cache_cleared,
                                    Formatter.formatShortFileSize(requireContext(), freed)));
                            refreshCacheSize();
                        });
                    });
                })
                .show();
    }
}
