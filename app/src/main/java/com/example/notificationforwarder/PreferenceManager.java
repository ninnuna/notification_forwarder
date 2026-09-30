package com.example.notificationforwarder;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages secure storage for the application.
 * Uses EncryptedSharedPreferences to protect sensitive data like Web Tokens and PINs.
 * Maintains an LRUCache for the NotificationService to prevent duplicate forwarding.
 */
public class PreferenceManager {
    // Shared Preference Keys
    private static final String KEY_WEB_TOKEN = "web_token";
    private static final String KEY_SERVICE_ENABLED = "service_enabled";
    private static final String KEY_DELAY_VALUE = "delay_value";
    private static final String KEY_FORWARD_PACKAGES = "forward_packages";
    private static final String KEY_LOCKED_PACKAGES = "locked_packages";
    private static final String KEY_CUSTOM_PIN = "custom_security_pin";

    private final SharedPreferences preferences;

    // Notification Deduplication Cache (restored functionality)
    private final LRUCache lruCache = new LRUCache(2000);

    // Default system apps to keep locked by default
    private static final String DEFAULT_LOCKS = "com.android.settings," +
            "com.android.packageinstaller,com.google.android.packageinstaller," +
            "com.samsung.android.packageinstaller," +
            "com.samsung.android.samsungpass,com.samsung.android.samsungpassautofill";

    public PreferenceManager(Context context) {
        preferences = getEncryptedPrefs(context);
    }

    /**
     * Initializes EncryptedSharedPreferences using the Android Keystore.
     */
    private static SharedPreferences getEncryptedPrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            return EncryptedSharedPreferences.create(
                    context,
                    "secure_utilities_prefs", // File name
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            // Fallback to standard SharedPreferences if encryption fails to prevent app crash
            return context.getSharedPreferences("utility_prefs_fallback", Context.MODE_PRIVATE);
        }
    }

    // --- LRU Cache Access (for NotificationService) ---

    public LRUCache getLruCache() {
        return lruCache;
    }

    // --- Security PIN Logic ---

    public String getCustomPin() {
        return preferences.getString(KEY_CUSTOM_PIN, "");
    }

    public void setCustomPin(String pin) {
        preferences.edit().putString(KEY_CUSTOM_PIN, pin).apply();
    }

    // --- App Lock Logic ---

    /**
     * Retrieves the set of packages currently locked.
     * Using a Set matches the standard SharedPreferences.getStringSet API.
     */
    public Set<String> getLockedPackages() {
        // Return a Set instead of a List to avoid type mismatch
        return preferences.getStringSet("locked_packages", new HashSet<>());
    }

    /**
     * Saves the set of locked packages.
     */
    public void setLockedPackages(List<String> packageList) {
        // Converts the List back to a Set for storage
        Set<String> set = new HashSet<>(packageList);
        preferences.edit().putStringSet("locked_packages", set).apply();
    }

    /**
     * Checks if a specific package is in the locked set.
     *
     * @param packageName The package name to check.
     * @return true if the package is locked.
     */
    public boolean isPackageLocked(String packageName) {
        Set<String> locked = getLockedPackages();
        return locked != null && locked.contains(packageName);
    }

    // --- Notification Forwarding Logic ---

    public String getSelectedPackages() {
        return preferences.getString(KEY_FORWARD_PACKAGES, "");
    }

    public void setSelectedPackages(String csv) {
        preferences.edit().putString(KEY_FORWARD_PACKAGES, csv).apply();
    }

    // --- General Settings ---

    public String getWebToken() {
        return preferences.getString(KEY_WEB_TOKEN, "");
    }

    public void setWebToken(String token) {
        preferences.edit().putString(KEY_WEB_TOKEN, token).apply();
    }

    public int getDelayValue() {
        // Default to 60000ms (1 minute) if the value is 0 or unset
        int val = preferences.getInt(KEY_DELAY_VALUE, 60000);
        return val <= 0 ? 60000 : val;
    }

    public void setDelayValue(String delay) {
        try {
            int value = TextUtils.isEmpty(delay) ? 0 : Integer.parseInt(delay);
            preferences.edit().putInt(KEY_DELAY_VALUE, value).apply();
        } catch (NumberFormatException e) {
            preferences.edit().putInt(KEY_DELAY_VALUE, 60000).apply();
        }
    }

    public boolean isServiceEnabled() {
        return preferences.getBoolean(KEY_SERVICE_ENABLED, false);
    }

    public void setServiceEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply();
    }

    // Inside PreferenceManager.java - Add these keys
    private static final String KEY_FORWARD_WORK = "forward_work_profile";
    private static final String KEY_FORWARD_SECURE = "forward_secure_folder";

    // Add these methods
    public boolean isForwardWorkProfileEnabled() {
        return preferences.getBoolean(KEY_FORWARD_WORK, true); // Default true
    }

    public void setForwardWorkProfile(boolean enabled) {
        preferences.edit().putBoolean(KEY_FORWARD_WORK, enabled).apply();
    }

    public boolean isForwardSecureFolderEnabled() {
        return preferences.getBoolean(KEY_FORWARD_SECURE, true); // Default true
    }

    public void setForwardSecureFolder(boolean enabled) {
        preferences.edit().putBoolean(KEY_FORWARD_SECURE, enabled).apply();
    }

    // --- Dashboard Lock and Session Tracking Logic ---
    private static final String KEY_LAST_ACTIVE_TIME = "last_active_time";

    /**
     * Updates the last active timestamp to the current system time.
     * Call this whenever the user successfully unlocks the passcode overlay
     * or interacts with the dashboard screens.
     */
    public void updateLastActiveTime() {
        preferences.edit().putLong(KEY_LAST_ACTIVE_TIME, System.currentTimeMillis()).apply();
    }

    /**
     * Resets the session timer to zero.
     * Called in MainActivity.onStop() to enforce immediate background lockups.
     */
    public void clearDashboardSession() {
        preferences.edit().putLong(KEY_LAST_ACTIVE_TIME, 0).apply();
    }

    /**
     * Evaluates if the elapsed duration since the last interaction
     * exceeds the user-configured lock timeout settings.
     *
     * @return true if the dashboard session has expired or hasn't been initialized yet.
     */
    public boolean shouldRelockDashboard() {
        long lastActive = preferences.getLong(KEY_LAST_ACTIVE_TIME, 0);

        // If the session has never been unlocked or was cleared on exit, force lock
        if (lastActive == 0) {
            return true;
        }

        long currentTime = System.currentTimeMillis();
        long elapsed = currentTime - lastActive;

        // Safely references your existing getDelayValue() integer method
        int timeout = getDelayValue();

        return elapsed > timeout;
    }
}