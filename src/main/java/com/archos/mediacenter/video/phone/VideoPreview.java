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

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.userdata.UserDataStore;

/**
 * Hold-to-preview: a card above the current screen showing one large frame of a video at a time
 * and a scale of {@link PreviewFrames#COUNT} frames.
 *
 * <p>The card lives in the activity's own window (no activity, no dialog) and the screen behind
 * it is blurred. The touch that opened it keeps driving it: while the finger rests, the frames
 * change by themselves; once it slides horizontally, the frame at the finger's position is shown;
 * lifting it closes the card at once. Nothing is played and nothing is written to the history.</p>
 *
 * <p>Without a touch (accessibility action, keyboard), the frames change by themselves and the
 * next touch or Back closes the card.</p>
 */
final class VideoPreview {

    interface OnDismissListener {
        void onDismissed(VideoPreview preview);
    }

    private static final int COUNT = PreviewFrames.COUNT;
    @VisibleForTesting
    static final long AUTO_ADVANCE_MS = 700;
    private static final long SHOW_MS = 140;
    private static final float BLUR_DP = 18;
    private static final float DEFAULT_ASPECT = 16f / 9f;
    private static final int INSET_TYPES = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();

    private final ViewGroup mHost;
    /** The app screen behind the card (blurred). */
    private final View mScreen;
    private final int mScreenAccessibility;
    private final Overlay mOverlay;
    private final ImageView mImage;
    private final View mLoading;
    private final TextView mMessage;
    private final TextView mTimecode;
    private final TextView mEnd;
    private final FrameScaleView mScale;
    private final PreviewFrames mFrames;
    private final OnDismissListener mDismissListener;
    private final boolean mTouchDriven;
    private final int mPointerId;
    private final float mStartX;
    private final int mTouchSlop;
    private final float mBlurRadius;
    private final int[] mLocation = new int[2];
    private final Bitmap[] mBitmaps = new Bitmap[COUNT];
    private final int[] mStates = new int[COUNT];
    private final Runnable mAdvance = this::advance;
    private ValueAnimator mShowAnimator;

    private int mSelected;
    private boolean mManual;
    private boolean mShowing;
    private boolean mFailed;
    private boolean mHasFrameAspect;
    private long mDuration = -1;

    /**
     * Opens the preview of a list row.
     *
     * @param touchDriven true when a finger is down (the long press): it then drives the card
     * @param x           finger position in window coordinates, when touch driven
     * @param y           finger position in window coordinates, when touch driven
     * @param placeholder list thumbnail shown until the first frame arrives, may be null
     */
    static VideoPreview show(Activity activity, ListItems.VideoItem item, Bitmap placeholder, boolean touchDriven,
                             int pointerId, float x, float y, OnDismissListener listener) {
        VideoPreview preview = new VideoPreview(activity, item, placeholder, touchDriven, pointerId, x, y, listener);
        preview.attach();
        return preview;
    }

