package com.example.notificationforwarder;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PreferenceManager {
    private static final String PREFERENCES_NAME = "notification_forwarder_preferences";
    private static final String KEY_WEB_TOKEN = "web_token";
    private static final String KEY_SERVICE_ENABLED = "service_enabled";

    private final SharedPreferences preferences;

    private static final String KEY_SELECTED_PACKAGES = "selected_packages";

    public String getSelectedPackages() {
        return preferences.getString(KEY_SELECTED_PACKAGES, "");
    }

    private final LRUCache lruCache = new LRUCache(2000);

    public LRUCache getLruCache() {
        return lruCache;
    }

    public void setSelectedPackages(String csv) {
        preferences.edit().putString(KEY_SELECTED_PACKAGES, csv).apply();
    }

    public void setSelectedPackages(List<String> packages) {
        String csv = TextUtils.join(",", packages);
        preferences.edit().putString(KEY_SELECTED_PACKAGES, csv).apply();
    }

    public PreferenceManager(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public String getWebToken() {
        return preferences.getString(KEY_WEB_TOKEN, "");
    }

    public void setWebToken(String webToken) {
        preferences.edit().putString(KEY_WEB_TOKEN, webToken).apply();
    }

    public boolean isServiceEnabled() {
        return preferences.getBoolean(KEY_SERVICE_ENABLED, false);
    }

    public void setServiceEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply();
    }
}
