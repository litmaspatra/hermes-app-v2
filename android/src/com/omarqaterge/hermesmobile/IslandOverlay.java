package com.omarqaterge.hermesmobile;

import android.content.ClipData;
import android.content.ClipDescription;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * The floating "island": a small pill at the top of the screen, drawn by the app itself (display-over-other-apps).
 * A notification chip can't hold a pet, keep buttons on screen or accept a drop, so this does:
 *  - a cat that reacts to what Hermes is doing (sleeping, thinking, working, alarmed, asking, happy, sad);
 *  - the waiting approval with Allow / Deny buttons that stay until it is answered (the system never hides them);
 *  - a drop target: drag an image, file or text onto it, type what to do with it, and Hermes does it in the background.
 * All view work happens on the main thread; HermesService hands over immutable State snapshots through update().
 */
final class IslandOverlay {
    enum Mood { SLEEP, THINK, WORK, ALERT, ASK, HAPPY, SAD, LEARN, DROP, LISTEN }

    /** What the island shows right now, built by HermesService under its lock. */
    static final class State {
        Mood mood = Mood.SLEEP;
        String text = "";
        String sub = "";
        JSONObject approval;   // the first waiting approval, or null
        int approvals;         // how many are waiting
        int secsLeft;          // seconds left for the first one (0 = past its time, still waiting)
        float frac = 1f;       // fraction of its time left
    }

    static final int BG = 0xF2101216, GOLD = 0xFFD9A441, GREEN = 0xFF3DBE7A, RED = 0xFFE5564B, AMBER = 0xFFE8912D;
    static final int MAX_FILES = 5;
    static final long MAX_FILE = 20L * 1024 * 1024;

    static boolean canShow(Context c) {
        return Settings.canDrawOverlays(c);
    }

    private final HermesService svc;
    private final Context ui;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;

    private Root root;
    private WindowManager.LayoutParams lp;
    private LinearLayout col;
    private final ArrayList<Object[]> pills = new ArrayList<>(); // {TextView, base colour}
    private int ink = Color.WHITE, ink2 = 0xFF9AA0A6;
    private long lingerUntil = 0;
    private Mood prevMood = Mood.SLEEP;
    private boolean listening;
    private String listenText = "";

    private PetView pet;
    private TextView title, sub;
    private LinearLayout approvalBox, dropBox, buttons;
    private TextView apWhat, apCmd;
    private ImageView thumb;
    private TextView dropInfo;
    private EditText input;
    private State state = new State();
    private boolean added, suppressed, hiddenIdle, dragging, hovering, panelOpen, focusable, destroyed;
    private String note = "";
    private long noteUntil = 0;
    private final ArrayList<Uri> dropFiles = new ArrayList<>();
    private String dropText = "";

