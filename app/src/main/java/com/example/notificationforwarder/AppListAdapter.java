package com.example.notificationforwarder;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    private final List<AppInfo> appList;
    private final AppSelectionListener listener;

    public interface AppSelectionListener {
        void onForwardingChanged(String packageName, boolean isChecked);
        void onLockChanged(String packageName, boolean isChecked);
    }

    public AppListAdapter(List<AppInfo> appList, AppSelectionListener listener) {
        this.appList = appList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.app_list_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo app = appList.get(position);
        holder.appNameText.setText(app.appName);
        holder.packageNameText.setText(app.packageName);
        holder.appIconImage.setImageDrawable(app.icon);

        holder.checkboxForward.setOnCheckedChangeListener(null);
        holder.checkboxLock.setOnCheckedChangeListener(null);

        holder.checkboxForward.setChecked(app.isForwardingSelected);
        holder.checkboxLock.setChecked(app.isLockSelected);

        holder.checkboxForward.setOnCheckedChangeListener((bv, isChecked) -> {
            app.isForwardingSelected = isChecked;
            listener.onForwardingChanged(app.packageName, isChecked);
        });

        holder.checkboxLock.setOnCheckedChangeListener((bv, isChecked) -> {
            app.isLockSelected = isChecked;
            listener.onLockChanged(app.packageName, isChecked);
        });
    }

    @Override
    public int getItemCount() { return appList.size(); }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView appIconImage;
        TextView appNameText, packageNameText;
        CheckBox checkboxForward, checkboxLock;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            appIconImage = itemView.findViewById(R.id.app_icon);
            appNameText = itemView.findViewById(R.id.app_name);
            packageNameText = itemView.findViewById(R.id.package_name);
            checkboxForward = itemView.findViewById(R.id.checkbox_forward);
            checkboxLock = itemView.findViewById(R.id.checkbox_lock);
        }
    }
}