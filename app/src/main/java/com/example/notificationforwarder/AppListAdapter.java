package com.example.notificationforwarder;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;


public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {
    private final List<AppInfo> apps;
    private final AppSelectionListener listener;

    public interface AppSelectionListener {
        void onAppSelectionChanged(String packageName, boolean isSelected);
    }

    public AppListAdapter(List<AppInfo> apps, AppSelectionListener listener) {
        this.apps = apps;
        this.listener = listener;
    }

    @Override
    public ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.app_list_item, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        AppInfo app = apps.get(position);
        holder.appName.setText(app.appName);
        holder.packageName.setText(app.packageName);
        holder.icon.setImageDrawable(app.icon);
        holder.checkBox.setOnCheckedChangeListener(null);
        holder.checkBox.setChecked(app.isSelected);
        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            app.isSelected = isChecked;
            listener.onAppSelectionChanged(app.packageName, isChecked);
        });
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView appName, packageName;
        android.widget.ImageView icon;
        android.widget.CheckBox checkBox;

        public ViewHolder(View itemView) {
            super(itemView);
            appName = itemView.findViewById(R.id.app_name);
            packageName = itemView.findViewById(R.id.package_name);
            icon = itemView.findViewById(R.id.app_icon);
            checkBox = itemView.findViewById(R.id.app_checkbox);
        }
    }
}

