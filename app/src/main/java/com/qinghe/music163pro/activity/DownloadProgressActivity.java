package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.manager.BatchDownloadManager;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.model.DownloadTask;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Download progress activity - shows all download tasks with progress bars
 */
public class DownloadProgressActivity extends BaseWatchActivity {

    private ListView lvDownloadTasks;
    private TextView tvEmpty;
    private Button btnClearCompleted;
    private Button btnCancelAll;
    private DownloadTaskAdapter adapter;
    private BatchDownloadManager batchDownloadManager;
    private MusicPlayerManager playerManager;
    private final List<DownloadTask> downloadTasks = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_progress);

        batchDownloadManager = BatchDownloadManager.getInstance();
        playerManager = MusicPlayerManager.getInstance();

        // Set cookie for download manager
        String cookie = playerManager.getCookie();
        batchDownloadManager.setCookie(cookie);

        // Setup UI
        lvDownloadTasks = findViewById(R.id.lv_download_tasks);
        tvEmpty = findViewById(R.id.tv_empty);
        btnClearCompleted = findViewById(R.id.btn_clear_completed);
        btnCancelAll = findViewById(R.id.btn_cancel_all);

        // Setup adapter
        adapter = new DownloadTaskAdapter(this, downloadTasks);
        lvDownloadTasks.setAdapter(adapter);

        // Setup listeners
        batchDownloadManager.setProgressListener(new BatchDownloadManager.DownloadProgressListener() {
            @Override
            public void onProgress(DownloadTask task) {
                updateTask(task);
            }

            @Override
            public void onStatusChange(DownloadTask task) {
                updateTask(task);
            }

            @Override
            public void onSuccess(DownloadTask task, String filePath) {
                updateTask(task);
                Toast.makeText(DownloadProgressActivity.this,
                        task.getSong().getName() + " 下载完成", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(DownloadTask task, String message) {
                updateTask(task);
                Toast.makeText(DownloadProgressActivity.this,
                        task.getSong().getName() + " 下载失败: " + message, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCancelled(DownloadTask task) {
                removeTask(task.getTaskId());
                Toast.makeText(DownloadProgressActivity.this,
                        task.getSong().getName() + " 已取消", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAlreadyDownloaded(Song song) {
                Toast.makeText(DownloadProgressActivity.this,
                        song.getName() + " 已下载", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAlreadyQueued(Song song) {
                Toast.makeText(DownloadProgressActivity.this,
                        song.getName() + " 已在下载队列中", Toast.LENGTH_SHORT).show();
            }
        });

        // Button listeners
        btnClearCompleted.setOnClickListener(v -> {
            batchDownloadManager.clearCompletedTasks();
            loadTasks();
        });

        btnCancelAll.setOnClickListener(v -> {
            batchDownloadManager.cancelAllDownloads();
            loadTasks();
        });

        // Long click to cancel individual task
        lvDownloadTasks.setOnItemLongClickListener((parent, view, position, id) -> {
            DownloadTask task = downloadTasks.get(position);
            if (task != null && !"completed".equals(task.getStatus()) && !"error".equals(task.getStatus())) {
                showCancelDialog(task);
            }
            return true;
        });

        // Load initial tasks
        loadTasks();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTasks();
    }

    private void loadTasks() {
        downloadTasks.clear();
        downloadTasks.addAll(batchDownloadManager.getAllTasks());
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateTask(DownloadTask task) {
        runOnUiThread(() -> {
            boolean found = false;
            for (int i = 0; i < downloadTasks.size(); i++) {
                if (downloadTasks.get(i).getTaskId() == task.getTaskId()) {
                    downloadTasks.set(i, task);
                    found = true;
                    break;
                }
            }
            if (!found) {
                downloadTasks.add(task);
            }
            adapter.notifyDataSetChanged();
            updateEmptyState();
        });
    }

    private void removeTask(long taskId) {
        runOnUiThread(() -> {
            for (int i = 0; i < downloadTasks.size(); i++) {
                if (downloadTasks.get(i).getTaskId() == taskId) {
                    downloadTasks.remove(i);
                    adapter.notifyDataSetChanged();
                    updateEmptyState();
                    break;
                }
            }
        });
    }

    private void updateEmptyState() {
        if (downloadTasks.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            lvDownloadTasks.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            lvDownloadTasks.setVisibility(View.VISIBLE);
        }
    }

    private void showCancelDialog(DownloadTask task) {
        showConfirmDialog("取消下载", "确定取消下载「" + task.getSong().getName() + "」？", () -> {
            batchDownloadManager.cancelDownload(task.getTaskId());
        });
    }

    /**
     * Custom adapter for download tasks
     */
    private static class DownloadTaskAdapter extends ArrayAdapter<DownloadTask> {
        public DownloadTaskAdapter(DownloadProgressActivity activity, List<DownloadTask> tasks) {
            super(activity, R.layout.item_download_task, tasks);
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = View.inflate(getContext(), R.layout.item_download_task, null);
            }

            DownloadTask task = getItem(position);
            if (task != null) {
                TextView tvSongName = view.findViewById(R.id.tv_song_name);
                TextView tvArtist = view.findViewById(R.id.tv_artist);
                TextView tvStatus = view.findViewById(R.id.tv_status);
                TextView tvProgress = view.findViewById(R.id.tv_progress);
                ProgressBar progressBar = view.findViewById(R.id.progress_bar);

                tvSongName.setText(task.getSong().getName());
                tvArtist.setText(task.getSong().getArtist());
                tvStatus.setText(getStatusText(task.getStatus()));

                String progressText = String.format(Locale.US, "%d%%", task.getProgress());
                if (task.getTotalBytes() > 0) {
                    progressText += String.format(Locale.US, " (%.1f MB / %.1f MB)",
                            task.getDownloadedBytes() / 1024.0 / 1024.0,
                            task.getTotalBytes() / 1024.0 / 1024.0);
                }
                tvProgress.setText(progressText);
                progressBar.setProgress(task.getProgress());

                // Color coding for status
                int statusColor = 0xFF808080; // Default gray
                switch (task.getStatus()) {
                    case "downloading":
                        statusColor = 0xFF4CAF50; // Green
                        break;
                    case "completed":
                        statusColor = 0xFF2196F3; // Blue
                        break;
                    case "error":
                        statusColor = 0xFFF44336; // Red
                        break;
                    case "cancelled":
                        statusColor = 0xFFFF9800; // Orange
                        break;
                }
                tvStatus.setTextColor(statusColor);
            }

            return view;
        }

        private String getStatusText(String status) {
            switch (status) {
                case "pending":
                    return "等待中";
                case "downloading":
                    return "下载中";
                case "completed":
                    return "已完成";
                case "error":
                    return "下载失败";
                case "cancelled":
                    return "已取消";
                default:
                    return status;
            }
        }
    }
}
