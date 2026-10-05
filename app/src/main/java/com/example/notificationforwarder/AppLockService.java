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
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        CharSequence pkgCharSeq = event.getPackageName();
        if (pkgCharSeq == null) return;
        String packageName = pkgCharSeq.toString();

        // 1. IGNORE LIST: Explicitly ignore Bitwarden and System UI
        // We do NOT return immediately if it's one of these; instead, we "Keep-Alive"
        // the last active locked app so the timer doesn't expire during autofill.
        if (packageName.equals("android") ||
                packageName.equals("com.android.systemui") ||
                packageName.contains("bitwarden") ||
                packageName.equals(getPackageName())) {

            // If an overlay appears, refresh timestamps for any apps currently "in session"
            // so the timer doesn't run out while the user is looking at the Bitwarden popup.
            long now = System.currentTimeMillis();
            for (String activePkg : unlockTimestamps.keySet()) {
                unlockTimestamps.put(activePkg, now);
            }
            return;
        }

        // 2. STRICT WHITELIST: If the package is NOT in our locked list, stop here.
        if (!preferenceManager.isPackageLocked(packageName)) {
            // We clear the guard but don't reset timestamps for other apps
            currentLockingPackage = "";
            return;
        }

        // 3. LOCK LOGIC (for Whitelisted Apps only)
        long lastUnlockTime = unlockTimestamps.getOrDefault(packageName, 60L);
        long relockTimeoutMs = preferenceManager.getDelayValue();
        long currentTime = System.currentTimeMillis();

        boolean isExpired = (currentTime - lastUnlockTime) > relockTimeoutMs;

        if (isExpired) {
            // Only launch if we aren't already trying to lock this specific package
            if (!packageName.equals(currentLockingPackage)) {
                currentLockingPackage = packageName;
                launchLockScreen(packageName);
            }
        } else {
            // --- KEEP-ALIVE ---
            // Refresh the timestamp every time the window changes within the locked app.
            // This prevents erratic re-locking during internal navigation (MakeMyTrip).
            unlockTimestamps.put(packageName, currentTime);
            currentLockingPackage = "";
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