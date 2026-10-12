package com.omarqaterge.hermesmobile;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The assist gesture (corner swipe) with Hermes as the digital assistant: a compact panel over whatever is on
 * screen (pet + transcript), not the full app. If anything about it fails, the old behaviour is used: the app
 * opens in Live mode. Works on the lock screen too, which is why it is the main locked-screen trigger.
 */
public class HermesVoiceSession extends VoiceInteractionSession {
    private TextView text;
    private IslandOverlay.PetView pet;

    HermesVoiceSession(Context c) {
        super(c);
    }

    @Override
    public View onCreateContentView() {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        float d = getContext().getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);
        box.setPadding(pad, pad / 2, pad, pad / 2);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xF2101216);
        g.setCornerRadius(28 * d);
        box.setBackground(g);
        pet = new IslandOverlay.PetView(getContext());
        pet.setMood(IslandOverlay.Mood.LISTEN);
        pet.setSpecies(getContext().getSharedPreferences("hm", Context.MODE_PRIVATE).getString("pet", "cat"));
        box.addView(pet, new LinearLayout.LayoutParams((int) (44 * d), (int) (38 * d)));
        text = new TextView(getContext());
        text.setTextColor(Color.WHITE);
        text.setTextSize(15);
        text.setText("Listening…");
        box.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        FrameWrap w = new FrameWrap(getContext(), box, (int) (16 * d));
        return w;
    }

    /** Adds the margin around the panel so it floats instead of touching the screen edges. */
    private static final class FrameWrap extends android.widget.FrameLayout {
        FrameWrap(Context c, View inner, int margin) {
            super(c);
            LayoutParams lp = new LayoutParams(-1, -2, Gravity.TOP);
            lp.setMargins(margin, margin * 2, margin, margin);
            addView(inner, lp);
        }
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        HermesService svc = HermesService.instance;
        try {
            Window w = getWindow() == null ? null : getWindow().getWindow();
            if (w != null) {
                w.setGravity(Gravity.TOP);
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
            if (svc == null) throw new IllegalStateException("service not running");
            svc.startListeningWith(new VoiceController.Ui() {
                @Override
                public void transcript(String t) {
                    if (text != null) text.setText(t == null || t.isEmpty() ? "Listening…" : t);
                }

                @Override
                public void end() {
                    hide();
                }
            });
        } catch (Exception e) {
            fallbackToApp();
        }
    }

    private void fallbackToApp() {
        try {
            startAssistantActivity(new Intent(Intent.ACTION_ASSIST).setClass(getContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
        hide();
    }

    @Override
    public void onHide() {
        HermesService svc = HermesService.instance;
        if (svc != null) svc.stopListening();
        super.onHide();
    }
}
