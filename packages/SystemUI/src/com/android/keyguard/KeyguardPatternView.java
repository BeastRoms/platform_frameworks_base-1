/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.keyguard;

import static com.android.internal.util.LatencyTracker.ACTION_CHECK_CREDENTIAL;
import static com.android.internal.util.LatencyTracker.ACTION_CHECK_CREDENTIAL_UNLOCKED;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.LinearLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternChecker;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;
import com.android.settingslib.animation.AppearAnimationCreator;
import com.android.settingslib.animation.AppearAnimationUtils;
import com.android.settingslib.animation.DisappearAnimationUtils;

import java.util.List;

public class KeyguardPatternView extends LinearLayout implements KeyguardSecurityView,
        AppearAnimationCreator<LockPatternView.CellState>,
        EmergencyButton.EmergencyButtonCallback {

    private static final String TAG = "SecurityPatternView";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;

    // how long before we clear the wrong pattern
    private static final int PATTERN_CLEAR_TIMEOUT_MS = 2000;

    // how long we stay awake after each key beyond MIN_PATTERN_BEFORE_POKE_WAKELOCK
    private static final int UNLOCK_PATTERN_WAKE_INTERVAL_MS = 7000;

    // how many cells the user has to cross before we poke the wakelock
    private static final int MIN_PATTERN_BEFORE_POKE_WAKELOCK = 2;

    // How much we scale up the duration of the disappear animation when the current user is locked
    public static final float DISAPPEAR_MULTIPLIER_LOCKED = 1.5f;

    // Extra padding, in pixels, that should eat touch events.
    private static final int PATTERNS_TOUCH_AREA_EXTENSION = 40;

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final AppearAnimationUtils mAppearAnimationUtils;
    private final DisappearAnimationUtils mDisappearAnimationUtils;
    private final DisappearAnimationUtils mDisappearAnimationUtilsLocked;
    private final int[] mTmpPosition = new int[2];
    private final Rect mTempRect = new Rect();
    private final Rect mLockPatternScreenBounds = new Rect();

    private CountDownTimer mCountdownTimer = null;
    private LockPatternUtils mLockPatternUtils;
    private AsyncTask<?, ?, ?> mPendingLockCheck;
    private LockPatternView mLockPatternView;
    private KeyguardSecurityCallback mCallback;

    /**
     * Keeps track of the last time we poked the wake lock during dispatching of the touch event.
     * Initialized to something guaranteed to make us poke the wakelock when the user starts
     * drawing the pattern.
     * @see #dispatchTouchEvent(android.view.MotionEvent)
     */
    private long mLastPokeTime = -UNLOCK_PATTERN_WAKE_INTERVAL_MS;

    /**
     * Useful for clearing out the wrong pattern after a delay
     */
    private Runnable mCancelPatternRunnable = new Runnable() {
        @Override
        public void run() {
            mLockPatternView.clearPattern();
        }
    };
    @VisibleForTesting
    KeyguardMessageArea mSecurityMessageDisplay;
    private View mEcaView;
    private ViewGroup mContainer;
    private int mDisappearYTranslation;

    enum FooterMode {
        Normal,
        ForgotLockPattern,
        VerifyUnlocked
    }

    public KeyguardPatternView(Context context) {
        this(context, null);
    }

    public KeyguardPatternView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        mAppearAnimationUtils = new AppearAnimationUtils(context,
                AppearAnimationUtils.DEFAULT_APPEAR_DURATION, 1.5f /* translationScale */,
                2.0f /* delayScale */, AnimationUtils.loadInterpolator(
                        mContext, android.R.interpolator.linear_out_slow_in));
        mDisappearAnimationUtils = new DisappearAnimationUtils(context,
                125, 1.2f /* translationScale */,
                0.6f /* delayScale */, AnimationUtils.loadInterpolator(
                        mContext, android.R.interpolator.fast_out_linear_in));
        mDisappearAnimationUtilsLocked = new DisappearAnimationUtils(context,
                (long) (125 * DISAPPEAR_MULTIPLIER_LOCKED), 1.2f /* translationScale */,
                0.6f /* delayScale */, AnimationUtils.loadInterpolator(
                mContext, android.R.interpolator.fast_out_linear_in));
        mDisappearYTranslation = getResources().getDimensionPixelSize(
                R.dimen.disappear_y_translation);
    }

    @Override
    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    @Override
    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLockPatternUtils = mLockPatternUtils == null
                ? new LockPatternUtils(mContext) : mLockPatternUtils;

        mLockPatternView = findViewById(R.id.lockPatternView);
        mLockPatternView.setSaveEnabled(false);
        mLockPatternView.setOnPatternListener(new UnlockPatternListener());
        mLockPatternView.setInStealthMode(!mLockPatternUtils.isVisiblePatternEnabled(
                KeyguardUpdateMonitor.getCurrentUser()));

        // vibrate mode will be the same for the life of this screen
        mLockPatternView.setTactileFeedbackEnabled(mLockPatternUtils.isTactileFeedbackEnabled());

        mEcaView = findViewById(R.id.keyguard_selector_fade_container);
        mContainer = findViewById(R.id.container);

        EmergencyButton button = findViewById(R.id.emergency_call_button);
        if (button != null) {
            button.setCallback(this);
        }

        View cancelBtn = findViewById(R.id.cancel_button);
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(view -> {
                mCallback.reset();
                mCallback.onCancelClicked();
            });
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mSecurityMessageDisplay = KeyguardMessageArea.findSecurityMessageDisplay(this);
    }

    @Override
    public void onEmergencyButtonClickedWhenInCall() {
        mCallback.reset();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean result = super.onTouchEvent(ev);
        // as long as the user is entering a pattern (i.e sending a touch event that was handled
        // by this screen), keep poking the wake lock so that the screen will stay on.
        final long elapsed = SystemClock.elapsedRealtime() - mLastPokeTime;
        if (result && (elapsed > (UNLOCK_PATTERN_WAKE_INTERVAL_MS - 100))) {
            mLastPokeTime = SystemClock.elapsedRealtime();
        }
        mTempRect.set(0, 0, 0, 0);
        offsetRectIntoDescendantCoords(mLockPatternView, mTempRect);
        ev.offsetLocation(mTempRect.left, mTempRect.top);
        result = mLockPatternView.dispatchTouchEvent(ev) || result;
        ev.offsetLocation(-mTempRect.left, -mTempRect.top);
        return result;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mLockPatternView.getLocationOnScreen(mTmpPosition);
        mLockPatternScreenBounds.set(mTmpPosition[0] - PATTERNS_TOUCH_AREA_EXTENSION,
                mTmpPosition[1] - PATTERNS_TOUCH_AREA_EXTENSION,
                mTmpPosition[0] + mLockPatternView.getWidth() + PATTERNS_TOUCH_AREA_EXTENSION,
                mTmpPosition[1] + mLockPatternView.getHeight() + PATTERNS_TOUCH_AREA_EXTENSION);
    }

    @Override
    public void reset() {
        // reset lock pattern
        mLockPatternView.setInStealthMode(!mLockPatternUtils.isVisiblePatternEnabled(
                KeyguardUpdateMonitor.getCurrentUser()));
        mLockPatternView.enableInput();
        mLockPatternView.setEnabled(true);
        mLockPatternView.clearPattern();

        if (mSecurityMessageDisplay == null) {
            return;
        }

        // if the user is currently locked out, enforce it.
        long deadline = mLockPatternUtils.getLockoutAttemptDeadline(
                KeyguardUpdateMonitor.getCurrentUser());
        if (deadline != 0) {
            handleAttemptLockout(deadline);
        } else {
            displayDefaultSecurityMessage();
        }
    }

    private void displayDefaultSecurityMessage() {
        if (mSecurityMessageDisplay != null) {
            mSecurityMessageDisplay.setMessage("");
        }
    }

    @Override
    public void showUsabilityHint() {
    }

    @Override
    public boolean disallowInterceptTouch(MotionEvent event) {
        return !mLockPatternView.isEmpty()
                || mLockPatternScreenBounds.contains((int) event.getRawX(), (int) event.getRawY());
    }

    /** TODO: hook this up */
    public void cleanUp() {
        if (DEBUG) Log.v(TAG, "Cleanup() called on " + this);
        mLockPatternUtils = null;
        mLockPatternView.setOnPatternListener(null);
    }

    private class UnlockPatternListener implements LockPatternView.OnPatternListener {

        @Override
        public void onPatternStart() {
            mLockPatternView.removeCallbacks(mCancelPatternRunnable);
            mSecurityMessageDisplay.setMessage("");
        }

        @Override
        public void onPatternCleared() {
        }

        @Override
        public void onPatternCellAdded(List<LockPatternView.Cell> pattern) {
            mCallback.userActivity();
        }

        @Override
        public void onPatternDetected(final List<LockPatternView.Cell> pattern) {
            mLockPatternView.disableInput();
            if (mPendingLockCheck != null) {
                mPendingLockCheck.cancel(false);
            }

            final int userId = KeyguardUpdateMonitor.getCurrentUser();
            if (pattern.size() < LockPatternUtils.MIN_PATTERN_REGISTER_FAIL) {
                mLockPatternView.enableInput();
                onPatternChecked(userId, false, 0, false /* not valid - too short */);
                return;
            }

            if (LatencyTracker.isEnabled(mContext)) {
                LatencyTracker.getInstance(mContext).onActionStart(ACTION_CHECK_CREDENTIAL);
                LatencyTracker.getInstance(mContext).onActionStart(ACTION_CHECK_CREDENTIAL_UNLOCKED);
            }
            mPendingLockCheck = LockPatternChecker.checkPattern(
                    mLockPatternUtils,
                    pattern,
                    userId,
                    new LockPatternChecker.OnCheckCallback() {

                        @Override
                        public void onEarlyMatched() {
                            if (LatencyTracker.isEnabled(mContext)) {
                                LatencyTracker.getInstance(mContext).onActionEnd(
                                        ACTION_CHECK_CREDENTIAL);
                            }
                            onPatternChecked(userId, true /* matched */, 0 /* timeoutMs */,
                                    true /* isValidPattern */);
                        }

                        @Override
                        public void onChecked(boolean matched, int timeoutMs) {
                            if (LatencyTracker.isEnabled(mContext)) {
                                LatencyTracker.getInstance(mContext).onActionEnd(
                                        ACTION_CHECK_CREDENTIAL_UNLOCKED);
                            }
                            mLockPatternView.enableInput();
                            mPendingLockCheck = null;
                            if (!matched) {
                                onPatternChecked(userId, false /* matched */, timeoutMs,
                                        true /* isValidPattern */);
                            }
                        }

                        @Override
                        public void onCancelled() {
                            // We already got dismissed with the early matched callback, so we
                            // cancelled the check. However, we still need to note down the latency.
                            if (LatencyTracker.isEnabled(mContext)) {
                                LatencyTracker.getInstance(mContext).onActionEnd(
                                        ACTION_CHECK_CREDENTIAL_UNLOCKED);
                            }
                        }
                    });
            if (pattern.size() > MIN_PATTERN_BEFORE_POKE_WAKELOCK) {
                mCallback.userActivity();
            }
        }

        private void onPatternChecked(int userId, boolean matched, int timeoutMs,
                boolean isValidPattern) {
            boolean dismissKeyguard = KeyguardUpdateMonitor.getCurrentUser() == userId;
            if (matched) {
                mLockPatternUtils.sanitizePassword();
                mCallback.reportUnlockAttempt(userId, true, 0);
                if (dismissKeyguard) {
                    mLockPatternView.setDisplayMode(LockPatternView.DisplayMode.Correct);
                    mCallback.dismiss(true, userId);
                }
            } else {
                mLockPatternView.setDisplayMode(LockPatternView.DisplayMode.Wrong);
                if (isValidPattern) {
                    mCallback.reportUnlockAttempt(userId, false, timeoutMs);
                    if (timeoutMs > 0) {
                        long deadline = mLockPatternUtils.setLockoutAttemptDeadline(
                                userId, timeoutMs);
                        handleAttemptLockout(deadline);
                    }
                }
                if (timeoutMs == 0) {
                    mSecurityMessageDisplay.setMessage(R.string.kg_wrong_pattern);
                    mLockPatternView.postDelayed(mCancelPatternRunnable, PATTERN_CLEAR_TIMEOUT_MS);
                }
            }
        }
    }

    private void handleAttemptLockout(long elapsedRealtimeDeadline) {
        mLockPatternView.clearPattern();
        mLockPatternView.setEnabled(false);
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        final long secondsInFuture = (long) Math.ceil(
                (elapsedRealtimeDeadline - elapsedRealtime) / 1000.0);
        mCountdownTimer = new CountDownTimer(secondsInFuture * 1000, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                final int secondsRemaining = (int) Math.round(millisUntilFinished / 1000.0);
                mSecurityMessageDisplay.setMessage(mContext.getResources().getQuantityString(
                                R.plurals.kg_too_many_failed_attempts_countdown,
                                secondsRemaining, secondsRemaining));
            }

            @Override
            public void onFinish() {
                mLockPatternView.setEnabled(true);
                displayDefaultSecurityMessage();
            }

        }.start();
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void onPause() {
        if (mCountdownTimer != null) {
            mCountdownTimer.cancel();
            mCountdownTimer = null;
        }
        if (mPendingLockCheck != null) {
            mPendingLockCheck.cancel(false);
            mPendingLockCheck = null;
        }
        displayDefaultSecurityMessage();
    }

    @Override
    public void onResume(int reason) {
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void showPromptReason(int reason) {
        switch (reason) {
            case PROMPT_REASON_RESTART:
                mSecurityMessageDisplay.setMessage(R.string.kg_prompt_reason_restart_pattern);
                break;
            case PROMPT_REASON_TIMEOUT:
                mSecurityMessageDisplay.setMessage(R.string.kg_prompt_reason_timeout_pattern);
                break;
            case PROMPT_REASON_DEVICE_ADMIN:
                mSecurityMessageDisplay.setMessage(R.string.kg_prompt_reason_device_admin);
                break;
            case PROMPT_REASON_USER_REQUEST:
                mSecurityMessageDisplay.setMessage(R.string.kg_prompt_reason_user_request);
                break;
            case PROMPT_REASON_NONE:
                break;
            default:
                mSecurityMessageDisplay.setMessage(R.string.kg_prompt_reason_timeout_pattern);
                break;
        }
    }

    @Override
    public void showMessage(CharSequence message, ColorStateList colorState) {
        if (colorState != null) {
            mSecurityMessageDisplay.setNextMessageColor(colorState);
        }
        mSecurityMessageDisplay.setMessage(message);
    }

    @Override
    public void startAppearAnimation() {
        enableClipping(false);
        setAlpha(1f);
        setTranslationY(mAppearAnimationUtils.getStartTranslation());
        AppearAnimationUtils.startTranslationYAnimation(this, 0 /* delay */, 500 /* duration */,
                0, mAppearAnimationUtils.getInterpolator());
        mAppearAnimationUtils.startAnimation2d(
                mLockPatternView.getCellStates(),
                new Runnable() {
                    @Override
                    public void run() {
                        enableClipping(true);
                    }
                },
                this);
        if (!TextUtils.isEmpty(mSecurityMessageDisplay.getText())) {
            mAppearAnimationUtils.createAnimation(mSecurityMessageDisplay, 0,
                    AppearAnimationUtils.DEFAULT_APPEAR_DURATION,
                    mAppearAnimationUtils.getStartTranslation(),
                    true /* appearing */,
                    mAppearAnimationUtils.getInterpolator(),
                    null /* finishRunnable */);
        }
    }

    @Override
    public boolean startDisappearAnimation(final Runnable finishRunnable) {
        float durationMultiplier = mKeyguardUpdateMonitor.needsSlowUnlockTransition()
                ? DISAPPEAR_MULTIPLIER_LOCKED
                : 1f;
        mLockPatternView.clearPattern();
        enableClipping(false);
        setTranslationY(0);
        AppearAnimationUtils.startTranslationYAnimation(this, 0 /* delay */,
                (long) (300 * durationMultiplier),
                -mDisappearAnimationUtils.getStartTranslation(),
                mDisappearAnimationUtils.getInterpolator());

        DisappearAnimationUtils disappearAnimationUtils = mKeyguardUpdateMonitor
                .needsSlowUnlockTransition()
                        ? mDisappearAnimationUtilsLocked
                        : mDisappearAnimationUtils;
        disappearAnimationUtils.startAnimation2d(mLockPatternView.getCellStates(),
                () -> {
                    enableClipping(true);
                    if (finishRunnable != null) {
                        finishRunnable.run();
                    }
                }, KeyguardPatternView.this);
        if (!TextUtils.isEmpty(mSecurityMessageDisplay.getText())) {
            mDisappearAnimationUtils.createAnimation(mSecurityMessageDisplay, 0,
                    (long) (200 * durationMultiplier),
                    - mDisappearAnimationUtils.getStartTranslation() * 3,
                    false /* appearing */,
                    mDisappearAnimationUtils.getInterpolator(),
                    null /* finishRunnable */);
        }
        return true;
    }

    private void enableClipping(boolean enable) {
        setClipChildren(enable);
        mContainer.setClipToPadding(enable);
        mContainer.setClipChildren(enable);
    }

    @Override
    public void createAnimation(final LockPatternView.CellState animatedCell, long delay,
            long duration, float translationY, final boolean appearing,
            Interpolator interpolator,
            final Runnable finishListener) {
        mLockPatternView.startCellStateAnimation(animatedCell,
                1f, appearing ? 1f : 0f, /* alpha */
                appearing ? translationY : 0f, appearing ? 0f : translationY, /* translation */
                appearing ? 0f : 1f, 1f /* scale */,
                delay, duration, interpolator, finishListener);
        if (finishListener != null) {
            // Also animate the Emergency call
            mAppearAnimationUtils.createAnimation(mEcaView, delay, duration, translationY,
                    appearing, interpolator, null);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public CharSequence getTitle() {
        return getContext().getString(
                com.android.internal.R.string.keyguard_accessibility_pattern_unlock);
    }
}
