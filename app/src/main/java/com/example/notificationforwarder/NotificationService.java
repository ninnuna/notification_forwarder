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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationService";
    private static final String CHANNEL_ID = "notification_forwarder_channel_v5";
    private static final int FOREGROUND_NOTIFICATION_ID = 1;
    private static final String OWN_PACKAGE_NAME = "com.example.notificationforwarder";
    private PreferenceManager preferenceManager;
    private ExecutorService executorService;
    
    // --- App Lock Tracking Variables ---
    private ScheduledExecutorService pollingExecutor;
    private UsageStatsManager usageStatsManager;
    private static final Map<String, Long> unlockTimestamps = new HashMap<>();
    private static final Map<String, Long> leaveTimestamps = new HashMap<>();
    public static String currentLockingPackage = "";
    private static String currentForegroundApp = "";
    private static long lastLockLaunchTime = 0;
    
    // --- Network Speed Tracking Variables ---
    private long lastRxBytes = android.net.TrafficStats.UNSUPPORTED;
    private long lastTxBytes = android.net.TrafficStats.UNSUPPORTED;
    private long lastSpeedCheckTime = 0;
    
    private NotificationManager notificationManager;
    public static Map<String, String> tokenMap;
    public static final MessageHelper slackHelper = new SlackHelper();
    public static final MessageHelper telegramHelper = new TelegramHelper();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        preferenceManager = new PreferenceManager(this);
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        tokenMap = parseWebTokens(preferenceManager.getWebToken());
        executorService = Executors.newSingleThreadExecutor();
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification());
        
        startAppLockPolling();
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
        if (pollingExecutor != null) {
            pollingExecutor.shutdownNow();
        }
    }

    // --- Polling Logic ---
    private void startAppLockPolling() {
        if (pollingExecutor != null) {
            pollingExecutor.shutdownNow();
        }
        pollingExecutor = Executors.newScheduledThreadPool(2);
        // Use FixedDelay to prevent rapid burst executions when process wakes from cache
        pollingExecutor.scheduleWithFixedDelay(this::checkForegroundApp, 0, 500, TimeUnit.MILLISECONDS);
        pollingExecutor.scheduleWithFixedDelay(this::updateNetworkSpeed, 0, 3000, TimeUnit.MILLISECONDS);
    }
    
    private void updateNetworkSpeed() {
        long currentTime = System.currentTimeMillis();
        long currentRx = android.net.TrafficStats.getTotalRxBytes();
        long currentTx = android.net.TrafficStats.getTotalTxBytes();

        if (currentRx == android.net.TrafficStats.UNSUPPORTED || currentTx == android.net.TrafficStats.UNSUPPORTED) {
            return;
        }

        if (lastRxBytes != android.net.TrafficStats.UNSUPPORTED && lastTxBytes != android.net.TrafficStats.UNSUPPORTED && lastSpeedCheckTime > 0) {
            long rxDiff = currentRx - lastRxBytes;
            long txDiff = currentTx - lastTxBytes;
            long timeDiff = currentTime - lastSpeedCheckTime;

            if (timeDiff > 0) {
                long rxBytesPerSec = (rxDiff * 1000) / timeDiff;
                long txBytesPerSec = (txDiff * 1000) / timeDiff;

                String downSpeed = formatSpeed(rxBytesPerSec);
                String upSpeed = formatSpeed(txBytesPerSec);

                Notification notification = createForegroundNotification("↓ " + downSpeed + "   ↑ " + upSpeed);
                if (notificationManager != null) {
                    notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification);
                }
            }
        }

        lastRxBytes = currentRx;
        lastTxBytes = currentTx;
        lastSpeedCheckTime = currentTime;
    }

    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 1024 * 1024) {
            return (bytesPerSec / 1024) + " KB/s";
        } else {
            return String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024f * 1024f));
        }
    }

    private String lastDetectedPackage = "";

    private void checkForegroundApp() {
        if (usageStatsManager == null) return;

        long endTime = System.currentTimeMillis();
        // Always query the last 2000ms. Do NOT use a delta timestamp. 
        // This ensures if a sub-activity resumes and we miss the exact ms window due to the 500ms delay,
        // we still catch it in the rolling 2-second net.
        long startTime = endTime - 2000;

        UsageEvents events = usageStatsManager.queryEvents(startTime, endTime);
        UsageEvents.Event event = new UsageEvents.Event();

        String latestForegroundApp = null;

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestForegroundApp = event.getPackageName();
            }
        }

        // Persist the last known foreground app so we evaluate it EVERY 500ms,
        // even if no new UsageEvents fired in this specific 500ms window.
        if (latestForegroundApp != null) {
            lastDetectedPackage = latestForegroundApp;
        }

        if (!lastDetectedPackage.isEmpty()) {
            handlePackageChange(lastDetectedPackage);
        }
    }

    private void handlePackageChange(String packageName) {
        packageName = packageName.trim();
        if (packageName.isEmpty()) return;

        // Ignore events from our own application / lock screen
        if (packageName.equals(getPackageName()) || packageName.contains("AppLockActivity")) {
            return;
        }

        // If switching from a previous locked app to a new package, record leave timestamp
        if (!currentForegroundApp.isEmpty() && !currentForegroundApp.equals(packageName)) {
            if (preferenceManager.isPackageLocked(currentForegroundApp)) {
                leaveTimestamps.put(currentForegroundApp, System.currentTimeMillis());
            }
        }

        // STRICT WHITELIST: If package is NOT locked, record foreground app and exit
        if (!preferenceManager.isPackageLocked(packageName)) {
            currentForegroundApp = packageName;
            if (!AppLockActivity.isVisible && !packageName.equals(currentLockingPackage)) {
                currentLockingPackage = "";
            }
            return;
        }

        // LOCK LOGIC FOR MARKED/LOCKED APPS
        long currentTime = System.currentTimeMillis();
        long relockTimeoutMs = preferenceManager.getDelayValue();

        if (packageName.equals(currentForegroundApp) && unlockTimestamps.containsKey(packageName)) {
            unlockTimestamps.put(packageName, currentTime);
            leaveTimestamps.remove(packageName);
            return;
        }

        boolean isUnlocked = unlockTimestamps.containsKey(packageName);
        Long lastLeaveTimeObj = leaveTimestamps.get(packageName);
        long lastLeaveTime = lastLeaveTimeObj != null ? lastLeaveTimeObj : 0L;
        long awayDuration = lastLeaveTime > 0 ? (currentTime - lastLeaveTime) : Long.MAX_VALUE;

        boolean isExpired = !isUnlocked || (lastLeaveTime > 0 && awayDuration > relockTimeoutMs);

        if (isExpired) {
            // Aggressively re-assert lock if it was bypassed, suppressed, or destroyed.
            // Reduced cooldown to 500ms to immediately crush sub-activities pushing the lock screen down.
            if (!AppLockActivity.isVisible) {
                if (!packageName.equals(currentLockingPackage) || (currentTime - lastLockLaunchTime > 500)) {
                    currentLockingPackage = packageName;
                    lastLockLaunchTime = currentTime;
                    launchLockScreen(packageName);
                }
            }
        } else {
            unlockTimestamps.put(packageName, currentTime);
            leaveTimestamps.remove(packageName);
            currentForegroundApp = packageName;
            if (packageName.equals(currentLockingPackage)) {
                currentLockingPackage = "";
            }
        }
    }

    private void launchLockScreen(String packageName) {
        Intent intent = new Intent(this, AppLockActivity.class);
        // Using REORDER_TO_FRONT along with NEW_TASK effectively forces the activity 
        // to aggressively pop back to the top of the z-order even if Garmin drops a new window.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                        Intent.FLAG_ACTIVITY_SINGLE_TOP | 
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | 
                        Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra("TARGET_PACKAGE", packageName);
        startActivity(intent);
    }

    public static void setAppUnlocked(String packageName) {
        long now = System.currentTimeMillis();
        unlockTimestamps.put(packageName, now);
        leaveTimestamps.remove(packageName);
        currentForegroundApp = packageName;
        currentLockingPackage = "";
    }
    // --- End App Lock Polling Logic ---

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
            // "p" Profile: Check against the App Selection list
            if (!preferenceManager.getTelegramPackages().contains(pkgName) &&
                !preferenceManager.getSlackPackages().contains(pkgName)) {
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

        boolean forwardToTelegram = preferenceManager.getTelegramPackages().contains(pkgName);
        boolean forwardToSlack = preferenceManager.getSlackPackages().contains(pkgName);

        if (!forwardToTelegram && !forwardToSlack) return;

        // 4. Keyword Filtering
        String lowerTitle = title.toLowerCase();
        String lowerText = text.toLowerCase();

        boolean tgKeywordsEmpty = preferenceManager.getParsedTelegramKeywords().isEmpty();
        boolean slackKeywordsEmpty = preferenceManager.getParsedSlackKeywords().isEmpty();

        boolean tgKeywordMatch = tgKeywordsEmpty || containsKeyword(lowerTitle, lowerText, preferenceManager.getParsedTelegramKeywords());
        boolean slackKeywordMatch = slackKeywordsEmpty || containsKeyword(lowerTitle, lowerText, preferenceManager.getParsedSlackKeywords());

        // If it matches Telegram keywords, block Slack routing unless Slack explicitly matches too.
        // If the Slack list is empty, default it to true, but NEVER allow it to piggyback on Telegram's match.
        boolean shouldSendTg = forwardToTelegram && tgKeywordMatch;
        boolean shouldSendSlack = forwardToSlack && (!slackKeywordsEmpty ? slackKeywordMatch : !tgKeywordMatch);

        if (!shouldSendTg && !shouldSendSlack) return;

        // 5. Deduplication
        long lastSent = preferenceManager.getLruCache().get(text.hashCode());

        if (lastSent == -1) {
            String senderInfo = String.format("-- %s [%s] @ %s", appName, profileLabel, epochToTimeStamp(notification.when));

            // 6. Dynamic Channel Routing
            if (shouldSendTg) {
                String tgChannel = getChannelKey("telegram", pkgName, profile);
                sendMessage(tgChannel, title, text, senderInfo, appName, pkgName.toLowerCase(), sbn.getTag(), sbn.getId(), profile);
            }
            if (shouldSendSlack) {
                String slackChannel = getChannelKey("slack", pkgName, profile);
                sendMessage(slackChannel, title, text, senderInfo, appName, pkgName.toLowerCase(), sbn.getTag(), sbn.getId(), profile);
            }

            preferenceManager.getLruCache().put(text.hashCode(), notification.when);
        }
    }
    
    private boolean containsKeyword(String lowerTitle, String lowerText, java.util.List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return false;
        for (String k : keywords) {
            if (lowerTitle.contains(k) || lowerText.contains(k)) {
                return true;
            }
        }
        return false;
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

        // Make sure we parse tokens if they haven't been loaded yet.
        if (tokenMap == null || tokenMap.isEmpty()) {
            tokenMap = parseWebTokens(tokens);
        }

        String webToken = tokenMap.getOrDefault(key, "");
        if (TextUtils.isEmpty(webToken)) {
            Log.e(TAG, "No valid token found for channel: " + channel + " with key: " + key);
            return;
        }

        // 1. Determine the sender cleanly before jumping into the background thread
        MessageHelper sender;
        if (key.equals(preferenceManager.getParsedRoutingConfig().personalTgKey) || channel.equals("telegram")) {
            sender = telegramHelper;
        } else {
            sender = slackHelper;
        }

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
    private String getChannelKey(String channel, String packageName, String profile) {
        PreferenceManager.RoutingConfig config = preferenceManager.getParsedRoutingConfig();
        
        if ("w".equals(profile)) return config.workKey;

        if ("p".equals(profile)) {
            if ("telegram".equals(channel)) return config.personalTgKey;
            if ("slack".equals(channel)) return config.personalSlackKey;
        }

        if ("s".equals(profile)) {
            if (config.s1Pkgs.contains(packageName)) return config.s1Key;
            if (config.s2Pkgs.contains(packageName)) return config.s2Key;
        }

        return config.defaultKey;
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
                NotificationManager.IMPORTANCE_HIGH); // Changed to HIGH to pin to top
        channel.setDescription("Channel for Notification Forwarder Service");
        channel.setSound(null, null);
        channel.enableVibration(false);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createForegroundNotification() {
        return createForegroundNotification("Service is running");
    }

    private Notification createForegroundNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Notification Forwarder")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setOnlyAlertOnce(true) // Prevent vibrating on every update
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

