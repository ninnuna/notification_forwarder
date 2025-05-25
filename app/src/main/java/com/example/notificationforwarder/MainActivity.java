package com.example.notificationforwarder;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private EditText webTokenEditText;
    private Button saveButton;
    private Button testButton;
    private Button startServiceButton;
    private Button stopServiceButton;
    private TextView statusTextView;
    private PreferenceManager preferenceManager;

    // Collapsible section
    private TextView tokenSectionHeader;
    private LinearLayout tokenSectionContent;

    // App selection UI
    private RecyclerView appListRecyclerView;
    private AppListAdapter appListAdapter;
    private List<AppInfo> appList = new ArrayList<>();
    private Set<String> selectedPackages = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferenceManager = new PreferenceManager(this);

        // Collapsible section
        tokenSectionHeader = findViewById(R.id.token_section_header);
        tokenSectionContent = findViewById(R.id.token_section_content);

        webTokenEditText = findViewById(R.id.web_token_edit_text);
        saveButton = findViewById(R.id.save_button);
        testButton = findViewById(R.id.test_notification);
        startServiceButton = findViewById(R.id.start_service_button);
        stopServiceButton = findViewById(R.id.stop_service_button);
        statusTextView = findViewById(R.id.status_text_view);

        // Collapsible logic
        tokenSectionHeader.setOnClickListener(v -> {
            if (tokenSectionContent.getVisibility() == View.VISIBLE) {
                tokenSectionContent.setVisibility(View.GONE);
                tokenSectionHeader.setText("Web Token ▲");
            } else {
                tokenSectionContent.setVisibility(View.VISIBLE);
                tokenSectionHeader.setText("Web Token ▼");
            }
        });

        // App list UI
        appListRecyclerView = findViewById(R.id.app_list_recycler_view);
        appListRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        appListAdapter = new AppListAdapter(appList, this::onAppSelectionChanged);
        appListRecyclerView.setAdapter(appListAdapter);

        // Load saved values
        String webToken = preferenceManager.getWebToken();
        if (!TextUtils.isEmpty(webToken)) {
            webTokenEditText.setText(webToken);
        }

        saveButton.setOnClickListener(v -> saveSettings());
        startServiceButton.setOnClickListener(v -> startNotificationService());
        stopServiceButton.setOnClickListener(v -> stopNotificationService());
        testButton.setOnClickListener(v -> SlackHelper.sendMessage(
                preferenceManager.getWebToken(), NotificationService.MSG_STRING, success -> {}));

        updateServiceStatus();

        // Check for notification listener permission
        if (!isNotificationServiceEnabled()) {
            showNotificationPermissionDialog();
        }

        // Load app selections and installed apps
        loadSelectedPackages();
        loadInstalledApps();

        // Auto-start service if settings are available
        if (!TextUtils.isEmpty(webToken) && !isServiceRunning(NotificationService.class)) {
            startNotificationService();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    private void saveSettings() {
        String webToken = webTokenEditText.getText().toString().trim();

        if (TextUtils.isEmpty(webToken)) {
            Toast.makeText(this, "Please enter Web Token", Toast.LENGTH_SHORT).show();
            return;
        }

        preferenceManager.setWebToken(webToken);
        Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show();

        // Restart service to apply new settings
        if (isServiceRunning(NotificationService.class)) {
            stopNotificationService();
            startNotificationService();
        }
    }

    private void startNotificationService() {
        if (!isNotificationServiceEnabled()) {
            showNotificationPermissionDialog();
            return;
        }

        String webToken = preferenceManager.getWebToken();

        if (TextUtils.isEmpty(webToken)) {
            Toast.makeText(this, "Please save Web Token", Toast.LENGTH_SHORT).show();
            return;
        }

        // Enable boot receiver
        ComponentName receiver = new ComponentName(this, BootReceiver.class);
        PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(receiver,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

        // Start the service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(new Intent(this, NotificationService.class));
        } else {
            startService(new Intent(this, NotificationService.class));
        }

        preferenceManager.setServiceEnabled(true);
        updateServiceStatus();
        Toast.makeText(this, "Notification forwarding service started", Toast.LENGTH_SHORT).show();
    }

    private void stopNotificationService() {
        stopService(new Intent(this, NotificationService.class));
        preferenceManager.setServiceEnabled(false);
        updateServiceStatus();
        Toast.makeText(this, "Notification forwarding service stopped", Toast.LENGTH_SHORT).show();
    }

    private boolean isNotificationServiceEnabled() {
        String packageName = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            String[] names = flat.split(":");
            for (String name : names) {
                ComponentName componentName = ComponentName.unflattenFromString(name);
                if (componentName != null && TextUtils.equals(packageName, componentName.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void showNotificationPermissionDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.notification_permission_explanation, null);
        Button grantPermissionButton = dialogView.findViewById(R.id.grant_permission_button);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Notification Access Required")
                .setView(dialogView)
                .setCancelable(false)
                .create();

        grantPermissionButton.setOnClickListener(v -> {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            startActivity(intent);
            dialog.dismiss();
        });

        dialog.show();
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void updateServiceStatus() {
        if (isServiceRunning(NotificationService.class)) {
            statusTextView.setText("Status: On");
            statusTextView.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            startServiceButton.setEnabled(false);
            stopServiceButton.setEnabled(true);
        } else {
            statusTextView.setText("Status: Off");
            statusTextView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            startServiceButton.setEnabled(true);
            stopServiceButton.setEnabled(false);
        }
    }

    // --- App List Logic ---

    private void loadSelectedPackages() {
        String csv = preferenceManager.getSelectedPackages();
        selectedPackages.clear();
        if (!TextUtils.isEmpty(csv)) {
            selectedPackages.addAll(Arrays.asList(csv.split(",")));
        }
    }

    private void saveSelectedPackages() {
        String csv = TextUtils.join(",", selectedPackages);
        preferenceManager.setSelectedPackages(csv);
    }

    private void loadInstalledApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        appList.clear();
        for (ApplicationInfo appInfo : packages) {
            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) { // skip system apps
                String appName = pm.getApplicationLabel(appInfo).toString();
                String packageName = appInfo.packageName;
                Drawable icon = appInfo.loadIcon(pm);
                boolean isSelected = selectedPackages.contains(packageName);
                appList.add(new AppInfo(appName, packageName, icon, isSelected));
            }
        }
        // Sort alphabetically
        appList.sort((a, b) -> a.appName.compareToIgnoreCase(b.appName));
        appListAdapter.notifyDataSetChanged();
    }

    private void onAppSelectionChanged(String packageName, boolean isSelected) {
        if (isSelected) {
            selectedPackages.add(packageName);
        } else {
            selectedPackages.remove(packageName);
        }
        saveSelectedPackages();
    }
}
