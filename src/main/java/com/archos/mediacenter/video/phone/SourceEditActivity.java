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
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.MediaRef;
import com.archos.mediacenter.video.userdata.NetworkError;
import com.archos.mediacenter.video.userdata.NetworkSource;
import com.archos.mediacenter.video.userdata.SourceAccess;
import com.archos.mediacenter.video.userdata.SourceProtocol;
import com.archos.mediacenter.video.userdata.UserDataStore;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputLayout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Add or edit an SMB, FTP or SFTP connection. "Test" never saves anything; editing keeps the
 * permanent id so favorites, history and shortcuts stay attached.
 */
public class SourceEditActivity extends PhoneBaseActivity {

    private static final Logger log = LoggerFactory.getLogger(SourceEditActivity.class);

    static final String EXTRA_SOURCE_ID = "source_id";
    private static final String STATE_ID = "draft_id";
    private static final String STATE_HOST_KEY = "host_key";
    private static final String STATE_VERIFIED = "verified";

    private UserDataStore mStore;
    /** null when creating a source. */
    private NetworkSource mOriginal;
    private String mId;
    private String mHostKey;
    private SourceProtocol mProtocol = SourceProtocol.SMB;
    private String mInitialSignature;
    /** Signature of the configuration that passed the last test. */
    private String mVerifiedSignature;
    private Future<?> mTask;

    private MaterialButtonToggleGroup mProtocolGroup;
    private EditText mName, mHost, mUser, mPort, mPassword, mShare, mDomain, mRootPath;
    private TextInputLayout mPasswordLayout;
    private MaterialSwitch mFtps;
    private View mSmbGroup, mFtpGroup, mStatus, mStatusProgress;
    private TextView mSftpKey, mStatusText, mFtpWarning;
    private ImageView mStatusIcon;

    public static Intent intent(Context context, String sourceId) {
        Intent intent = new Intent(context, SourceEditActivity.class);
        if (sourceId != null) intent.putExtra(EXTRA_SOURCE_ID, sourceId);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone_source_edit);
        SystemBars.pad(findViewById(R.id.form_root), true, true, true);
        mStore = UserDataStore.get(this);

        String sourceId = getIntent().getStringExtra(EXTRA_SOURCE_ID);
        if (sourceId != null) {
            mOriginal = mStore.getSource(sourceId);
            if (mOriginal == null) {
                finish();
                return;
            }
        }
        ((TextView) findViewById(R.id.toolbar_title)).setText(mOriginal == null ? R.string.phone_add_source_title : R.string.phone_edit_source_title);
        findViewById(R.id.toolbar_back).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        mProtocolGroup = findViewById(R.id.form_protocol);
        mName = findViewById(R.id.form_name);
        mHost = findViewById(R.id.form_host);
        mUser = findViewById(R.id.form_user);
        mPort = findViewById(R.id.form_port);
        mPassword = findViewById(R.id.form_password);
        mPasswordLayout = findViewById(R.id.form_password_layout);
        mShare = findViewById(R.id.form_share);
        mDomain = findViewById(R.id.form_domain);
        mRootPath = findViewById(R.id.form_root_path);
        mFtps = findViewById(R.id.form_ftps);
        mFtpWarning = findViewById(R.id.form_ftp_warning);
        mSmbGroup = findViewById(R.id.form_smb_group);
        mFtpGroup = findViewById(R.id.form_ftp_group);
        mSftpKey = findViewById(R.id.form_sftp_key);
        mStatus = findViewById(R.id.form_status);
        mStatusProgress = findViewById(R.id.form_status_progress);
        mStatusIcon = findViewById(R.id.form_status_icon);
        mStatusText = findViewById(R.id.form_status_text);

