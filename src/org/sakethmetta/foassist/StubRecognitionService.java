package org.sakethmetta.foassist;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * Android only lists an assistant that names a recognition service. This one refuses every
 * request; AssistSession talks to Google's recognizer directly (Bridge.pickRecognizer).
 */
public class StubRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent intent, Callback callback) {
        try {
            callback.error(SpeechRecognizer.ERROR_CLIENT);
        } catch (RemoteException ignored) {
        }
    }

    @Override
    protected void onCancel(Callback callback) {
    }

    @Override
    protected void onStopListening(Callback callback) {
    }
}
