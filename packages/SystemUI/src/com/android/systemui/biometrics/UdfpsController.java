/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_KEYGUARD;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.systemui.classifier.Classifier.UDFPS_AUTHENTICATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.hardware.biometrics.BiometricOverlayConstants;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.voltage.VoltageUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.Execution;
import com.android.systemui.util.time.SystemClock;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

import kotlin.Unit;

/**
 * Shows and hides the under-display fingerprint sensor (UDFPS) overlay, handles UDFPS touch events,
 * and coordinates triggering of the high-brightness mode (HBM).
 *
 * Note that the current architecture is designed so that a single {@link UdfpsController}
 * controls/manages all UDFPS sensors. In other words, a single controller is registered with
 * {@link com.android.server.biometrics.sensors.fingerprint.FingerprintService}, and interfaces such
 * as {@link FingerprintManager#onPointerDown(int, int, int, float, float)} or
 * {@link IUdfpsOverlayController#showUdfpsOverlay(int)} should all have
 * {@code sensorId} parameters.
 */
@SuppressWarnings("deprecation")
@SysUISingleton
public class UdfpsController implements DozeReceiver {
    private static final String TAG = "UdfpsController";
    private static final String PULSE_ACTION = "com.android.systemui.doze.pulse";
    private static final long AOD_INTERRUPT_TIMEOUT_MILLIS = 1000;
    private static final long DEFAULT_VIBRATION_DURATION = 1000; // milliseconds

    // Minimum required delay between consecutive touch logs in milliseconds.
    private static final long MIN_TOUCH_LOG_INTERVAL = 50;

    private final Context mContext;
    private final ColorDisplayManager mColorDisplayManager;
    private final Execution mExecution;
    private final FingerprintManager mFingerprintManager;
    @NonNull private final LayoutInflater mInflater;
    private final WindowManager mWindowManager;
    private final DelayableExecutor mFgExecutor;
    @NonNull private final PanelExpansionStateManager mPanelExpansionStateManager;
    @NonNull private final StatusBarStateController mStatusBarStateController;
    @NonNull private final KeyguardStateController mKeyguardStateController;
    @NonNull private final StatusBarKeyguardViewManager mKeyguardViewManager;
    @NonNull private final DumpManager mDumpManager;
    @NonNull private final SystemUIDialogManager mDialogManager;
    @NonNull private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Nullable private final Vibrator mVibrator;
    @NonNull private final FalsingManager mFalsingManager;
    @NonNull private final PowerManager mPowerManager;
    @NonNull private final AccessibilityManager mAccessibilityManager;
    @NonNull private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Nullable private final UdfpsHbmProvider mHbmProvider;
    @NonNull private final KeyguardBypassController mKeyguardBypassController;
    @NonNull private final ConfigurationController mConfigurationController;
    @NonNull private final SystemClock mSystemClock;
    @VisibleForTesting @NonNull final BiometricDisplayListener mOrientationListener;
    @NonNull private final UnlockedScreenOffAnimationController
            mUnlockedScreenOffAnimationController;
    // Currently the UdfpsController supports a single UDFPS sensor. If devices have multiple
    // sensors, this, in addition to a lot of the code here, will be updated.
    @VisibleForTesting final FingerprintSensorPropertiesInternal mSensorProps;
    private final WindowManager.LayoutParams mCoreLayoutParams;

    // Tracks the velocity of a touch to help filter out the touches that move too fast.
    @Nullable private VelocityTracker mVelocityTracker;
    // The ID of the pointer for which ACTION_DOWN has occurred. -1 means no pointer is active.
    private int mActivePointerId = -1;
    // The timestamp of the most recent touch log.
    private long mTouchLogTime;
    // Sensor has a good capture for this touch. Do not need to illuminate for this particular
    // touch event anymore. In other words, do not illuminate until user lifts and touches the
    // sensor area again.
    // TODO: We should probably try to make touch/illumination things more of a FSM
    private boolean mGoodCaptureReceived;

    @Nullable private UdfpsView mView;
    // The current request from FingerprintService. Null if no current request.
    @Nullable ServerRequest mServerRequest;

    // The fingerprint AOD trigger doesn't provide an ACTION_UP/ACTION_CANCEL event to tell us when
    // to turn off high brightness mode. To get around this limitation, the state of the AOD
    // interrupt is being tracked and a timeout is used as a last resort to turn off high brightness
    // mode.
    private boolean mIsAodInterruptActive;
    @Nullable private Runnable mCancelAodTimeoutAction;
    private boolean mScreenOn;
    private Runnable mAodInterruptRunnable;
    private boolean mOnFingerDown;
    private boolean mAttemptedToDismissKeyguard;
    private final int mUdfpsVendorCode;
    private Set<Callback> mCallbacks = new HashSet<>();
    private boolean mNightDisplayEnabled;
    private UdfpsAnimation mUdfpsAnimation;
    private boolean mFrameworkDimming;
    private int[][] mBrightnessAlphaArray;

    @VisibleForTesting
    public static final AudioAttributes VIBRATION_SONIFICATION_ATTRIBUTES =
            new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    // vibration will bypass battery saver mode:
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .build();

