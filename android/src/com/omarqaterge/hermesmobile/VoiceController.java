package com.omarqaterge.hermesmobile;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    private Ui ui;
    private final Runnable giveUp = () -> finish(null);

    VoiceController(HermesService svc) {
        this.svc = svc;
    }

    boolean isActive() {
        return active;
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
        active = true;
        buzz();
        ui.transcript("");
        try {
            sr = SpeechRecognizer.createSpeechRecognizer(svc);
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
                    finish(null);
                }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, svc.getSharedPreferences("hm", Context.MODE_PRIVATE).getString("voice_lang", "en-IN"))
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            sr.startListening(i);
            main.postDelayed(giveUp, 12_000);
        } catch (Exception e) {
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
            reply = "I didn't catch that.";
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
