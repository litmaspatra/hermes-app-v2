package com.omarqaterge.hermesmobile;

import android.content.Intent;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * Required by the system for an app that is a voice-interaction assistant. Hermes does its own listening (see
 * VoiceController), so this only reports that it can't be used as a general speech recogniser.
 */
public class HermesRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent intent, Callback callback) {
        try {
            callback.error(SpeechRecognizer.ERROR_CLIENT);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onCancel(Callback callback) {}

    @Override
    protected void onStopListening(Callback callback) {}
}
