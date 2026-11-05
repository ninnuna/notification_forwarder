package com.example.notificationforwarder;

import android.graphics.drawable.Drawable;

public class AppInfo {
    public String appName;
    public String packageName;
    public Drawable icon;
    public boolean isTelegramSelected;
    public boolean isSlackSelected;
    public boolean isLockSelected;
    public int appCategory;

    public AppInfo(String appName, String packageName, Drawable icon, boolean isTelegramSelected, boolean isSlackSelected, boolean isLockSelected) {
        this.appName = appName;
        this.packageName = packageName;
        this.icon = icon;
        this.isTelegramSelected = isTelegramSelected;
        this.isSlackSelected = isSlackSelected;
        this.isLockSelected = isLockSelected;
    }
}