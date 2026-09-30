package com.example.notificationforwarder;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppSelectionActivity extends AppCompatActivity {

    private RecyclerView appListRecyclerView;
    private AppListAdapter appListAdapter;
    private Button btnClearList;
    private Button btnSaveList;

    private List<AppInfo> appList = new ArrayList<>();
    private Set<String> selectedPackages = new HashSet<>();
    private Set<String> lockedPackages = new HashSet<>();
    private PreferenceManager preferenceManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_selection);

        preferenceManager = new PreferenceManager(this);

        appListRecyclerView = findViewById(R.id.app_list_recycler_view);
        btnClearList = findViewById(R.id.btn_clear_list);
        btnSaveList = findViewById(R.id.btn_save_list);

        appListRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        appListAdapter = new AppListAdapter(appList, new AppListAdapter.AppSelectionListener() {
            @Override
            public void onForwardingChanged(String packageName, boolean isChecked) {
                if (isChecked) selectedPackages.add(packageName);
                else selectedPackages.remove(packageName);
            }

            @Override
            public void onLockChanged(String packageName, boolean isChecked) {
                if (isChecked) lockedPackages.add(packageName);
                else lockedPackages.remove(packageName);
            }
        });
        appListRecyclerView.setAdapter(appListAdapter);

        btnSaveList.setOnClickListener(v -> {
            preferenceManager.setSelectedPackages(TextUtils.join(",", selectedPackages));
            preferenceManager.setLockedPackages(new ArrayList<>(lockedPackages));
            Toast.makeText(this, "Changes saved successfully", Toast.LENGTH_SHORT).show();
            finish();
        });

        btnClearList.setOnClickListener(v -> {
            selectedPackages.clear();
            lockedPackages.clear();
            for (AppInfo app : appList) {
                app.isForwardingSelected = false;
                app.isLockSelected = false;
            }
            appListAdapter.notifyDataSetChanged();
            Toast.makeText(this, "Selections cleared. Click Save to apply.", Toast.LENGTH_SHORT).show();
        });

        loadSelectedPackages();
        loadLockedPackages();
        loadInstalledApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preferenceManager.shouldRelockDashboard()) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        } else {
            preferenceManager.updateLastActiveTime();
        }
    }

    private void loadSelectedPackages() {
        String csv = preferenceManager.getSelectedPackages();
        selectedPackages.clear();
        if (!TextUtils.isEmpty(csv)) {
            selectedPackages.addAll(Arrays.asList(csv.split(",")));
        }
    }

    private void loadLockedPackages() {
        Set<String> locked = preferenceManager.getLockedPackages();
        lockedPackages.clear();
        if (locked != null) {
            lockedPackages.addAll(locked);
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