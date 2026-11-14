package com.example.notificationforwarder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final String KEY_CUSTOM_PIN = "custom_security_pin";

    private final SharedPreferences preferences;

    // Notification Deduplication Cache (restored functionality)
    private final LRUCache lruCache = new LRUCache(2000);

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
        return preferences.getStringSet("locked_packages", new HashSet<>());
    }

    /**
     * Saves the set of locked packages.
     */
    public void setLockedPackages(List<String> packageList) {
        Set<String> set = new HashSet<>();
        if (packageList != null) {
            for (String pkg : packageList) {
                if (!TextUtils.isEmpty(pkg)) {
                    set.add(pkg.trim());
                }
            }
        }
        preferences.edit().putStringSet("locked_packages", set).apply();
    }

    /**
     * Checks if a specific package is in the locked set.
     *
     * @param packageName The package name to check.
     * @return true if the package is locked.
     */
    public boolean isPackageLocked(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        Set<String> locked = getLockedPackages();
        if (locked == null || locked.isEmpty()) {
            return false;
        }
        return locked.contains(packageName.trim());
    }

    // --- Notification Forwarding Logic ---
    private static final String KEY_TELEGRAM_PACKAGES = "telegram_forward_packages";
    private static final String KEY_SLACK_PACKAGES = "slack_forward_packages";

    public String getTelegramPackages() {
        return preferences.getString(KEY_TELEGRAM_PACKAGES, "");
    }

    public void setTelegramPackages(String csv) {
        preferences.edit().putString(KEY_TELEGRAM_PACKAGES, csv).apply();
    }
    
    public String getSlackPackages() {
        return preferences.getString(KEY_SLACK_PACKAGES, "");
    }

    public void setSlackPackages(String csv) {
        preferences.edit().putString(KEY_SLACK_PACKAGES, csv).apply();
    }

    // --- Keyword Filtering ---
    private static final String KEY_TELEGRAM_KEYWORDS = "telegram_keywords";
    private static final String KEY_SLACK_KEYWORDS = "slack_keywords";

    private String lastRawTelegram = null;
    private List<String> parsedTelegram = new ArrayList<>();

    public String getTelegramKeywords() {
        return preferences.getString(KEY_TELEGRAM_KEYWORDS, "");
    }

    public List<String> getParsedTelegramKeywords() {
        String raw = getTelegramKeywords();
        if (!raw.equals(lastRawTelegram)) {
            lastRawTelegram = raw;
            parsedTelegram.clear();
            if (!raw.isEmpty()) {
                for (String kw : raw.split(",")) {
                    String k = kw.trim().toLowerCase();
                    if (!k.isEmpty()) parsedTelegram.add(k);
                }
            }
        }
        return parsedTelegram;
    }

    public void setTelegramKeywords(String keywords) {
        preferences.edit().putString(KEY_TELEGRAM_KEYWORDS, keywords).apply();
    }

    private String lastRawSlack = null;
    private List<String> parsedSlack = new ArrayList<>();

    public String getSlackKeywords() {
        return preferences.getString(KEY_SLACK_KEYWORDS, "");
    }

    public List<String> getParsedSlackKeywords() {
        String raw = getSlackKeywords();
        if (!raw.equals(lastRawSlack)) {
            lastRawSlack = raw;
            parsedSlack.clear();
            if (!raw.isEmpty()) {
                for (String kw : raw.split(",")) {
                    String k = kw.trim().toLowerCase();
                    if (!k.isEmpty()) parsedSlack.add(k);
                }
            }
        }
        return parsedSlack;
    }

    public void setSlackKeywords(String keywords) {
        preferences.edit().putString(KEY_SLACK_KEYWORDS, keywords).apply();
    }
    
    // --- Location Config ---
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_LOCATION_INTERVAL = "location_interval";
    
    public String getDeviceName() {
        return preferences.getString(KEY_DEVICE_NAME, Build.MODEL);
    }
    
    public void setDeviceName(String name) {
        preferences.edit().putString(KEY_DEVICE_NAME, name).apply();
    }
    
    public int getLocationInterval() {
        return preferences.getInt(KEY_LOCATION_INTERVAL, 0); // 0 means disabled
    }
    
    public void setLocationInterval(String intervalSecs) {
        try {
            int value = TextUtils.isEmpty(intervalSecs) ? 0 : Integer.parseInt(intervalSecs);
            preferences.edit().putInt(KEY_LOCATION_INTERVAL, value).apply();
        } catch (NumberFormatException e) {
            preferences.edit().putInt(KEY_LOCATION_INTERVAL, 0).apply();
        }
    }

    // --- Dynamic Routing Config Logic ---
    private static final String KEY_ROUTING_CONFIG = "routing_config_v2";

    public String getRoutingConfig() {
        return preferences.getString(KEY_ROUTING_CONFIG, 
                "w=client; p_tg=colab; p_slk=other; s1=ttn|dummy; s2=client1|dummy; default=other");
    }

    public void setRoutingConfig(String config) {
        preferences.edit().putString(KEY_ROUTING_CONFIG, config).apply();
    }

    public static class RoutingConfig {
        public String workKey = "client";
        public String personalTgKey = "colab";
        public String personalSlackKey = "other";
        public String s1Key = "ttn";
        public Set<String> s1Pkgs = new HashSet<>();
        public String s2Key = "client1";
        public Set<String> s2Pkgs = new HashSet<>();
        public String defaultKey = "other";
    }

    private String lastRawRoutingConfig = null;
    private RoutingConfig parsedRoutingConfig = new RoutingConfig();

    public RoutingConfig getParsedRoutingConfig() {
        String raw = getRoutingConfig();
        if (!raw.equals(lastRawRoutingConfig)) {
            lastRawRoutingConfig = raw;
            parsedRoutingConfig = new RoutingConfig(); // reset to defaults
            
            if (!raw.isEmpty()) {
                for (String rule : raw.split(";")) {
                    String[] parts = rule.trim().split("=");
                    if (parts.length == 2) {
                        String ruleName = parts[0].trim().toLowerCase();
                        String ruleValue = parts[1].trim();
                        
                        if (ruleName.equals("w")) parsedRoutingConfig.workKey = ruleValue;
                        else if (ruleName.equals("p_tg")) parsedRoutingConfig.personalTgKey = ruleValue;
                        else if (ruleName.equals("p_slk")) parsedRoutingConfig.personalSlackKey = ruleValue;
                        else if (ruleName.equals("default")) parsedRoutingConfig.defaultKey = ruleValue;
                        else if (ruleName.equals("s1") || ruleName.equals("s2")) {
                            String[] valParts = ruleValue.split("\\|");
                            if (valParts.length > 0) {
                                String key = valParts[0].trim();
                                Set<String> pkgs = new HashSet<>();
                                if (valParts.length > 1) {
                                    for (String p : valParts[1].split(",")) {
                                        pkgs.add(p.trim().toLowerCase());
                                    }
                                }
                                if (ruleName.equals("s1")) {
                                    parsedRoutingConfig.s1Key = key;
                                    parsedRoutingConfig.s1Pkgs = pkgs;
                                } else {
                                    parsedRoutingConfig.s2Key = key;
                                    parsedRoutingConfig.s2Pkgs = pkgs;
                                }
                            }
                        }
                    }
                }
            }
        }
        return parsedRoutingConfig;
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