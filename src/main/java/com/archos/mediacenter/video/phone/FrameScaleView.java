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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.archos.mediacenter.video.R;

/**
 * Scale of the preview card: one dot per frame on a track, numbered 1..n below, the selected
 * frame drawn as a large knob. Frames not extracted yet are faded, failed ones hollow.
 */
public final class FrameScaleView extends View {

    static final int STATE_PENDING = 0;
    static final int STATE_READY = 1;
    static final int STATE_FAILED = 2;

    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mActive = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKnobCenter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelSelected = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mAccent;
    private final int mInactive;
    private final float mDotRadius;
    private final float mKnobRadius;
    private final float mKnobCenterRadius;
    private final float mHaloRadius;
    private final float mLabelGap;

    private int[] mStates = new int[PreviewFrames.COUNT];
    private int mSelected;

    public FrameScaleView(Context context) {
        this(context, null);
    }

    public FrameScaleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mAccent = ContextCompat.getColor(context, R.color.nova_accent);
        mInactive = ContextCompat.getColor(context, R.color.nova_text_secondary);
        mDotRadius = dp(3.5f);
        mKnobRadius = dp(10);
        mKnobCenterRadius = dp(4);
        mHaloRadius = dp(15);
        mLabelGap = dp(4);

        mTrack.setColor(ContextCompat.getColor(context, R.color.nova_track));
        mTrack.setStrokeWidth(dp(3));
        mTrack.setStrokeCap(Paint.Cap.ROUND);
        mActive.setColor(mAccent);
        mActive.setStrokeWidth(dp(3));
        mActive.setStrokeCap(Paint.Cap.ROUND);
        mDot.setStrokeWidth(dp(1.5f));
        mHalo.setColor(mAccent);
        mHalo.setAlpha(0x40);
        mKnobCenter.setColor(0xFFFFFFFF);
        float textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics());
        mLabel.setTextSize(textSize);
        mLabel.setTextAlign(Paint.Align.CENTER);
        mLabel.setColor(mInactive);
        mLabelSelected.setTextSize(textSize);
        mLabelSelected.setTextAlign(Paint.Align.CENTER);
        mLabelSelected.setColor(mAccent);
        mLabelSelected.setTypeface(Typeface.DEFAULT_BOLD);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    void setCount(int count) {
        mStates = new int[count];
        mSelected = Math.min(mSelected, count - 1);
        invalidate();
    }

    int getCount() {
        return mStates.length;
    }

    void setSelected(int index) {
        if (index == mSelected) return;
        mSelected = index;
        invalidate();
    }

    int getSelected() {
        return mSelected;
    }

    void setState(int index, int state) {
        if (mStates[index] == state) return;
        mStates[index] = state;
        invalidate();
    }

    private float left() {
        return getPaddingLeft() + mHaloRadius;
    }

    private float right() {
        return getWidth() - getPaddingRight() - mHaloRadius;
    }

    private float step() {
        int n = mStates.length;
        return n > 1 ? (right() - left()) / (n - 1) : 0;
    }

    float dotX(int index) {
        return left() + index * step();
    }

    /** Frame whose dot is nearest to this horizontal position (view coordinates). */
    int indexAt(float x) {
        float step = step();
        if (step <= 0) return 0;
        int index = Math.round((x - left()) / step);
        return Math.max(0, Math.min(mStates.length - 1, index));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Paint.FontMetrics fm = mLabel.getFontMetrics();
        int height = Math.round(getPaddingTop() + 2 * mHaloRadius + mLabelGap + (fm.descent - fm.ascent) + getPaddingBottom());
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = mStates.length;
        if (n == 0) return;
        float cy = getPaddingTop() + mHaloRadius;
        float first = dotX(0);
        float selected = dotX(mSelected);
        canvas.drawLine(first, cy, dotX(n - 1), cy, mTrack);
        if (mSelected > 0) canvas.drawLine(first, cy, selected, cy, mActive);

        for (int i = 0; i < n; i++) {
            if (i == mSelected) continue;
            float x = dotX(i);
            int color = i < mSelected ? mAccent : mInactive;
            if (mStates[i] == STATE_FAILED) {
                mDot.setStyle(Paint.Style.STROKE);
                mDot.setColor(color);
                mDot.setAlpha(0x80);
            } else {
                mDot.setStyle(Paint.Style.FILL);
                mDot.setColor(color);
                if (mStates[i] == STATE_PENDING) mDot.setAlpha(0x66);
            }
            canvas.drawCircle(x, cy, mDotRadius, mDot);
        }
        canvas.drawCircle(selected, cy, mHaloRadius, mHalo);
        canvas.drawCircle(selected, cy, mKnobRadius, mActive);
        canvas.drawCircle(selected, cy, mKnobCenterRadius, mKnobCenter);

        // numbers: all of them when they fit, otherwise every other one (and the selected one)
        float step = step();
        float labelWidth = mLabel.measureText(String.valueOf(n)) + dp(3);
        int every = step >= labelWidth ? 1 : step * 2 >= labelWidth ? 2 : 5;
        Paint.FontMetrics fm = mLabel.getFontMetrics();
        float baseline = cy + mHaloRadius + mLabelGap - fm.ascent;
        for (int i = 0; i < n; i++) {
            boolean isSelected = i == mSelected;
            if (!isSelected && i != 0 && (i + 1) % every != 0) continue;
            if (!isSelected && every > 1 && Math.abs(i - mSelected) * step < labelWidth) continue;
            canvas.drawText(String.valueOf(i + 1), dotX(i), baseline, isSelected ? mLabelSelected : mLabel);
        }
    }
}
