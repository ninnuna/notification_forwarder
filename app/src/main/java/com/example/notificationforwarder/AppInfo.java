package com.example.notificationforwarder;

import android.graphics.drawable.Drawable;

public class AppInfo {
    public String appName;
    public String packageName;
    public Drawable icon;
    public boolean isForwardingSelected;
    public boolean isLockSelected;
    public int appCategory;

    public AppInfo(String appName, String packageName, Drawable icon, boolean isForwardingSelected, boolean isLockSelected) {
        this.appName = appName;
        this.packageName = packageName;
        this.icon = icon;
        this.isForwardingSelected = isForwardingSelected;
        this.isLockSelected = isLockSelected;
    }
}