        if (mOriginal != null) {
            mId = mOriginal.id;
            mHostKey = mOriginal.hostKeyFingerprint;
            mProtocol = mOriginal.protocol;
            mName.setText(mOriginal.name);
            mHost.setText(mOriginal.host);
            mUser.setText(mOriginal.username);
            if (mOriginal.port > 0) mPort.setText(String.valueOf(mOriginal.port));
            mShare.setText(mOriginal.share);
            mDomain.setText(mOriginal.domain);
            mRootPath.setText(mOriginal.rootPath == null || "/".equals(mOriginal.rootPath) ? "" : mOriginal.rootPath);
            mFtps.setChecked(mOriginal.ftpSecure);
            if (mOriginal.hasPassword()) mPasswordLayout.setHelperText(getString(R.string.phone_password_kept));
            // the protocol defines the paths: it is fixed once the source exists
            for (int i = 0; i < mProtocolGroup.getChildCount(); i++) mProtocolGroup.getChildAt(i).setEnabled(false);
        } else {
            mId = NetworkSource.create(SourceProtocol.SMB).id;
        }
        if (savedInstanceState != null) {
            mId = savedInstanceState.getString(STATE_ID, mId);
            mHostKey = savedInstanceState.getString(STATE_HOST_KEY, mHostKey);
            mVerifiedSignature = savedInstanceState.getString(STATE_VERIFIED);
        }
        mProtocolGroup.check(buttonFor(mProtocol));
        mProtocolGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            SourceProtocol previous = mProtocol;
            mProtocol = protocolFor(checkedId);
            // keep a custom port, follow the protocol default otherwise
            String port = mPort.getText().toString().trim();
            if (port.equals(String.valueOf(previous.defaultPort))) mPort.setText("");
            updateProtocolViews();
        });
        mFtps.setOnCheckedChangeListener((b, checked) -> mFtpWarning.setVisibility(checked ? View.GONE : View.VISIBLE));
        ((TextInputLayout) findViewById(R.id.form_share_layout)).setEndIconOnClickListener(v -> browseShares());
        findViewById(R.id.form_test).setOnClickListener(v -> {
            if (validate()) runTest(null, null);
        });
        findViewById(R.id.form_save).setOnClickListener(v -> save());

        updateProtocolViews();
        mInitialSignature = signature(buildDraft(), mPassword.getText().toString());
        TextWatcher clearStatus = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (mTask == null && !Objects.equals(mVerifiedSignature, currentSignature())) mStatus.setVisibility(View.GONE);
            }
        };
        for (EditText e : new EditText[]{mHost, mUser, mPort, mPassword, mShare, mDomain, mRootPath}) e.addTextChangedListener(clearStatus);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!isDirty()) {
                    finish();
                    return;
                }
                new MaterialAlertDialogBuilder(SourceEditActivity.this)
                        .setTitle(R.string.phone_discard_title)
                        .setMessage(R.string.phone_discard_message)
                        .setPositiveButton(R.string.phone_discard, (d, w) -> finish())
                        .setNegativeButton(R.string.phone_keep_editing, null)
                        .show();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_ID, mId);
        outState.putString(STATE_HOST_KEY, mHostKey);
        outState.putString(STATE_VERIFIED, mVerifiedSignature);
    }

    @Override
    protected void onDestroy() {
        if (mTask != null) mTask.cancel(true);
        super.onDestroy();
    }

    private static int buttonFor(SourceProtocol protocol) {
        switch (protocol) {
            case FTP:
                return R.id.form_protocol_ftp;
            case SFTP:
                return R.id.form_protocol_sftp;
            default:
                return R.id.form_protocol_smb;
        }
    }

    private static SourceProtocol protocolFor(int buttonId) {
        if (buttonId == R.id.form_protocol_ftp) return SourceProtocol.FTP;
        if (buttonId == R.id.form_protocol_sftp) return SourceProtocol.SFTP;
        return SourceProtocol.SMB;
    }

    private void updateProtocolViews() {
        mSmbGroup.setVisibility(mProtocol == SourceProtocol.SMB ? View.VISIBLE : View.GONE);
        mFtpGroup.setVisibility(mProtocol == SourceProtocol.FTP ? View.VISIBLE : View.GONE);
        mFtpWarning.setVisibility(mFtps.isChecked() ? View.GONE : View.VISIBLE);
        mPort.setHint(String.valueOf(mProtocol.defaultPort));
        if (mProtocol == SourceProtocol.SFTP) {
            mSftpKey.setVisibility(View.VISIBLE);
            mSftpKey.setText(mHostKey != null ? getString(R.string.phone_sftp_key, mHostKey) : getString(R.string.phone_sftp_key_unknown));
        } else {
            mSftpKey.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Draft
    // ------------------------------------------------------------------------------------------

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private NetworkSource buildDraft() {
        NetworkSource s = mOriginal != null ? mOriginal.copy() : NetworkSource.create(mProtocol);
        s.id = mId;
        s.protocol = mProtocol;
        s.host = text(mHost);
        s.name = text(mName).isEmpty() ? s.host : text(mName);
        int port = 0;
        try {
            if (!text(mPort).isEmpty()) port = Integer.parseInt(text(mPort));
        } catch (NumberFormatException ignored) {
            port = -1;
        }
        s.port = port;
        s.username = text(mUser);
        s.share = mProtocol == SourceProtocol.SMB ? text(mShare) : null;
        s.domain = mProtocol == SourceProtocol.SMB ? text(mDomain) : null;
        String root = text(mRootPath);
        s.rootPath = root.isEmpty() ? "/" : MediaRef.normalizePath(root);
        s.ftpSecure = mProtocol == SourceProtocol.FTP && mFtps.isChecked();
        s.hostKeyFingerprint = mProtocol == SourceProtocol.SFTP ? mHostKey : null;
        return s;
    }

    /** Everything that changes what the test checks, password included (kept in memory only). */
    private static String signature(NetworkSource s, String password) {
        return TextUtils.join("\u0000", new Object[]{s.protocol, s.name, s.host, s.port, s.share, s.rootPath,
                s.username, s.domain, s.ftpSecure, password});
    }

    private String currentSignature() {
        return signature(buildDraft(), mPassword.getText().toString());
    }

    private boolean isDirty() {
        return !Objects.equals(mInitialSignature, currentSignature());
    }

    private boolean validate() {
        boolean ok = true;
        TextInputLayout hostLayout = findViewById(R.id.form_host_layout);
        if (text(mHost).isEmpty()) {
            hostLayout.setError(getString(R.string.phone_error_required));
            ok = false;
        } else {
            hostLayout.setError(null);
        }
        TextInputLayout portLayout = findViewById(R.id.form_port_layout);
        NetworkSource draft = buildDraft();
        if (draft.port < 0 || draft.port > 65535) {
            portLayout.setError(getString(R.string.phone_error_port));
            ok = false;
        } else {
            portLayout.setError(null);
        }
        return ok;
    }

    /** The typed password, or the stored one when the field was left empty while editing. */
    private String passwordForConnection() {
        String typed = mPassword.getText().toString();
        if (!typed.isEmpty() || mOriginal == null) return typed;
        String stored = mStore.getPassword(mOriginal);
        return stored == null ? "" : stored;
    }

    // ------------------------------------------------------------------------------------------
    // Test and save
    // ------------------------------------------------------------------------------------------

    private void showStatus(boolean inProgress, boolean success, String message) {
        mStatus.setVisibility(View.VISIBLE);
        mStatusProgress.setVisibility(inProgress ? View.VISIBLE : View.GONE);
        mStatusIcon.setVisibility(inProgress ? View.GONE : View.VISIBLE);
        int color = ContextCompat.getColor(this, inProgress ? R.color.nova_text_secondary
                : success ? R.color.nova_status_ok : R.color.nova_error);
        mStatusIcon.setImageResource(success ? R.drawable.ic_phone_check : R.drawable.ic_phone_cloud_off);
        ImageViewCompat.setImageTintList(mStatusIcon, ColorStateList.valueOf(color));
        mStatusText.setTextColor(color);
        mStatusText.setText(message);
    }

    private interface FailureHandler {
        void onFailure(NetworkError error);
    }

    private void runTest(Runnable onSuccess, FailureHandler onFailure) {
        if (mTask != null) return;
        final NetworkSource draft = buildDraft();
        final String signature = currentSignature();
        showStatus(true, false, getString(R.string.phone_testing));
        mTask = SourceAccess.test(draft, passwordForConnection(), (files, error) -> {
            mTask = null;
            if (isFinishing()) return;
            if (error == null) {
                mVerifiedSignature = signature;
                showStatus(false, true, getString(R.string.phone_test_ok));
                if (onSuccess != null) onSuccess.run();
                return;
            }
            if (error.kind == NetworkError.Kind.HOST_KEY && error.cause instanceof SourceAccess.HostKeyException) {
                mStatus.setVisibility(View.GONE);
                ConnectionDialogs.confirmHostKey(this, draft, (SourceAccess.HostKeyException) error.cause, () -> {
                    mHostKey = draft.hostKeyFingerprint;
                    updateProtocolViews();
                    runTest(onSuccess, onFailure);
                });
                return;
            }
            showStatus(false, false, ConnectionDialogs.describe(this, error));
            if (onFailure != null) onFailure.onFailure(error);
        });
    }

    private void browseShares() {
        if (!validate()) return;
        showStatus(true, false, getString(R.string.phone_testing));
        SourceAccess.listShares(buildDraft(), passwordForConnection(), (shares, error) -> {
            if (isFinishing()) return;
            if (error != null) {
                showStatus(false, false, ConnectionDialogs.describe(this, error));
                return;
            }
            mStatus.setVisibility(View.GONE);
            if (shares.isEmpty()) {
                showStatus(false, false, getString(R.string.phone_shares_none));
                return;
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.phone_field_share)
                    .setItems(shares.toArray(new CharSequence[0]), (d, which) -> mShare.setText(shares.get(which)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    private void save() {
        if (!validate()) return;
        if (Objects.equals(mVerifiedSignature, currentSignature())) {
            persist(NetworkSource.Status.AVAILABLE);
            return;
        }
        runTest(() -> persist(NetworkSource.Status.AVAILABLE), error -> new MaterialAlertDialogBuilder(this)
                // the network may simply be unreachable now: explicit save without verification
                .setTitle(R.string.phone_save_unverified_title)
                .setMessage(getString(R.string.phone_save_unverified_message, ConnectionDialogs.describe(this, error)))
                .setPositiveButton(R.string.phone_save_anyway, (d, w) -> persist(NetworkSource.Status.UNKNOWN))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private void persist(NetworkSource.Status status) {
        NetworkSource draft = buildDraft();
        draft.status = status;
        draft.statusTime = System.currentTimeMillis();
        String typed = mPassword.getText().toString();
        // empty field while editing keeps the stored password
        String newPassword = !typed.isEmpty() ? typed : (mOriginal == null ? "" : null);
        try {
            mStore.saveSource(draft, newPassword);
        } catch (Exception e) {
            log.error("persist: cannot encrypt the password", e);
            showStatus(false, false, getString(R.string.phone_error_unknown));
            return;
        }
        SourceAccess.registerCredentials(draft, mStore.getPassword(draft));
        if (draft.hostKeyFingerprint != null) SourceAccess.markHostKeyTrusted(draft.id);
        setResult(Activity.RESULT_OK);
        finish();
    }
}
