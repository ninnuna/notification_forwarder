package com.example.notificationforwarder;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

public class NetworkSpeedOverlay {

    private final Context context;
    private final NotificationManager notificationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable speedUpdater;

    private long lastRxBytes = 0;
    private long lastTxBytes = 0;

    private static final String CHANNEL_ID = "network_speed_silent_channel";
    private static final int NOTIFICATION_ID = 9999;

    public NetworkSpeedOverlay(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Changed from MIN to LOW:
            // LOW stays in the "Silent" section but isn't strictly hidden/minimized
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Network Speed Monitor",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            channel.setSound(null, null); // Ensure it's silent
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void updateNotification(String downSpeed, String upSpeed) {
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.notification_speed);
        remoteViews.setTextViewText(R.id.tv_download, "↓ " + downSpeed);
        remoteViews.setTextViewText(R.id.tv_upload, "↑ " + upSpeed);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                // Use a transparent or very simple icon
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                // REMOVED: .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                // Removing the style prevents the "One more notification" header wrapper

                .setCustomContentView(remoteViews)
                .setCustomBigContentView(remoteViews) // Ensures it shows even when expanded

                .setOngoing(true)
                .setSilent(true)
                // Changed from MIN to LOW so it is visible immediately in the tray
                .setPriority(NotificationCompat.PRIORITY_MAX);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    public void startTracking() {
        if (speedUpdater != null) return;

        lastRxBytes = TrafficStats.getTotalRxBytes();
        lastTxBytes = TrafficStats.getTotalTxBytes();

        speedUpdater = new Runnable() {
            @Override
            public void run() {
                long currentRx = TrafficStats.getTotalRxBytes();
                long currentTx = TrafficStats.getTotalTxBytes();

                if (currentRx == TrafficStats.UNSUPPORTED || currentTx == TrafficStats.UNSUPPORTED) {
                    updateNotification("N/A", "N/A");
                    return;
                }

                long rxBytesPerSec = Math.max(0, currentRx - lastRxBytes);
                long txBytesPerSec = Math.max(0, currentTx - lastTxBytes);

                lastRxBytes = currentRx;
                lastTxBytes = currentTx;

                updateNotification(formatSpeedBits(rxBytesPerSec), formatSpeedBits(txBytesPerSec));
                handler.postDelayed(this, 2000); // 2-second sampling interval
            }
        };

        handler.post(speedUpdater);
    }

    private String formatSpeedBits(long bytesPerSec) {
        long bitsPerSec = bytesPerSec * 8;
        double kbps = bitsPerSec / 1000.0;

        if (kbps < 1000.0) {
            return String.format(Locale.getDefault(), "%.1f Kbps", kbps);
        } else {
            return String.format(Locale.getDefault(), "%.1f Mbps", kbps / 1000.0);
        }
    }

    public void stopTracking() {
        if (handler != null && speedUpdater != null) {
            handler.removeCallbacks(speedUpdater);
            speedUpdater = null;
        }
        notificationManager.cancel(NOTIFICATION_ID);
    }
}