    private VideoPreview(Activity activity, ListItems.VideoItem item, Bitmap placeholder, boolean touchDriven,
                         int pointerId, float x, float y, OnDismissListener listener) {
        mHost = activity.findViewById(android.R.id.content);
        mScreen = mHost.getChildAt(0);
        mScreenAccessibility = mScreen.getImportantForAccessibility();
        mDismissListener = listener;
        mTouchDriven = touchDriven;
        mPointerId = pointerId;
        mTouchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        mBlurRadius = BLUR_DP * activity.getResources().getDisplayMetrics().density;

        mOverlay = new Overlay(activity);
        View card = LayoutInflater.from(activity).inflate(R.layout.view_phone_preview, mOverlay, false);
        mOverlay.setCard(card);
        mHost.getLocationInWindow(mLocation);
        mStartX = x;
        mOverlay.mFingerY = touchDriven ? y - mLocation[1] : Float.NaN;

        mImage = card.findViewById(R.id.preview_image);
        mLoading = card.findViewById(R.id.preview_loading);
        mMessage = card.findViewById(R.id.preview_message);
        mTimecode = card.findViewById(R.id.preview_timecode);
        mEnd = card.findViewById(R.id.preview_end);
        mScale = card.findViewById(R.id.preview_scale);
        mScale.setCount(COUNT);
        card.findViewById(R.id.preview_frame).setClipToOutline(true);
        ((TextView) card.findViewById(R.id.preview_name)).setText(item.title);
        ((TextView) card.findViewById(R.id.preview_scale_label)).setText(
                activity.getResources().getQuantityString(R.plurals.phone_preview_frames, COUNT, COUNT));
        ((TextView) card.findViewById(R.id.preview_start)).setText(Formats.timecode(0));
        if (!touchDriven) {
            // nothing to slide: frames only change by themselves
            card.findViewById(R.id.preview_hint_swipe).setVisibility(View.GONE);
            card.findViewById(R.id.preview_hint_separator).setVisibility(View.GONE);
            ((TextView) card.findViewById(R.id.preview_hint_release)).setText(R.string.phone_preview_hint_tap);
        }
        ViewCompat.setAccessibilityPaneTitle(card, activity.getString(R.string.phone_preview_title));
        if (placeholder != null && placeholder.getWidth() > 0 && placeholder.getHeight() > 0) {
            mImage.setImageBitmap(placeholder);
            mOverlay.mAspect = (float) placeholder.getWidth() / placeholder.getHeight();
        }

        long knownDuration = item.durationMs > 0 ? item.durationMs : UserDataStore.get(activity).getKnownDuration(item.ref);
        mFrames = new PreviewFrames(activity, item.source, item.ref, item.size, item.modified, knownDuration,
                new PreviewFrames.Listener() {
                    @Override
                    public void onDuration(long durationMs) {
                        VideoPreview.this.onDuration(durationMs);
                    }

                    @Override
                    public void onFrame(int index, Bitmap frame) {
                        VideoPreview.this.onFrame(index, frame);
                    }

                    @Override
                    public void onError(@StringRes int message) {
                        VideoPreview.this.onError(message);
                    }
                });
    }

    private void attach() {
        mShowing = true;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(mHost);
        if (insets != null) mOverlay.mInsets = insets.getInsets(INSET_TYPES);
        ViewCompat.setOnApplyWindowInsetsListener(mOverlay, (v, windowInsets) -> {
            mOverlay.setInsets(windowInsets.getInsets(INSET_TYPES));
            return windowInsets;
        });
        mOverlay.fitHints(mHost.getWidth(), mHost.getHeight());
        mHost.addView(mOverlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        ViewCompat.requestApplyInsets(mOverlay);
        mScreen.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        mOverlay.setAlpha(0f);
        mShowAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(SHOW_MS);
        mShowAnimator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            mOverlay.setAlpha(f);
            setBlur(f);
        });
        mShowAnimator.start();

