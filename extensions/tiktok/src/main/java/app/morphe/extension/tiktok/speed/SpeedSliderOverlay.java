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
     * Custom lightweight circular gauge HUD:
     * - Renders as an ultra-compact futuristic dial (tachometer / watch crown style).
     * - Sweeps an electric cyan arc with orbital ticks as speed is scrolled up/down.
     * - Micro-pulses on each speed notch for a mechanical tactile feel.
     * - Side-slide locks with an illuminated neon ring and lock badge.
     */
    private static class SpeedSliderView extends View {
        private final List<Float> speeds;
        private int currentIdx;
        private boolean isLocked;
        private boolean isResetNormal = false;
        private final float density;

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lockGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeTickOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeTickInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arcBounds = new RectF();

        public SpeedSliderView(Context context, List<Float> speeds, int initialIdx, boolean locked) {
            super(context);
            this.speeds = speeds;
            this.currentIdx = initialIdx;
            this.isLocked = locked;
            this.density = getResources().getDisplayMetrics().density;

            bgPaint.setColor(Color.argb(205, 14, 16, 22)); // Deep obsidian glass
            bgPaint.setStyle(Paint.Style.FILL);

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(1f * density);

            trackArcPaint.setColor(Color.argb(35, 255, 255, 255));
            trackArcPaint.setStyle(Paint.Style.STROKE);
            trackArcPaint.setStrokeWidth(2.5f * density);
            trackArcPaint.setStrokeCap(Paint.Cap.ROUND);

            progressArcPaint.setColor(Color.parseColor("#00E5FF")); // Electric neon cyan
            progressArcPaint.setStyle(Paint.Style.STROKE);
            progressArcPaint.setStrokeWidth(2.5f * density);
            progressArcPaint.setStrokeCap(Paint.Cap.ROUND);

            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(13.5f * density);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            chevronPaint.setColor(Color.argb(120, 0, 229, 255));
            chevronPaint.setTextSize(7.5f * density);
            chevronPaint.setTextAlign(Paint.Align.CENTER);
            chevronPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

            lockGlyphPaint.setColor(Color.parseColor("#00E5FF"));
            lockGlyphPaint.setTextSize(9.5f * density);
            lockGlyphPaint.setTextAlign(Paint.Align.CENTER);

            tickPaint.setColor(Color.argb(80, 255, 255, 255));
            tickPaint.setStyle(Paint.Style.FILL);

            activeTickOuterPaint.setColor(Color.parseColor("#00E5FF"));
            activeTickOuterPaint.setStyle(Paint.Style.FILL);

            activeTickInnerPaint.setColor(Color.WHITE);
            activeTickInnerPaint.setStyle(Paint.Style.FILL);
        }

        public void setExpandedMode(boolean expanded) {
            // Circle seamlessly adapts in place without bloating layout
            invalidate();
        }

        public void setCurrentIndex(int idx) {
            if (this.currentIdx != idx) {
                this.currentIdx = idx;
                this.isResetNormal = false;
                // Punchy mechanical notch scale-pulse
                animate().scaleX(1.12f).scaleY(1.12f).setDuration(60)
                    .withEndAction(() -> animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start())
                    .start();
                invalidate();
            }
        }

        public void setLockedState(boolean locked, boolean resetNormal) {
            this.isLocked = locked;
            this.isResetNormal = resetNormal;
            // Magnetic snap scale-pop
            animate().scaleX(1.18f).scaleY(1.18f).setDuration(80)
                .withEndAction(() -> animate().scaleX(1.0f).scaleY(1.0f).setDuration(110).start())
                .start();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Elegant compact circular footprint (54dp diameter)
            int diameter = (int) (54f * density);
            setMeasuredDimension(diameter, diameter);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float outerRadius = Math.min(w, h) / 2f - (1f * density);

            // 1. Sleek circular glass base
            canvas.drawCircle(cx, cy, outerRadius, bgPaint);

            // 2. Specular border ring (glows cyan when locked)
            if (isLocked) {
                borderPaint.setColor(Color.parseColor("#00E5FF"));
                borderPaint.setStrokeWidth(1.5f * density);
            } else {
                borderPaint.setColor(Color.argb(45, 255, 255, 255));
                borderPaint.setStrokeWidth(1f * density);
            }
            canvas.drawCircle(cx, cy, outerRadius, borderPaint);

            if (speeds == null || speeds.isEmpty()) return;

            int count = speeds.size();
            float arcRadius = outerRadius - (3.5f * density);
            arcBounds.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius);

            float startAngle = 135f;
            float totalSweep = 270f;

            // 3. Circular track gauge
            canvas.drawArc(arcBounds, startAngle, totalSweep, false, trackArcPaint);

            // 4. Electric cyan speed progress arc
            float ratio = count > 1 ? (float) currentIdx / (float) (count - 1) : 1f;
            float currentSweep = Math.max(3f, ratio * totalSweep);
            canvas.drawArc(arcBounds, startAngle, currentSweep, false, progressArcPaint);

            // 5. Orbital notch ticks along the perimeter
            for (int i = 0; i < count; i++) {
                float stepRatio = count > 1 ? (float) i / (float) (count - 1) : 0f;
                float angleDeg = startAngle + (stepRatio * totalSweep);
                double angleRad = Math.toRadians(angleDeg);
                float dotX = cx + (float) (arcRadius * Math.cos(angleRad));
                float dotY = cy + (float) (arcRadius * Math.sin(angleRad));

                if (i == currentIdx) {
                    canvas.drawCircle(dotX, dotY, 4f * density, activeTickOuterPaint);
                    canvas.drawCircle(dotX, dotY, 1.8f * density, activeTickInnerPaint);
                } else {
                    canvas.drawCircle(dotX, dotY, 1.2f * density, tickPaint);
                }
            }

            // 6. Center typography & status
            float currentSpeedVal = (currentIdx < speeds.size()) ? speeds.get(currentIdx) : 2.0f;
            String speedStr = PlaybackSpeedPatch.speedLabel(currentSpeedVal) + "x";

            if (isResetNormal) {
                textPaint.setColor(Color.parseColor("#FFCC00"));
                textPaint.setTextSize(13f * density);
                canvas.drawText("1.0x", cx, cy + (4.5f * density), textPaint);
            } else if (isLocked) {
                // Lock icon above, speed below
                canvas.drawText("🔒", cx, cy - (3.5f * density), lockGlyphPaint);
                textPaint.setColor(Color.parseColor("#00E5FF"));
                textPaint.setTextSize(11f * density);
                canvas.drawText(speedStr, cx, cy + (9.5f * density), textPaint);
            } else {
                // Centered speed value with micro chevrons
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(13.5f * density);
                canvas.drawText(speedStr, cx, cy + (4.5f * density), textPaint);

                // Subtle guide chevrons hinting vertical movement
                canvas.drawText("▲", cx, cy - (9.5f * density), chevronPaint);
                canvas.drawText("▼", cx, cy + (16f * density), chevronPaint);
            }
        }
    }
}