    // haptic to use for successful device entry
    public static final VibrationEffect EFFECT_CLICK =
            VibrationEffect.get(VibrationEffect.EFFECT_CLICK);

    private final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurnedOn() {
            mScreenOn = true;
            if (mAodInterruptRunnable != null) {
                mAodInterruptRunnable.run();
                mAodInterruptRunnable = null;
            }
        }

        @Override
        public void onScreenTurnedOff() {
            mScreenOn = false;
        }
    };

    /**
     * Keeps track of state within a single FingerprintService request. Note that this state
     * persists across configuration changes, etc, since it is considered a single request.
     *
     * TODO: Perhaps we can move more global variables into here
     */
    private static class ServerRequest {
        // Reason the overlay has been requested. See IUdfpsOverlayController for definitions.
        final int mRequestReason;
        @NonNull final IUdfpsOverlayControllerCallback mCallback;
        @Nullable final UdfpsEnrollHelper mEnrollHelper;

        ServerRequest(int requestReason, @NonNull IUdfpsOverlayControllerCallback callback,
                @Nullable UdfpsEnrollHelper enrollHelper) {
            mRequestReason = requestReason;
            mCallback = callback;
            mEnrollHelper = enrollHelper;
        }

        void onEnrollmentProgress(int remaining) {
            if (mEnrollHelper != null) {
                mEnrollHelper.onEnrollmentProgress(remaining);
            }
        }

        void onAcquiredGood() {
            if (mEnrollHelper != null) {
                mEnrollHelper.animateIfLastStep();
            }
        }

        void onEnrollmentHelp() {
            if (mEnrollHelper != null) {
                mEnrollHelper.onEnrollmentHelp();
            }
        }

        void onUserCanceled() {
            try {
                mCallback.onUserCanceled();
            } catch (RemoteException e) {
                Log.e(TAG, "Remote exception", e);
            }
        }
    }

    public class UdfpsOverlayController extends IUdfpsOverlayController.Stub {
        @Override
        public void showUdfpsOverlay(int sensorId, int reason,
                @NonNull IUdfpsOverlayControllerCallback callback) {
            mFgExecutor.execute(() -> {
                final UdfpsEnrollHelper enrollHelper;
                if (reason == BiometricOverlayConstants.REASON_ENROLL_FIND_SENSOR
                        || reason == BiometricOverlayConstants.REASON_ENROLL_ENROLLING) {
                    enrollHelper = new UdfpsEnrollHelper(mContext, mFingerprintManager, reason);
                } else {
                    enrollHelper = null;
                }
                mServerRequest = new ServerRequest(reason, callback, enrollHelper);
                updateOverlay();
            });
        }

        @Override
        public void hideUdfpsOverlay(int sensorId) {
            mFgExecutor.execute(() -> {
                if (mKeyguardUpdateMonitor.isFingerprintDetectionRunning()) {
                    // if we get here, we expect keyguardUpdateMonitor's fingerprintRunningState
                    // to be updated shortly afterwards
                    Log.d(TAG, "hiding udfps overlay when "
                            + "mKeyguardUpdateMonitor.isFingerprintDetectionRunning()=true");
                }

                mServerRequest = null;
                updateOverlay();
            });
        }

        @Override
        public void onAcquiredGood(int sensorId) {
            mFgExecutor.execute(() -> {
                if (mView == null) {
                    Log.e(TAG, "Null view when onAcquiredGood for sensorId: " + sensorId);
                    return;
                }
                mGoodCaptureReceived = true;
                mView.stopIllumination();
                if (mServerRequest != null) {
                    mServerRequest.onAcquiredGood();
                } else {
                    Log.e(TAG, "Null serverRequest when onAcquiredGood");
                }
            });
        }

        @Override
        public void onEnrollmentProgress(int sensorId, int remaining) {
            mFgExecutor.execute(() -> {
                if (mServerRequest == null) {
                    Log.e(TAG, "onEnrollProgress received but serverRequest is null");
                    return;
                }
                mServerRequest.onEnrollmentProgress(remaining);
            });
        }

        @Override
        public void onEnrollmentHelp(int sensorId) {
            mFgExecutor.execute(() -> {
                if (mServerRequest == null) {
                    Log.e(TAG, "onEnrollmentHelp received but serverRequest is null");
                    return;
                }
                mServerRequest.onEnrollmentHelp();
            });
        }

        @Override
        public void setDebugMessage(int sensorId, String message) {
            mFgExecutor.execute(() -> {
                if (mView == null) {
                    return;
                }
                mView.setDebugMessage(message);
            });
        }

        @Override
        public void onAcquired(int sensorId, int acquiredInfo, int vendorCode) {
            mFgExecutor.execute(() -> {
                if (acquiredInfo == 6 && (mStatusBarStateController.isDozing() || !mScreenOn)) {
                    if (vendorCode == mUdfpsVendorCode) {
                        if (mContext.getResources().getBoolean(R.bool.config_pulseOnFingerDown)) {
                            mContext.sendBroadcastAsUser(new Intent(PULSE_ACTION),
                                    new UserHandle(UserHandle.USER_CURRENT));
                        } else {
                            mPowerManager.wakeUp(mSystemClock.uptimeMillis(),
                                    PowerManager.WAKE_REASON_GESTURE, TAG);
                        }
                        onAodInterrupt(0, 0, 0, 0); // To-Do pass proper values
                    }
                }
            });
        }
    }

    /**
     * Calculate the pointer speed given a velocity tracker and the pointer id.
     * This assumes that the velocity tracker has already been passed all relevant motion events.
     */
    public static float computePointerSpeed(@NonNull VelocityTracker tracker, int pointerId) {
        final float vx = tracker.getXVelocity(pointerId);
        final float vy = tracker.getYVelocity(pointerId);
        return (float) Math.sqrt(Math.pow(vx, 2.0) + Math.pow(vy, 2.0));
    }

    /**
     * Whether the velocity exceeds the acceptable UDFPS debouncing threshold.
     */
    public static boolean exceedsVelocityThreshold(float velocity) {
        return velocity > 750f;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mServerRequest != null
                    && mServerRequest.mRequestReason != REASON_AUTH_KEYGUARD
                    && Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                Log.d(TAG, "ACTION_CLOSE_SYSTEM_DIALOGS received, mRequestReason: "
                        + mServerRequest.mRequestReason);
                mServerRequest.onUserCanceled();
                mServerRequest = null;
                updateOverlay();
            }
        }
    };

    /**
     * Forwards touches to the udfps controller / view
     */
    public boolean onTouch(MotionEvent event) {
        if (mView == null) {
            return false;
        }
        return onTouch(mView, event, false);
    }

    @SuppressLint("ClickableViewAccessibility")
    private final UdfpsView.OnTouchListener mOnTouchListener = (view, event) ->
            onTouch(view, event, true);

    @SuppressLint("ClickableViewAccessibility")
    private final UdfpsView.OnHoverListener mOnHoverListener = (view, event) ->
            onTouch(view, event, true);

    private final AccessibilityManager.TouchExplorationStateChangeListener
            mTouchExplorationStateChangeListener = enabled -> updateTouchListener();

    /**
     * @param x coordinate
     * @param y coordinate
     * @param relativeToUdfpsView true if the coordinates are relative to the udfps view; else,
     *                            calculate from the display dimensions in portrait orientation
     */
    private boolean isWithinSensorArea(UdfpsView udfpsView, float x, float y,
            boolean relativeToUdfpsView) {
        if (relativeToUdfpsView) {
            // TODO: move isWithinSensorArea to UdfpsController.
            return udfpsView.isWithinSensorArea(x, y);
        }

        if (mView == null || mView.getAnimationViewController() == null) {
            return false;
        }

        return !mView.getAnimationViewController().shouldPauseAuth()
                && getSensorLocation().contains(x, y);
    }

    private boolean onTouch(View view, MotionEvent event, boolean fromUdfpsView) {
        UdfpsView udfpsView = (UdfpsView) view;
        final boolean isIlluminationRequested = udfpsView.isIlluminationRequested();
        boolean handled = false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_OUTSIDE:
                udfpsView.onTouchOutsideView();
                return true;
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_HOVER_ENTER:
                Trace.beginSection("UdfpsController.onTouch.ACTION_DOWN");
                // To simplify the lifecycle of the velocity tracker, make sure it's never null
                // after ACTION_DOWN, and always null after ACTION_CANCEL or ACTION_UP.
                if (mVelocityTracker == null) {
                    mVelocityTracker = VelocityTracker.obtain();
                } else {
                    // ACTION_UP or ACTION_CANCEL is not guaranteed to be called before a new
                    // ACTION_DOWN, in that case we should just reuse the old instance.
                    mVelocityTracker.clear();
                }

                boolean withinSensorArea =
                        isWithinSensorArea(udfpsView, event.getX(), event.getY(), fromUdfpsView);
                if (withinSensorArea) {
                    Trace.beginAsyncSection("UdfpsController.e2e.onPointerDown", 0);
                    Log.v(TAG, "onTouch | action down");
                    // The pointer that causes ACTION_DOWN is always at index 0.
                    // We need to persist its ID to track it during ACTION_MOVE that could include
                    // data for many other pointers because of multi-touch support.
                    mActivePointerId = event.getPointerId(0);
                    final int idx = mActivePointerId == -1
                            ? event.getPointerId(0)
                            : event.findPointerIndex(mActivePointerId);
                    mVelocityTracker.addMovement(event);
                    onFingerDown((int) event.getRawX(), (int) event.getRawY(),
                            (int) event.getTouchMinor(idx), (int) event.getTouchMajor(idx));
                    handled = true;
                }
                if ((withinSensorArea || fromUdfpsView) && shouldTryToDismissKeyguard()) {
                    Log.v(TAG, "onTouch | dismiss keyguard ACTION_DOWN");
                    if (!mOnFingerDown) {
                        playStartHaptic();
                    }
                    mKeyguardViewManager.notifyKeyguardAuthenticated(false /* strongAuth */);
                    mAttemptedToDismissKeyguard = true;
                }
                Trace.endSection();
                break;

            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                Trace.beginSection("UdfpsController.onTouch.ACTION_MOVE");
                final int idx = mActivePointerId == -1
                        ? event.getPointerId(0)
                        : event.findPointerIndex(mActivePointerId);
                if (idx == event.getActionIndex()) {
                    boolean actionMoveWithinSensorArea =
                            isWithinSensorArea(udfpsView, event.getX(idx), event.getY(idx),
                                    fromUdfpsView);
                    if ((fromUdfpsView || actionMoveWithinSensorArea)
                            && shouldTryToDismissKeyguard()) {
                        Log.v(TAG, "onTouch | dismiss keyguard ACTION_MOVE");
                        if (!mOnFingerDown) {
                            playStartHaptic();
                        }
                        mKeyguardViewManager.notifyKeyguardAuthenticated(false /* strongAuth */);
                        mAttemptedToDismissKeyguard = true;
                        break;
                    }
                    if (actionMoveWithinSensorArea) {
                        if (mVelocityTracker == null) {
                            // touches could be injected, so the velocity tracker may not have
                            // been initialized (via ACTION_DOWN).
                            mVelocityTracker = VelocityTracker.obtain();
                        }
                        mVelocityTracker.addMovement(event);
                        // Compute pointer velocity in pixels per second.
                        mVelocityTracker.computeCurrentVelocity(1000);
                        // Compute pointer speed from X and Y velocities.
                        final float v = computePointerSpeed(mVelocityTracker, mActivePointerId);
                        final float minor = event.getTouchMinor(idx);
                        final float major = event.getTouchMajor(idx);
                        final boolean exceedsVelocityThreshold = exceedsVelocityThreshold(v);
                        final String touchInfo = String.format(
                                "minor: %.1f, major: %.1f, v: %.1f, exceedsVelocityThreshold: %b",
                                minor, major, v, exceedsVelocityThreshold);
                        final long sinceLastLog = mSystemClock.elapsedRealtime() - mTouchLogTime;
                        if (!isIlluminationRequested && !mGoodCaptureReceived &&
                                !exceedsVelocityThreshold) {
                            onFingerDown((int) event.getRawX(), (int) event.getRawY(), minor,
                                    major);
                            Log.v(TAG, "onTouch | finger down: " + touchInfo);
                            mTouchLogTime = mSystemClock.elapsedRealtime();
                            mPowerManager.userActivity(mSystemClock.uptimeMillis(),
                                    PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
                            handled = true;
                        } else if (sinceLastLog >= MIN_TOUCH_LOG_INTERVAL) {
                            Log.v(TAG, "onTouch | finger move: " + touchInfo);
                            mTouchLogTime = mSystemClock.elapsedRealtime();
                        }
                    } else {
                        Log.v(TAG, "onTouch | finger outside");
                        onFingerUp();
                    }
                }
                Trace.endSection();
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_HOVER_EXIT:
                Trace.beginSection("UdfpsController.onTouch.ACTION_UP");
                mActivePointerId = -1;
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                Log.v(TAG, "onTouch | finger up");
                mAttemptedToDismissKeyguard = false;
                onFingerUp();
                mFalsingManager.isFalseTouch(UDFPS_AUTHENTICATION);
                Trace.endSection();
                break;

            default:
                // Do nothing.
        }
        return handled;
    }

    private boolean shouldTryToDismissKeyguard() {
        return mView.getAnimationViewController() != null
                && mView.getAnimationViewController() instanceof UdfpsKeyguardViewController
                && mKeyguardStateController.canDismissLockScreen()
                && !mAttemptedToDismissKeyguard;
    }

    @Inject
    public UdfpsController(@NonNull Context context,
            @NonNull Execution execution,
            @NonNull LayoutInflater inflater,
            @Nullable FingerprintManager fingerprintManager,
            @NonNull WindowManager windowManager,
            @NonNull StatusBarStateController statusBarStateController,
            @Main DelayableExecutor fgExecutor,
            @NonNull PanelExpansionStateManager panelExpansionStateManager,
            @NonNull StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            @NonNull DumpManager dumpManager,
            @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
            @NonNull FalsingManager falsingManager,
            @NonNull PowerManager powerManager,
            @NonNull AccessibilityManager accessibilityManager,
            @NonNull LockscreenShadeTransitionController lockscreenShadeTransitionController,
            @NonNull ScreenLifecycle screenLifecycle,
            @Nullable Vibrator vibrator,
            @NonNull UdfpsHapticsSimulator udfpsHapticsSimulator,
            @NonNull UdfpsHbmProvider hbmProvider,
            @NonNull KeyguardStateController keyguardStateController,
            @NonNull KeyguardBypassController keyguardBypassController,
            @NonNull DisplayManager displayManager,
            @Main Handler mainHandler,
            @NonNull ConfigurationController configurationController,
            @NonNull SystemClock systemClock,
            @NonNull UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            @NonNull SystemUIDialogManager dialogManager) {
        mContext = context;
        mExecution = execution;
        mVibrator = vibrator;
        mInflater = inflater;
        // The fingerprint manager is queried for UDFPS before this class is constructed, so the
        // fingerprint manager should never be null.
        mFingerprintManager = checkNotNull(fingerprintManager);
        mWindowManager = windowManager;
        mFgExecutor = fgExecutor;
        mPanelExpansionStateManager = panelExpansionStateManager;
        mStatusBarStateController = statusBarStateController;
        mKeyguardStateController = keyguardStateController;
        mKeyguardViewManager = statusBarKeyguardViewManager;
        mDumpManager = dumpManager;
        mDialogManager = dialogManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mFalsingManager = falsingManager;
        mPowerManager = powerManager;
        mAccessibilityManager = accessibilityManager;
        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        mHbmProvider = hbmProvider;
        screenLifecycle.addObserver(mScreenObserver);
        mScreenOn = screenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_ON;
        mKeyguardBypassController = keyguardBypassController;
        mConfigurationController = configurationController;
        mSystemClock = systemClock;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;
        mColorDisplayManager = context.getSystemService(ColorDisplayManager.class);

        mSensorProps = findFirstUdfps();
        // At least one UDFPS sensor exists
        checkArgument(mSensorProps != null);
        mOrientationListener = new BiometricDisplayListener(
                context,
                displayManager,
                mainHandler,
                new BiometricDisplayListener.SensorType.UnderDisplayFingerprint(mSensorProps),
                () -> {
                    onOrientationChanged();
                    return Unit.INSTANCE;
                });

        mCoreLayoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY,
                0 /* flags set in computeLayoutParams() */,
                PixelFormat.TRANSLUCENT);
        mCoreLayoutParams.setTitle(TAG);
        mCoreLayoutParams.setFitInsetsTypes(0);
        mCoreLayoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        mCoreLayoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mCoreLayoutParams.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
        mCoreLayoutParams.dimAmount = 0;

        mFrameworkDimming = mContext.getResources().getBoolean(R.bool.config_udfpsFrameworkDimming);

        parseBrightnessAlphaArray();

        mFingerprintManager.setUdfpsOverlayController(new UdfpsOverlayController());

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(mBroadcastReceiver, filter);

        udfpsHapticsSimulator.setUdfpsController(this);

        mUdfpsVendorCode = mContext.getResources().getInteger(R.integer.config_udfps_vendor_code);

        if (VoltageUtils.isPackageInstalled(mContext, "com.power.hub.udfps.resources")) {
            mUdfpsAnimation = new UdfpsAnimation(mContext, mWindowManager, mSensorProps);
        }
    }

    /**
     * Play haptic to signal udfps scanning started.
     */
    @VisibleForTesting
    public void playStartHaptic() {
        boolean vibrate = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.UDFPS_HAPTIC_FEEDBACK, 1) == 1;
        if (mVibrator != null && vibrate) {
            mVibrator.vibrate(
                    Process.myUid(),
                    mContext.getOpPackageName(),
                    EFFECT_CLICK,
                    "udfps-onStart-click",
                    VIBRATION_SONIFICATION_ATTRIBUTES);
        }
    }

    @Nullable
    private FingerprintSensorPropertiesInternal findFirstUdfps() {
        for (FingerprintSensorPropertiesInternal props :
                mFingerprintManager.getSensorPropertiesInternal()) {
            if (props.isAnyUdfpsType()) {
                return props;
            }
        }
        return null;
    }

    @Override
    public void dozeTimeTick() {
        if (mView != null) {
            mView.dozeTimeTick();
        }
    }

    /**
     * @return where the UDFPS exists on the screen in pixels.
     */
    public RectF getSensorLocation() {
        // This is currently used to calculate the amount of space available for notifications
        // on lockscreen and for the udfps light reveal animation on keyguard.
        // Keyguard is only shown in portrait mode for now, so this will need to
        // be updated if that ever changes.
        final SensorLocationInternal location = mSensorProps.getLocation();
        return new RectF(location.sensorLocationX - location.sensorRadius,
                location.sensorLocationY - location.sensorRadius,
                location.sensorLocationX + location.sensorRadius,
                location.sensorLocationY + location.sensorRadius);
    }

    private void updateOverlay() {
        mExecution.assertIsMainThread();

        if (mServerRequest != null) {
            showUdfpsOverlay(mServerRequest);
        } else {
            hideUdfpsOverlay();
        }
    }

    private boolean shouldRotate(@Nullable UdfpsAnimationViewController animation) {
        if (!(animation instanceof UdfpsKeyguardViewController)) {
            // always rotate view if we're not on the keyguard
            return true;
        }

        // on the keyguard, make sure we don't rotate if we're going to sleep or not occluded
        if (mKeyguardUpdateMonitor.isGoingToSleep() || !mKeyguardStateController.isOccluded()) {
            return false;
        }

        return true;
    }

    private WindowManager.LayoutParams computeLayoutParams(
            @Nullable UdfpsAnimationViewController animation) {
        final int paddingX = animation != null ? animation.getPaddingX() : 0;
        final int paddingY = animation != null ? animation.getPaddingY() : 0;

        mCoreLayoutParams.flags = Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
        if (animation != null && animation.listenForTouchesOutsideView()) {
            mCoreLayoutParams.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        }

        // Default dimensions assume portrait mode.
        final SensorLocationInternal location = mSensorProps.getLocation();
        mCoreLayoutParams.x = location.sensorLocationX - location.sensorRadius - paddingX;
        mCoreLayoutParams.y = location.sensorLocationY - location.sensorRadius - paddingY;
        mCoreLayoutParams.height = 2 * location.sensorRadius + 2 * paddingX;
        mCoreLayoutParams.width = 2 * location.sensorRadius + 2 * paddingY;

        Point p = new Point();
        // Gets the size based on the current rotation of the display.
        mContext.getDisplay().getRealSize(p);

        // Transform dimensions if the device is in landscape mode
        switch (mContext.getDisplay().getRotation()) {
            case Surface.ROTATION_90:
                if (!shouldRotate(animation)) {
                    Log.v(TAG, "skip rotating udfps location ROTATION_90");
                    break;
                } else {
                    Log.v(TAG, "rotate udfps location ROTATION_90");
                }
                mCoreLayoutParams.x = location.sensorLocationY - location.sensorRadius
                        - paddingX;
                mCoreLayoutParams.y = p.y - location.sensorLocationX - location.sensorRadius
                        - paddingY;
                break;

            case Surface.ROTATION_270:
                if (!shouldRotate(animation)) {
                    Log.v(TAG, "skip rotating udfps location ROTATION_270");
                    break;
                } else {
                    Log.v(TAG, "rotate udfps location ROTATION_270");
                }
                mCoreLayoutParams.x = p.x - location.sensorLocationY - location.sensorRadius
                        - paddingX;
                mCoreLayoutParams.y = location.sensorLocationX - location.sensorRadius
                        - paddingY;
                break;

            default:
                // Do nothing to stay in portrait mode.
                // Keyguard is always in portrait mode.
        }
        // avoid announcing window title
        mCoreLayoutParams.accessibilityTitle = " ";
        return mCoreLayoutParams;
    }


    private void onOrientationChanged() {
        // When the configuration changes it's almost always necessary to destroy and re-create
        // the overlay's window to pass it the new LayoutParams.
        // Hiding the overlay will destroy its window. It's safe to hide the overlay regardless
        // of whether it is already hidden.
        final boolean wasShowingAltAuth = mKeyguardViewManager.isShowingAlternateAuth();
        hideUdfpsOverlay();

        // If the overlay needs to be shown, this will re-create and show the overlay with the
        // updated LayoutParams. Otherwise, the overlay will remain hidden.
        updateOverlay();
        if (wasShowingAltAuth) {
            mKeyguardViewManager.showGenericBouncer(true);
        }
    }

    private void showUdfpsOverlay(@NonNull ServerRequest request) {
        mNightDisplayEnabled = mColorDisplayManager.isNightDisplayActivated();
        if (mNightDisplayEnabled) {
            mColorDisplayManager.setNightDisplayActivated(false);
        }
        mExecution.assertIsMainThread();

        final int reason = request.mRequestReason;

        if (mUdfpsAnimation != null) {
            mUdfpsAnimation.setIsKeyguard(reason ==
                    BiometricOverlayConstants.REASON_AUTH_KEYGUARD);
        }

        if (mView == null) {
            try {
                Log.v(TAG, "showUdfpsOverlay | adding window reason=" + reason);

                mView = (UdfpsView) mInflater.inflate(R.layout.udfps_view, null, false);
                mOnFingerDown = false;
                mView.setSensorProperties(mSensorProps);
                mView.setHbmProvider(mHbmProvider);
                UdfpsAnimationViewController<?> animation = inflateUdfpsAnimation(reason);
                mAttemptedToDismissKeyguard = false;
                if (animation != null) {
                    animation.init();
                    mView.setAnimationViewController(animation);
                }
                mOrientationListener.enable();

                // This view overlaps the sensor area, so prevent it from being selectable
                // during a11y.
                if (reason == BiometricOverlayConstants.REASON_ENROLL_FIND_SENSOR
                        || reason == BiometricOverlayConstants.REASON_ENROLL_ENROLLING
                        || reason == BiometricOverlayConstants.REASON_AUTH_BP) {
                    mView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                }

                mWindowManager.addView(mView, computeLayoutParams(animation));
                mAccessibilityManager.addTouchExplorationStateChangeListener(
                        mTouchExplorationStateChangeListener);
                updateTouchListener();
            } catch (RuntimeException e) {
                Log.e(TAG, "showUdfpsOverlay | failed to add window", e);
            }
        } else {
            Log.v(TAG, "showUdfpsOverlay | the overlay is already showing");
        }
    }

    @Nullable
    private UdfpsAnimationViewController<?> inflateUdfpsAnimation(int reason) {
        switch (reason) {
            case BiometricOverlayConstants.REASON_ENROLL_FIND_SENSOR:
            case BiometricOverlayConstants.REASON_ENROLL_ENROLLING:
                UdfpsEnrollView enrollView = (UdfpsEnrollView) mInflater.inflate(
                        R.layout.udfps_enroll_view, null);
                mView.addView(enrollView);
                enrollView.updateSensorLocation(mSensorProps);
                return new UdfpsEnrollViewController(
                        enrollView,
                        mServerRequest.mEnrollHelper,
                        mStatusBarStateController,
                        mPanelExpansionStateManager,
                        mDialogManager,
                        mDumpManager
                );
            case BiometricOverlayConstants.REASON_AUTH_KEYGUARD:
                UdfpsKeyguardView keyguardView = (UdfpsKeyguardView)
                        mInflater.inflate(R.layout.udfps_keyguard_view, null);
                mView.addView(keyguardView);
                return new UdfpsKeyguardViewController(
                        keyguardView,
                        mStatusBarStateController,
                        mPanelExpansionStateManager,
                        mKeyguardViewManager,
                        mKeyguardUpdateMonitor,
                        mDumpManager,
                        mLockscreenShadeTransitionController,
                        mConfigurationController,
                        mSystemClock,
                        mKeyguardStateController,
                        mUnlockedScreenOffAnimationController,
                        mDialogManager,
                        this
                );
            case BiometricOverlayConstants.REASON_AUTH_BP:
                // note: empty controller, currently shows no visual affordance
                UdfpsBpView bpView = (UdfpsBpView) mInflater.inflate(R.layout.udfps_bp_view, null);
                mView.addView(bpView);
                return new UdfpsBpViewController(
                        bpView,
                        mStatusBarStateController,
                        mPanelExpansionStateManager,
                        mDialogManager,
                        mDumpManager
                );
            case BiometricOverlayConstants.REASON_AUTH_OTHER:
            case BiometricOverlayConstants.REASON_AUTH_SETTINGS:
                UdfpsFpmOtherView authOtherView = (UdfpsFpmOtherView)
                        mInflater.inflate(R.layout.udfps_fpm_other_view, null);
                mView.addView(authOtherView);
                return new UdfpsFpmOtherViewController(
                        authOtherView,
                        mStatusBarStateController,
                        mPanelExpansionStateManager,
                        mDialogManager,
                        mDumpManager
                );
            default:
                Log.e(TAG, "Animation for reason " + reason + " not supported yet");
                return null;
        }
    }

    private void hideUdfpsOverlay() {
        if (mNightDisplayEnabled) {
            mColorDisplayManager.setNightDisplayActivated(true);
        }
        mExecution.assertIsMainThread();

        if (mView != null) {
            Log.v(TAG, "hideUdfpsOverlay | removing window");
            // Reset the controller back to its starting state.
            onFingerUp();
            boolean wasShowingAltAuth = mKeyguardViewManager.isShowingAlternateAuth();
            mWindowManager.removeView(mView);
            mView.setOnTouchListener(null);
            mView.setOnHoverListener(null);
            mView.setAnimationViewController(null);
            if (wasShowingAltAuth) {
                mKeyguardViewManager.resetAlternateAuth(true);
            }
            mAccessibilityManager.removeTouchExplorationStateChangeListener(
                    mTouchExplorationStateChangeListener);
            mView = null;
        } else {
            Log.v(TAG, "hideUdfpsOverlay | the overlay is already hidden");
        }

        mOrientationListener.disable();
    }

    /**
     * Request fingerprint scan.
     *
     * This is intended to be called in response to a sensor that triggers an AOD interrupt for the
     * fingerprint sensor.
     */
    void onAodInterrupt(int screenX, int screenY, float major, float minor) {
        if (mIsAodInterruptActive) {
            return;
        }

        if (!mKeyguardUpdateMonitor.isFingerprintDetectionRunning()) {
            mKeyguardViewManager.showBouncer(true);
            return;
        }

        mAodInterruptRunnable = () -> {
            mIsAodInterruptActive = true;
            // Since the sensor that triggers the AOD interrupt doesn't provide
            // ACTION_UP/ACTION_CANCEL,  we need to be careful about not letting the screen
            // accidentally remain in high brightness mode. As a mitigation, queue a call to
            // cancel the fingerprint scan.
            mCancelAodTimeoutAction = mFgExecutor.executeDelayed(this::onCancelUdfps,
                    AOD_INTERRUPT_TIMEOUT_MILLIS);
            // using a hard-coded value for major and minor until it is available from the sensor
            onFingerDown(screenX, screenY, minor, major);
        };

        if (mScreenOn && mAodInterruptRunnable != null) {
            mAodInterruptRunnable.run();
            mAodInterruptRunnable = null;
        }
    }

    /**
     * Add a callback for fingerUp and fingerDown events
     */
    public void addCallback(Callback cb) {
        mCallbacks.add(cb);
    }

    /**
     * Remove callback
     */
    public void removeCallback(Callback cb) {
        mCallbacks.remove(cb);
    }

    /**
     * Cancel updfs scan affordances - ability to hide the HbmSurfaceView (white circle) before
     * user explicitly lifts their finger. Generally, this should be called whenever udfps fails
     * or errors.
     *
     * The sensor that triggers an AOD fingerprint interrupt (see onAodInterrupt) doesn't give
     * ACTION_UP/ACTION_CANCEL events, so and AOD interrupt scan needs to be cancelled manually.
     * This should be called when authentication either succeeds or fails. Failing to cancel the
     * scan will leave the screen in high brightness mode and will show the HbmSurfaceView until
     * the user lifts their finger.
     */
    void onCancelUdfps() {
        onFingerUp();
        if (!mIsAodInterruptActive) {
            return;
        }
        if (mCancelAodTimeoutAction != null) {
            mCancelAodTimeoutAction.run();
            mCancelAodTimeoutAction = null;
        }
        mIsAodInterruptActive = false;
    }

    public boolean isFingerDown() {
        return mOnFingerDown;
    }

    private void onFingerDown(int x, int y, float minor, float major) {
        mExecution.assertIsMainThread();
        if (mView == null) {
            Log.w(TAG, "Null view in onFingerDown");
            return;
        }

        updateViewDimAmount(true);

        if (mView.getAnimationViewController() instanceof UdfpsKeyguardViewController
                && !mStatusBarStateController.isDozing()) {
            mKeyguardBypassController.setUserHasDeviceEntryIntent(true);
        }

        if (!mOnFingerDown) {
            playStartHaptic();

            if (!mKeyguardUpdateMonitor.isFaceDetectionRunning()) {
                mKeyguardUpdateMonitor.requestFaceAuth(/* userInitiatedRequest */ false);
            }
        }
        mOnFingerDown = true;
        mFingerprintManager.onPointerDown(mSensorProps.sensorId, x, y, minor, major);
        Trace.endAsyncSection("UdfpsController.e2e.onPointerDown", 0);
        Trace.beginAsyncSection("UdfpsController.e2e.startIllumination", 0);
        mView.startIllumination(() -> {
            mFingerprintManager.onUiReady(mSensorProps.sensorId);
            Trace.endAsyncSection("UdfpsController.e2e.startIllumination", 0);
        });

        for (Callback cb : mCallbacks) {
            cb.onFingerDown();
        }
        if (mUdfpsAnimation != null) {
            mUdfpsAnimation.show();
        }
    }

    private void onFingerUp() {
        mExecution.assertIsMainThread();
        mActivePointerId = -1;
        mGoodCaptureReceived = false;
        if (mView == null) {
            Log.w(TAG, "Null view in onFingerUp");
            return;
        }
        if (mOnFingerDown) {
            mFingerprintManager.onPointerUp(mSensorProps.sensorId);
            for (Callback cb : mCallbacks) {
                cb.onFingerUp();
            }
        }
        if (mUdfpsAnimation != null) {
            mUdfpsAnimation.hide();
        }
        mOnFingerDown = false;
        if (mView.isIlluminationRequested()) {
            mView.stopIllumination();
        }
        updateViewDimAmount(false);
    }

    private void updateTouchListener() {
        if (mView == null) {
            return;
        }

        if (mAccessibilityManager.isTouchExplorationEnabled()) {
            mView.setOnHoverListener(mOnHoverListener);
            mView.setOnTouchListener(null);
        } else {
            mView.setOnHoverListener(null);
            mView.setOnTouchListener(mOnTouchListener);
        }
    }

    private static int interpolate(int x, int xa, int xb, int ya, int yb) {
        return ya - (ya - yb) * (x - xa) / (xb - xa);
    }

    private void updateViewDimAmount(boolean pressed) {
        if (mFrameworkDimming) {
            if (pressed) {
                int curBrightness = Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, 100);
                int i, dimAmount;
                for (i = 0; i < mBrightnessAlphaArray.length; i++) {
                    if (mBrightnessAlphaArray[i][0] >= curBrightness) break;
                }
                if (i == 0) {
                    dimAmount = mBrightnessAlphaArray[i][1];
                } else if (i == mBrightnessAlphaArray.length) {
                    dimAmount = mBrightnessAlphaArray[i-1][1];
                } else {
                    dimAmount = interpolate(curBrightness,
                            mBrightnessAlphaArray[i][0], mBrightnessAlphaArray[i-1][0],
                            mBrightnessAlphaArray[i][1], mBrightnessAlphaArray[i-1][1]);
                }
                mCoreLayoutParams.dimAmount = dimAmount / 255.0f;
            } else {
                mCoreLayoutParams.dimAmount = 0;
            }
            mWindowManager.updateViewLayout(mView, mCoreLayoutParams);
        }
    }

    private void parseBrightnessAlphaArray() {
        if (mFrameworkDimming) {
            String[] array = mContext.getResources().getStringArray(
                    R.array.config_udfpsDimmingBrightnessAlphaArray);
            mBrightnessAlphaArray = new int[array.length][2];
            for (int i = 0; i < array.length; i++) {
                String[] s = array[i].split(",");
                mBrightnessAlphaArray[i][0] = Integer.parseInt(s[0]);
                mBrightnessAlphaArray[i][1] = Integer.parseInt(s[1]);
            }
        }
    }

    /**
     * Callback for fingerUp and fingerDown events.
     */
    public interface Callback {
        /**
         * Called onFingerUp events. Will only be called if the finger was previously down.
         */
        void onFingerUp();

        /**
         * Called onFingerDown events.
         */
        void onFingerDown();
    }
}
