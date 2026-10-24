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
    private static boolean draggedDown = false;
    private static boolean isSliding = false;
    private static boolean axisLockedVertical = false;

    private static float startX = 0f;
    private static float startY = 0f;
    private static float lastScrubX = 0f;
    private static int currentIndex = 0;

    private static List<Float> speedSteps = new ArrayList<>();
    private static WeakReference<SpeedSliderView> viewReference = new WeakReference<>(null);
    private static WeakReference<Window> interceptedWindow = new WeakReference<>(null);
    private static Window.Callback originalWindowCallback = null;

    private SpeedSliderOverlay() {}

    public static boolean isHolding() {
        return holding;
    }

    public static boolean suppressNativeBanner() {
        return Settings.HOLD_SPEED_SLIDER_ENABLED.get();
    }

    /**
     * Intercepts TikTok's native release method parameter (Z - isLocked).
     * Returns true if our slider locked the speed, so TikTok native locks immediately.
     */
    public static boolean overrideReleaseLocked(boolean nativeLocked) {
        if (!Settings.HOLD_SPEED_SLIDER_ENABLED.get()) {
            return nativeLocked;
        }
        return currentlyLocked;
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

        Utils.runOnMainThread(() -> {
            try {
                Activity activity = Utils.getVisibleActivity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

                speedSteps = parseSpeedSteps(Settings.HOLD_SPEED_SLIDER_STEPS.get());

                // Determine start speed (from remembered hold speed or 2.0x default)
                float remembered = 2.0f;
                try {
                    remembered = Float.parseFloat(Settings.HOLD_SPEED.get());
                } catch (Exception ignored) {}

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
                lastScrubX = x;
                currentIndex = bestIdx;
                holding = true;
                draggedDown = false;
                isSliding = false;
                axisLockedVertical = false;
                wasLockedBeforeGesture = currentlyLocked;

                float selectedSpeed = speedSteps.get(currentIndex);
                PlaybackSpeedPatch.setLiveSpeed(selectedSpeed);

                showOverlay(activity, speedSteps, currentIndex, wasLockedBeforeGesture);
            } catch (Throwable t) {
                Logger.printException(() -> "Could not initialize hold speed slider", t);
            }
        });
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

            // 1. Check for vertical dominant intent (Axis Locking)
            // If dragging downward significantly more than sideways, lock out horizontal changes
            if (!axisLockedVertical && dyTotal > 18f * density && dyTotal > Math.abs(dxTotal)) {
                axisLockedVertical = true;
            }

            // 2. Relative horizontal scrub (only when not locked to vertical axis)
            if (!axisLockedVertical) {
                // Check if user has started sliding horizontally
                if (!isSliding && Math.abs(dxTotal) > 16f * density) {
                    isSliding = true;
                    lastScrubX = curX;
                    SpeedSliderView view = viewReference.get();
                    if (view != null) {
                        view.setSlidingMode(true);
                    }
                }

                if (isSliding) {
                    float scrubDelta = curX - lastScrubX;
                    float stepThreshold = 32f * density;

                    if (Math.abs(scrubDelta) >= stepThreshold) {
                        int stepCount = (int) (scrubDelta / stepThreshold);
                        int newIdx = Math.max(0, Math.min(speedSteps.size() - 1, currentIndex + stepCount));

                        if (newIdx != currentIndex) {
                            currentIndex = newIdx;
                            float speed = speedSteps.get(currentIndex);
                            PlaybackSpeedPatch.setLiveSpeed(speed);

                            // Remember this speed for next time
                            Settings.HOLD_SPEED.save(PlaybackSpeedPatch.speedLabel(speed));

                            SpeedSliderView view = viewReference.get();
                            if (view != null) {
                                view.setCurrentIndex(currentIndex);
                            }
                        }
                        // Advance lastScrubX by the consumed distance
                        lastScrubX += stepCount * stepThreshold;
                    }
                }
            }

            // 3. Vertical pull-down to lock or reset to normal
            float lockThreshold = 52f * density;
            if (dyTotal > lockThreshold && !draggedDown) {
                draggedDown = true;
                SpeedSliderView view = viewReference.get();

                if (wasLockedBeforeGesture) {
                    // Pulling down while already locked resets back to normal 1.0x
                    currentlyLocked = false;
                    PlaybackSpeedPatch.setLiveSpeed(1.0f);
                    if (view != null) {
                        view.setLockedState(false, true);
                    }
                } else {
                    // Lock current speed
                    currentlyLocked = true;
                    if (view != null) {
                        view.setLockedState(true, false);
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
        if (!holding) return;
        holding = false;

        Utils.runOnMainThread(() -> {
            detachTouchInterceptor();
            SpeedSliderView view = viewReference.get();
            if (currentlyLocked) {
                // Keep playing at chosen speed; actively enforce to avoid any native reset
                if (speedSteps != null && currentIndex >= 0 && currentIndex < speedSteps.size()) {
                    float chosen = speedSteps.get(currentIndex);
                    PlaybackSpeedPatch.setLiveSpeed(chosen);
                    Settings.HOLD_SPEED.save(PlaybackSpeedPatch.speedLabel(chosen));

                    if (view != null) {
                        view.postDelayed(() -> PlaybackSpeedPatch.setLiveSpeed(chosen), 50);
                        view.postDelayed(() -> PlaybackSpeedPatch.setLiveSpeed(chosen), 150);
                        view.postDelayed(() -> PlaybackSpeedPatch.setLiveSpeed(chosen), 300);
                    }
                }

                if (view != null) {
                    view.animate()
                        .alpha(0f)
                        .setDuration(250)
                        .setStartDelay(500)
                        .withEndAction(SpeedSliderOverlay::removeOverlay)
                        .start();
                }
            } else {
                // Not locked: immediately return to normal 1.0x playback
                PlaybackSpeedPatch.setLiveSpeed(1.0f);
                if (view != null) {
                    view.postDelayed(() -> PlaybackSpeedPatch.setLiveSpeed(1.0f), 50);
                    view.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction(SpeedSliderOverlay::removeOverlay)
                        .start();
                }
            }
        });
    }

    private static void attachTouchInterceptor(Activity activity) {
        try {
            if (activity == null) return;
            Window window = activity.getWindow();
            if (window == null) return;

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

        removeOverlay();
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

    /**
     * Custom lightweight overlay view:
     * - Displays as a compact ghost pill by default on hold.
     * - Smoothly expands to reveal horizontal speed track when sliding horizontally.
     */
    private static class SpeedSliderView extends View {
        private final List<Float> speeds;
        private int currentIdx;
        private boolean isLocked;
        private boolean isResetNormal = false;
        private boolean isSlidingMode = false;
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

            bgPaint.setColor(Color.argb(175, 18, 18, 22)); // Translucent dark frosted
            bgPaint.setStyle(Paint.Style.FILL);

            borderPaint.setColor(Color.argb(45, 255, 255, 255));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(1f * density);

            linePaint.setColor(Color.argb(60, 255, 255, 255));
            linePaint.setStrokeWidth(2f * density);
            linePaint.setStrokeCap(Paint.Cap.ROUND);

            textPaint.setColor(Color.argb(165, 255, 255, 255));
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

            dotPaint.setColor(Color.argb(160, 255, 255, 255));
            dotPaint.setStyle(Paint.Style.FILL);

            activeDotOuterPaint.setColor(Color.parseColor("#3897F0"));
            activeDotOuterPaint.setStyle(Paint.Style.FILL);

            activeDotInnerPaint.setColor(Color.WHITE);
            activeDotInnerPaint.setStyle(Paint.Style.FILL);
        }

        public void setSlidingMode(boolean sliding) {
            if (this.isSlidingMode != sliding) {
                this.isSlidingMode = sliding;
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
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            if (!isSlidingMode) {
                // Ghost pill mode: compact and lightweight
                int pillWidth = (int) (240f * density);
                int pillHeight = (int) (38f * density);
                setMeasuredDimension(Math.min(pillWidth, screenWidth - (int) (32f * density)), pillHeight);
            } else {
                // Expanded scrubber mode
                int count = Math.max(2, speeds.size());
                float itemWidth = 42f * density;
                float totalWidth = (count - 1) * itemWidth + 56f * density;
                int width = (int) Math.min(totalWidth, screenWidth - (int) (32f * density));
                int height = (int) (76f * density);
                setMeasuredDimension(width, height);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();
            float radius = isSlidingMode ? 16f * density : (h / 2f);

            bounds.set(borderPaint.getStrokeWidth(), borderPaint.getStrokeWidth(),
                       w - borderPaint.getStrokeWidth(), h - borderPaint.getStrokeWidth());

            // 1. Sleek frosted background card
            canvas.drawRoundRect(bounds, radius, radius, bgPaint);
            canvas.drawRoundRect(bounds, radius, radius, borderPaint);

            float currentSpeedVal = (speeds != null && currentIdx < speeds.size()) ? speeds.get(currentIdx) : 2.0f;
            String speedStr = PlaybackSpeedPatch.speedLabel(currentSpeedVal) + "x";

            if (!isSlidingMode) {
                // Ghost Pill: Draw clean single status line
                String ghostText;
                if (isResetNormal) {
                    pillTextPaint.setColor(Color.parseColor("#FFCC00"));
                    ghostText = "⏪ Reset to 1.0x Normal";
                } else if (isLocked) {
                    pillTextPaint.setColor(Color.parseColor("#3897F0"));
                    ghostText = "🔒 " + speedStr + " Locked • Pull down to unlock";
                } else {
                    pillTextPaint.setColor(Color.WHITE);
                    ghostText = "⚡ " + speedStr + " Speed • Pull down to lock";
                }
                canvas.drawText(ghostText, w / 2f, (h / 2f) + (4.5f * density), pillTextPaint);
                return;
            }

            // Expanded scrubber mode
            if (speeds == null || speeds.isEmpty()) return;

            float paddingHorizontal = 24f * density;
            float usableWidth = w - (paddingHorizontal * 2f);
            float trackY = 34f * density;
            float topTextY = 20f * density;
            float bottomStatusY = 62f * density;

            int count = speeds.size();
            float step = count > 1 ? usableWidth / (count - 1) : 0;

            // 2. Horizontal track line
            canvas.drawLine(paddingHorizontal, trackY, w - paddingHorizontal, trackY, linePaint);

            // 3. Speed ticks and labels
            for (int i = 0; i < count; i++) {
                float x = paddingHorizontal + (i * step);
                float speedVal = speeds.get(i);
                String label = PlaybackSpeedPatch.speedLabel(speedVal) + "x";

                if (i == currentIdx) {
                    canvas.drawText(label, x, topTextY, activeTextPaint);
                    canvas.drawCircle(x, trackY, 6.5f * density, activeDotOuterPaint);
                    canvas.drawCircle(x, trackY, 2.8f * density, activeDotInnerPaint);
                } else {
                    canvas.drawText(label, x, topTextY, textPaint);
                    canvas.drawCircle(x, trackY, 3f * density, dotPaint);
                }
            }

            // 4. Status caption
            String statusText;
            if (isResetNormal) {
                pillTextPaint.setColor(Color.parseColor("#FFCC00"));
                statusText = "⏪ Reset to 1.0x Normal";
            } else if (isLocked) {
                pillTextPaint.setColor(Color.parseColor("#3897F0"));
                statusText = "🔒 " + speedStr + " Locked • Pull down to unlock";
            } else {
                pillTextPaint.setColor(Color.WHITE);
                statusText = "⚡ Slide to scrub • Pull down to lock";
            }
            canvas.drawText(statusText, w / 2f, bottomStatusY, pillTextPaint);
        }
    }
}
