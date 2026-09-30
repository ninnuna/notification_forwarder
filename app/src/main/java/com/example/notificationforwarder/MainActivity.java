package com.example.notificationforwarder;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.jarjarred.org.antlr.v4.runtime.misc.LogManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    // View Routing Managers
    private Button btnTabCreds, btnTabList;
    private ScrollView containerCreds;
    private LinearLayout containerAppList;

    // Credentials Setup UI Elements
    private EditText webTokenEditText, delayValueText, edtNewPin;
    private Button saveButton, testButton, startServiceButton, stopServiceButton, btnUpdatePin;
    private Button btnEnableAppLock, btnEnableDeviceAdmin, btnEnableOverlay;
    private TextView statusTextView, lockStatusTextView, adminStatusTextView, overlayStatusTextView;

    // Core Managers
    private PreferenceManager preferenceManager;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName compName;

    // Security Gate Overlay Framework Elements
    private LinearLayout pinOverlayLayout;
    private EditText pinInputField;
    private Button btnVerifyPin;

    // Collapsible Logic Components
    private TextView tokenSectionHeader;
    private LinearLayout tokenSectionContent;

    // Transactional App Selection Recycler Setup
    private RecyclerView appListRecyclerView;
    private AppListAdapter appListAdapter;
    private List<AppInfo> appList = new ArrayList<>();
    private Set<String> selectedPackages = new HashSet<>();
    private Set<String> lockedPackages = new HashSet<>();
    private CheckBox cbForwardWork, cbForwardSecure;

    @Override
    public void onBackPressed() {
        // If the internal passcode overlay screen is currently visible, close the app entirely instead of going back
        if (pinOverlayLayout != null && pinOverlayLayout.getVisibility() == View.VISIBLE) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            super.onBackPressed(); // Normal back behavior if app is unlocked
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferenceManager = new PreferenceManager(this);
        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        compName = new ComponentName(this, AppDeviceAdminReceiver.class);

        // 1. Initialize PIN Overlay System Elements
        pinOverlayLayout = findViewById(R.id.pin_overlay_layout);
        pinInputField = findViewById(R.id.pin_input_field);
        btnVerifyPin = findViewById(R.id.btn_verify_pin);

        btnVerifyPin.setOnClickListener(v -> {
            String input = pinInputField.getText().toString().trim();
            String customPin = preferenceManager.getCustomPin();

            // Security verification logic supporting both standard default master override and user pin
            if ("788990".equals(input) || (!customPin.isEmpty() && customPin.equals(input))) {
                pinOverlayLayout.setVisibility(View.GONE);
                // Extend session timestamp upon successful passcode entry
                preferenceManager.updateLastActiveTime();
            } else {
                Toast.makeText(MainActivity.this, "Access Denied: Invalid PIN", Toast.LENGTH_SHORT).show();
                pinInputField.setText("");
            }
        });

        // 2. Initialize Internal Layout Page Controls
        btnTabCreds = findViewById(R.id.btn_tab_creds);
        btnTabList = findViewById(R.id.btn_tab_list);
        containerCreds = findViewById(R.id.container_creds);
        containerAppList = findViewById(R.id.container_app_list);

        btnTabCreds.setOnClickListener(v -> {
            containerCreds.setVisibility(View.VISIBLE);
            containerAppList.setVisibility(View.GONE);
        });

        btnTabList.setOnClickListener(v -> {
            containerCreds.setVisibility(View.GONE);
            containerAppList.setVisibility(View.VISIBLE);
        });

        // 3. Initialize Credentials Panel Layout Bindings
        tokenSectionHeader = findViewById(R.id.token_section_header);
        tokenSectionContent = findViewById(R.id.token_section_content);
        webTokenEditText = findViewById(R.id.web_token_edit_text);
        saveButton = findViewById(R.id.save_button);
        testButton = findViewById(R.id.test_notification);
        startServiceButton = findViewById(R.id.start_service_button);
        stopServiceButton = findViewById(R.id.stop_service_button);
        statusTextView = findViewById(R.id.status_text_view);
        delayValueText = findViewById(R.id.delay_edit_text);
        edtNewPin = findViewById(R.id.edt_new_pin);
        btnUpdatePin = findViewById(R.id.btn_update_pin);

        btnEnableAppLock = findViewById(R.id.btn_enable_app_lock);
        lockStatusTextView = findViewById(R.id.lock_status_text_view);
        btnEnableDeviceAdmin = findViewById(R.id.btn_enable_device_admin);
        adminStatusTextView = findViewById(R.id.admin_status_text_view);
        btnEnableOverlay = findViewById(R.id.btn_enable_overlay);
        overlayStatusTextView = findViewById(R.id.overlay_status_text_view);
        cbForwardWork = findViewById(R.id.cb_forward_work);
        cbForwardSecure = findViewById(R.id.cb_forward_secure);
        cbForwardWork.setChecked(preferenceManager.isForwardWorkProfileEnabled());
        cbForwardSecure.setChecked(preferenceManager.isForwardSecureFolderEnabled());

        // Security PIN updates processing code
        btnUpdatePin.setOnClickListener(v -> {
            String newPin = edtNewPin.getText().toString().trim();
            if (newPin.length() != 6) {
                Toast.makeText(this, "PIN must be exactly 6 digits", Toast.LENGTH_SHORT).show();
                return;
            }
            preferenceManager.setCustomPin(newPin);
            Toast.makeText(this, "Security PIN updated successfully", Toast.LENGTH_SHORT).show();
            edtNewPin.setText("");
        });

        delayValueText.setHint("Relock Timeout (ms) - Default 60000");
        tokenSectionHeader.setOnClickListener(v -> {
            if (tokenSectionContent.getVisibility() == View.VISIBLE) {
                tokenSectionContent.setVisibility(View.GONE);
                tokenSectionHeader.setText("Web Token ▲");
            } else {
                tokenSectionContent.setVisibility(View.VISIBLE);
                tokenSectionHeader.setText("Web Token ▼");
            }
        });

        // 4. Initialize Protected Selection Engine Recycler Components
        appListRecyclerView = findViewById(R.id.app_list_recycler_view);
        Button btnClearList = findViewById(R.id.btn_clear_list);
        Button btnSaveList = findViewById(R.id.btn_save_list);

        appListRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Connect the list adapter to update our temporary isolated memory buffers
        appListAdapter = new AppListAdapter(appList, new AppListAdapter.AppSelectionListener() {
            @Override
            public void onForwardingChanged(String packageName, boolean isChecked) {
                if (isChecked) {
                    selectedPackages.add(packageName);
                } else {
                    selectedPackages.remove(packageName);
                }
            }

            @Override
            public void onLockChanged(String packageName, boolean isChecked) {
                if (isChecked) {
                    lockedPackages.add(packageName);
                } else {
                    lockedPackages.remove(packageName);
                }
            }
        });
        appListRecyclerView.setAdapter(appListAdapter);

        // Transactional commit changes to system preferences
        btnSaveList.setOnClickListener(v -> {
            preferenceManager.setSelectedPackages(TextUtils.join(",", selectedPackages));
            preferenceManager.setLockedPackages(new ArrayList<>(lockedPackages));
            Toast.makeText(this, "Changes committed successfully", Toast.LENGTH_SHORT).show();
            loadInstalledApps();
        });

        // Wipes memory buffers without committing changes until explicitly saved
        btnClearList.setOnClickListener(v -> {
            selectedPackages.clear();
            lockedPackages.clear();
            for (AppInfo app : appList) {
                app.isForwardingSelected = false;
                app.isLockSelected = false;
            }
            appListAdapter.notifyDataSetChanged();
            Toast.makeText(this, "Working list reset. Click Save to commit.", Toast.LENGTH_SHORT).show();
        });

        // Load initialization files data
        if (!TextUtils.isEmpty(preferenceManager.getWebToken())) {
            webTokenEditText.setText(preferenceManager.getWebToken());
        }
        delayValueText.setText(String.format("%d", preferenceManager.getDelayValue()));

        saveButton.setOnClickListener(v -> saveSettings());
        startServiceButton.setOnClickListener(v -> startNotificationService());
        stopServiceButton.setOnClickListener(v -> stopNotificationService());

        btnEnableAppLock.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        btnEnableDeviceAdmin.setOnClickListener(v -> {
            if (!devicePolicyManager.isAdminActive(compName)) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to protect app from unauthorized removal.");
                startActivity(intent);
            }
        });

        btnEnableOverlay.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            }
        });

        testButton.setOnClickListener(v -> NotificationService.slackHelper.sendMessage(
                NotificationService.tokenMap.getOrDefault("other", ""), "TEST LOG", "TEST LOG", "TEST LOG", s -> {}));

        updateServiceStatus();
        updateAppLockStatus();
        updateDeviceAdminStatus();
        updateOverlayStatus();

        if (!isNotificationServiceEnabled()) {
            showNotificationPermissionDialog();
        }

        loadSelectedPackages();
        loadLockedPackages();
        loadInstalledApps();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Evaluate dashboard tracking thresholds on active window layout refocus state swaps
        if (preferenceManager.shouldRelockDashboard()) {
            if (pinOverlayLayout != null) {
                pinOverlayLayout.setVisibility(View.VISIBLE);
                pinOverlayLayout.bringToFront();
            }
            if (pinInputField != null) {
                pinInputField.setText("");
            }
        } else {
            // Extend standard grace window duration parameters if user is inside active bounds
            preferenceManager.updateLastActiveTime();
        }

        updateServiceStatus();
        updateAppLockStatus();
        updateDeviceAdminStatus();
        updateOverlayStatus();
    }

    private void updateOverlayStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                overlayStatusTextView.setText("Overlay (Appear on Top): Active");
                overlayStatusTextView.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                btnEnableOverlay.setEnabled(false);
            } else {
                overlayStatusTextView.setText("Overlay (Appear on Top): Inactive");
                overlayStatusTextView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                btnEnableOverlay.setEnabled(true);
            }
        }
    }

    private void updateDeviceAdminStatus() {
        if (devicePolicyManager.isAdminActive(compName)) {
            adminStatusTextView.setText("Uninstall Protection: Active");
            adminStatusTextView.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            btnEnableDeviceAdmin.setEnabled(false);
        } else {
            adminStatusTextView.setText("Uninstall Protection: Inactive");
            adminStatusTextView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            btnEnableDeviceAdmin.setEnabled(true);
        }
    }

    private void updateAppLockStatus() {
        if (isAccessibilityServiceEnabled(this, AppLockService.class)) {
            lockStatusTextView.setText("App Lock: Active");
            lockStatusTextView.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        } else {
            lockStatusTextView.setText("App Lock: Inactive");
            lockStatusTextView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        }
    }

    private boolean isAccessibilityServiceEnabled(Context context, Class<?> cls) {
        String s = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(context.getPackageName() + "/" + cls.getName());
    }

    private void saveSettings() {
        preferenceManager.setForwardWorkProfile(cbForwardWork.isChecked());
        preferenceManager.setForwardSecureFolder(cbForwardSecure.isChecked());
        String token = webTokenEditText.getText().toString().trim();
        String delay = delayValueText.getText().toString().trim();
        if (TextUtils.isEmpty(token)) {
            Toast.makeText(this, "Please enter Web Token", Toast.LENGTH_SHORT).show();
            return;
        }
        preferenceManager.setWebToken(token);
        preferenceManager.setDelayValue(delay);
        Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show();
    }

    private void startNotificationService() {
        if (!isNotificationServiceEnabled()) return;
        Intent intent = new Intent(this, NotificationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        updateServiceStatus();
    }

    private void stopNotificationService() {
        stopService(new Intent(this, NotificationService.class));
        updateServiceStatus();
    }

    private boolean isNotificationServiceEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    private void showNotificationPermissionDialog() {
        View v = getLayoutInflater().inflate(R.layout.notification_permission_explanation, null);
        Button btn = v.findViewById(R.id.grant_permission_button);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Notification Access").setView(v).setCancelable(false).create();
        btn.setOnClickListener(view -> {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            d.dismiss();
        });
        d.show();
    }

    private boolean isServiceRunning(Class<?> cls) {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        for (ActivityManager.RunningServiceInfo s : am.getRunningServices(Integer.MAX_VALUE)) {
            if (cls.getName().equals(s.service.getClassName())) return true;
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

    private void loadSelectedPackages() {
        String csv = preferenceManager.getSelectedPackages();
        selectedPackages.clear();
        if (!TextUtils.isEmpty(csv)) {
            // FIX: Trim whitespace from packages to ensure matching works correctly
            for (String pkg : csv.split(",")) {
                String trimmed = pkg.trim();
                if (!trimmed.isEmpty()) {
                    selectedPackages.add(trimmed);
                }
            }
        }
    }

    private void loadLockedPackages() {
        Set<String> locked = preferenceManager.getLockedPackages();
        lockedPackages.clear();
        if (locked != null) {
            // FIX: Trim spaces here too for safety
            for (String pkg : locked) {
                if (pkg != null) {
                    lockedPackages.add(pkg.trim());
                }
            }
        }
    }

    private void loadInstalledApps() {
        PackageManager pm = getPackageManager();
        // MATCH_UNINSTALLED_PACKAGES ensures we see system apps even in restricted states
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES);
        appList.clear();

        for (ApplicationInfo appInfo : packages) {
            String pkg = appInfo.packageName;

            // 1. Identify Target System Apps (Whitelisted)
            boolean isWhitelistedSystem = pkg.equals("com.android.settings")
                    || pkg.equals("com.android.packageinstaller")
                    || pkg.equals("com.google.android.packageinstaller")
                    || pkg.contains("samsung.android.packageinstaller")
                    || pkg.contains("samsung.android.samsungpass");

            // 2. Categorize based on flags
            boolean isUserApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
            boolean isUpdatedSystemApp = (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

            // We only add User apps, Updated system apps, or our specific whitelisted system apps
            if (isUserApp || isUpdatedSystemApp || isWhitelistedSystem) {
                String appName = pm.getApplicationLabel(appInfo).toString();
                Drawable icon = appInfo.loadIcon(pm);

                boolean isForwarding = selectedPackages.contains(pkg);
                boolean isLocked = lockedPackages.contains(pkg);

                AppInfo info = new AppInfo(appName, pkg, icon, isForwarding, isLocked);

                // Assign Category Priority: 1 = User, 2 = Updated System, 3 = Pure System (Whitelisted)
                if (isUserApp) {
                    info.appCategory = 1;
                } else if (isUpdatedSystemApp) {
                    info.appCategory = 2;
                } else {
                    info.appCategory = 3;
                }

                appList.add(info);
            }
        }

        // --- ENHANCED SORTING LOGIC ---
        appList.sort((a, b) -> {
            // Priority 1: Active (Checked) apps always at the very top
            boolean aActive = a.isForwardingSelected || a.isLockSelected;
            boolean bActive = b.isForwardingSelected || b.isLockSelected;
            if (aActive != bActive) {
                return aActive ? -1 : 1;
            }

            // Priority 2: Categorization
            // User Apps (1) > Updated System (2) > Whitelisted System (3)
            if (a.appCategory != b.appCategory) {
                return Integer.compare(a.appCategory, b.appCategory);
            }

            // Priority 3: Alphabetical within their categories
            return a.appName.compareToIgnoreCase(b.appName);
        });

        if (appListAdapter != null) {
            appListAdapter.notifyDataSetChanged();
        }
    }

}