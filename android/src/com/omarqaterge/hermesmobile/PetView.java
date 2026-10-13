package com.omarqaterge.hermesmobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import com.omarqaterge.hermesmobile.IslandOverlay.Mood;

/**
 * The pet: three drawn characters (a blob, an original chibi girl "Mika", and a cat) that act out what Hermes is
 * doing. Every expression (eyes, brows, mouth, blush, arms, bounce, tilt) is a number that eases toward the target of
 * the current mood, so changes are smooth instead of a sudden swap. Plain Canvas shapes, no image files.
 * The same drawing renders the animated view on the island and a still of each mood for the system notification.
 */
final class PetView extends View {
    // indexes into the eased pose
    private static final int EYE = 0, LX = 1, LY = 2, BROW = 3, MOUTH = 4, SMILE = 5, BLUSH = 6, BOUNCE = 7, SHAKE = 8, TILT = 9, UP = 10, TYPE = 11, CHIN = 12, N = 13;

    private Mood mood = Mood.SLEEP;
    private String species = "blob";
    private boolean running = true;
    private final float[] pose = new float[N];
    private long lastMs = 0;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    static final int INK = 0xFF23202B;

    PetView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setTextAlign(Paint.Align.CENTER);
        float[] t = target(mood);
        System.arraycopy(t, 0, pose, 0, N);
    }

    void setMood(Mood m) {
        if (m == mood) return;
        mood = m;
        running = true;
        invalidate();
    }

    void setSpecies(String s) {
        if (s == null) s = "blob";
        if (!s.equals("blob") && !s.equals("mika") && !s.equals("cat")) s = "blob";
        if (s.equals(species)) return;
        species = s;
        invalidate();
    }

    void stop() {
        running = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        invalidate();
    }

    /** A still of one mood (for the system notification's large icon). */
    static Bitmap snapshot(Context c, String species, Mood mood, int px) {
        PetView v = new PetView(c);
        v.species = "mika".equals(species) || "cat".equals(species) ? species : "blob";
        v.mood = mood;
        System.arraycopy(target(mood), 0, v.pose, 0, N); // no easing: show the mood itself
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        v.layout(0, 0, px, px);
        v.paintPet(cv, px, px, 1.3f);
        return b;
    }

    /** Where each expression number should end up for a mood. */
    private static float[] target(Mood m) {
        //                         EYE   LX    LY    BROW  MOUTH SMILE BLUSH BOUNCE SHAKE TILT  UP   TYPE CHIN
        switch (m) {
            case SLEEP:  return f(0f,   0f,   0f,   0f,   0f,   0.3f, 0.3f, 0.02f, 0f,   5f,   0f,  0f,  0f);
            case THINK:  return f(0.95f, 0.6f, -0.6f, 0.4f, 0.1f, 0f,   0f,   0.03f, 0f,   6f,   0f,  0f,  1f);
            case WORK:   return f(1f,   0f,   0.5f, 0.1f, 0f,   0.35f, 0f,   0.09f, 0f,   0f,   0f,  1f,  0f);
            case ALERT:  return f(1.35f, 0f,   0f,   1f,   0.9f, -0.3f, 0f,   0.02f, 1f,   0f,   1f,  0f,  0f);
            case ASK:    return f(1.1f, 0.3f, -0.2f, 0.6f, 0.15f, 0f,   0f,   0.03f, 0f,   -9f,  0f,  0f,  0f);
            case HAPPY:  return f(-1f,  0f,   0f,   0.3f, 0.7f, 1f,   1f,   0.16f, 0f,   0f,   1f,  0f,  0f);
            case SAD:    return f(0.75f, 0f,   0.5f, -1f,  0f,   -1f,  0f,   0f,    0f,   0f,   0f,  0f,  0f);
            case LEARN:  return f(1.2f, 0.3f, -0.7f, 0.6f, 0.4f, 0.7f, 0.3f, 0.06f, 0f,   0f,   0.3f, 0f, 0f);
            case DROP:   return f(1.3f, 0f,   0f,   0.7f, 0.9f, 1f,   0.6f, 0.16f, 0f,   0f,   1f,  0f,  0f);
            case LISTEN: return f(1.15f, 0.2f, 0f,   0.4f, 0.1f, 0.2f, 0f,   0.04f, 0f,   -6f,  0f,  0f,  0f);
            default:     return f(1f,   0f,   0f,   0f,   0f,   0.2f, 0f,   0.03f, 0f,   0f,   0f,  0f,  0f);
        }
    }

    private static float[] f(float... a) {
        return a;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastMs == 0 ? 0.04f : Math.min(0.1f, (now - lastMs) / 1000f);
        lastMs = now;
        float[] t = target(mood);
        float k = Math.min(1f, dt * 9f); // ease: about a third of a second to settle
        for (int i = 0; i < N; i++) pose[i] += (t[i] - pose[i]) * k;
        paintPet(c, getWidth(), getHeight(), now / 1000f);
        if (running && isAttachedToWindow()) postInvalidateDelayed(mood == Mood.SLEEP ? 70 : 33);
    }

    // ── drawing ───────────────────────────────────────────────

    private void paintPet(Canvas c, float w, float h, float sec) {
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h);
        float bounceAmp = pose[BOUNCE];
        float speed = mood == Mood.WORK ? 7f : mood == Mood.HAPPY || mood == Mood.DROP ? 6f : mood == Mood.SLEEP ? 1.6f : 3f;
        float ph = (float) Math.sin(sec * speed);
        float dy = -s * bounceAmp * Math.abs(ph);
        float dx = s * 0.035f * pose[SHAKE] * (float) Math.sin(sec * 30);
        float tilt = pose[TILT] * (float) (0.6 + 0.4 * Math.sin(sec * 1.5));
        float squash = bounceAmp * 0.9f * (1f - Math.abs(ph)); // squashed on the ground, stretched in the air
        c.save();
        c.translate(w / 2f + dx, h * 0.6f + dy);
        c.rotate(tilt);
        c.scale(1f + squash * 0.35f, 1f - squash * 0.35f);
        switch (species) {
            case "mika":
                drawMika(c, s, sec);
                break;
            case "cat":
                drawCat(c, s, sec);
                break;
            default:
                drawBlob(c, s, sec);
        }
        c.restore();
        drawExtra(c, w, h, sec);
    }

    private boolean blinking(float sec, float offset) {
        return ((sec + offset) % 3.6f) > 3.48f;
    }

    /** One eye. `r` is its size; the mood decides open, wide, closed or happy-arch. */
    private void eye(Canvas c, float x, float y, float r, int iris, boolean glossy, float sec, int side) {
        float open = pose[EYE];
        line.setColor(INK);
        line.setStrokeWidth(Math.max(1.6f, r * 0.34f));
        if (open <= -0.5f) { // happy ^ ^
            rect.set(x - r, y - r * 0.5f, x + r, y + r * 1.1f);
            c.drawArc(rect, 200, 140, false, line);
            return;
        }
        if (open < 0.18f || blinking(sec, side * 0.07f)) { // closed or blinking
            rect.set(x - r, y - r * 0.8f, x + r, y + r * 0.8f);
            c.drawArc(rect, 20, 140, false, line);
            return;
        }
        float ry = r * Math.min(1.45f, open) * 1.15f, rx = r * (open > 1.2f ? 1.05f : 0.92f);
        float ox = pose[LX] * r * 0.35f, oy = pose[LY] * r * 0.3f;
        fill.setColor(Color.WHITE);
        rect.set(x - rx, y - ry, x + rx, y + ry);
        if (glossy) c.drawOval(rect, fill);
        fill.setColor(iris);
        float ir = open > 1.2f ? 0.62f : 0.82f;
        rect.set(x - rx * ir + ox, y - ry * ir + oy, x + rx * ir + ox, y + ry * ir + oy);
        c.drawOval(rect, fill);
        fill.setColor(INK);
        rect.set(x - rx * 0.4f + ox, y - ry * 0.4f + oy, x + rx * 0.4f + ox, y + ry * 0.4f + oy);
        c.drawOval(rect, fill);
        fill.setColor(Color.WHITE);
        c.drawCircle(x - rx * 0.3f + ox, y - ry * 0.35f + oy, rx * 0.24f, fill);
        c.drawCircle(x + rx * 0.3f + ox, y + ry * 0.3f + oy, rx * 0.11f, fill);
        if (glossy) { // anime upper lid
            line.setStrokeWidth(Math.max(1.4f, r * 0.28f));
            rect.set(x - rx, y - ry, x + rx, y + ry);
            c.drawArc(rect, 200, 140, false, line);
        }
    }

    private void brow(Canvas c, float x, float y, float len, int side) {
        float b = pose[BROW];
        if (Math.abs(b) < 0.08f) return;
        line.setColor(INK);
        line.setStrokeWidth(Math.max(1.4f, len * 0.16f));
        float up = b > 0 ? b * len * 0.5f : 0;          // raised (surprise, curious)
        float tilt = b < 0 ? -b * len * 0.5f : 0;       // worried: inner end higher
        float innerX = x - side * len * 0.5f, outerX = x + side * len * 0.5f;
        c.drawLine(innerX, y - up - tilt, outerX, y - up + tilt * 0.4f, line);
    }

    private void mouth(Canvas c, float x, float y, float w) {
        float open = pose[MOUTH], sm = pose[SMILE];
        line.setColor(INK);
        line.setStrokeWidth(Math.max(1.4f, w * 0.14f));
        if (open > 0.3f) {
            float hh = w * (0.25f + 0.55f * open);
            path.reset();
            if (sm > 0.35f) { // open smile: a D shape with a tongue
                path.moveTo(x - w * 0.6f, y);
                path.quadTo(x, y + hh * 2f, x + w * 0.6f, y);
                path.close();
                fill.setColor(INK);
                c.drawPath(path, fill);
                fill.setColor(0xFFFF8FA0);
                rect.set(x - w * 0.28f, y + hh * 0.5f, x + w * 0.28f, y + hh * 0.95f);
                c.drawOval(rect, fill);
            } else { // "o"
                fill.setColor(INK);
                rect.set(x - w * 0.28f, y, x + w * 0.28f, y + hh * 1.3f);
                c.drawOval(rect, fill);
            }
            return;
        }
        float bend = sm * w * 0.5f; // + smile, - frown
        path.reset();
        path.moveTo(x - w * 0.55f, y);
        path.quadTo(x, y + bend, x + w * 0.55f, y);
        c.drawPath(path, line);
    }

    private void blush(Canvas c, float x, float y, float r) {
        if (pose[BLUSH] < 0.05f) return;
        fill.setColor(((int) (pose[BLUSH] * 110)) << 24 | 0xFF7C93);
        rect.set(x - r, y - r * 0.55f, x + r, y + r * 0.55f);
        c.drawOval(rect, fill);
    }

    // ── blob: a bouncy mint slime ──────────────────────────────
    private void drawBlob(Canvas c, float s, float sec) {
        float r = s * 0.34f;
        float wob = (float) Math.sin(sec * 2.2f) * r * 0.04f;
        // arms (little nubs)
        float up = pose[UP], type = pose[TYPE];
        fill.setColor(0xFF5FCDAE);
        for (int side = -1; side <= 1; side += 2) {
            float ax = side * r * 1.05f;
            float ay = r * 0.15f - up * r * 0.9f + type * r * 0.5f + (type > 0.5f ? (float) Math.sin(sec * 14 + side) * r * 0.12f : 0f);
            c.drawCircle(ax, ay, r * 0.2f, fill);
        }
        // body
        path.reset();
        path.moveTo(-r * 1.15f, r * 0.55f);
        path.cubicTo(-r * 1.3f, -r * 0.5f + wob, -r * 0.6f, -r * 1.1f, 0, -r * 1.1f);
        path.cubicTo(r * 0.6f, -r * 1.1f, r * 1.3f, -r * 0.5f - wob, r * 1.15f, r * 0.55f);
        path.cubicTo(r * 0.9f, r * 0.95f, -r * 0.9f, r * 0.95f, -r * 1.15f, r * 0.55f);
        path.close();
        fill.setColor(0xFF7BE0C3);
        c.drawPath(path, fill);
        fill.setColor(0x55FFFFFF); // glossy highlight
        rect.set(-r * 0.75f, -r * 0.95f, -r * 0.1f, -r * 0.55f);
        c.drawOval(rect, fill);
        fill.setColor(0x2200664D); // belly shade
        rect.set(-r * 0.9f, r * 0.3f, r * 0.9f, r * 0.9f);
        c.drawOval(rect, fill);
        // face
        float ex = r * 0.45f, ey = -r * 0.1f;
        blush(c, -r * 0.8f, r * 0.2f, r * 0.2f);
        blush(c, r * 0.8f, r * 0.2f, r * 0.2f);
        eye(c, -ex, ey, r * 0.2f, 0xFF35B08F, false, sec, -1);
        eye(c, ex, ey, r * 0.2f, 0xFF35B08F, false, sec, 1);
        brow(c, -ex, ey - r * 0.42f, r * 0.35f, -1);
        brow(c, ex, ey - r * 0.42f, r * 0.35f, 1);
        mouth(c, 0, r * 0.22f, r * 0.34f);
    }

    // ── Mika: an original chibi girl (lavender hair, star clip, teal eyes) ──
    private void drawMika(Canvas c, float s, float sec) {
        final int hair = 0xFF9A86F0, hairDark = 0xFF7C68DC, skin = 0xFFFFE2CF, dress = 0xFFFFC857, dressDark = 0xFFE6A92E;
        float hr = s * 0.27f;           // head radius
        float hy = -s * 0.12f;          // head centre
        float up = pose[UP], type = pose[TYPE], chin = pose[CHIN];
        float sway = (float) Math.sin(sec * 2.4f);
        // twin tails behind everything, swinging
        fill.setColor(hairDark);
        for (int side = -1; side <= 1; side += 2) {
            c.save();
            c.translate(side * hr * 0.95f, hy + hr * 0.1f);
            c.rotate(side * (14f + sway * 9f + (mood == Mood.WORK || mood == Mood.HAPPY || mood == Mood.DROP ? (float) Math.sin(sec * 7) * 6f : 0f)));
            rect.set(-hr * 0.3f, -hr * 0.15f, hr * 0.3f, hr * 1.35f);
            c.drawOval(rect, fill);
            c.restore();
        }
        // body: small dress, arms, feet
        fill.setColor(dress);
        path.reset();
        path.moveTo(-hr * 0.45f, hy + hr * 0.95f);
        path.lineTo(hr * 0.45f, hy + hr * 0.95f);
        path.lineTo(hr * 0.75f, hy + hr * 1.75f);
        path.lineTo(-hr * 0.75f, hy + hr * 1.75f);
        path.close();
        c.drawPath(path, fill);
        fill.setColor(dressDark);
        rect.set(-hr * 0.75f, hy + hr * 1.6f, hr * 0.75f, hy + hr * 1.85f);
        c.drawRoundRect(rect, hr * 0.12f, hr * 0.12f, fill);
        fill.setColor(skin);
        for (int side = -1; side <= 1; side += 2) { // feet
            rect.set(side * hr * 0.42f - hr * 0.18f, hy + hr * 1.8f, side * hr * 0.42f + hr * 0.18f, hy + hr * 2.05f);
            c.drawOval(rect, fill);
        }
        line.setColor(skin);
        line.setStrokeWidth(hr * 0.24f);
        for (int side = -1; side <= 1; side += 2) {
            float sx = side * hr * 0.5f, sy = hy + hr * 1.1f;
            float hx, hyy;
            if (up > 0.5f) { // hands up (cheering / alarmed)
                hx = side * hr * 1.1f;
                hyy = hy - hr * 0.2f + (float) Math.sin(sec * (mood == Mood.ALERT ? 28f : 9f)) * hr * 0.1f;
            } else if (type > 0.5f) { // typing
                hx = side * hr * 0.35f;
                hyy = hy + hr * 1.5f + (float) Math.sin(sec * 16f + side * 1.6f) * hr * 0.12f;
            } else if (chin > 0.5f && side > 0) { // thinking: hand on chin
                hx = hr * 0.25f;
                hyy = hy + hr * 0.95f;
            } else if (mood == Mood.LISTEN && side > 0) { // cupped ear
                hx = hr * 1.05f;
                hyy = hy + hr * 0.15f;
            } else { // resting
                hx = side * hr * 0.85f;
                hyy = hy + hr * 1.5f;
            }
            c.drawLine(sx, sy, hx, hyy, line);
            fill.setColor(skin);
            c.drawCircle(hx, hyy, hr * 0.17f, fill);
        }
        // back of the head hair, then the face
        fill.setColor(hair);
        rect.set(-hr * 1.12f, hy - hr * 1.12f, hr * 1.12f, hy + hr * 1.0f);
        c.drawOval(rect, fill);
        fill.setColor(skin);
        rect.set(-hr * 0.98f, hy - hr * 0.78f, hr * 0.98f, hy + hr * 0.98f);
        c.drawOval(rect, fill);
        // fringe
        fill.setColor(hair);
        path.reset();
        path.moveTo(-hr * 1.05f, hy - hr * 0.2f);
        path.cubicTo(-hr * 1.1f, hy - hr * 1.2f, hr * 1.1f, hy - hr * 1.2f, hr * 1.05f, hy - hr * 0.2f);
        path.quadTo(hr * 0.75f, hy - hr * 0.85f, hr * 0.35f, hy - hr * 0.55f);
        path.quadTo(0, hy - hr * 0.95f, -hr * 0.35f, hy - hr * 0.55f);
        path.quadTo(-hr * 0.75f, hy - hr * 0.85f, -hr * 1.05f, hy - hr * 0.2f);
        path.close();
        c.drawPath(path, fill);
        // star clip
        fill.setColor(0xFFFFD84A);
        starAt(c, hr * 0.62f, hy - hr * 0.78f, hr * 0.3f);
        // face
        float ex = hr * 0.42f, ey = hy + hr * 0.12f;
        blush(c, -hr * 0.68f, hy + hr * 0.5f, hr * 0.2f);
        blush(c, hr * 0.68f, hy + hr * 0.5f, hr * 0.2f);
        eye(c, -ex, ey, hr * 0.27f, 0xFF3FC1B0, true, sec, -1);
        eye(c, ex, ey, hr * 0.27f, 0xFF3FC1B0, true, sec, 1);
        brow(c, -ex, ey - hr * 0.5f, hr * 0.34f, -1);
        brow(c, ex, ey - hr * 0.5f, hr * 0.34f, 1);
        mouth(c, 0, hy + hr * 0.62f, hr * 0.3f);
    }

    private void starAt(Canvas c, float x, float y, float r) {
        path.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = i % 2 == 0 ? r : r * 0.45f;
            float px = x + (float) Math.cos(a) * rr, py = y + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        path.close();
        c.drawPath(path, fill);
    }

    // ── cat ────────────────────────────────────────────────────
    private void drawCat(Canvas c, float s, float sec) {
        float r = s * 0.31f;
        final int fur = 0xFFE9B15C, furDark = 0xFFC98F3C, earIn = 0xFFF2A5A0;
        boolean sad = pose[SMILE] < -0.5f;
        // tail wag
        line.setColor(furDark);
        line.setStrokeWidth(r * 0.3f);
        path.reset();
        path.moveTo(r * 0.9f, r * 0.7f);
        path.quadTo(r * 1.6f, r * 0.5f + (float) Math.sin(sec * (mood == Mood.HAPPY || mood == Mood.WORK ? 9 : 2.5f)) * r * 0.3f, r * 1.3f, -r * 0.2f);
        c.drawPath(path, line);
        // body
        fill.setColor(fur);
        rect.set(-r * 0.8f, r * 0.2f, r * 0.8f, r * 1.1f);
        c.drawOval(rect, fill);
        // arms/paws
        float up = pose[UP], type = pose[TYPE];
        for (int side = -1; side <= 1; side += 2) {
            float px = side * r * 0.55f;
            float py = r * 0.95f - up * r * 1.1f - type * r * 0.15f + (type > 0.5f ? (float) Math.sin(sec * 15 + side) * r * 0.1f : 0f);
            c.drawCircle(px, py, r * 0.2f, fill);
        }
        // ears
        for (int side = -1; side <= 1; side += 2) {
            float tipX = side * r * (sad ? 1.05f : 0.75f), tipY = sad ? -r * 0.75f : -r * 1.3f;
            path.reset();
            path.moveTo(side * r * 0.95f, -r * 0.1f);
            path.lineTo(tipX, tipY);
            path.lineTo(side * r * 0.12f, -r * 0.82f);
            path.close();
            fill.setColor(fur);
            c.drawPath(path, fill);
            path.reset();
            path.moveTo(side * r * 0.8f, -r * 0.25f);
            path.lineTo(side * r * (sad ? 0.95f : 0.72f), sad ? -r * 0.7f : -r * 1.05f);
            path.lineTo(side * r * 0.3f, -r * 0.78f);
            path.close();
            fill.setColor(earIn);
            c.drawPath(path, fill);
        }
        // head
        fill.setColor(fur);
        rect.set(-r * 1.08f, -r * 0.92f, r * 1.08f, r * 0.82f);
        c.drawOval(rect, fill);
        blush(c, -r * 0.72f, r * 0.2f, r * 0.2f);
        blush(c, r * 0.72f, r * 0.2f, r * 0.2f);
        eye(c, -r * 0.42f, -r * 0.1f, r * 0.22f, 0xFF5BB85B, false, sec, -1);
        eye(c, r * 0.42f, -r * 0.1f, r * 0.22f, 0xFF5BB85B, false, sec, 1);
        brow(c, -r * 0.42f, -r * 0.5f, r * 0.32f, -1);
        brow(c, r * 0.42f, -r * 0.5f, r * 0.32f, 1);
        fill.setColor(earIn);
        path.reset();
        path.moveTo(-r * 0.1f, r * 0.12f);
        path.lineTo(r * 0.1f, r * 0.12f);
        path.lineTo(0, r * 0.25f);
        path.close();
        c.drawPath(path, fill);
        mouth(c, 0, r * 0.3f, r * 0.34f);
        line.setColor(0x99FFFFFF); // whiskers
        line.setStrokeWidth(Math.max(1f, r * 0.05f));
        for (int side = -1; side <= 1; side += 2)
            for (int k = -1; k <= 1; k++) c.drawLine(side * r * 0.62f, r * 0.28f + k * r * 0.1f, side * r * 1.3f, r * 0.24f + k * r * 0.25f, line);
    }

    // ── bubbles and symbols above the pet ──────────────────────
    private void drawExtra(Canvas c, float w, float h, float sec) {
        float s = Math.min(w, h);
        float x = w * 0.84f, y = h * 0.2f, u = s * 0.075f;
        switch (mood) {
            case SLEEP: {
                txt.setColor(0xFF9AA0A6);
                for (int k = 0; k < 2; k++) {
                    float p = ((sec * 0.6f) + k * 0.5f) % 1f;
                    txt.setTextSize(u * (1.4f + p * 0.8f));
                    txt.setAlpha((int) (255 * (1f - p)));
                    c.drawText("z", x - u * 0.6f + p * u, y + u * 1.4f - p * u * 2.4f, txt);
                }
                txt.setAlpha(255);
                break;
            }
            case THINK: {
                int on = (int) (sec * 3) % 3;
                for (int k = 0; k < 3; k++) {
                    fill.setColor(k == on ? IslandOverlay.GOLD : 0x88B0B6BD);
                    c.drawCircle(x - u * 1.8f + k * u * 1.8f, y + (k == on ? -u * 0.4f : 0), u * 0.6f, fill);
                }
                break;
            }
            case WORK: {
                line.setColor(IslandOverlay.GOLD);
                line.setStrokeWidth(Math.max(1.5f, u * 0.5f));
                c.save();
                c.rotate(sec * 240f, x, y);
                c.drawLine(x - u * 1.3f, y, x + u * 1.3f, y, line);
                c.drawLine(x, y - u * 1.3f, x, y + u * 1.3f, line);
                c.restore();
                break;
            }
            case ALERT: {
                fill.setColor(IslandOverlay.RED);
                c.drawCircle(x, y, u * 1.9f, fill);
                txt.setColor(Color.WHITE);
                txt.setTextSize(u * 3f);
                c.drawText("!", x, y + u * 1.05f, txt);
                fill.setColor(0xFF6FB6FF); // sweat drop
                float p = (sec * 1.4f) % 1f;
                c.drawCircle(w * 0.2f, h * 0.3f + p * u * 4f, u * 0.7f, fill);
                break;
            }
            case ASK: {
                fill.setColor(IslandOverlay.GOLD);
                c.drawCircle(x, y, u * 1.9f, fill);
                txt.setColor(INK);
                txt.setTextSize(u * 3f);
                c.drawText("?", x, y + u * 1.05f, txt);
                break;
            }
            case LISTEN: {
                for (int k = 0; k < 4; k++) {
                    float bh = u * (1f + 2.2f * Math.abs((float) Math.sin(sec * 7 + k * 1.3f)));
                    fill.setColor(IslandOverlay.GOLD);
                    rect.set(x - u * 2.6f + k * u * 1.4f, y + u * 1.4f - bh, x - u * 2.6f + k * u * 1.4f + u * 0.9f, y + u * 1.4f);
                    c.drawRoundRect(rect, u * 0.45f, u * 0.45f, fill);
                }
                break;
            }
            case HAPPY:
            case DROP: {
                float p = (sec * 1.2f) % 1f;
                fill.setColor(mood == Mood.DROP ? IslandOverlay.GOLD : IslandOverlay.GREEN);
                spark(c, x - u * 0.6f, y + u * 0.6f - p * u * 1.4f, u * (2f - p * 0.9f));
                spark(c, x + u * 0.9f, y - u * 0.9f + p * u * 0.4f, u * (0.7f + p * 0.8f));
                break;
            }
            case SAD: {
                float p = (sec * 0.8f) % 1f;
                fill.setColor(0xFF6FB6FF);
                c.drawCircle(w * 0.36f, h * 0.55f + p * h * 0.25f, u * 0.75f, fill);
                break;
            }
            case LEARN: {
                float g = 0.6f + 0.4f * (float) Math.sin(sec * 4);
                fill.setColor(0x44FFD966);
                c.drawCircle(x, y, u * (2.7f + g * 0.4f), fill);
                fill.setColor(0xFFFFD966);
                c.drawCircle(x, y - u * 0.3f, u * 1.6f, fill);
                fill.setColor(0xFF9AA0A6);
                rect.set(x - u * 0.9f, y + u * 1.1f, x + u * 0.9f, y + u * 2f);
                c.drawRect(rect, fill);
                break;
            }
            default:
                break;
        }
    }

    private void spark(Canvas c, float x, float y, float s) {
        path.reset();
        path.moveTo(x, y - s);
        path.lineTo(x + s * 0.28f, y - s * 0.28f);
        path.lineTo(x + s, y);
        path.lineTo(x + s * 0.28f, y + s * 0.28f);
        path.lineTo(x, y + s);
        path.lineTo(x - s * 0.28f, y + s * 0.28f);
        path.lineTo(x - s, y);
        path.lineTo(x - s * 0.28f, y - s * 0.28f);
        path.close();
        c.drawPath(path, fill);
    }
}