        select(0);
        mFrames.start();
        mOverlay.postDelayed(mAdvance, AUTO_ADVANCE_MS);
    }

    boolean isShowing() {
        return mShowing;
    }

    /** Closes the card at once: no player, no history, extraction stopped. */
    void dismiss() {
        if (!mShowing) return;
        mShowing = false;
        mOverlay.removeCallbacks(mAdvance);
        if (mShowAnimator != null) mShowAnimator.cancel();
        mFrames.cancel();
        mHost.removeView(mOverlay);
        setBlur(0f);
        mScreen.setImportantForAccessibility(mScreenAccessibility);
        mDismissListener.onDismissed(this);
    }

    private void setBlur(float fraction) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Blur.apply(mScreen, mBlurRadius * fraction);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static final class Blur {
        static void apply(View view, float radius) {
            view.setRenderEffect(radius < 0.5f ? null : RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Touch: every event of the window while the card is shown (window coordinates)
    // ------------------------------------------------------------------------------------------

    void onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (!mTouchDriven) return;
                int index = ev.findPointerIndex(mPointerId);
                if (index >= 0) onFingerMoved(ev.getX(index));
                return;
            case MotionEvent.ACTION_POINTER_DOWN:
                return;
            case MotionEvent.ACTION_POINTER_UP:
                if (ev.getPointerId(ev.getActionIndex()) == mPointerId) dismiss();
                return;
            default:
                // finger lifted, gesture cancelled, or a new touch when opened without one
                dismiss();
        }
    }

    private void onFingerMoved(float x) {
        if (!mManual) {
            if (Math.abs(x - mStartX) < mTouchSlop) return;
            // from now on the finger chooses: frames no longer change by themselves
            mManual = true;
            mOverlay.removeCallbacks(mAdvance);
        }
        mScale.getLocationInWindow(mLocation);
        int index = mScale.indexAt(x - mLocation[0]);
        if (index != mSelected) {
            select(index);
            mScale.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private void advance() {
        if (!mShowing || mManual || mFailed) return;
        int next = -1;
        for (int d = 1; d <= COUNT; d++) {
            int i = (mSelected + d) % COUNT;
            if (mStates[i] != FrameScaleView.STATE_FAILED) {
                next = i;
                break;
            }
        }
        if (next >= 0 && next != mSelected) {
            // wait for the next frame rather than showing a spinner
            if (mBitmaps[next] != null) select(next);
            else mFrames.setFocus(next, true);
        }
        mOverlay.postDelayed(mAdvance, AUTO_ADVANCE_MS);
    }

    private void select(int index) {
        mSelected = index;
        mScale.setSelected(index);
        mFrames.setFocus(index, !mManual);
        showSelected();
    }

    private void showSelected() {
        Bitmap frame = mBitmaps[mSelected];
        if (frame != null) mImage.setImageBitmap(frame);
        boolean loading = frame == null && !mFailed && mStates[mSelected] == FrameScaleView.STATE_PENDING;
        mLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        // the picture still shown is not the selected frame: dimmed under the spinner
        mImage.setAlpha(mFailed ? 0.3f : loading ? 0.5f : 1f);
        if (mDuration > 0 && !mFailed) {
            String time = Formats.timecode(PreviewFrames.timeOf(mSelected, mDuration));
            mTimecode.setText(time);
            mTimecode.setVisibility(View.VISIBLE);
            mScale.setContentDescription(mScale.getContext().getString(R.string.phone_preview_frame_description,
                    mSelected + 1, COUNT, time));
        } else {
            mTimecode.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Frames
    // ------------------------------------------------------------------------------------------

    private void onDuration(long durationMs) {
        mDuration = durationMs;
        mEnd.setText(Formats.timecode(durationMs));
        showSelected();
    }

    private void onFrame(int index, Bitmap frame) {
        mBitmaps[index] = frame;
        mStates[index] = frame != null ? FrameScaleView.STATE_READY : FrameScaleView.STATE_FAILED;
        mScale.setState(index, mStates[index]);
        if (frame != null && !mHasFrameAspect && frame.getWidth() > 0 && frame.getHeight() > 0) {
            mHasFrameAspect = true;
            mOverlay.setAspect((float) frame.getWidth() / frame.getHeight());
        }
        if (index == mSelected) showSelected();
    }

    private void onError(@StringRes int message) {
        mFailed = true;
        mOverlay.removeCallbacks(mAdvance);
        mMessage.setText(message);
        mMessage.setVisibility(View.VISIBLE);
        showSelected();
    }

    @VisibleForTesting
    int getSelected() {
        return mSelected;
    }

    // ------------------------------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------------------------------

    /**
     * Top of the card: centred, unless the finger would then be on the frame or under it (the
     * hand comes from below). The card then moves up just enough for the frame to end above the
     * finger, which usually lands on the scale; when even that is impossible it stays centred.
     *
     * @param imageTop    top of the frame in the card
     * @param imageBottom bottom of the frame in the card
     * @param fingerY     NaN without a finger
     */
    static int placeCard(int areaTop, int areaBottom, int cardHeight, int imageTop, int imageBottom, float fingerY,
                         int clearance) {
        int centered = areaTop + Math.max(0, (areaBottom - areaTop - cardHeight) / 2);
        if (Float.isNaN(fingerY)) return centered;
        int clearAbove = Math.round(fingerY) - clearance - imageBottom;
        if (clearAbove >= centered) return centered;
        if (clearAbove >= areaTop) return clearAbove;
        return centered;
    }

    /**
     * Full-window layer holding the card: about 94% of the width inside the system bars, the frame
     * as high as the video aspect ratio allows in the space left.
     */
    @SuppressLint("ViewConstructor")
    static final class Overlay extends ViewGroup {
        private static final float WIDTH_FRACTION = 0.94f;

        private View mCard;
        private View mFrame;
        private View mHints;
        Insets mInsets = Insets.NONE;
        float mAspect = DEFAULT_ASPECT;
        /** Finger position in this layer, NaN without a finger. */
        float mFingerY = Float.NaN;
        private final int mMargin;
        private final int mMinFrame;
        private final int mMinCardWidth;
        private final int mClearance;

        Overlay(Context context) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            mMargin = Math.round(12 * density);
            mMinFrame = Math.round(120 * density);
            mMinCardWidth = Math.round(340 * density);
            mClearance = Math.round(28 * density);
            setBackgroundColor(ContextCompat.getColor(context, R.color.nova_preview_scrim));
        }

        void setCard(View card) {
            mCard = card;
            mFrame = card.findViewById(R.id.preview_frame);
            mHints = card.findViewById(R.id.preview_hints);
            addView(card);
        }

        void setInsets(Insets insets) {
            if (insets.equals(mInsets)) return;
            mInsets = insets;
            requestLayout();
        }

        void setAspect(float aspect) {
            if (Math.abs(aspect - mAspect) < 0.01f) return;
            mAspect = aspect;
            requestLayout();
        }

        /**
         * Short screens (landscape phones) drop the gesture hints to keep a usable frame. Decided
         * once, before the card is shown: toggling it while measuring would loop.
         */
        void fitHints(int width, int height) {
            if (width <= 0 || height <= 0) return;
            int areaHeight = height - mInsets.top - mInsets.bottom - 2 * mMargin;
            if (chromeHeight(cardWidth(width)) + mMinFrame > areaHeight) mHints.setVisibility(GONE);
        }

        private int cardWidth(int width) {
            return Math.round((width - mInsets.left - mInsets.right) * WIDTH_FRACTION);
        }

        /** Height of the card without its frame. */
        private int chromeHeight(int cardWidth) {
            mFrame.getLayoutParams().height = 0;
            mCard.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            return mCard.getMeasuredHeight();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(width, height);
            int areaHeight = height - mInsets.top - mInsets.bottom - 2 * mMargin;
            int padding = mCard.getPaddingLeft() + mCard.getPaddingRight();
            int cardWidth = cardWidth(width);
            int chrome = chromeHeight(cardWidth);
            int frameHeight = Math.round((cardWidth - padding) / mAspect);
            int maxFrame = Math.max(mMinFrame, areaHeight - chrome);
            if (frameHeight > maxFrame) {
                frameHeight = maxFrame;
                // a narrower card rather than wide black bars, as long as the scale stays usable
                int fitted = Math.round(frameHeight * mAspect) + padding;
                int narrowed = Math.max(Math.min(cardWidth, mMinCardWidth), fitted);
                if (narrowed < cardWidth) {
                    cardWidth = narrowed;
                    chrome = chromeHeight(cardWidth);
                }
            }
            mFrame.getLayoutParams().height = frameHeight;
            mCard.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(chrome + frameHeight, MeasureSpec.EXACTLY));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int height = b - t;
            int cardWidth = mCard.getMeasuredWidth();
            int cardHeight = mCard.getMeasuredHeight();
            int left = mInsets.left + (width - mInsets.left - mInsets.right - cardWidth) / 2;
            // lay out once to know where the frame sits in the card
            mCard.layout(left, 0, left + cardWidth, cardHeight);
            int top = placeCard(mInsets.top + mMargin, height - mInsets.bottom - mMargin, cardHeight,
                    mFrame.getTop(), mFrame.getBottom(), mFingerY, mClearance);
            mCard.layout(left, top, left + cardWidth, top + cardHeight);
        }

        @Override
        public boolean shouldDelayChildPressedState() {
            return false;
        }
    }
}
