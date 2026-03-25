package com.everone11.uvccamera.xposed;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UVC Camera Xposed 模块主界面：显示已安装应用列表，供用户选择 Hook 目标。
 */
public class MainActivity extends Activity {

    private TextView tvCurrentTarget;
    private Button btnClear;
    private EditText etSearch;
    private ListView listView;
    private View loadingOverlay;

    private AppAdapter adapter;
    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<AppInfo> filteredApps = new ArrayList<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvCurrentTarget = findViewById(R.id.tv_current_target);
        btnClear = findViewById(R.id.btn_clear);
        etSearch = findViewById(R.id.et_search);
        listView = findViewById(R.id.list_view);
        loadingOverlay = findViewById(R.id.loading_overlay);

        adapter = new AppAdapter();
        listView.setAdapter(adapter);

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PrefManager.setTargetPackage(MainActivity.this, "");
                updateCurrentTargetView();
                adapter.notifyDataSetChanged();
                Toast.makeText(MainActivity.this, R.string.hook_all_apps, Toast.LENGTH_SHORT).show();
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                AppInfo app = filteredApps.get(position);
                PrefManager.setTargetPackage(MainActivity.this, app.packageName);
                updateCurrentTargetView();
                adapter.notifyDataSetChanged();
                Toast.makeText(MainActivity.this,
                        getString(R.string.selected_app, app.appName), Toast.LENGTH_SHORT).show();
            }
        });

        updateCurrentTargetView();
        loadAppsAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCurrentTargetView();
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void updateCurrentTargetView() {
        String pkg = PrefManager.getTargetPackage(this);
        if (pkg == null || pkg.isEmpty()) {
            tvCurrentTarget.setText(R.string.current_target_all);
        } else {
            String appName = getAppName(pkg);
            tvCurrentTarget.setText(getString(R.string.current_target, appName, pkg));
        }
    }

    private String getAppName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private void filterApps(String query) {
        filteredApps.clear();
        if (query == null || query.trim().isEmpty()) {
            filteredApps.addAll(allApps);
        } else {
            String lower = query.toLowerCase();
            for (AppInfo app : allApps) {
                if (app.appName.toLowerCase().contains(lower)
                        || app.packageName.toLowerCase().contains(lower)) {
                    filteredApps.add(app);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void loadAppsAsync() {
        loadingOverlay.setVisibility(View.VISIBLE);
        final PackageManager pm = getPackageManager();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final List<AppInfo> result = new ArrayList<>();
                List<ApplicationInfo> packages =
                        pm.getInstalledApplications(PackageManager.GET_META_DATA);

                for (ApplicationInfo info : packages) {
                    // 排除无启动器且为系统应用的后台服务
                    if (pm.getLaunchIntentForPackage(info.packageName) == null
                            && (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                        continue;
                    }
                    AppInfo app = new AppInfo();
                    app.packageName = info.packageName;
                    app.appName = pm.getApplicationLabel(info).toString();
                    app.icon = pm.getApplicationIcon(info);
                    result.add(app);
                }

                Collections.sort(result, new Comparator<AppInfo>() {
                    @Override
                    public int compare(AppInfo a, AppInfo b) {
                        return a.appName.compareToIgnoreCase(b.appName);
                    }
                });

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        loadingOverlay.setVisibility(View.GONE);
                        allApps.clear();
                        allApps.addAll(result);
                        filterApps(etSearch.getText().toString());
                    }
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // AppInfo data class
    // -------------------------------------------------------------------------

    static class AppInfo {
        String appName;
        String packageName;
        Drawable icon;
    }

    // -------------------------------------------------------------------------
    // ListView adapter
    // -------------------------------------------------------------------------

    class AppAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return filteredApps.size();
        }

        @Override
        public AppInfo getItem(int position) {
            return filteredApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_app, parent, false);
                holder = new ViewHolder();
                holder.icon = convertView.findViewById(R.id.iv_icon);
                holder.name = convertView.findViewById(R.id.tv_name);
                holder.pkg = convertView.findViewById(R.id.tv_package);
                holder.selected = convertView.findViewById(R.id.tv_selected);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            AppInfo app = filteredApps.get(position);
            holder.icon.setImageDrawable(app.icon);
            holder.name.setText(app.appName);
            holder.pkg.setText(app.packageName);

            String currentTarget = PrefManager.getTargetPackage(MainActivity.this);
            if (app.packageName.equals(currentTarget)) {
                holder.selected.setVisibility(View.VISIBLE);
            } else {
                holder.selected.setVisibility(View.GONE);
            }

            return convertView;
        }

        class ViewHolder {
            ImageView icon;
            TextView name;
            TextView pkg;
            TextView selected;
        }
    }
}
