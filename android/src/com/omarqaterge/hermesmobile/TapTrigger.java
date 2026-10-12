package com.omarqaterge.hermesmobile;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Optional "triple tap anywhere" trigger (Settings → Voice, off by default). A 1 px invisible overlay window gets a
 * special ACTION_OUTSIDE event for every touch that lands outside it, i.e. anywhere on screen. Three of those within
 * the set time start the voice listener. It has no position and sees no content. Normal use (fast typing, games) can
 * trigger it by accident, which is why it is opt-in. Android may hide overlays on the lock screen.
 */
final class TapTrigger {
    private final HermesService svc;
    private final WindowManager wm;
    private final View view;
    private final long[] taps = new long[3];
    private int n = 0;
    private long lastFire = 0;
    private boolean added;

    TapTrigger(HermesService svc) {
        this.svc = svc;
        this.wm = svc.getSystemService(WindowManager.class);
        this.view = new View(svc);
        view.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) onTap();
            return false;
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("Hermes tap trigger");
        try {
            wm.addView(view, lp);
            added = true;
        } catch (Exception ignored) {
        }
    }

    private void onTap() {
        long now = SystemClock.uptimeMillis();
        long window = svc.getSharedPreferences("hm", Context.MODE_PRIVATE).getInt("triple_ms", 700);
        window = Math.max(400, Math.min(1200, window));
        taps[n % 3] = now;
        n++;
        if (n >= 3 && now - taps[n % 3] <= window && now - lastFire > 2000) { // taps[n % 3] is the oldest of the last three
            lastFire = now;
            n = 0;
            svc.startListening();
        }
    }

    void destroy() {
        if (!added) return;
        try {
            wm.removeView(view);
        } catch (Exception ignored) {
        }
        added = false;
    }
}
