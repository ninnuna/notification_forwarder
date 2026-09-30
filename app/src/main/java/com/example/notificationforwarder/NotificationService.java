package com.example.notificationforwarder;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import android.content.Context;
import android.os.UserManager;
import android.os.UserHandle;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationService";
    private static final String CHANNEL_ID = "notification_forwarder_channel";
    private static final int FOREGROUND_NOTIFICATION_ID = 1;
    private static final String OWN_PACKAGE_NAME = "com.example.notificationforwarder";
    private PreferenceManager preferenceManager;
    private ExecutorService executorService;
    private NotificationManager notificationManager;
    public static Map<String, String> tokenMap;
    public static final MessageHelper slackHelper = new SlackHelper();
    public static final MessageHelper telegramHelper = new TelegramHelper();
    private NetworkSpeedOverlay networkSpeedOverlay;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        preferenceManager = new PreferenceManager(this);
        tokenMap = parseWebTokens(preferenceManager.getWebToken());
        executorService = Executors.newSingleThreadExecutor();
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification());
        networkSpeedOverlay = new NetworkSpeedOverlay(this);
        networkSpeedOverlay.startTracking();
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
        if (networkSpeedOverlay != null) {
            networkSpeedOverlay.stopTracking();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        String pkgName = sbn.getPackageName();
        Context context = getApplicationContext();

        // Line 93: Evaluates profile origin: "p" (Personal), "w" (Work), or "s" (Secure)
        String profile = getNotificationType(context, sbn);
        String profileLabel = "Personal";

        // 1. Profile Routing & Permission Checks
        if ("w".equals(profile)) {
            if (!preferenceManager.isForwardWorkProfileEnabled()) return;
            profileLabel = "Work Profile";
        } else if ("s".equals(profile)) {
            if (!preferenceManager.isForwardSecureFolderEnabled()) return;
            profileLabel = "Secure Folder";
        } else {
            // "p" Profile: Strict check against the App Selection list
            if (!preferenceManager.getSelectedPackages().contains(pkgName)) {
                return;
            }
        }

        // 2. Base Filtering (Ongoing, Self, and Time-limit)
        if (sbn.isOngoing()
                || OWN_PACKAGE_NAME.equals(pkgName)
                || (System.currentTimeMillis() - notification.when > 10_800_000)) {
            return;
        }

        // 3. Content Validation
        CharSequence charTitle = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence charText = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(charTitle) && TextUtils.isEmpty(charText)) return;

        String title = charTitle == null ? "" : charTitle.toString();
        String text = charText == null ? "" : charText.toString();
        String appName = getApplicationName(context, sbn, pkgName);

        // 4. Keyword Filtering (e.g., WhatsApp logic)
        String lowerTitle = title.toLowerCase();
        String lowerText = text.toLowerCase();
        boolean isWhatsApp = appName.toLowerCase().contains("whatsapp");
        boolean hasKeywords = lowerTitle.contains("school") || lowerTitle.contains("deliver")
                || lowerText.contains("school") || lowerText.contains("deliver");

        if (isWhatsApp && !hasKeywords) return;

        // 5. Channel Determination & Deduplication
        String channel = (isWhatsApp && !"w".equals(profile)) ? "telegram" : "slack";
        long lastSent = preferenceManager.getLruCache().get(text.hashCode());

        if (lastSent == -1) {
            // 6. Metadata Construction with Profile Info
            String senderInfo = String.format("-- %s [%s] @ %s",
                    appName,
                    profileLabel,
                    epochToTimeStamp(notification.when));

            sendMessage(channel, title, text, senderInfo, appName,
                    pkgName.toLowerCase(), sbn.getTag(), sbn.getId(), profile);

            preferenceManager.getLruCache().put(text.hashCode(), notification.when);
        }
    }

    private String getNotificationType(Context context, StatusBarNotification sbn) {
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        UserHandle userHandle = sbn.getUser();

        // If serial number is 0, it's the primary/personal profile
        if (userManager.getSerialNumberForUser(userHandle) == 0) {
            return "p";
        }

        // If it's not personal, check if it's a Work Profile vs Secure Folder
        LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        return launcherApps.getProfiles().contains(userHandle) ? "w" : "s";
    }


    private void writeMessageToStorage(String key, String title, String text, String senderInfo) {
        if (executorService == null || executorService.isShutdown()) return;

        executorService.execute(() -> {
            try {
                File externalBaseDir = getExternalFilesDir(null);
                if (externalBaseDir == null) return;

                File keyFolder = new File(externalBaseDir, key);
                if (!keyFolder.exists()) {
                    keyFolder.mkdirs();
                }

                String dateStamp = new SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(new Date());
                File dailyLogFile = new File(keyFolder, dateStamp + ".txt");

                String logEntry = String.format("[%s] Title: %s | Text: %s\n", senderInfo, title, text);

                // OPTIMIZED: Wrapped in FileWriter and BufferedWriter for rapid-fire stream efficiency
                try (java.io.FileWriter fw = new java.io.FileWriter(dailyLogFile, true);
                     java.io.BufferedWriter bw = new java.io.BufferedWriter(fw)) {
                    bw.write(logEntry);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error writing message to external storage for key: " + key, e);
            }
        });
    }

    public void sendMessage(String channel, CharSequence title, String text, String senderInfo, String appName,
                            String packageName, String tag, int id, String profile) {
        String tokens = preferenceManager.getWebToken();
        if (TextUtils.isEmpty(tokens)) {
            Log.e(TAG, "Web token not set");
            return;
        }

        String key = getChannelKey(channel, packageName, profile);
        // NEW: Automatically write to device storage, segregated by key
        writeMessageToStorage(key, String.valueOf(title), text, senderInfo);

        String webToken = tokenMap.getOrDefault(key, "");
        if (TextUtils.isEmpty(webToken)) {
            Log.e(TAG, "No valid token found for app: " + appName);
            return;
        }

        // 1. Determine the sender cleanly before jumping into the background thread
        MessageHelper sender = "telegram".equals(channel) ? telegramHelper : slackHelper;

        // 2. Process notification in a background thread
        executorService.execute(() ->
                sender.sendMessage(webToken, String.valueOf(title), text, senderInfo, success ->
                        handleSendResult(success, appName, String.valueOf(title), tag, id)
                )
        );
    }

    // Private helper method to handle the result of the message send operation
    private void handleSendResult(boolean success, String appName, String title, String tag, int id) {
        if (!success) {
            Log.e(TAG, "Failed to send message");
            return; // Exit early on failure
        }

        // Main success flow
        showForwardedNotification(appName, title);
        notificationManager.cancel(tag, id);

        // Clear all notifications on even hours
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) % 2 == 0) {
            notificationManager.cancelAll();
        }

        Log.d(TAG, "Message sent successfully");
    }

    // A new private method to parse the tokens. This can be cached in a class member.
    private static Map<String, String> parseWebTokens(String tokens) {
        return Arrays.stream(tokens.split(","))
                .map(pair -> pair.split("##"))
                .filter(pair -> pair.length == 2)
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]));
    }

    @NonNull
    private static String getChannelKey(String channel, String packageName, String profile) {
        if ("w".equals(profile)) return "client";

        if ("p".equals(profile) && "telegram".equals(channel)) return "colab";

        if ("s".equals(profile)) {
            // Group package names into lists for fast, clean lookups
            if (List.of("com.google.android.gm", "com.google.android.calendar",
                            "com.google.android.apps.dynamite", "com.google.android.apps.tachyon")
                    .contains(packageName)) {
                return "ttn";
            }

            if (List.of("com.microsoft.teams", "com.microsoft.office.outlook",
                            "com.duosecurity.duomobile", "com.azure.authenticator")
                    .contains(packageName)) {
                return "client1";
            }
        }

        return "other";
    }

    /** @noinspection deprecation*/
    private String getApplicationName(String packageName) {
        PackageManager packageManager = getPackageManager();
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            return packageManager.getApplicationLabel(appInfo).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private String getApplicationName(Context context, StatusBarNotification sbn, String pkgName) {
        UserHandle userHandle = sbn.getUser(); // This gives you the specific profile user
        // 1. Try the cross-profile LauncherApps service (Best for Work Profiles/Secure Folder)
        LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) {
            try {
                // Check if the app exists in the target profile
                if (launcherApps.isPackageEnabled(pkgName, userHandle)) {
                    // Fetch the actual label directly from that profile context
                    CharSequence label = context.getPackageManager().getUserBadgedLabel(
                            pkgName, userHandle
                    );
                    return label.toString();
                }
            } catch (Exception e) {
                // Fallback if cross-profile access is restricted by policy
            }
        }
        // 2. Fallback to standard PackageManager (For the primary profile)
        return getApplicationName(pkgName);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Notification Forwarder Service",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Channel for Notification Forwarder Service");
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
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
        {
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

