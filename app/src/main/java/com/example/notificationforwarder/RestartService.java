package com.example.notificationforwarder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class RestartService extends BroadcastReceiver {

    private static final String TAG = "RestartService";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Service restart requested");

        PreferenceManager preferenceManager = new PreferenceManager(context);

        if (preferenceManager.isServiceEnabled()) {
            Log.d(TAG, "Restarting service");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(new Intent(context, NotificationService.class));
            } else {
                context.startService(new Intent(context, NotificationService.class));
            }
        }
    }
}
