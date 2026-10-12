package com.omarqaterge.hermesmobile;

import android.content.ClipData;
import android.content.ClipDescription;
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
import android.view.ContextThemeWrapper;
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
        header.addView(pet, new LinearLayout.LayoutParams(dp(44), dp(38)));
        LinearLayout texts = new LinearLayout(ui);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = label("", 13, Color.WHITE, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setMaxWidth(dp(220));
        sub = label("", 11, 0xFF9AA0A6, false);
        sub.setSingleLine(true);
        sub.setMaxWidth(dp(220));
        texts.addView(title);
        texts.addView(sub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart(dp(4));
        header.addView(texts, tp);
        header.setOnClickListener(v -> svc.islandOpenApp());
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
        apWhat.setMaxLines(2);
        apCmd = label("", 11, GOLD, false);
        apCmd.setTypeface(Typeface.MONOSPACE);
        apCmd.setMaxLines(3);
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
        applyTheme(full);

        boolean approving = state.approval != null;
        Mood m = listening ? Mood.LISTEN : dragging ? Mood.DROP : state.mood;
        pet.setMood(m);

        String t;
        String s = state.sub == null ? "" : state.sub;
        if (listening) t = listenText.isEmpty() ? "Listening…" : listenText;
        else if (noting) t = note;
        else if (dragging) t = hovering ? "Drop it here" : "Drop to ask Hermes";
        else if (panelOpen) t = "What should I do with it?";
        else if (m == Mood.ALERT) {
            t = state.secsLeft > 0 ? "Approve? " + state.secsLeft + "s" : "Approve? Still waiting";
            if (state.approvals > 1) s = "+" + (state.approvals - 1) + " more waiting";
        } else if (idle) t = "";
        else t = state.text == null ? "" : state.text;
        if (listening) s = "";
        title.setText(t);
        title.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
        sub.setText(s);
        sub.setVisibility(s.isEmpty() || t.isEmpty() ? View.GONE : View.VISIBLE);

        boolean showApproval = approving && !dragging && !listening;
        approvalBox.setVisibility(showApproval ? View.VISIBLE : View.GONE);
        if (approving) {
            String what = state.approval.optString("what", "");
            String cmd = state.approval.optString("command", "");
            if (what.isEmpty() && cmd.isEmpty()) what = state.approval.optString("body", "a command");
            String risk = ApprovalPolicy.risk(cmd);
            if (risk != null) what = "⚠ " + risk + (what.isEmpty() ? "" : " · " + what);
            apWhat.setText(what);
            apWhat.setVisibility(what.isEmpty() ? View.GONE : View.VISIBLE);
            apCmd.setText(cmd.isEmpty() ? "" : "$ " + cmd);
            apCmd.setVisibility(cmd.isEmpty() ? View.GONE : View.VISIBLE);
            buttons.getChildAt(1).setVisibility(state.approval.optBoolean("allow_session", true) ? View.VISIBLE : View.GONE);
        }
        dropBox.setVisibility(panelOpen && !dragging && !listening ? View.VISIBLE : View.GONE);

        root.setMinimumWidth(!full ? dp(120) : dragging ? dp(250) : panelOpen || showApproval ? dp(300) : 0);
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
            boolean glass = "glass".equals(th) && full;
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
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(6);
        lp.setTitle("Hermes island");
        try {
            wm.addView(root, lp);
            added = true;
            focusable = false;
        } catch (Exception e) {
            added = false; // permission revoked meanwhile: the service drops the island on its next refresh
        }
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

    // ── the pet ──────────────────────────────────────────────

    /** A cat's face, redrawn about 25 times a second (slower asleep) while the island is on screen. */
    static final class PetView extends View {
        private Mood mood = Mood.SLEEP;
        private boolean running = true;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private static final int FUR = 0xFFE9B15C, EAR = 0xFFF2A5A0, INK = 0xFF1B1B1F;

        PetView(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            txt.setTypeface(Typeface.DEFAULT_BOLD);
            txt.setTextAlign(Paint.Align.CENTER);
        }

        void setMood(Mood m) {
            if (m == mood) return;
            mood = m;
            running = true;
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

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float sec = SystemClock.uptimeMillis() / 1000f;
            float r = h * 0.34f;
            float cx = w * 0.42f, cy = h * 0.62f;
            float dx = 0, dy = 0, rot = 0, sy = 1;
            switch (mood) {
                case SLEEP:
                    sy = 1f + 0.03f * (float) Math.sin(sec * 1.6);
                    break;
                case THINK:
                    rot = 8f * (float) Math.sin(sec * 1.4);
                    break;
                case WORK:
                    dy = -h * 0.04f * Math.abs((float) Math.sin(sec * 7));
                    break;
                case ALERT:
                    dx = h * 0.04f * (float) Math.sin(sec * 30);
                    break;
                case ASK:
                    rot = -10f + 4f * (float) Math.sin(sec * 2);
                    break;
                case LISTEN:
                    rot = 6f * (float) Math.sin(sec * 3);
                    break;
                case HAPPY:
                case DROP:
                    dy = -h * 0.09f * Math.abs((float) Math.sin(sec * 6));
                    break;
                case SAD:
                    dy = h * 0.03f;
                    break;
                case LEARN:
                    dy = -h * 0.02f * (float) Math.sin(sec * 3);
                    break;
            }
            c.save();
            c.translate(cx + dx, cy + dy);
            c.rotate(rot);
            c.scale(1f, sy);
            drawCat(c, r, sec);
            c.restore();
            drawExtra(c, w, h, sec);
            if (running && isAttachedToWindow()) postInvalidateDelayed(mood == Mood.SLEEP ? 90 : 40);
        }

        private void drawCat(Canvas c, float r, float sec) {
            boolean sad = mood == Mood.SAD;
            // ears
            for (int side = -1; side <= 1; side += 2) {
                path.reset();
                float tipX = side * r * (sad ? 1.1f : 0.78f), tipY = sad ? -r * 0.78f : -r * 1.18f;
                path.moveTo(side * r * 0.97f, -r * 0.15f);
                path.lineTo(tipX, tipY);
                path.lineTo(side * r * 0.12f, -r * 0.82f);
                path.close();
                fill.setColor(FUR);
                c.drawPath(path, fill);
                path.reset();
                path.moveTo(side * r * 0.82f, -r * 0.3f);
                path.lineTo(side * r * (sad ? 0.98f : 0.74f), sad ? -r * 0.72f : -r * 0.98f);
                path.lineTo(side * r * 0.3f, -r * 0.78f);
                path.close();
                fill.setColor(EAR);
                c.drawPath(path, fill);
            }
            // head
            fill.setColor(FUR);
            rect.set(-r * 1.08f, -r * 0.92f, r * 1.08f, r * 0.92f);
            c.drawOval(rect, fill);
            if (mood == Mood.HAPPY || mood == Mood.DROP) {
                fill.setColor(0x66F2A5A0);
                c.drawCircle(-r * 0.7f, r * 0.25f, r * 0.2f, fill);
                c.drawCircle(r * 0.7f, r * 0.25f, r * 0.2f, fill);
            }
            // eyes
            float ex = r * 0.42f, ey = -r * 0.08f, er = r * 0.2f;
            boolean blink = (sec % 3.4f) > 3.28f;
            line.setColor(INK);
            line.setStrokeWidth(Math.max(1.5f, r * 0.1f));
            for (int side = -1; side <= 1; side += 2) {
                float x = side * ex;
                switch (mood) {
                    case SLEEP:
                        line.setStyle(Paint.Style.STROKE);
                        rect.set(x - er, ey - er * 0.7f, x + er, ey + er * 0.7f);
                        c.drawArc(rect, 20, 140, false, line);
                        break;
                    case HAPPY:
                    case DROP:
                        rect.set(x - er, ey - er * 0.4f, x + er, ey + er * 1.2f);
                        c.drawArc(rect, 200, 140, false, line);
                        break;
                    case ALERT: {
                        fill.setColor(Color.WHITE);
                        c.drawCircle(x, ey, er * 1.45f, fill);
                        fill.setColor(INK);
                        c.drawCircle(x, ey, er * 0.55f, fill);
                        break;
                    }
                    default: {
                        float look = mood == Mood.THINK ? 1f : 0f;
                        float px = look * er * 0.3f * (float) Math.sin(sec * 1.4), py = -look * er * 0.3f;
                        float k = blink ? 0.12f : (mood == Mood.ASK && side > 0 ? 1.3f : 1f);
                        fill.setColor(INK);
                        rect.set(x - er * 0.75f + px, ey - er * k + py, x + er * 0.75f + px, ey + er * k + py);
                        c.drawOval(rect, fill);
                        if (!blink) {
                            fill.setColor(Color.WHITE);
                            c.drawCircle(x - er * 0.2f + px, ey - er * 0.35f + py, er * 0.28f, fill);
                        }
                        if (sad) {
                            line.setStrokeWidth(Math.max(1.2f, r * 0.08f));
                            c.drawLine(x - side * er * 1.1f, ey - er * 1.5f, x + side * er * 0.9f, ey - er * 1.1f, line);
                            line.setStrokeWidth(Math.max(1.5f, r * 0.1f));
                        }
                    }
                }
            }
            // nose and mouth
            fill.setColor(EAR);
            path.reset();
            path.moveTo(-r * 0.1f, r * 0.14f);
            path.lineTo(r * 0.1f, r * 0.14f);
            path.lineTo(0, r * 0.26f);
            path.close();
            c.drawPath(path, fill);
            line.setColor(INK);
            line.setStrokeWidth(Math.max(1.2f, r * 0.07f));
            if (mood == Mood.HAPPY || mood == Mood.DROP) {
                path.reset();
                path.moveTo(-r * 0.28f, r * 0.34f);
                path.quadTo(0, r * 0.82f, r * 0.28f, r * 0.34f);
                path.close();
                fill.setColor(INK);
                c.drawPath(path, fill);
            } else if (mood == Mood.ALERT) {
                rect.set(-r * 0.11f, r * 0.36f, r * 0.11f, r * 0.62f);
                fill.setColor(INK);
                c.drawOval(rect, fill);
            } else if (sad) {
                rect.set(-r * 0.25f, r * 0.42f, r * 0.25f, r * 0.74f);
                c.drawArc(rect, 200, 140, false, line);
            } else {
                rect.set(-r * 0.24f, r * 0.2f, 0, r * 0.46f);
                c.drawArc(rect, 0, 160, false, line);
                rect.set(0, r * 0.2f, r * 0.24f, r * 0.46f);
                c.drawArc(rect, 20, 160, false, line);
            }
            // whiskers
            line.setColor(0x88FFFFFF);
            line.setStrokeWidth(Math.max(1f, r * 0.05f));
            for (int side = -1; side <= 1; side += 2)
                for (int k = -1; k <= 1; k++)
                    c.drawLine(side * r * 0.62f, r * 0.3f + k * r * 0.1f, side * r * 1.25f, r * 0.26f + k * r * 0.24f, line);
        }

        private void drawExtra(Canvas c, float w, float h, float sec) {
            float x = w * 0.86f, y = h * 0.3f, u = h * 0.16f;
            switch (mood) {
                case SLEEP: {
                    txt.setColor(0xFF9AA0A6);
                    float p = (sec * 0.7f) % 1f;
                    txt.setTextSize(u * (1.1f + p * 0.5f));
                    txt.setAlpha((int) (255 * (1f - p)));
                    c.drawText("z", x - u * 0.4f, y + u * 0.9f - p * u * 1.6f, txt);
                    txt.setAlpha(255);
                    break;
                }
                case THINK: {
                    int on = (int) (sec * 3) % 3;
                    for (int k = 0; k < 3; k++) {
                        fill.setColor(k == on ? GOLD : 0x66FFFFFF);
                        c.drawCircle(x - u * 1.1f + k * u * 1.1f, y + (k == on ? -u * 0.3f : 0), u * 0.42f, fill);
                    }
                    break;
                }
                case WORK: {
                    line.setColor(GOLD);
                    line.setStrokeWidth(Math.max(1.5f, u * 0.35f));
                    c.save();
                    c.rotate(sec * 240f, x, y);
                    c.drawLine(x - u, y, x + u, y, line);
                    c.drawLine(x, y - u, x, y + u, line);
                    c.restore();
                    break;
                }
                case ALERT: {
                    fill.setColor(RED);
                    c.drawCircle(x, y, u * 1.35f, fill);
                    txt.setColor(Color.WHITE);
                    txt.setTextSize(u * 2f);
                    c.drawText("!", x, y + u * 0.7f, txt);
                    break;
                }
                case LISTEN: {
                    // a little level meter: four bars that dance
                    for (int k = 0; k < 4; k++) {
                        float bh = u * (0.8f + 1.6f * Math.abs((float) Math.sin(sec * 7 + k * 1.3f)));
                        fill.setColor(GOLD);
                        rect.set(x - u * 1.9f + k * u * 1.0f, y + u - bh, x - u * 1.9f + k * u * 1.0f + u * 0.6f, y + u);
                        c.drawRoundRect(rect, u * 0.3f, u * 0.3f, fill);
                    }
                    break;
                }
                case ASK: {
                    fill.setColor(GOLD);
                    c.drawCircle(x, y, u * 1.35f, fill);
                    txt.setColor(INK);
                    txt.setTextSize(u * 2f);
                    c.drawText("?", x, y + u * 0.7f, txt);
                    break;
                }
                case HAPPY:
                case DROP: {
                    float p = (sec * 1.2f) % 1f;
                    fill.setColor(mood == Mood.DROP ? GOLD : GREEN);
                    spark(c, x - u * 0.4f, y + u * 0.4f - p * u, u * (1.4f - p * 0.6f));
                    spark(c, x + u * 0.5f, y - u * 0.6f + p * u * 0.3f, u * (0.5f + p * 0.5f));
                    break;
                }
                case SAD: {
                    float p = (sec * 0.8f) % 1f;
                    fill.setColor(0xFF6FB6FF);
                    float tx = w * 0.42f - h * 0.34f * 0.42f, ty = h * 0.62f + h * 0.34f * (0.1f + p * 0.7f);
                    c.drawCircle(tx, ty, u * 0.5f, fill);
                    break;
                }
                case LEARN: {
                    float g = 0.6f + 0.4f * (float) Math.sin(sec * 4);
                    fill.setColor(0x33FFD966);
                    c.drawCircle(x, y, u * (1.9f + g * 0.3f), fill);
                    fill.setColor(0xFFFFD966);
                    c.drawCircle(x, y - u * 0.2f, u * 1.1f, fill);
                    fill.setColor(0xFF9AA0A6);
                    rect.set(x - u * 0.6f, y + u * 0.8f, x + u * 0.6f, y + u * 1.4f);
                    c.drawRect(rect, fill);
                    break;
                }
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
}
