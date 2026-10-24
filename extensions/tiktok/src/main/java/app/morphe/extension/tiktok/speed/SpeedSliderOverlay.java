package app.morphe.extension.tiktok.speed;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Interactive MX Player-style horizontal speed scrubber HUD for long-press hold.
 * Allows scrubbing between customizable speeds in real time and pulling down to lock or toggle normal.
 */
public final class SpeedSliderOverlay {
    private static volatile boolean holding = false;
    private static volatile boolean currentlyLocked = false;
    private static boolean wasLockedBeforeGesture = false;
    private static boolean draggedDown = false;

    private static float startX = 0f;
    private static float startY = 0f;
    private static int initialIndex = 0;
    private static int currentIndex = 0;

    private static List<Float> speedSteps = new ArrayList<>();
    private static WeakReference<SpeedSliderView> viewReference = new WeakReference<>(null);

    private SpeedSliderOverlay() {}

    public static boolean isHolding() {
        return holding;
    }

    public static boolean suppressNativeBanner() {
        return Settings.HOLD_SPEED_SLIDER_ENABLED.get();
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
                Activity activity = Utils.getActivity();
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
                initialIndex = bestIdx;
                currentIndex = bestIdx;
                holding = true;
                draggedDown = false;
                wasLockedBeforeGesture = currentlyLocked;

                float selectedSpeed = speedSteps.get(currentIndex);
                PlaybackSpeedPatch.setLiveSpeed(selectedSpeed);

                showOverlay(activity, speedSteps, currentIndex);
            } catch (Throwable t) {
                Logger.printException(() -> "Could not initialize hold speed slider", t);
            }
        });
    }

    public static void onDispatchTouch(MotionEvent event) {
        if (!holding || event == null) return;

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - startX;
            float dy = event.getY() - startY;

            Activity activity = Utils.getActivity();
            if (activity == null) return;
            float density = activity.getResources().getDisplayMetrics().density;

            // 1. Horizontal scrub
            float stepDistancePx = 42f * density;
            int stepOffset = Math.round(dx / stepDistancePx);
            int newIdx = Math.max(0, Math.min(speedSteps.size() - 1, initialIndex + stepOffset));

            if (newIdx != currentIndex) {
                currentIndex = newIdx;
                float speed = speedSteps.get(currentIndex);
                PlaybackSpeedPatch.setLiveSpeed(speed);

                // Remember this speed for next time
                Settings.HOLD_SPEED.set(PlaybackSpeedPatch.speedLabel(speed));

                SpeedSliderView view = viewReference.get();
                if (view != null) {
                    view.setCurrentIndex(currentIndex);
                    try {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    } catch (Throwable ignored) {}
                }
            }

            // 2. Vertical pull-down to lock or reset
            float lockThreshold = 70f * density;
            if (dy > lockThreshold && !draggedDown) {
                draggedDown = true;
                SpeedSliderView view = viewReference.get();

                if (wasLockedBeforeGesture) {
                    // Pulling down while already locked resets back to normal 1.0x
                    currentlyLocked = false;
                    PlaybackSpeedPatch.setLiveSpeed(1.0f);
                    if (view != null) {
                        view.setLockedState(false, true);
                        try {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        } catch (Throwable ignored) {}
                    }
                } else {
                    // Lock current speed
                    currentlyLocked = true;
                    if (view != null) {
                        view.setLockedState(true, false);
                        try {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        } catch (Throwable ignored) {}
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
            SpeedSliderView view = viewReference.get();
            if (currentlyLocked) {
                // Keep playing at chosen speed; fade HUD out smoothly
                if (view != null) {
                    view.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .setStartDelay(650)
                        .withEndAction(SpeedSliderOverlay::removeOverlay)
                        .start();
                }
            } else {
                // Not locked: immediately return to normal 1.0x playback
                PlaybackSpeedPatch.setLiveSpeed(1.0f);
                if (view != null) {
                    view.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction(SpeedSliderOverlay::removeOverlay)
                        .start();
                }
            }
        });
    }

    private static void showOverlay(Activity activity, List<Float> speeds, int activeIdx) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return;

        removeOverlay();

        SpeedSliderView view = new SpeedSliderView(activity, speeds, activeIdx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        lp.topMargin = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.20f);

        view.setLayoutParams(lp);
        view.setAlpha(0f);
        root.addView(view);
        view.animate().alpha(1f).setDuration(180).start();

        viewReference = new WeakReference<>(view);
    }

    private static void removeOverlay() {
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
     * Custom lightweight view that renders the MX Player speed slider HUD.
     */
    private static final class SpeedSliderView extends View {
        private final List<Float> speeds;
        private int currentIdx;
        private boolean isLocked = false;
        private boolean isResetNormal = false;

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeDotOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeDotInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF bounds = new RectF();
        private final float density;

        public SpeedSliderView(Context context, List<Float> speeds, int initialIdx) {
            super(context);
            this.speeds = speeds;
            this.currentIdx = initialIdx;
            this.density = context.getResources().getDisplayMetrics().density;

            initPaints();
        }

        private void initPaints() {
            bgPaint.setColor(Color.parseColor("#E0141416"));
            bgPaint.setStyle(Paint.Style.FILL);

            borderPaint.setColor(Color.parseColor("#33FFFFFF"));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(1.2f * density);

            linePaint.setColor(Color.parseColor("#4DFFFFFF"));
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(2.5f * density);
            linePaint.setStrokeCap(Paint.Cap.ROUND);

            textPaint.setColor(Color.parseColor("#A6FFFFFF"));
            textPaint.setTextSize(12f * density);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

            activeTextPaint.setColor(Color.parseColor("#3897F0"));
            activeTextPaint.setTextSize(14f * density);
            activeTextPaint.setTextAlign(Paint.Align.CENTER);
            activeTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            dotPaint.setColor(Color.parseColor("#B3FFFFFF"));
            dotPaint.setStyle(Paint.Style.FILL);

            activeDotOuterPaint.setColor(Color.parseColor("#3897F0"));
            activeDotOuterPaint.setStyle(Paint.Style.FILL);

            activeDotInnerPaint.setColor(Color.WHITE);
            activeDotInnerPaint.setStyle(Paint.Style.FILL);

            statusPaint.setColor(Color.WHITE);
            statusPaint.setTextSize(14f * density);
            statusPaint.setTextAlign(Paint.Align.CENTER);
            statusPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
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
            int count = Math.max(2, speeds.size());
            float itemWidth = 44f * density;
            float totalWidth = (count - 1) * itemWidth + 56f * density;
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int width = (int) Math.min(totalWidth, screenWidth - 32f * density);
            int height = (int) (86f * density);
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();
            float radius = 18f * density;

            bounds.set(borderPaint.getStrokeWidth(), borderPaint.getStrokeWidth(),
                       w - borderPaint.getStrokeWidth(), h - borderPaint.getStrokeWidth());

            // 1. Frosted background card
            canvas.drawRoundRect(bounds, radius, radius, bgPaint);
            canvas.drawRoundRect(bounds, radius, radius, borderPaint);

            if (speeds.isEmpty()) return;

            float paddingHorizontal = 28f * density;
            float usableWidth = w - (paddingHorizontal * 2f);
            float trackY = 40f * density;
            float topTextY = 24f * density;
            float bottomStatusY = 70f * density;

            int count = speeds.size();
            float step = count > 1 ? usableWidth / (count - 1) : 0;

            // 2. Draw horizontal track line
            canvas.drawLine(paddingHorizontal, trackY, w - paddingHorizontal, trackY, linePaint);

            // 3. Draw ticks and speed labels
            for (int i = 0; i < count; i++) {
                float x = paddingHorizontal + (i * step);
                float speedVal = speeds.get(i);
                String label = PlaybackSpeedPatch.speedLabel(speedVal) + "x";

                if (i == currentIdx) {
                    // Active speed label
                    canvas.drawText(label, x, topTextY, activeTextPaint);

                    // Active dot (large blue outer + white core)
                    canvas.drawCircle(x, trackY, 7f * density, activeDotOuterPaint);
                    canvas.drawCircle(x, trackY, 3.2f * density, activeDotInnerPaint);
                } else {
                    // Inactive speed label
                    canvas.drawText(label, x, topTextY, textPaint);

                    // Inactive dot
                    canvas.drawCircle(x, trackY, 3.2f * density, dotPaint);
                }
            }

            // 4. Bottom status line
            String statusText;
            if (isResetNormal) {
                statusPaint.setColor(Color.parseColor("#FFCC00"));
                statusText = "⏪ 1.0x Normal Speed";
            } else if (isLocked) {
                statusPaint.setColor(Color.parseColor("#3897F0"));
                statusText = "🔒 " + PlaybackSpeedPatch.speedLabel(speeds.get(currentIdx)) + "x Speed Locked";
            } else {
                statusPaint.setColor(Color.WHITE);
                statusText = "⏩ " + PlaybackSpeedPatch.speedLabel(speeds.get(currentIdx)) + "x Speed Playing";
            }

            canvas.drawText(statusText, w / 2f, bottomStatusY, statusPaint);
        }
    }
}
