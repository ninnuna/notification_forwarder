package com.example.notificationforwarder;

import android.graphics.drawable.Drawable;

public class AppInfo {
    public String appName;
    public String packageName;
    public android.graphics.drawable.Drawable icon;
    public boolean isSelected;

    public boolean isSystem;

    public String getAppName() {
        return appName;
    }

    public String getPackageName() {
        return packageName;
    }

    public Drawable getIcon() {
        return icon;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public boolean isSystem() {
        return isSystem;
    }

    public AppInfo(String appName, String packageName, android.graphics.drawable.Drawable icon, boolean isSelected, boolean isSystem) {
        this.appName = appName;
        this.packageName = packageName;
        this.icon = icon;
        this.isSelected = isSelected;
        this.isSystem = isSystem;
    }
}

