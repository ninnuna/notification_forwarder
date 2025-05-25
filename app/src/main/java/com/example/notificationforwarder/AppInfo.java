package com.example.notificationforwarder;

public class AppInfo {
    public String appName;
    public String packageName;
    public android.graphics.drawable.Drawable icon;
    public boolean isSelected;

    public AppInfo(String appName, String packageName, android.graphics.drawable.Drawable icon, boolean isSelected) {
        this.appName = appName;
        this.packageName = packageName;
        this.icon = icon;
        this.isSelected = isSelected;
    }
}

