package com.example.notificationforwarder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Boot completed received");

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {

            PreferenceManager preferenceManager = new PreferenceManager(context);

            if (preferenceManager.isServiceEnabled()) {
                Log.d(TAG, "Starting service after boot");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(new Intent(context, NotificationService.class));
                } else {
                    context.startService(new Intent(context, NotificationService.class));
                }
            }
        }
    }
}

