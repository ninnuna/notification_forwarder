package com.example.notificationforwarder;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;

public class AppLockService extends AccessibilityService {

    private PreferenceManager preferenceManager;
    private static final Map<String, Long> unlockTimestamps = new HashMap<>();
    public static String currentLockingPackage = "";
    private static final String CHANNEL_ID = "AppLockServiceChannel";
    private String lastActivePackage = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        preferenceManager = new PreferenceManager(this);

        // FIX: Start as Foreground Service to prevent OS from killing it
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("App Protector Active")
                .setContentText("Monitoring protected applications")
                .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure this icon exists
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        startForeground(1, notification);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        CharSequence pkgCharSeq = event.getPackageName();
        if (pkgCharSeq == null) return;
        String packageName = pkgCharSeq.toString();

        // FIX: If the event is coming from our own Lock Screen or App, ignore it.
        // This prevents the "Flicker Loop" where starting the lock triggers another lock check.
        if (packageName.equals(getPackageName()) || packageName.contains("AppLockActivity")) {
            return;
        }

        if (packageName.equals(lastActivePackage)) {
            unlockTimestamps.put(packageName, System.currentTimeMillis());
            return;
        }

        // 1. IGNORE LIST (Bitwarden / System UI)
        if (packageName.equals("android") ||
                packageName.equals("com.android.systemui") ||
                packageName.contains("bitwarden")) {

            long now = System.currentTimeMillis();
            for (String activePkg : unlockTimestamps.keySet()) {
                unlockTimestamps.put(activePkg, now);
            }
            return;
        }

        // 2. STRICT WHITELIST
        if (!preferenceManager.isPackageLocked(packageName)) {
            // Only clear the guard if we've actually moved to a different app
            if (!packageName.equals(currentLockingPackage)) {
                currentLockingPackage = "";
            }
            return;
        }

        // 3. LOCK LOGIC
        long lastUnlockTime = unlockTimestamps.getOrDefault(packageName, 0L);
        long relockTimeoutMs = preferenceManager.getDelayValue();
        long currentTime = System.currentTimeMillis();

        boolean isExpired = (lastUnlockTime == 0) || (currentTime - lastUnlockTime) > relockTimeoutMs;

        if (isExpired) {
            // FIX: Guard against rapid multi-intent firing
            if (!packageName.equals(currentLockingPackage)) {
                currentLockingPackage = packageName;
                launchLockScreen(packageName);
            }
        } else {
            // Keep-Alive for internal navigation
            unlockTimestamps.put(packageName, currentTime);
            // If the user is actively using the app, clear the locking guard
            if (packageName.equals(currentLockingPackage)) {
                currentLockingPackage = "";
            }
        }
    }

    private void launchLockScreen(String packageName) {
        Intent intent = new Intent(this, AppLockActivity.class);
        // FIX: Use FLAG_ACTIVITY_SINGLE_TOP to prevent multiple instances
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("TARGET_PACKAGE", packageName);
        startActivity(intent);
    }

    // Update this method in AppLockService.java
    public static void setAppUnlocked(String packageName, AppLockService serviceInstance) {
        unlockTimestamps.put(packageName, System.currentTimeMillis());
        currentLockingPackage = "";

        // This is the vital part:
        if (serviceInstance != null) {
            serviceInstance.lastActivePackage = packageName;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "App Lock Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onInterrupt() {}
}
