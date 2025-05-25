package com.example.notificationforwarder;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NotificationService extends NotificationListenerService {

    public static final String MSG_STRING = "*Sub:* %s\n*Msg:* %s\n_-- %s @ %s_\n--------------------------------------------------------";
    private static final String TAG = "NotificationService";
    private static final String CHANNEL_ID = "notification_forwarder_channel";
    private static final int FOREGROUND_NOTIFICATION_ID = 1;
    private static final String OWN_PACKAGE_NAME = "com.example.notificationforwarder";

    private PreferenceManager preferenceManager;
    private ExecutorService executorService;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");

        preferenceManager = new PreferenceManager(this);
        executorService = Executors.newSingleThreadExecutor();

        createNotificationChannel();
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed");

        // Attempt to restart the service
        if (preferenceManager.isServiceEnabled()) {
            Intent intent = new Intent(this, RestartService.class);
            sendBroadcast(intent);
        }

        if (executorService != null) {
            executorService.shutdown();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Skip our own notifications to avoid loops
        // Skip ongoing notifications like media players, downloads, etc.
        if (sbn.isOngoing() || OWN_PACKAGE_NAME.equals(sbn.getPackageName())) {
            return;
        }

        if (!preferenceManager.getSelectedPackages().contains(sbn.getPackageName())) {
            return; // Ignore notifications from unselected packages
        }

        Notification notification = sbn.getNotification();
        CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        String appName = getApplicationName(sbn.getPackageName());

        if (title == null) title = "";
        if (text == null) text = "";

        final String message = String.format(MSG_STRING,
                title, text, appName, epochToTimeStamp(sbn.getNotification().when));
       sendMessage(title, message, appName);

    }

    public void sendMessage(CharSequence title, String message, String appName) {
        String webToken = preferenceManager.getWebToken();
        if (TextUtils.isEmpty(webToken)) {
            Log.e(TAG, "Web token not set");
            return;
        }
        // Process notification in a background thread
        executorService.execute(() -> {
            SlackHelper.sendMessage(webToken, message, success -> {
                if (success) {
                    showForwardedNotification(appName, title.toString());
                    Log.d(TAG, "Message sent successfully");
                } else {
                    Log.e(TAG, "Failed to send message");
                }
            });
        });
    }
    private String getApplicationName(String packageName) {
        PackageManager packageManager = getPackageManager();
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            return packageManager.getApplicationLabel(appInfo).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Notification Forwarder Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Channel for Notification Forwarder Service");

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Notification Forwarder")
                .setContentText("Service is running")
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void showForwardedNotification(String appName, String title) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "forwarded_notification_channel",
                    "Forwarded Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "forwarded_notification_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Notification Forwarded")
                .setContentText("From " + appName + ": " + title)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setAutoCancel(true);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            // Use a dynamic ID to ensure notifications don't override each other
            int notificationId = (int) System.currentTimeMillis();
            notificationManager.notify(notificationId, builder.build());
        }
    }

    @SuppressLint("SimpleDateFormat")
    public static String epochToTimeStamp(long milliseconds) {
       return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(milliseconds));
    }
}

