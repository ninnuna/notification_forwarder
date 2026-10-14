package com.example.notificationforwarder;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;

public class AppLockService extends AccessibilityService {

    private PreferenceManager preferenceManager;
    private static final Map<String, Long> unlockTimestamps = new HashMap<>();
    private static final Map<String, Long> leaveTimestamps = new HashMap<>();
    public static String currentLockingPackage = "";
    private static String currentForegroundApp = "";
    private static final String CHANNEL_ID = "AppLockServiceChannel";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        preferenceManager = new PreferenceManager(this);

        // Start as Foreground Service to prevent OS from killing it
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("App Protector Active")
                .setContentText("Monitoring protected applications")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
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
        String packageName = pkgCharSeq.toString().trim();
        if (packageName.isEmpty()) return;

        // Ignore events from our own application / lock screen
        if (packageName.equals(getPackageName()) || packageName.contains("AppLockActivity")) {
            return;
        }

        // Check if device is in an active or ringing phone call
        if (isCallActive()) {
            refreshUnlockTimestamps();
            return;
        }

        // Check if package is a system overlay, dialer, keyboard, or system framework
        if (isIgnoredOrSystemPackage(packageName)) {
            refreshUnlockTimestamps();
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
            if (!packageName.equals(currentLockingPackage)) {
                currentLockingPackage = "";
            }
            return;
        }

        // LOCK LOGIC FOR MARKED/LOCKED APPS
        long currentTime = System.currentTimeMillis();
        long relockTimeoutMs = preferenceManager.getDelayValue();

        // 1. If user is currently actively inside this app, keep session alive
        if (packageName.equals(currentForegroundApp) && unlockTimestamps.containsKey(packageName)) {
            unlockTimestamps.put(packageName, currentTime);
            leaveTimestamps.remove(packageName);
            return;
        }

        // 2. Evaluate if lock session has expired
        boolean isUnlocked = unlockTimestamps.containsKey(packageName);
        Long lastLeaveTimeObj = leaveTimestamps.get(packageName);
        long lastLeaveTime = lastLeaveTimeObj != null ? lastLeaveTimeObj : 0L;
        long awayDuration = lastLeaveTime > 0 ? (currentTime - lastLeaveTime) : Long.MAX_VALUE;

        // Expired if it was never unlocked OR if away time exceeds the relock timeout
        boolean isExpired = !isUnlocked || (lastLeaveTime > 0 && awayDuration > relockTimeoutMs);

        if (isExpired) {
            if (!packageName.equals(currentLockingPackage)) {
                currentLockingPackage = packageName;
                launchLockScreen(packageName);
            }
        } else {
            // Re-entered within timeout grace period
            unlockTimestamps.put(packageName, currentTime);
            leaveTimestamps.remove(packageName);
            currentForegroundApp = packageName;
            if (packageName.equals(currentLockingPackage)) {
                currentLockingPackage = "";
            }
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isCallActive() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                int state = tm.getCallState();
                return state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isIgnoredOrSystemPackage(String pkg) {
        if (pkg.equals("android") ||
                pkg.equals("com.android.systemui") ||
                pkg.contains("bitwarden") ||
                pkg.contains("passwords") ||
                pkg.contains("autofill")) {
            return true;
        }

        // Dialer & In-Call UI packages
        if (pkg.equals("com.google.android.dialer") ||
                pkg.equals("com.samsung.android.incallui") ||
                pkg.equals("com.samsung.android.dialer") ||
                pkg.equals("com.android.incallui") ||
                pkg.equals("com.android.phone") ||
                pkg.equals("com.android.server.telecom") ||
                pkg.equals("com.google.android.incallui") ||
                pkg.equals("com.truecaller")) {
            return true;
        }

        // Input Method Editors (Keyboards)
        return pkg.contains("inputmethod") || pkg.contains("keyboard") ||
                pkg.equals("com.google.android.inputmethod.latin") ||
                pkg.equals("com.samsung.android.honeyboard");
    }

    private void refreshUnlockTimestamps() {
        long now = System.currentTimeMillis();
        if (!currentForegroundApp.isEmpty() && unlockTimestamps.containsKey(currentForegroundApp)) {
            unlockTimestamps.put(currentForegroundApp, now);
            leaveTimestamps.remove(currentForegroundApp);
        }
    }

    private void launchLockScreen(String packageName) {
        Intent intent = new Intent(this, AppLockActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("TARGET_PACKAGE", packageName);
        startActivity(intent);
    }

    public static void setAppUnlocked(String packageName, AppLockService serviceInstance) {
        long now = System.currentTimeMillis();
        unlockTimestamps.put(packageName, now);
        leaveTimestamps.remove(packageName);
        currentForegroundApp = packageName;
        currentLockingPackage = "";
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
