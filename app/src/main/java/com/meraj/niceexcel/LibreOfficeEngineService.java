package com.meraj.niceexcel;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts LibreOfficeKit in a separate Android process so a native LO crash
 * cannot directly take down the Native grid process.
 *
 * Important: this service never calls LibreOfficeKit JNI itself. All native
 * initialization and document operations are owned by LibreOfficeCalcEngine's
 * single LOK thread.
 */
public final class LibreOfficeEngineService extends Service {
    private static final String TAG = "NiceExcel-LO-Service";

    public static final int MSG_RECALCULATE_XLSX = 3;
    public static final int MSG_RECALCULATE_XLSX_RESULT = 4;

    public static final String KEY_OK = "ok";
    public static final String KEY_DETAIL = "detail";
    public static final String KEY_INPUT_PATH = "inputPath";
    public static final String KEY_OUTPUT_PATH = "outputPath";

    private final ExecutorService requestExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "NiceExcel-LO-Service-Request");
        thread.setDaemon(true);
        return thread;
    });

    private final Messenger messenger = new Messenger(
            new Handler(Looper.getMainLooper(), message -> {
                if (message.what != MSG_RECALCULATE_XLSX) return false;
                final Messenger replyTo = message.replyTo;
                final Bundle requestData = new Bundle(message.getData());
                requestExecutor.execute(() -> handleRecalculation(requestData, replyTo));
                return true;
            })
    );

    private void handleRecalculation(Bundle requestData, Messenger replyTo) {
        Message reply = Message.obtain(null, MSG_RECALCULATE_XLSX_RESULT);
        Bundle result = new Bundle();
        try {
            String inputPath = requestData.getString(KEY_INPUT_PATH);
            String outputPath = requestData.getString(KEY_OUTPUT_PATH);
            if (inputPath == null || outputPath == null) {
                throw new IllegalArgumentException("Input and output XLSX paths are required");
            }

            File input = new File(inputPath);
            File output = new File(outputPath);
            if (input.getCanonicalFile().equals(output.getCanonicalFile())) {
                throw new IllegalArgumentException("Calc input and output paths must be different");
            }

            LibreOfficeCalcEngine.INSTANCE.recalculateXlsxFile(
                    getApplicationContext(), input, output
            );

            if (!output.isFile() || output.length() == 0L) {
                throw new IllegalStateException("LibreOffice returned without creating an XLSX output");
            }
            result.putBoolean(KEY_OK, true);
            result.putString(KEY_DETAIL,
                    "LibreOffice Calc hard recalculation and save/reopen verification completed");
            Log.i(TAG, "CALC_SERVICE_SUCCESS outputBytes=" + output.length());
        } catch (Throwable failure) {
            result.putBoolean(KEY_OK, false);
            result.putString(KEY_DETAIL,
                    failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
            Log.e(TAG, "CALC_SERVICE_FAILURE", failure);
        }

        reply.setData(result);
        try {
            if (replyTo != null) replyTo.send(reply);
        } catch (RemoteException failure) {
            Log.w(TAG, "Unable to return LibreOffice calculation result", failure);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "LibreOffice isolated process started pid=" + android.os.Process.myPid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        // Do not destroy the static Office handle here. A bound-only Service can be
        // recreated while its isolated process is still alive; LibreOfficeKit's
        // bootstrap has process-wide one-time initialization and cannot safely
        // recreate a destroyed native handle in that same process. Keep the Office
        // session reusable; Android process death releases it. Document handles are
        // still destroyed after every calculation.
        requestExecutor.shutdown();
        super.onDestroy();
    }
}
