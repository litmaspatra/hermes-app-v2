package com.omarqaterge.hermesmobile;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.speech.RecognitionService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One spoken command: vibrate, listen (offline if the recogniser can), parse, run, say the result.
 * The microphone opens only here, only after a trigger, and closes after 2.5 s of silence or 12 s in all.
 * Main thread only.
 */
final class VoiceController {
    /** Where the live transcript is shown: the island, or the assistant panel. */
    interface Ui {
        void transcript(String text);

        void end();
    }

    private final HermesService svc;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeech;
    private boolean active;
    private boolean offline = true;
    private boolean retried;
    private Ui ui;
    private int lastError = -1;
    private final Runnable giveUp = () -> finish(null);

    VoiceController(HermesService svc) {
        this.svc = svc;
    }

    boolean isActive() {
        return active;
    }

    /**
     * The speech recogniser to use: the Google one if present, else any other, but never Hermes's own
     * (HermesRecognitionService). Once Hermes is the digital assistant Android makes its recogniser the DEFAULT, and
     * asking for "the default" would loop back into ourselves. Null when the phone has none.
     */
    static ComponentName realRecognizer(Context c) {
        ComponentName google = null, other = null;
        try {
            List<ResolveInfo> l = c.getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            for (ResolveInfo r : l) {
                if (r.serviceInfo == null || c.getPackageName().equals(r.serviceInfo.packageName)) continue;
                ComponentName cn = new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
                if ("com.google.android.googlequicksearchbox".equals(r.serviceInfo.packageName)) google = cn;
                else if (other == null) other = cn;
            }
        } catch (Exception ignored) {
        }
        return google != null ? google : other;
    }

    /** What to say for a recogniser error, so a failure is not always "I didn't catch that". */
    static String describe(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "I didn't catch that.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "I need the microphone permission.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                return "Speech recognition needs internet right now.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Speech recognition is busy. Try again.";
            case SpeechRecognizer.ERROR_AUDIO:
                return "I can't use the microphone right now.";
            default:
                return "Speech recognition failed (code " + code + ").";
        }
    }

    void start(Ui ui) {
        if (active) return;
        this.ui = ui;
        if (!svc.getSharedPreferences("hm", Context.MODE_PRIVATE).getBoolean("voice_enabled", true)) {
            say("Voice commands are turned off.");
            end();
            return;
        }
        if (svc.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            HermesService.askPermission(svc, Manifest.permission.RECORD_AUDIO);
            say("I need the microphone permission.");
            end();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(svc)) {
            say("Speech recognition isn't available on this phone.");
            end();
            return;
        }
        if (realRecognizer(svc) == null) {
            say("No speech recogniser is installed.");
            end();
            return;
        }
        active = true;
        offline = true;
        retried = false;
        buzz();
        ui.transcript("");
        listen();
    }

    private void listen() {
        try {
            sr = SpeechRecognizer.createSpeechRecognizer(svc, realRecognizer(svc));
            sr.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onEvent(int t, Bundle b) {}

                @Override
                public void onPartialResults(Bundle b) {
                    ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (l != null && !l.isEmpty() && VoiceController.this.ui != null) VoiceController.this.ui.transcript(l.get(0));
                }

                @Override
                public void onResults(Bundle b) {
                    ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    finish(l == null || l.isEmpty() ? null : l.get(0));
                }

                @Override
                public void onError(int code) {
                    // No offline pack for this language (or the recogniser dropped): try once more, online.
                    if (!retried && offline && (code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                            || code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || code == SpeechRecognizer.ERROR_CLIENT)) {
                        retried = true;
                        offline = false;
                        main.removeCallbacks(giveUp);
                        destroyRecognizer();
                        main.post(VoiceController.this::listen);
                        return;
                    }
                    lastError = code;
                    finish(null);
                }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, svc.getSharedPreferences("hm", Context.MODE_PRIVATE).getString("voice_lang", "en-IN"))
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            sr.startListening(i);
            main.postDelayed(giveUp, 12_000);
        } catch (Exception e) {
            lastError = -2;
            finish(null);
        }
    }

    void cancel() {
        main.removeCallbacks(giveUp);
        active = false;
        destroyRecognizer();
        end();
    }

    private void finish(String text) {
        if (!active) return;
        main.removeCallbacks(giveUp);
        active = false;
        destroyRecognizer();
        String reply;
        if (text == null || text.trim().isEmpty()) {
            reply = lastError >= 0 ? describe(lastError) : "I didn't catch that.";
            HistoryStore.append(svc, "voice", "", "(no speech)", "error " + lastError + ": " + reply);
            lastError = -1;
        } else {
            VoiceCommands.Command cmd = VoiceCommands.parse(text);
            boolean locked = VoiceActions.locked(svc);
            reply = VoiceActions.run(svc, cmd, locked);
            HistoryStore.append(svc, "voice", "", text, cmd.type + ": " + reply);
        }
        if (ui != null) ui.transcript(text == null ? "" : text);
        say(reply);
        main.postDelayed(this::end, 1800);
    }

    private void end() {
        Ui u = ui;
        ui = null;
        if (u != null) u.end();
    }

    private void destroyRecognizer() {
        if (sr != null) {
            try {
                sr.stopListening();
                sr.destroy();
            } catch (Exception ignored) {
            }
            sr = null;
        }
    }

    private void buzz() {
        try {
            Vibrator v = svc.getSystemService(Vibrator.class);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    private void say(String s) {
        if (ttsReady && tts != null) {
            tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "hm-voice");
            return;
        }
        pendingSpeech = s;
        if (tts == null) {
            tts = new TextToSpeech(svc, status -> main.post(() -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setLanguage(Locale.getDefault());
                    if (pendingSpeech != null) tts.speak(pendingSpeech, TextToSpeech.QUEUE_FLUSH, null, "hm-voice");
                }
                pendingSpeech = null;
            }));
        }
    }

    void destroy() {
        cancel();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }
}
