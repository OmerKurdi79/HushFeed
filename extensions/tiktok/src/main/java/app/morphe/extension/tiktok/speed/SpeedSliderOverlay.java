package app.morphe.extension.tiktok.speed;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Interactive horizontal speed scrubber HUD for long-press hold.
 * Allows scrubbing between customizable speeds in real time and pulling down to lock or toggle normal.
 */
public final class SpeedSliderOverlay {
    private static volatile boolean holding = false;
    private static volatile boolean currentlyLocked = false;
    private static boolean wasLockedBeforeGesture = false;
    private static boolean draggedSide = false;
    private static boolean isVerticalScrubbing = false;

    private static float startX = 0f;
    private static float startY = 0f;
    private static float lastScrubY = 0f;
    private static int currentIndex = 0;
    private static float lastHoldSpeed = 2.0f;

    private static List<Float> speedSteps = new ArrayList<>();
    private static WeakReference<SpeedSliderView> viewReference = new WeakReference<>(null);
    private static WeakReference<Window> interceptedWindow = new WeakReference<>(null);
    private static Window.Callback originalWindowCallback = null;

    private SpeedSliderOverlay() {}

    public static boolean isHolding() {
        return holding;
    }

    public static void onNewVideo() {
        if (holding) {
            // Active gesture in progress or starting; do not clobber holding flag!
            return;
        }
        if (!Settings.REMEMBER_SPEED.get()) {
            currentlyLocked = false;
            wasLockedBeforeGesture = false;
        }
        draggedSide = false;
        isVerticalScrubbing = false;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            removeOverlay();
        } else {
            Utils.runOnMainThread(() -> {
                if (!holding) removeOverlay();
            });
        }
    }

    public static boolean suppressNativeBanner() {
        return Settings.HOLD_SPEED_SLIDER_ENABLED.get();
    }

    /**
     * Intercepts TikTok's native release method parameter (Z - isLocked).
     */
    public static boolean overrideReleaseLocked(boolean nativeLocked) {
        if (!Settings.HOLD_SPEED_SLIDER_ENABLED.get()) {
            return nativeLocked;
        }
        return false;
    }

    public static List<Float> parseSpeedSteps(String config) {
        List<Float> list = new ArrayList<>();
        if (config != null && !config.trim().isEmpty()) {
            String[] parts = config.split("[,\\s]+");
            for (String p : parts) {
                try {
                    float val = Float.parseFloat(p.trim().replace("x", "").replace("X", ""));
                    if (val >= 0.1f && val <= 10.0f && !list.contains(val)) {
                        list.add(val);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        if (list.isEmpty()) {
            list.add(0.5f);
            list.add(1.0f);
            list.add(1.5f);
            list.add(2.0f);
            list.add(2.5f);
            list.add(3.0f);
        }
        Collections.sort(list);
        return list;
    }

    public static void onHoldPress(float x, float y) {
        if (!Settings.HOLD_SPEED_SLIDER_ENABLED.get()) return;

        Runnable setup = () -> {
            try {
                Activity activity = Utils.getVisibleActivity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

                speedSteps = parseSpeedSteps(Settings.HOLD_SPEED_SLIDER_STEPS.get());

                // Determine start speed (from last scrubbed speed or 2.0x default)
                float remembered = lastHoldSpeed;

                // Find closest matching index
                int bestIdx = 0;
                float minDiff = Float.MAX_VALUE;
                for (int i = 0; i < speedSteps.size(); i++) {
                    float diff = Math.abs(speedSteps.get(i) - remembered);
                    if (diff < minDiff) {
                        minDiff = diff;
                        bestIdx = i;
                    }
                }

                startX = x;
                startY = y;
                lastScrubY = y;
                currentIndex = bestIdx;
                draggedSide = false;
                isVerticalScrubbing = false;
                wasLockedBeforeGesture = currentlyLocked;

                float selectedSpeed = speedSteps.get(currentIndex);
                PlaybackSpeedPatch.setLiveSpeed(selectedSpeed);

                // Set holding flag AFTER setLiveSpeed so beginVideo() cannot clobber it
                holding = true;

                showOverlay(activity, speedSteps, currentIndex, wasLockedBeforeGesture);
            } catch (Throwable t) {
                Logger.printException(() -> "Could not initialize hold speed slider", t);
            }
        };

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            setup.run();
        } else {
            Utils.runOnMainThread(setup);
        }
    }

    public static void onDispatchTouch(MotionEvent event) {
        if (!holding || event == null) return;

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            float curX = event.getX();
            float curY = event.getY();
            float dxTotal = curX - startX;
            float dyTotal = curY - startY;

            Activity activity = Utils.getVisibleActivity();
            if (activity == null) return;
            float density = activity.getResources().getDisplayMetrics().density;

            // 1. Horizontal slide to Lock / Unlock (swiping left or right past threshold)
            float lockThreshold = 38f * density;
            if (Math.abs(dxTotal) > lockThreshold && !draggedSide) {
                draggedSide = true;
                SpeedSliderView view = viewReference.get();

                if (wasLockedBeforeGesture) {
                    // Swiping sideways while already locked unlocks and resets back to normal 1.0x
                    currentlyLocked = false;
                    PlaybackSpeedPatch.setLiveSpeed(1.0f);
                    if (Settings.REMEMBER_SPEED.get()) {
                        PlaybackSpeedPatch.rememberPlaybackSpeed(1.0f, "long_press");
                    }
                    if (view != null) {
                        view.setLockedState(false, true);
                    }
                } else {
                    // Lock current speed
                    currentlyLocked = true;
                    if (Settings.REMEMBER_SPEED.get() && speedSteps != null && currentIndex >= 0 && currentIndex < speedSteps.size()) {
                        PlaybackSpeedPatch.rememberPlaybackSpeed(speedSteps.get(currentIndex), "long_press");
                    }
                    if (view != null) {
                        view.setLockedState(true, false);
                    }
                }
            }

            // 2. Vertical scrub (pulling UP for faster, DOWN for slower)
            if (!draggedSide) {
                if (!isVerticalScrubbing && Math.abs(dyTotal) > 12f * density) {
                    isVerticalScrubbing = true;
                    lastScrubY = curY;
                    SpeedSliderView view = viewReference.get();
                    if (view != null) {
                        view.setExpandedMode(true);
                    }
                }

                if (isVerticalScrubbing) {
                    float scrubDelta = curY - lastScrubY;
                    float stepThreshold = 26f * density;

                    // Pulling UP (negative dy) = faster (increase index)
                    // Pulling DOWN (positive dy) = slower (decrease index)
                    if (Math.abs(scrubDelta) >= stepThreshold) {
                        int stepCount = (int) (-scrubDelta / stepThreshold);
                        int newIdx = Math.max(0, Math.min(speedSteps.size() - 1, currentIndex + stepCount));

                        if (newIdx != currentIndex) {
                            currentIndex = newIdx;
                            float speed = speedSteps.get(currentIndex);
                            PlaybackSpeedPatch.setLiveSpeed(speed);

                            lastHoldSpeed = speed;

                            SpeedSliderView view = viewReference.get();
                            if (view != null) {
                                view.setCurrentIndex(currentIndex);
                            }
                        }
                        lastScrubY -= stepCount * stepThreshold;
                    }
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            onHoldEnd();
        }
    }

    public static void onHoldRelease(boolean nativeLocked) {
        onHoldEnd();
    }

    private static void onHoldEnd() {
        SpeedSliderView view = viewReference.get();
        if (!holding && view == null) return;
        holding = false;

        // If user didn't swipe sideways during this gesture, lock state must NOT change
        if (!draggedSide) {
            currentlyLocked = wasLockedBeforeGesture;
        }

        Utils.runOnMainThread(() -> {
            detachTouchInterceptor();
            dismissNativeBanners(Utils.getVisibleActivity());
            SpeedSliderView activeView = viewReference.get();
            if (currentlyLocked) {
                // Keep playing at chosen speed; actively enforce to avoid any native reset
                if (speedSteps != null && currentIndex >= 0 && currentIndex < speedSteps.size()) {
                    float chosen = speedSteps.get(currentIndex);
                    PlaybackSpeedPatch.setLiveSpeed(chosen);
                    lastHoldSpeed = chosen;

                    if (Settings.REMEMBER_SPEED.get()) {
                        PlaybackSpeedPatch.rememberPlaybackSpeed(chosen, "long_press");
                    }

                    if (activeView != null) {
                        activeView.postDelayed(() -> PlaybackSpeedPatch.setLiveSpeed(chosen), 50);
                        activeView.postDelayed(() -> PlaybackSpeedPatch.setLiveSpeed(chosen), 150);
                        activeView.postDelayed(() -> PlaybackSpeedPatch.setLiveSpeed(chosen), 300);
                        activeView.postDelayed(() -> dismissNativeBanners(Utils.getVisibleActivity()), 100);
                        activeView.postDelayed(() -> dismissNativeBanners(Utils.getVisibleActivity()), 300);
                    }
                }

                if (activeView != null) {
                    activeView.animate()
                        .alpha(0f)
                        .setDuration(250)
                        .setStartDelay(500)
                        .withEndAction(SpeedSliderOverlay::removeOverlay)
                        .start();
                } else {
                    removeOverlay();
                }
            } else {
                // Not locked: immediately return to normal 1.0x playback
                PlaybackSpeedPatch.setLiveSpeed(1.0f);
                if (activeView != null) {
                    activeView.postDelayed(() -> PlaybackSpeedPatch.setLiveSpeed(1.0f), 50);
                    activeView.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction(SpeedSliderOverlay::removeOverlay)
                        .start();
                } else {
                    removeOverlay();
                }
            }
        });
    }

    private static void attachTouchInterceptor(Activity activity) {
        try {
            if (activity == null) return;
            Window window = activity.getWindow();
            if (window == null) return;

            if (originalWindowCallback != null && interceptedWindow.get() == window) {
                return;
            }

            detachTouchInterceptor();

            Window.Callback original = window.getCallback();
            if (original == null) return;

            originalWindowCallback = original;
            interceptedWindow = new WeakReference<>(window);

            Window.Callback proxy = (Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class},
                (proxyObj, method, args) -> {
                    if ("dispatchTouchEvent".equals(method.getName()) && args != null && args.length > 0) {
                        if (args[0] instanceof MotionEvent) {
                            onDispatchTouch((MotionEvent) args[0]);
                        }
                    }
                    return method.invoke(original, args);
                }
            );

            window.setCallback(proxy);
        } catch (Throwable t) {
            Logger.printException(() -> "Could not attach window touch interceptor", t);
        }
    }

    private static void detachTouchInterceptor() {
        try {
            Window window = interceptedWindow.get();
            if (window != null && originalWindowCallback != null) {
                window.setCallback(originalWindowCallback);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Could not detach window touch interceptor", t);
        } finally {
            originalWindowCallback = null;
            interceptedWindow.clear();
        }
    }

    private static void showOverlay(Activity activity, List<Float> speeds, int activeIdx, boolean locked) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return;

        detachTouchInterceptor();
        SpeedSliderView oldView = viewReference.get();
        if (oldView != null) {
            ViewGroup p = (ViewGroup) oldView.getParent();
            if (p != null) p.removeView(oldView);
            viewReference.clear();
        }
        dismissNativeBanners(activity);
        attachTouchInterceptor(activity);

        SpeedSliderView view = new SpeedSliderView(activity, speeds, activeIdx, locked);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;

        boolean isLandscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        float marginRatio = isLandscape ? 0.08f : 0.14f;
        lp.topMargin = (int) (activity.getResources().getDisplayMetrics().heightPixels * marginRatio);

        view.setLayoutParams(lp);
        view.setAlpha(0f);
        root.addView(view);
        viewReference = new WeakReference<>(view);

        view.animate()
            .alpha(1f)
            .setDuration(150)
            .start();
    }

    private static void removeOverlay() {
        if (holding) return; // NEVER remove overlay while user is actively holding!
        detachTouchInterceptor();
        SpeedSliderView view = viewReference.get();
        if (view != null) {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) {
                parent.removeView(view);
            }
            viewReference.clear();
        }
    }

    public static void dismissNativeBanners(Activity activity) {
        if (activity == null) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor instanceof ViewGroup) {
                ViewGroup root = (ViewGroup) decor;
                hideBannersRecursively(root);
                restoreBottomTabs(root);
            }
        } catch (Throwable ignored) {}
    }

    private static void restoreBottomTabs(ViewGroup root) {
        if (root == null) return;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == null) continue;
            if (child.getId() != View.NO_ID) {
                try {
                    String name = child.getResources().getResourceEntryName(child.getId()).toLowerCase();
                    if (name.contains("bottom") || name.contains("tab") || name.contains("navigation")) {
                        child.setEnabled(true);
                        child.setClickable(true);
                    }
                } catch (Throwable ignored) {}
            }
            if (child instanceof ViewGroup) {
                restoreBottomTabs((ViewGroup) child);
            }
        }
    }

    private static void hideBannersRecursively(ViewGroup parent) {
        if (parent == null) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == null) continue;
            if (child instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) child;
                CharSequence cs = tv.getText();
                if (cs != null) {
                    String s = cs.toString().toLowerCase();
                    if (s.contains("اسحب") || s.contains("pull down") || s.contains("swipe down")
                            || s.contains("السرعة") || s.contains("speed:") || s.contains("قفل")
                            || s.contains("locked at") || s.contains("playing at")) {
                        View container = (child.getParent() instanceof View) ? (View) child.getParent() : child;
                        int h = container.getHeight();
                        if (h <= 0 || h < 450) {
                            container.setVisibility(View.GONE);
                            container.setClickable(false);
                            container.setFocusable(false);
                            container.setEnabled(false);
                            if (container.getParent() instanceof ViewGroup) {
                                ViewGroup p = (ViewGroup) container.getParent();
                                if (p.getHeight() < 450) {
                                    p.setClickable(false);
                                    p.setFocusable(false);
                                }
                            }
                        }
                    }
                }
            } else if (child instanceof ViewGroup) {
                hideBannersRecursively((ViewGroup) child);
            }
        }
    }

    /**
     * Custom lightweight overlay view:
     * - Displays as a compact ghost pill by default on hold.
     * - Smoothly expands to reveal vertical speed dial when pulling up/down.
     * - Side-slide toggles lock state.
     */
    private static class SpeedSliderView extends View {
        private final List<Float> speeds;
        private int currentIdx;
        private boolean isLocked;
        private boolean isResetNormal = false;
        private boolean isExpandedMode = false;
        private final float density;

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeDotOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeDotInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        public SpeedSliderView(Context context, List<Float> speeds, int initialIdx, boolean locked) {
            super(context);
            this.speeds = speeds;
            this.currentIdx = initialIdx;
            this.isLocked = locked;
            this.density = getResources().getDisplayMetrics().density;

            bgPaint.setColor(Color.argb(185, 16, 18, 24)); // Frosted dark obsidian
            bgPaint.setStyle(Paint.Style.FILL);

            borderPaint.setColor(Color.argb(45, 255, 255, 255));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(1f * density);

            linePaint.setColor(Color.argb(55, 255, 255, 255));
            linePaint.setStrokeWidth(1.5f * density);
            linePaint.setStrokeCap(Paint.Cap.ROUND);

            textPaint.setColor(Color.argb(150, 255, 255, 255));
            textPaint.setTextSize(12f * density);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

            activeTextPaint.setColor(Color.parseColor("#3897F0"));
            activeTextPaint.setTextSize(13.5f * density);
            activeTextPaint.setTextAlign(Paint.Align.CENTER);
            activeTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            pillTextPaint.setColor(Color.WHITE);
            pillTextPaint.setTextSize(13.5f * density);
            pillTextPaint.setTextAlign(Paint.Align.CENTER);
            pillTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            dotPaint.setColor(Color.argb(140, 255, 255, 255));
            dotPaint.setStyle(Paint.Style.FILL);

            activeDotOuterPaint.setColor(Color.parseColor("#3897F0"));
            activeDotOuterPaint.setStyle(Paint.Style.FILL);

            activeDotInnerPaint.setColor(Color.WHITE);
            activeDotInnerPaint.setStyle(Paint.Style.FILL);
        }

        public void setExpandedMode(boolean expanded) {
            if (this.isExpandedMode != expanded) {
                this.isExpandedMode = expanded;
                requestLayout();
                invalidate();
            }
        }

        public void setCurrentIndex(int idx) {
            this.currentIdx = idx;
            this.isResetNormal = false;
            invalidate();
        }

        public void setLockedState(boolean locked, boolean resetNormal) {
            this.isLocked = locked;
            this.isResetNormal = resetNormal;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (!isExpandedMode) {
                // Minimalist compact pill: only speed number & lock icon
                int pillWidth = isLocked ? (int) (96f * density) : (int) (76f * density);
                int pillHeight = (int) (32f * density);
                setMeasuredDimension(pillWidth, pillHeight);
            } else {
                // Vertical sleek dial: slim width, dynamic height based on steps
                int count = Math.max(2, speeds.size());
                float itemHeight = 26f * density;
                int height = (int) (count * itemHeight + 20f * density);
                int width = isLocked ? (int) (86f * density) : (int) (70f * density);
                setMeasuredDimension(width, height);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();
            float radius = isExpandedMode ? 18f * density : (h / 2f);

            bounds.set(borderPaint.getStrokeWidth(), borderPaint.getStrokeWidth(),
                       w - borderPaint.getStrokeWidth(), h - borderPaint.getStrokeWidth());

            // 1. Sleek frosted glass card
            canvas.drawRoundRect(bounds, radius, radius, bgPaint);
            if (isLocked) {
                borderPaint.setColor(Color.parseColor("#3897F0"));
                borderPaint.setStrokeWidth(1.5f * density);
            } else {
                borderPaint.setColor(Color.argb(45, 255, 255, 255));
                borderPaint.setStrokeWidth(1f * density);
            }
            canvas.drawRoundRect(bounds, radius, radius, borderPaint);

            float currentSpeedVal = (speeds != null && currentIdx < speeds.size()) ? speeds.get(currentIdx) : 2.0f;
            String speedStr = PlaybackSpeedPatch.speedLabel(currentSpeedVal) + "x";

            if (!isExpandedMode) {
                // Minimalist pill: just the speed number, lock icon if locked
                String pillText;
                if (isResetNormal) {
                    pillTextPaint.setColor(Color.parseColor("#FFCC00"));
                    pillText = "1.0x";
                } else if (isLocked) {
                    pillTextPaint.setColor(Color.parseColor("#3897F0"));
                    pillText = "🔒 " + speedStr;
                } else {
                    pillTextPaint.setColor(Color.WHITE);
                    pillText = speedStr;
                }
                canvas.drawText(pillText, w / 2f, (h / 2f) + (4.5f * density), pillTextPaint);
                return;
            }

            // Expanded vertical dial: sleek vertical rail, higher speeds at top, lower at bottom
            if (speeds == null || speeds.isEmpty()) return;

            int count = speeds.size();
            float itemHeight = 26f * density;
            float topPadding = 18f * density;
            float railX = 18f * density;
            float textX = isLocked ? (w / 2f + 4f * density) : (w / 2f + 4f * density);

            // Vertical track line connecting dots
            float startY = topPadding;
            float endY = topPadding + ((count - 1) * itemHeight);
            canvas.drawLine(railX, startY, railX, endY, linePaint);

            // Speeds: Top is fastest (count - 1), bottom is slowest (0)
            for (int i = 0; i < count; i++) {
                int displayIdx = (count - 1) - i;
                float y = topPadding + (i * itemHeight);
                float speedVal = speeds.get(displayIdx);
                String label = PlaybackSpeedPatch.speedLabel(speedVal) + "x";

                if (displayIdx == currentIdx) {
                    if (isLocked) {
                        label = "🔒 " + label;
                    }
                    canvas.drawText(label, textX, y + (4.5f * density), activeTextPaint);
                    canvas.drawCircle(railX, y, 5.5f * density, activeDotOuterPaint);
                    canvas.drawCircle(railX, y, 2.5f * density, activeDotInnerPaint);
                } else {
                    canvas.drawText(label, textX, y + (4f * density), textPaint);
                    canvas.drawCircle(railX, y, 2.5f * density, dotPaint);
                }
            }
        }
    }
}
