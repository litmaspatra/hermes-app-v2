package com.omarqaterge.hermesmobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * "Ask Hermes": the small window behind the Hermes entry in the phone's share sheet and drag tray (drag an image, file or
 * text onto Hermes there). It shows what arrived, asks what Hermes should do with it, and hands it to the same background
 * path the floating pet's drop card uses (HermesService.islandSubmit). It works with either island choice, and needs no
 * "display over other apps" permission because it is a normal window.
 */
public class ShareDropActivity extends Activity {
    private static final int GOLD = 0xFFD9A441, BG = 0xF2101216, MAX_FILES = 5;
    private static final long MAX_FILE = 20L * 1024 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Uri> copied = new ArrayList<>();
    private String sharedText = "";
    private int unreadable;
    private boolean ready;
    private ImageView thumb;
    private TextView info;
    private EditText input;
    private float density;

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        setFinishOnTouchOutside(true);
        build();
        intake(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        intake(intent);
    }

    private TextView label(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView pill(String s, int color, Runnable onClick) {
        TextView t = label(s, 13, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor((color & 0x00FFFFFF) | 0x33000000);
        g.setStroke(dp(1), (color & 0x00FFFFFF) | 0x99000000);
        t.setBackground(g);
        t.setClickable(true);
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    private void build() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        FrameLayout scrim = new FrameLayout(this);
        scrim.setOnClickListener(v -> finish());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(dp(28));
        bg.setStroke(dp(1), GOLD & 0x00FFFFFF | 0x80000000);
        panel.setBackground(bg);
        panel.setClickable(true); // a tap inside must not reach the scrim

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        head.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView title = label("What should Hermes do with it?", 15, Color.WHITE, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(dp(10));
        head.addView(title, tp);
        panel.addView(head);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(10), 0, 0);
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setVisibility(View.GONE);
        top.addView(thumb, new LinearLayout.LayoutParams(dp(56), dp(56)));
        info = label("Reading what you shared…", 12, 0xFFCFD3D8, false);
        info.setMaxLines(3);
        info.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1f);
        ip.setMarginStart(dp(10));
        top.addView(info, ip);
        panel.addView(top);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(10), 0, 0);
        for (String c : new String[] {"Describe it", "Extract text", "Summarize"}) {
            TextView ch = pill(c, 0xFFCFD3D8, () -> {
                input.setText(c);
                input.setSelection(input.length());
            });
            ch.setTextSize(11);
            ch.setPadding(dp(9), dp(6), dp(9), dp(6));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.setMarginEnd(dp(6));
            chips.addView(ch, cp);
        }
        panel.addView(chips);

        input = new EditText(this);
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
        input.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams inp = new LinearLayout.LayoutParams(-1, -2);
        inp.topMargin = dp(10);
        panel.addView(input, inp);

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setPadding(0, dp(10), 0, 0);
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, -2, 1f);
        a1.setMarginEnd(dp(8));
        acts.addView(pill("✕ Cancel", 0xFFCFD3D8, this::finish), a1);
        acts.addView(pill("Send ➤", GOLD, this::send), new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(acts);

        int w = Math.min(dm.widthPixels - dp(24), dp(420));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(w, -2);
        pl.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        pl.topMargin = dp(48);
        scrim.addView(panel, pl);
        setContentView(scrim);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    /** Collects the shared files and text from the intent, and copies the files into the app's cache off the main thread. */
    private void intake(Intent in) {
        final ArrayList<Uri> uris = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        try {
            if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
                ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (l != null) for (Uri u : l) if (u != null && uris.size() < MAX_FILES) uris.add(u);
            } else {
                Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) uris.add(u);
            }
            CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null && t.length() > 0 && text.length() < 20000) text.append(t);
            ClipData clip = in.getClipData();
            if (clip != null) {
                for (int k = 0; k < clip.getItemCount(); k++) {
                    ClipData.Item it = clip.getItemAt(k);
                    Uri u = it.getUri();
                    if (u != null && uris.size() < MAX_FILES && !uris.contains(u)) uris.add(u);
                    else if (u == null && text.length() == 0 && it.getText() != null) text.append(it.getText());
                }
            }
        } catch (Exception ignored) {
        }
        ready = false;
        new Thread(() -> {
            ArrayList<Uri> done = new ArrayList<>();
            for (Uri u : uris) {
                Uri c = copyToCache(this, u);
                if (c != null) done.add(c);
            }
            final String shared = text.toString();
            final int bad = uris.size() - done.size();
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                copied.clear();
                copied.addAll(done);
                sharedText = shared;
                unreadable = bad;
                show();
                ready = true;
            });
        }, "share-drop").start();
    }

    private void show() {
        StringBuilder s = new StringBuilder();
        Bitmap bmp = null;
        if (!copied.isEmpty()) {
            File f = new File(copied.get(0).getPath());
            bmp = preview(f);
            s.append(copied.size() == 1 ? f.getName().replaceFirst("^\\d+_", "") : copied.size() + " files");
            if (unreadable > 0) s.append("\n").append(unreadable).append(" couldn't be read");
        } else if (unreadable > 0) {
            s.append("Couldn't read that file. Share it from the app's own Share button instead.");
        }
        if (!sharedText.isEmpty()) {
            if (s.length() > 0) s.append("\n");
            s.append(sharedText.length() > 90 ? sharedText.substring(0, 90) + "…" : sharedText);
        }
        if (s.length() == 0) s.append("Ask Hermes anything, or share a file, an image or some text to it.");
        info.setText(s);
        if (bmp != null) {
            thumb.setImageBitmap(bmp);
            thumb.setVisibility(View.VISIBLE);
        } else {
            thumb.setVisibility(View.GONE);
        }
        input.requestFocus();
    }

    private void send() {
        String typed = input.getText().toString().trim();
        if (typed.isEmpty()) {
            input.setHint("Type what Hermes should do");
            input.setHintTextColor(0xFFE8912D);
            return;
        }
        if (!ready) {
            Toast.makeText(this, "One moment, still reading it", Toast.LENGTH_SHORT).show();
            return;
        }
        String prompt = sharedText.isEmpty() ? typed : typed + "\n\n" + sharedText;
        ArrayList<Uri> files = new ArrayList<>(copied);
        HermesService s = HermesService.instance;
        if (s != null) {
            s.islandSubmit(prompt, files);
        } else {
            // Hermes isn't running: start the app just long enough to carry the message.
            MainActivity.queueIsland(prompt, files);
            try {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra("island", true));
            } catch (Exception ex) {
                MainActivity.clearQueuedIsland();
                Toast.makeText(this, "Couldn't start Hermes", Toast.LENGTH_LONG).show();
                return;
            }
        }
        Toast.makeText(this, "Sent. Hermes is on it", Toast.LENGTH_SHORT).show();
        finish();
    }

    private static Bitmap preview(File f) {
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

    /** Copies one shared file into the app's cache, so it can be read after the sender's grant is gone (max 20 MB). */
    static Uri copyToCache(Context c, Uri u) {
        try {
            File dir = new File(c.getCacheDir(), "island");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            long cutoff = System.currentTimeMillis() - 3600_000L; // old drops are not needed any more
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (f.lastModified() < cutoff) f.delete();

            String name = null;
            try (android.database.Cursor cur = c.getContentResolver().query(u, null, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (ni >= 0) name = cur.getString(ni);
                }
            } catch (Exception ignored) {
            }
            if (name == null || name.isEmpty()) name = u.getLastPathSegment();
            if (name == null || name.isEmpty()) name = "shared";
            name = name.replaceAll("[/\\\\\\x00-\\x1f]", "_");
            if (!name.contains(".")) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(c.getContentResolver().getType(u));
                if (ext != null) name = name + "." + ext;
            }
            File out = new File(dir, System.currentTimeMillis() + "_" + name);
            long total = 0;
            try (InputStream in = c.getContentResolver().openInputStream(u); FileOutputStream os = new FileOutputStream(out)) {
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
}
