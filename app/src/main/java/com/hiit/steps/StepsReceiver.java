package com.hiit.steps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.Objects;

// Use StepsService remotely

public class StepsReceiver extends BroadcastReceiver {

    private static final String TAG = "Steps";

    private static int ERROR_NOT_STARTED = -1;
    private static int ERROR_REMOTE = -2;

    private void log(String msg) {
        Log.d(TAG, "StepsReceiver(" + Thread.currentThread().getId() + "): " + msg);
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        log("onReceive, intent=" + intent);
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            for (String key : bundle.keySet()) {
                Object value = bundle.get(key);
                if (value == null) {
                    log(key);
                } else {
                    log(key + "[" + value.getClass().getCanonicalName() + "]=" + value);
                }
            }
        }
        final Intent stepsIntent = new Intent(context, StepsService.class);
        if (bundle != null) {
            stepsIntent.putExtras(bundle);
        }
        String action = intent.getAction();
        if (action.equals(Configuration.ACTION_START) || action.equals(Intent.ACTION_MAIN)) {
            context.startService(stepsIntent);
        } else if (action.equals(Configuration.ACTION_STOP)) {
            IBinder binder = peekService(context, stepsIntent);
            if (binder == null) {
                log("Service not started");
                setResultCode(ERROR_NOT_STARTED);
                return;
            }
            IStepsService service = IStepsService.Stub.asInterface(binder);
            try {
                service.addLifecycleCallback(releaseOnStop);
                if (!service.stop()) {
                    log("Service not started");
                    setResultCode(ERROR_NOT_STARTED);
                    return;
                }
                monitor.block();
            } catch (RemoteException e) {
                log("Error communicating with service");
                setResultCode(ERROR_REMOTE);
            }
        } else if (action.equals(Configuration.ACTION_RUN)) {
            // Start service and wait synchronously for it to stop.
            //
            // This may fail after an unknown timeout (~60 seconds on Galaxy S3).
            // The service can't be stopped with another broadcast because
            // onReceive() blocks!

            stepsIntent.putExtra(Configuration.EXTRA_LIFECYCLE_CALLBACK,
                    new LifecycleCallback(releaseOnStop));
            context.startService(stepsIntent);
            monitor.block();
        }
    }

    private final Monitor monitor = new Monitor();


    final ILifecycleCallback releaseOnStop = new ILifecycleCallback.Stub() {
        @Override
        public void stopped(int samples, String outputFile) throws RemoteException {
            setResult(samples, outputFile, null);
            monitor.release();
        }
    };

}