    // ── where the pet sits: under the status bar, on the top part of the screen's sides or the middle ──
    // tl top-left, tc top-centre, tr top-right, ml upper-left side, mr upper-right side; "wander" walks between them.
    static final String[] ANCHORS = {"tc", "tr", "mr", "ml", "tl"};
    private String curAnchor = "tc";
    private ValueAnimator mover;
    private boolean dragMoving, lastFull;
    private String lastPref;
    private float touchX, touchY;
    private int startX, startY;
    private final Runnable wanderTick = new Runnable() {
        @Override
        public void run() {
            wander();
        }
    };
    IslandOverlay(HermesService svc) {
        this.svc = svc;
        this.ui = new ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault);
        this.wm = svc.getSystemService(WindowManager.class);
        this.density = svc.getResources().getDisplayMetrics().density;
        main.post(this::build);
    }

    int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    // ── public API (any thread) ──────────────────────────────

    void update(State s) {
        main.post(() -> {
            if (destroyed) return;
            if (s.mood != Mood.SLEEP) hiddenIdle = false; // work brings a hidden island back
            if (prevMood != Mood.SLEEP && s.mood == Mood.SLEEP) {
                lingerUntil = SystemClock.uptimeMillis() + 4000; // stay a moment after Hermes finishes, then step aside
                main.postDelayed(this::render, 4100);
            }
            prevMood = s.mood;
            state = s;
            render();
        });
    }

    /** The app is on screen: it shows its own sheet, so the island steps aside. */
    void setSuppressed(boolean on) {
        main.post(() -> {
            suppressed = on;
            if (on && panelOpen) closePanel();
            render();
        });
    }

    /** A line shown in place of the status for a few seconds ("Sent", "Couldn't send"). */
    void note(String text) {
        main.post(() -> {
            note = text;
            noteUntil = SystemClock.uptimeMillis() + 4000;
            main.postDelayed(this::render, 4100);
            render();
        });
    }

    /** The voice listener is open (or its transcript changed): show the island whatever else is going on. */
    void listen(String transcript) {
        main.post(() -> {
            listening = true;
            listenText = transcript == null ? "" : transcript;
            render();
        });
    }

    void listenEnd() {
        main.post(() -> {
            listening = false;
            listenText = "";
            lingerUntil = SystemClock.uptimeMillis() + 2500;
            main.postDelayed(this::render, 2600);
            render();
        });
    }

    void destroy() {
        main.post(() -> {
            destroyed = true;
            main.removeCallbacks(wanderTick);
            if (mover != null) mover.cancel();
            remove();
        });
    }

    // ── views ────────────────────────────────────────────────

    private TextView label(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ui);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView pill(String s, int color, Runnable onClick) {
        TextView t = label(s, 13, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(0x22FFFFFF);
        g.setStroke(dp(1), color & 0x88FFFFFF);
        t.setBackground(g);
        t.setClickable(true);
        t.setOnClickListener(v -> onClick.run());
        pills.add(new Object[] {t, color});
        return t;
    }

    private void build() {
        if (destroyed || root != null) return;
        root = new Root(ui);
        root.setOnDragListener(this::onDrag);

        col = new LinearLayout(ui);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-2, -2));

        LinearLayout header = new LinearLayout(ui);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        pet = new PetView(ui);
        header.addView(pet, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout texts = new LinearLayout(ui);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = label("", 13, Color.WHITE, true);
        title.setMaxLines(5); // a few lines: the transcript, a reply, what is being approved
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setMaxWidth(dp(250));
        sub = label("", 11, 0xFF9AA0A6, false);
        sub.setSingleLine(true);
        sub.setMaxWidth(dp(220));
        texts.addView(title);
        texts.addView(sub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart(dp(4));
        header.addView(texts, tp);
        header.setOnClickListener(v -> svc.islandOpenApp());
        // Drag the pet to move it; it snaps to the nearest spot and stays there (Settings → Island → Position).
        header.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchX = e.getRawX();
                    touchY = e.getRawY();
                    if (added) {
                        startX = lp.x;
                        startY = lp.y;
                    }
                    dragMoving = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (!added) return false;
                    if (!dragMoving && (Math.abs(e.getRawX() - touchX) > dp(10) || Math.abs(e.getRawY() - touchY) > dp(10))) {
                        dragMoving = true;
                        if (mover != null) mover.cancel();
                        v.setPressed(false);
                        pet.walk(0);
                    }
                    if (dragMoving) {
                        lp.x = startX + (int) (e.getRawX() - touchX);
                        lp.y = startY + (int) (e.getRawY() - touchY);
                        try {
                            wm.updateViewLayout(root, lp);
                        } catch (Exception ignored) {
                        }
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragMoving) {
                        dragMoving = false;
                        if (svc.getSharedPreferences("hm", Context.MODE_PRIVATE).getBoolean("pet_snap", false)) snapNearest();
                        else pinHere();
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) main.post(() -> place(false)); // content changed size: keep it flush to its edge
        });
        main.postDelayed(wanderTick, 20_000);
        header.setOnLongClickListener(v -> {
            if (state.mood == Mood.SLEEP && !panelOpen) {
                hiddenIdle = true; // idle only: it comes back as soon as Hermes works
                render();
                return true;
            }
            return false;
        });
        col.addView(header);

        approvalBox = new LinearLayout(ui);
        approvalBox.setOrientation(LinearLayout.VERTICAL);
        approvalBox.setPadding(dp(4), dp(4), dp(4), dp(2));
        apWhat = label("", 12, Color.WHITE, true);
        apWhat.setMaxLines(4);
        apCmd = label("", 11, GOLD, false);
        apCmd.setTypeface(Typeface.MONOSPACE);
        apCmd.setMaxLines(8);
        apCmd.setEllipsize(android.text.TextUtils.TruncateAt.END);
        approvalBox.addView(apWhat);
        approvalBox.addView(apCmd);
        LinearLayout row = new LinearLayout(ui);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(2));
        buttons = row;
        row.addView(pill("✓ Allow", GREEN, () -> answer("once")), weight());
        row.addView(pill("Session", GOLD, () -> answer("session")), weight());
        row.addView(pill("✕ Deny", RED, () -> answer("deny")), weight());
        approvalBox.addView(row);
        col.addView(approvalBox);

        dropBox = new LinearLayout(ui);
        dropBox.setOrientation(LinearLayout.VERTICAL);
        dropBox.setPadding(dp(4), dp(6), dp(4), dp(2));
        LinearLayout top = new LinearLayout(ui);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        thumb = new ImageView(ui);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        top.addView(thumb, new LinearLayout.LayoutParams(dp(52), dp(52)));
        dropInfo = label("", 12, 0xFFCFD3D8, false);
        dropInfo.setMaxLines(3);
        dropInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1f);
        ip.setMarginStart(dp(10));
        top.addView(dropInfo, ip);
        dropBox.addView(top);
        LinearLayout chips = new LinearLayout(ui);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(8), 0, 0);
        for (String c : new String[]{"Describe it", "Extract text", "Summarize"}) {
            TextView ch = pill(c, 0xFFCFD3D8, () -> {
                input.setText(c);
                input.setSelection(input.length());
            });
            ch.setTextSize(11);
            ch.setPadding(dp(8), dp(5), dp(8), dp(5));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.setMarginEnd(dp(6));
            chips.addView(ch, cp);
        }
        dropBox.addView(chips);
        input = new EditText(ui);
        input.setHint("What should Hermes do with it?");
        input.setHintTextColor(0xFF7C838B);
        input.setTextColor(Color.WHITE);
        input.setTextSize(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(4);
        GradientDrawable ig = new GradientDrawable();
        ig.setCornerRadius(dp(14));
        ig.setColor(0x22FFFFFF);
        input.setBackground(ig);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams inp = new LinearLayout.LayoutParams(-1, -2);
        inp.topMargin = dp(8);
        dropBox.addView(input, inp);
        LinearLayout acts = new LinearLayout(ui);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setPadding(0, dp(8), 0, dp(2));
        acts.addView(pill("✕ Cancel", 0xFFCFD3D8, this::closePanel), weight());
        acts.addView(pill("Send ➤", GOLD, this::send), weight());
        dropBox.addView(acts);
        col.addView(dropBox);

        render();
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMarginEnd(dp(6));
        return p;
    }

    /** Back closes the prompt; the window only takes keys while the prompt is open. */
    private final class Root extends FrameLayout {
        Root(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && panelOpen) {
                if (e.getAction() == KeyEvent.ACTION_UP) closePanel();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    // ── render ───────────────────────────────────────────────

    private void render() {
        if (destroyed || root == null) return;
        long now = SystemClock.uptimeMillis();
        boolean idle = state.mood == Mood.SLEEP;
        boolean always = "always".equals(svc.getSharedPreferences("hm", Context.MODE_PRIVATE).getString("island_mode", "ondemand"));
        boolean noting = noteUntil > now;
        // Shown in full when Hermes is busy, an approval waits, a drag/drop/voice is going on, for a moment after it
        // finishes, or always if the user chose that. Otherwise only an invisible strip stays so a drag has a target.
        boolean full = !idle || listening || dragging || panelOpen || noting || lingerUntil > now || (always && !hiddenIdle);
        if (suppressed && !listening) {
            remove();
            return;
        }
        ensureAdded();
        lastFull = full;
        String pp = posPref();
        boolean moved = lastPref != null && !pp.equals(lastPref); // the user picked another spot in Settings: walk there
        lastPref = pp;
        place(moved);
        applyTheme(full);

        boolean approving = state.approval != null;
        Mood m = listening ? Mood.LISTEN : dragging ? Mood.DROP : state.mood;
        pet.setMood(m);
        pet.setSpecies(svc.getSharedPreferences("hm", Context.MODE_PRIVATE).getString("pet", "blob"));

        String t;
        String s = state.sub == null ? "" : state.sub;
        if (listening) t = listenText.isEmpty() ? "Listening…" : listenText;
        else if (noting) t = note;
        else if (dragging) t = hovering ? "Drop it here" : "Drop to ask Hermes";
        else if (panelOpen) t = "What should I do with it?";
        else if (m == Mood.ALERT) {
            t = state.secsLeft > 0 ? "Approve? " + state.secsLeft + "s" : "Approve? Still waiting";
            if (state.approvals <= 1) s = "Tap Allow or Deny";
            if (state.approvals > 1) s = "+" + (state.approvals - 1) + " more waiting";
        } else if (idle) t = "";
        else t = state.text == null ? "" : state.text;
        if (listening) s = "";
        title.setText(t);
        title.setTextSize(t.length() < 36 ? 13 : t.length() < 90 ? 11.5f : 10); // long text gets smaller, never cut off early
        title.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
        sub.setText(s);
        sub.setVisibility(s.isEmpty() || t.isEmpty() ? View.GONE : View.VISIBLE);

        // Approvals live in the system island (notification buttons). The floating pet only shows buttons if asked to.
        boolean showApproval = approving && !dragging && !listening && svc.getSharedPreferences("hm", Context.MODE_PRIVATE).getBoolean("island_approvals", true);
        approvalBox.setVisibility(showApproval ? View.VISIBLE : View.GONE);
        if (approving) {
            String what = state.approval.optString("what", "");
            String cmd = state.approval.optString("command", "");
            if (what.isEmpty() && cmd.isEmpty()) what = state.approval.optString("body", "a command");
            String risk = ApprovalPolicy.risk(cmd);
            if (risk != null) what = "⚠ " + risk + (what.isEmpty() ? "" : " · " + what);
            apWhat.setText(what);
            apWhat.setTextSize(what.length() < 80 ? 12 : 10.5f);
            apCmd.setTextSize(cmd.length() < 70 ? 11 : cmd.length() < 160 ? 10 : 9); // the longer, the smaller
            apWhat.setVisibility(what.isEmpty() ? View.GONE : View.VISIBLE);
            apCmd.setText(cmd.isEmpty() ? "" : "$ " + cmd);
            apCmd.setVisibility(cmd.isEmpty() ? View.GONE : View.VISIBLE);
            buttons.getChildAt(1).setVisibility(state.approval.optBoolean("allow_session", true) ? View.VISIBLE : View.GONE);
        }
        dropBox.setVisibility(panelOpen && !dragging && !listening ? View.VISIBLE : View.GONE);

        root.setMinimumWidth(!full ? dp(120) : dragging ? dp(250) : panelOpen || showApproval ? dp(320) : 0);
        root.setMinimumHeight(!full ? dp(20) : dragging ? dp(64) : 0);
        root.invalidate();
    }

    /** The island's frame and text follow the app theme (SharedPreferences "hm" key "theme"); the pet never changes. */
    private void applyTheme(boolean full) {
        String th = svc.getSharedPreferences("hm", Context.MODE_PRIVATE).getString("theme", "material");
        boolean night = (svc.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        col.setVisibility(full ? View.VISIBLE : View.GONE);
        int stroke = dragging ? GOLD : strokeFor(listening ? Mood.LISTEN : state.mood);
        Drawable bgd;
        int padR = dp(12), padB = dp(6);
        if (!full) {
            bgd = null;
            root.setPadding(0, 0, 0, 0);
        } else if ("neo".equals(th)) {
            ink = 0xFF000000;
            ink2 = 0xFF333333;
            GradientDrawable shadow = new GradientDrawable();
            shadow.setColor(0xFF000000);
            shadow.setCornerRadius(dp(6));
            GradientDrawable face = new GradientDrawable();
            face.setColor(0xFFFFF6E5);
            face.setCornerRadius(dp(6));
            face.setStroke(dp(3), 0xFF000000);
            LayerDrawable ld = new LayerDrawable(new Drawable[] {shadow, face});
            ld.setLayerInset(0, dp(4), dp(4), 0, 0);
            ld.setLayerInset(1, 0, 0, dp(4), dp(4));
            bgd = ld;
            padR += dp(4);
            padB += dp(4);
            root.setPadding(dp(10), dp(6), padR, padB);
        } else {
            GradientDrawable g = new GradientDrawable();
            if ("glass".equals(th)) {
                ink = 0xFF14161A;
                ink2 = 0xFF3A3F47;
                g.setColor(0xCCFFFFFF);
                g.setCornerRadius(dp(26));
                g.setStroke(dp(2), (stroke & 0x00FFFFFF) | 0xCC000000);
            } else {
                ink = night ? 0xFFE6E0E9 : 0xFF1D1B20;
                ink2 = night ? 0xFFCAC4D0 : 0xFF49454F;
                g.setColor(night ? 0xFF2B2930 : 0xFFF3EDF7);
                g.setCornerRadius(dp(28));
                g.setStroke(dp(2), (stroke & 0x00FFFFFF) | 0x99000000);
            }
            bgd = g;
            root.setPadding(dp(10), dp(6), padR, padB);
        }
        root.setBackground(bgd);
        title.setTextColor(ink);
        sub.setTextColor(ink2);
        apWhat.setTextColor(ink);
        apCmd.setTextColor(ink);
        dropInfo.setTextColor(ink2);
        input.setTextColor(ink);
        input.setHintTextColor(ink2);
        GradientDrawable ig = new GradientDrawable();
        ig.setCornerRadius(dp("neo".equals(th) ? 6 : 14));
        ig.setColor((ink & 0x00FFFFFF) | 0x1A000000);
        if ("neo".equals(th)) ig.setStroke(dp(2), 0xFF000000);
        input.setBackground(ig);
        for (Object[] pr : pills) {
            TextView t = (TextView) pr[0];
            int base = (Integer) pr[1];
            GradientDrawable pg = new GradientDrawable();
            if ("neo".equals(th)) {
                pg.setCornerRadius(dp(6));
                pg.setColor(base == GREEN ? 0xFF3DDC84 : base == GOLD ? 0xFFFFD43B : base == RED ? 0xFFFF3B30 : 0xFFFFFFFF);
                pg.setStroke(dp(2), 0xFF000000);
                t.setTextColor(0xFF000000);
            } else {
                pg.setCornerRadius(dp(16));
                pg.setColor((base & 0x00FFFFFF) | 0x33000000);
                pg.setStroke(dp(1), (base & 0x00FFFFFF) | 0x99000000);
                t.setTextColor(ink);
            }
            t.setBackground(pg);
        }
        // glass: blur what is behind the island where the system allows it (Android 12+)
        if (added && Build.VERSION.SDK_INT >= 31) {
            boolean glass = "glass".equals(th) && full && !svc.getSharedPreferences("hm", Context.MODE_PRIVATE).getBoolean("reduce_effects", false);
            int want = glass ? dp(24) : 0;
            if (lp.getBlurBehindRadius() != want) {
                lp.setBlurBehindRadius(want);
                if (glass) lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                else lp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                try {
                    wm.updateViewLayout(root, lp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private int strokeFor(Mood m) {
        switch (m) {
            case ALERT:
                return state.frac > 0.5f ? AMBER : RED;
            case THINK:
            case WORK:
            case ASK:
            case LEARN:
            case LISTEN:
                return GOLD;
            case HAPPY:
                return GREEN;
            case SAD:
                return RED;
            default:
                return 0x66808080;
        }
    }

    private void ensureAdded() {
        if (added) return;
        lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START; // x and y are absolute, so the pet can sit at either side
        lp.x = Math.max(0, (svc.getResources().getDisplayMetrics().widthPixels - dp(120)) / 2);
        lp.y = topOffset();
        lp.setTitle("Hermes island");
        try {
            wm.addView(root, lp);
            added = true;
            focusable = false;
        } catch (Exception e) {
            added = false; // permission revoked meanwhile: the service drops the island on its next refresh
        }
    }

    private String posPref() {
        return svc.getSharedPreferences("hm", Context.MODE_PRIVATE).getString("pet_pos", "wander");
    }

    /** Pixel position for a spot, for a window of the given size. */
    private int[] anchorXY(String a, int w) {
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        int margin = dp(8), top = topOffset();
        int left = margin, right = Math.max(margin, dm.widthPixels - w - margin);
        int x = (dm.widthPixels - w) / 2, y = top;
        switch (a) {
            case "tl":
                x = left;
                break;
            case "tr":
                x = right;
                break;
            case "ml":
                x = left;
                y = top + (int) (dm.heightPixels * 0.14f); // the upper part of the side, never the lower part
                break;
            case "mr":
                x = right;
                y = top + (int) (dm.heightPixels * 0.14f);
                break;
            default:
                break;
        }
        return new int[] {Math.max(left, Math.min(right, x)), y};
    }

    /** Moves the window to where the chosen spot is; `animate` walks there. */
    private void place(boolean animate) {
        if (!added || root == null || dragMoving) return;
        String pref = posPref();
        String a = "wander".equals(pref) ? curAnchor : pref;
        curAnchor = a;
        int w = root.getWidth() > 0 ? root.getWidth() : dp(120);
        int[] t = "free".equals(pref) ? freeXY(w) : anchorXY(a, w);
        if (lp.x == t[0] && lp.y == t[1]) return;
        if (mover != null && mover.isRunning()) return; // already walking; the end of the walk re-checks
        if (!animate) {
            lp.x = t[0];
            lp.y = t[1];
            try {
                wm.updateViewLayout(root, lp);
            } catch (Exception ignored) {
            }
            return;
        }
        final int fx = lp.x, fy = lp.y, tx = t[0], ty = t[1];
        pet.walk(Integer.compare(tx, fx));
        mover = ValueAnimator.ofFloat(0f, 1f);
        mover.setDuration(900);
        mover.setInterpolator(new DecelerateInterpolator());
        mover.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            lp.x = fx + (int) ((tx - fx) * f);
            lp.y = fy + (int) ((ty - fy) * f);
            try {
                wm.updateViewLayout(root, lp);
            } catch (Exception ignored) {
            }
            if (f >= 1f) {
                pet.walk(0);
                main.post(() -> place(false));
            }
        });
        mover.start();
    }

    /** Every 20–40 s, if the pet is just hanging around, it strolls to another spot. */
    private void wander() {
        main.postDelayed(wanderTick, 20_000 + (long) (Math.random() * 20_000));
        if (destroyed || !added || !"wander".equals(posPref()) || !lastFull) return;
        if (listening || dragging || panelOpen || state.approval != null || dragMoving || suppressed) return;
        String next = curAnchor;
        for (int i = 0; i < 6 && next.equals(curAnchor); i++) next = ANCHORS[(int) (Math.random() * ANCHORS.length)];
        curAnchor = next;
        place(true);
    }

    /** Where the pet was pinned, as a fraction of the screen, kept on screen whatever the window's size. */
    private int[] freeXY(int w) {
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        android.content.SharedPreferences sp = svc.getSharedPreferences("hm", Context.MODE_PRIVATE);
        int x = Math.round(sp.getFloat("pet_fx", 0.5f) * dm.widthPixels), y = Math.round(sp.getFloat("pet_fy", 0.02f) * dm.heightPixels);
        int h = root.getHeight() > 0 ? root.getHeight() : dp(60);
        return new int[] {Math.max(0, Math.min(dm.widthPixels - w, x)), Math.max(0, Math.min(dm.heightPixels - h, y))};
    }

    /** After a drag: stay exactly where it was dropped (Settings → Island → Position becomes "Pinned"). */
    private void pinHere() {
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        svc.getSharedPreferences("hm", Context.MODE_PRIVATE).edit().putString("pet_pos", "free")
                .putFloat("pet_fx", lp.x / (float) dm.widthPixels).putFloat("pet_fy", lp.y / (float) dm.heightPixels).apply();
        lastPref = "free";
    }

    /** After a drag: sit at the nearest spot, and stay there (the setting becomes that spot). */
    private void snapNearest() {
        int w = root.getWidth() > 0 ? root.getWidth() : dp(120);
        String best = "tc";
        long bestD = Long.MAX_VALUE;
        for (String a : ANCHORS) {
            int[] p = anchorXY(a, w);
            long d = (long) (p[0] - lp.x) * (p[0] - lp.x) + (long) (p[1] - lp.y) * (p[1] - lp.y);
            if (d < bestD) {
                bestD = d;
                best = a;
            }
        }
        svc.getSharedPreferences("hm", Context.MODE_PRIVATE).edit().putString("pet_pos", best).apply();
        curAnchor = best;
        place(true);
    }

    /** Top of the island. With the system's own island also on, sit below the status bar so it doesn't cover ours. */
    private int topOffset() {
        if (!svc.systemChipWanted()) return dp(6); // no system island: use the very top
        int id = svc.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int bar = id > 0 ? svc.getResources().getDimensionPixelSize(id) : dp(28);
        return bar + dp(4);
    }

    private void remove() {
        if (!added) return;
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
        }
        added = false;
        pet.stop();
    }

    private void setFocusable(boolean on) {
        if (!added || on == focusable) return;
        focusable = on;
        if (on) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
        }
        try {
            wm.updateViewLayout(root, lp);
        } catch (Exception ignored) {
        }
    }

    // ── approval buttons ─────────────────────────────────────

    private void answer(String choice) {
        JSONObject a = state.approval;
        if (a == null) return;
        for (int k = 0; k < buttons.getChildCount(); k++) buttons.getChildAt(k).setAlpha(0.45f); // one tap per answer
        buttons.postDelayed(() -> {
            for (int k = 0; k < buttons.getChildCount(); k++) buttons.getChildAt(k).setAlpha(1f);
        }, 4000);
        svc.islandApprove(a, choice);
    }

    // ── drag and drop ────────────────────────────────────────

    private boolean onDrag(View v, DragEvent e) {
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED: {
                ClipDescription d = e.getClipDescription();
                if (d == null || d.getMimeTypeCount() == 0 || suppressed) return false;
                dragging = true;
                hovering = false;
                render();
                return true;
            }
            case DragEvent.ACTION_DRAG_ENTERED:
                hovering = true;
                render();
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                hovering = false;
                render();
                return true;
            case DragEvent.ACTION_DROP:
                return onDrop(e);
            case DragEvent.ACTION_DRAG_ENDED:
                dragging = false;
                hovering = false;
                render();
                return true;
            default:
                return false;
        }
    }

    private boolean onDrop(DragEvent e) {
        ClipData clip = e.getClipData();
        if (clip == null || clip.getItemCount() == 0) return false;
        // The source app lends read access to what it dragged for as long as this is held. It is tied to an
        // activity, so it needs the app's activity to be alive; without it the copy below may still work
        // (public media, the app's own files) and otherwise says so.
        android.view.DragAndDropPermissions perms = null;
        MainActivity a = MainActivity.current;
        if (a != null) {
            try {
                perms = a.requestDragAndDropPermissions(e);
            } catch (Exception ignored) {
            }
        }
        final android.view.DragAndDropPermissions held = perms;
        final ArrayList<Uri> uris = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        for (int k = 0; k < clip.getItemCount(); k++) {
            ClipData.Item it = clip.getItemAt(k);
            Uri u = it.getUri();
            if (u != null && uris.size() < MAX_FILES) uris.add(u);
            else if (u == null && it.getText() != null && text.length() < 20000) {
                if (text.length() > 0) text.append("\n");
                text.append(it.getText());
            }
        }
        final String dropped = text.toString();
        new Thread(() -> {
            ArrayList<Uri> copied = new ArrayList<>();
            for (Uri u : uris) {
                Uri c = copyToCache(u);
                if (c != null) copied.add(c);
            }
            main.post(() -> {
                if (held != null) {
                    try {
                        held.release();
                    } catch (Exception ignored) {
                    }
                }
                dragging = false;
                hovering = false;
                if (copied.isEmpty() && dropped.isEmpty()) {
                    note("Couldn't read that. Share it to Hermes instead");
                    return;
                }
                openPanel(copied, dropped, uris.size() - copied.size());
            });
        }, "island-drop").start();
        return true;
    }

    /** Copies one dropped file into the app's cache, so it can be read after the source's grant is gone. */
    private Uri copyToCache(Uri u) {
        try {
            File dir = new File(svc.getCacheDir(), "island");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            long cutoff = System.currentTimeMillis() - 3600_000L; // old drops are not needed any more
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (f.lastModified() < cutoff) f.delete();

            String name = null;
            try (android.database.Cursor c = svc.getContentResolver().query(u, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (ni >= 0) name = c.getString(ni);
                }
            } catch (Exception ignored) {
            }
            if (name == null || name.isEmpty()) name = u.getLastPathSegment();
            if (name == null || name.isEmpty()) name = "dropped";
            name = name.replaceAll("[/\\\\\\x00-\\x1f]", "_");
            if (!name.contains(".")) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(svc.getContentResolver().getType(u));
                if (ext != null) name = name + "." + ext;
            }
            File out = new File(dir, System.currentTimeMillis() + "_" + name);
            long total = 0;
            try (InputStream in = svc.getContentResolver().openInputStream(u); FileOutputStream os = new FileOutputStream(out)) {
                if (in == null) return null;
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) {
                    total += r;
                    if (total > MAX_FILE) {
                        os.close();
                        out.delete();
                        return null;
                    }
                    os.write(buf, 0, r);
                }
            }
            return Uri.fromFile(out);
        } catch (Exception e) {
            return null;
        }
    }

    private void openPanel(ArrayList<Uri> files, String text, int unreadable) {
        dropFiles.clear();
        dropFiles.addAll(files);
        dropText = text;
        StringBuilder info = new StringBuilder();
        Bitmap bmp = null;
        if (!files.isEmpty()) {
            File f = new File(files.get(0).getPath());
            bmp = preview(f);
            info.append(files.size() == 1 ? f.getName().replaceFirst("^\\d+_", "") : files.size() + " files");
            if (unreadable > 0) info.append("\n").append(unreadable).append(" couldn't be read");
        }
        if (!text.isEmpty()) {
            if (info.length() > 0) info.append("\n");
            info.append(text.length() > 90 ? text.substring(0, 90) + "…" : text);
        }
        dropInfo.setText(info);
        if (bmp != null) {
            thumb.setImageBitmap(bmp);
            thumb.setVisibility(View.VISIBLE);
        } else {
            thumb.setImageDrawable(null);
            thumb.setVisibility(View.GONE);
        }
        input.setText("");
        panelOpen = true;
        render();
        setFocusable(true);
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = ui.getSystemService(InputMethodManager.class);
            if (imm != null && panelOpen) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 250);
    }

    private Bitmap preview(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 160 && o.outHeight / (sample * 2) >= 160) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    private void closePanel() {
        if (!panelOpen) return;
        panelOpen = false;
        InputMethodManager imm = ui.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        setFocusable(false);
        dropFiles.clear();
        dropText = "";
        render();
    }

    private void send() {
        String typed = input.getText().toString().trim();
        if (typed.isEmpty()) {
            input.setHint("Type what Hermes should do");
            input.setHintTextColor(AMBER);
            return;
        }
        String prompt = dropText.isEmpty() ? typed : typed + "\n\n" + dropText;
        ArrayList<Uri> files = new ArrayList<>(dropFiles);
        closePanel();
        note("Sent. Hermes is on it");
        svc.islandSubmit(prompt, files);
    }
}
