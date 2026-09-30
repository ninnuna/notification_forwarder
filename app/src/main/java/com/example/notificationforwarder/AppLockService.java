package com.example.notificationforwarder;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.Map;

public class AppLockService extends AccessibilityService {

    private PreferenceManager preferenceManager;

    // Tracks when each package was last verified successfully: <PackageName, EpochTimestamp>
    private static final Map<String, Long> unlockTimestamps = new HashMap<>();

    // Concurrency guard layout tracker to prevent rapid multi-intent firing cycles
    public static String currentLockingPackage = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        preferenceManager = new PreferenceManager(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgCharSeq = event.getPackageName();
            if (pkgCharSeq == null) return;

            String packageName = pkgCharSeq.toString();

            // Guard Clause: Ignore empty updates, our application layout, core OS components, and biometric prompts
            if (packageName.isEmpty()
                    || packageName.equals(getPackageName())
                    || packageName.equals("android")
                    || packageName.equals("com.android.systemui")
                    || packageName.contains("biometric")) {
                return;
            }

            // Verify if the package context matches the user's active protection list
            if (preferenceManager.isPackageLocked(packageName)) {
                long lastUnlockTime = unlockTimestamps.getOrDefault(packageName, 0L);
                long relockTimeoutMs = preferenceManager.getDelayValue(); // Read value from PreferenceManager

                // Evaluate if the grace period timeout has elapsed since the last manual validation
                boolean isExpired = (System.currentTimeMillis() - lastUnlockTime) > relockTimeoutMs;

                if (isExpired && !packageName.equals(currentLockingPackage)) {
                    currentLockingPackage = packageName;
                    launchLockScreen(packageName);
                }
            }
        }
    }

    /**
     * Updates the persistent grace period timestamp maps when a user successfully authenticates.
     */
    public static void setAppUnlocked(String packageName) {
        unlockTimestamps.put(packageName, System.currentTimeMillis());
        currentLockingPackage = "";
    }

    private void launchLockScreen(String packageName) {
        Intent intent = new Intent(this, AppLockActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("TARGET_PACKAGE", packageName);
        startActivity(intent);
    }

    @Override
    public void onInterrupt() {
        // Essential execution placeholder for AccessibilityService models
    }
}