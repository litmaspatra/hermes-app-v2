package com.omarqaterge.hermesmobile;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * Android makes the recogniser of the digital-assistant app the phone's DEFAULT speech recogniser. Hermes does not
 * recognise speech itself, so this forwards every request to the phone's real recogniser (Google's, or the first
 * other one installed) and relays the answers. Without this, anything that asked for "the default recogniser" (voice
 * typing, other apps, and Hermes's own listener) failed at once.
 */
public class HermesRecognitionService extends RecognitionService {
    private SpeechRecognizer real;

    @Override
    protected void onStartListening(Intent intent, Callback callback) {
        release();
        ComponentName target = VoiceController.realRecognizer(this);
        if (target == null) {
            fail(callback, SpeechRecognizer.ERROR_CLIENT);
            return;
        }
        try {
            real = SpeechRecognizer.createSpeechRecognizer(this, target);
            real.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle p) {
                    try {
                        callback.readyForSpeech(p);
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onBeginningOfSpeech() {
                    try {
                        callback.beginningOfSpeech();
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onRmsChanged(float v) {
                    try {
                        callback.rmsChanged(v);
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onBufferReceived(byte[] b) {
                    try {
                        callback.bufferReceived(b);
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onEndOfSpeech() {
                    try {
                        callback.endOfSpeech();
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onError(int code) {
                    fail(callback, code);
                }

                @Override
                public void onResults(Bundle b) {
                    try {
                        callback.results(b);
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onPartialResults(Bundle b) {
                    try {
                        callback.partialResults(b);
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onEvent(int type, Bundle b) {}
            });
            real.startListening(intent);
        } catch (Exception e) {
            fail(callback, SpeechRecognizer.ERROR_CLIENT);
        }
    }

    private void fail(Callback cb, int code) {
        try {
            cb.error(code);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onStopListening(Callback callback) {
        if (real != null) real.stopListening();
    }

    @Override
    protected void onCancel(Callback callback) {
        release();
    }

    private void release() {
        if (real != null) {
            try {
                real.cancel();
                real.destroy();
            } catch (Exception ignored) {
            }
            real = null;
        }
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }
}
