package com.henry.wordcount.bridge;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * v1.1.10：下载管理页（类似手机浏览器的「下载」）。
 * 列表展示本桥接导出的全部 PDF：点列表项直接打开查看；每行「删除」按钮删除文件（含底层文件）。
 */
public class DownloadsActivity extends Activity {
    private ListView listView;
    private TextView emptyView;
    private ArrayList<Downloads.Item> items;
    private MyAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        listView = findViewById(R.id.list_downloads);
        emptyView = findViewById(R.id.tv_empty);

        items = Downloads.read(this);
        adapter = new MyAdapter(this, items);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Downloads.Item it = items.get(position);
            Downloads.open(this, it);
        });

        refresh();
    }

    /** 从磁盘重读并刷新列表与空态提示 */
    private void refresh() {
        items.clear();
        items.addAll(Downloads.read(this));
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private class MyAdapter extends ArrayAdapter<Downloads.Item> {
        MyAdapter(Activity ctx, ArrayList<Downloads.Item> list) {
            super(ctx, R.layout.item_download, list);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_download, parent, false);
            }
            Downloads.Item it = getItem(position);
            TextView tvName = convertView.findViewById(R.id.tv_name);
            TextView tvMeta = convertView.findViewById(R.id.tv_meta);
            Button btnDel = convertView.findViewById(R.id.btn_del);

            tvName.setText(it.name);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
            String meta = sdf.format(new Date(it.time)) + "  " + formatSize(it.size);
            tvMeta.setText(meta);

            btnDel.setOnClickListener(v -> {
                // 删除时按磁盘最新顺序定位（read 已按时间倒序，与列表一致）
                ArrayList<Downloads.Item> fresh = Downloads.read(DownloadsActivity.this);
                if (position >= 0 && position < fresh.size()) {
                    Downloads.remove(DownloadsActivity.this, position);
                    refresh();
                    Toast.makeText(DownloadsActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                }
            });
            return convertView;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
