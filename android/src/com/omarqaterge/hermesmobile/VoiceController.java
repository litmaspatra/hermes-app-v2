package com.omarqaterge.hermesmobile;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.speech.RecognitionService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One spoken command: vibrate, listen (offline if the recogniser can), route (VoiceRouter), say the result.
 * The microphone opens only here, only after a trigger. A command ends when it is done. It becomes a CONVERSATION
 * (listen, reply, listen again ...) only when asked ("let's talk") or after Hermes answered a question; it ends on
 * "stop", "that's all", or after two silences. A guess at a contact is confirmed with a short yes / no.
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
    /** 0: chosen language, offline preferred. 1: same, online. 2: the phone's own language. 3: en-US. */
    private int attempt = 0;
    private Ui ui;
    private int lastError = -1;
    private boolean converse;            // a conversation is going: listen again after every answer
    private int silent;                  // listens in a row that heard nothing
    private boolean hermesChat;          // this conversation already has its Hermes chat
    private VoiceRouter.Result asked;    // a guess waiting for "yes" / "no"
    private Runnable afterSpeech;
    private int speechId;
    private final Runnable giveUp = () -> finish(null);
    private final Runnable speechGuard = this::speechDone;

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
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "Speech recognition has no language pack for that. Download one in Google speech settings.";
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
            say("Voice commands are turned off.", null);
            end();
            return;
        }
        if (svc.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            HermesService.askPermission(svc, Manifest.permission.RECORD_AUDIO);
            say("I need the microphone permission.", null);
            end();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(svc)) {
            say("Speech recognition isn't available on this phone.", null);
            end();
            return;
        }
        if (realRecognizer(svc) == null) {
            say("No speech recogniser is installed.", null);
            end();
            return;
        }
        active = true;
        attempt = 0;
        converse = false;
        silent = 0;
        asked = null;
        hermesChat = false;
        FastBrainClient.warm(svc);
        VoiceActions.warm(svc); // read the contacts now, so the first name is matched at once
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
                    finish(l == null || l.isEmpty() ? null : l);
                }

                @Override
                public void onError(int code) {
                    // Language unavailable (13), not supported (12), recogniser dropped (11, 5): walk down the fallbacks
                    // (online, then the phone's own language, then en-US) before giving up.
                    if (attempt < 3 && (code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                            || code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || code == SpeechRecognizer.ERROR_CLIENT)) {
                        attempt++;
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
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, attempt == 0)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5); // a few guesses: a misheard name is often the second one
            if (Build.VERSION.SDK_INT >= 33) {
                // Tell the recogniser which names to expect (contacts and your own aliases): it hears them far better.
                List<String> names = VoiceActions.cachedNames(svc);
                if (!names.isEmpty()) i.putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, new ArrayList<>(names.subList(0, Math.min(names.size(), 150))));
            }
            String lang = svc.getSharedPreferences("hm", Context.MODE_PRIVATE).getString("voice_lang", "en-IN");
            if (attempt <= 1) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            else if (attempt == 3) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US"); // attempt 2: no language = the phone's
            sr.startListening(i);
            main.postDelayed(giveUp, converse || asked != null ? 15_000 : 12_000);
        } catch (Exception e) {
            lastError = -2;
            finish(null);
        }
    }

    void cancel() {
        main.removeCallbacks(giveUp);
        active = false;
        converse = false;
        asked = null;
        afterSpeech = null;
        main.removeCallbacks(speechGuard);
        destroyRecognizer();
        if (tts != null && ttsReady) tts.stop();
        if (neural != null) neural.stop();
        end();
    }

    /** The recogniser gave up or heard nothing. In a conversation (or with a question open) that is not an error. */
    private boolean quiet() {
        return (converse || asked != null) && (lastError < 0 || lastError == SpeechRecognizer.ERROR_NO_MATCH || lastError == SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
    }

    /** `heard`: the recogniser's guesses, best first; null when it heard nothing or failed. */
    private void finish(List<String> heard) {
        if (!active) return;
        main.removeCallbacks(giveUp);
        active = false;
        destroyRecognizer();
        if (heard == null || heard.isEmpty() || heard.get(0).trim().isEmpty()) {
            if (quiet()) {
                lastError = -1;
                silent++;
                if (silent < 2 && asked == null) {
                    relisten();
                    return;
                }
                boolean wasTalking = converse;
                asked = null;
                converse = false;
                String bye = wasTalking ? "Okay, I'll stop listening." : "Okay.";
                if (ui != null) ui.transcript(bye);
                say(bye, () -> main.postDelayed(this::end, 700));
                return;
            }
            String reply = lastError >= 0 ? describe(lastError) : "I didn't catch that.";
            HistoryStore.append(svc, "voice", "", "(no speech)", "error " + lastError + ": " + reply);
            lastError = -1;
            if (ui != null) ui.transcript(reply);
            converse = false;
            asked = null;
            say(reply, () -> main.postDelayed(this::end, 2500));
            return;
        }
        silent = 0;
        lastError = -1;
        final String text = heard.get(0).trim();
        final boolean locked = VoiceActions.locked(svc);
        if (ui != null) ui.transcript(text);
        // 1. an open question ("Did you mean ...?") is answered with yes / no
        if (asked != null) {
            VoiceRouter.Result q = asked;
            if (q.options != null) { // "Which one: A or B?"
                if (VoiceCommands.yesNo(text) < 0) {
                    asked = null;
                    reply("Okay, I won't.", text, "no", false);
                    return;
                }
                int at = -1;
                for (String h : heard) {
                    String pick = ContactMatcher.pickOption(h, q.labels);
                    if (pick != null) {
                        at = q.labels.indexOf(pick);
                        break;
                    }
                }
                if (at < 0) {
                    reply("Sorry, which one? Say first, second, or the name.", text, "unclear choice", true);
                    return; // the question stays open
                }
                asked = null;
                VoiceCommands.Command chosen = q.options.get(at);
                VoiceActions.Outcome o = VoiceActions.exec(svc, chosen, locked, null);
                if (!chosen.number.isEmpty()) VoiceActions.pin(svc, chosen.name, chosen.number);       // same name twice: remember the number
                else if (q.heard != null) VoiceAliases.learn(svc, q.heard, chosen.name);              // else remember what the name means
                reply(o.reply, text, "chose " + chosen.type, converse);
                return;
            }
            int yn = VoiceCommands.yesNo(text);
            if (yn != 0) {
                asked = null;
                if (yn < 0) {
                    reply("Okay, I won't.", text, "no", false);
                } else if (q.fbConfirm) {
                    active = true;
                    VoiceRouter.answerFastBrain(svc, "yes", locked, r -> deliver(r, text));
                } else if (q.confirm != null) {
                    VoiceActions.Outcome o = VoiceActions.exec(svc, q.confirm, locked, null);
                    if (o.confirm == null && q.heard != null) VoiceAliases.learn(svc, q.heard, q.confirm.name); // next time it is instant
                    reply(o.reply, text, "confirmed " + q.confirm.type, converse);
                }
                return;
            }
            asked = null; // something else was said: it is a new command
        }
        // 2. talking about talking
        if (converse && VoiceCommands.isConversationEnd(text)) {
            converse = false;
            reply("Okay.", text, "end", false);
            return;
        }
        if (VoiceCommands.isConversationStart(text)) {
            converse = true;
            say("Okay, I'm listening.", this::relisten);
            if (ui != null) ui.transcript(text + "\nOkay, I'm listening.");
            return;
        }
        // 3. a command: routed off the main thread (FastBrain is a network call)
        if (ui != null) ui.transcript(text + "\n…");
        active = true; // busy until the answer is spoken: a second trigger must not start a second listener
        VoiceRouter.route(svc, heard, locked, r -> deliver(r, text));
    }

    /** The router's answer, on the main thread. */
    private void deliver(VoiceRouter.Result r, String text) {
        if (ui == null) return; // cancelled meanwhile
        if (r.toHermes) {
            askHermes(r.hermesText, r.command);
            return;
        }
        asked = r.confirm != null || r.fbConfirm || r.options != null ? r : null;
        HistoryStore.append(svc, "voice", "", text, r.how + " " + r.detail + ": " + r.reply);
        reply(r.reply, text, null, converse || asked != null);
    }

    /** Show and say the answer; then listen again (a conversation / a question) or finish. */
    private void reply(String reply, String text, String log, boolean listenAgain) {
        if (log != null) HistoryStore.append(svc, "voice", "", text, log + ": " + reply);
        active = true; // still ours until the speech is over
        if (ui != null) ui.transcript(text + "\n" + reply);
        say(reply, listenAgain ? this::relisten : () -> {
            active = false;
            main.postDelayed(this::end, 1200);
        });
    }

    /** Open questions go to Hermes, which can hold a conversation: its answer is read out, then it listens again. */
    private void askHermes(String text, boolean command) {
        active = true;
        if (ui != null) ui.transcript(text + (command ? "\nOn it…" : "\nThinking…"));
        final boolean fresh = !hermesChat;
        hermesChat = true;
        svc.askHermes(text, fresh, answer -> {
            if (ui == null) return;
            if (answer == null || answer.trim().isEmpty()) {
                HistoryStore.append(svc, "voice", "", text, "hermes: no answer");
                reply("I couldn't get an answer from Hermes.", text, null, false);
                return;
            }
            HistoryStore.append(svc, "voice", "", text, "hermes: answered");
            if (!command && svc.getSharedPreferences("hm", Context.MODE_PRIVATE).getBoolean("voice_follow", true)) converse = true;
            String spoken = VoiceCommands.speakable(answer);
            if (ui != null) ui.transcript(text + "\n" + spoken);
            say(spoken, converse ? this::relisten : () -> {
                active = false;
                main.postDelayed(this::end, 1200);
            });
        });
    }

    /** Opens the microphone again inside the same conversation (no buzz, no reset). */
    private void relisten() {
        if (ui == null) return;
        active = true;
        attempt = 0;
        lastError = -1;
        ui.transcript("");
        listen();
    }

    private void end() {
        active = false;
        converse = false;
        asked = null;
        hermesChat = false;
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

    private void say(String s, Runnable after) {
        afterSpeech = after;
        main.removeCallbacks(speechGuard);
        main.postDelayed(speechGuard, 60_000); // never wait forever for a speech engine that stays silent
        if (s != null && !s.trim().isEmpty() && NeuralTts.enabled(svc)) {
            if (neural == null) neural = new NeuralTts(svc);
            final int id = ++speechId;
            neural.speak(s, () -> {
                if (id == speechId) speechDone();
            }, () -> {
                if (id == speechId) sysSay(s); // the natural-voice server isn't running: the phone's own voice
            });
            return;
        }
        sysSay(s);
    }

    private NeuralTts neural;

    private void sysSay(String s) {
        if (ttsReady && tts != null) {
            speakNow(s);
            return;
        }
        pendingSpeech = s;
        if (tts == null) {
            tts = new TextToSpeech(svc, status -> main.post(() -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setLanguage(Locale.getDefault());
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) {}

                        @Override
                        public void onDone(String id) {
                            main.post(() -> {
                                if (id != null && id.equals("hm-voice-" + speechId)) speechDone();
                            });
                        }

                        @Override
                        public void onError(String id) {
                            main.post(() -> {
                                if (id != null && id.equals("hm-voice-" + speechId)) speechDone();
                            });
                        }
                    });
                    if (pendingSpeech != null) speakNow(pendingSpeech);
                } else {
                    speechDone(); // no speech engine: carry on silently
                }
                pendingSpeech = null;
            }));
        }
    }

    private void speakNow(String s) {
        speechId++;
        if (s == null || s.trim().isEmpty()) {
            speechDone();
            return;
        }
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "hm-voice-" + speechId);
    }

    /** The answer has been spoken (or could not be): do what comes next, once. */
    private void speechDone() {
        main.removeCallbacks(speechGuard);
        Runnable r = afterSpeech;
        afterSpeech = null;
        if (r != null) r.run();
    }

    void destroy() {
        cancel();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }
}
