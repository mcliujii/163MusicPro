package com.qinghe.music163pro.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.MusicApp;
import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.WatchConfirmDialog;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.manager.DownloadManager.DownloadProgress;
import com.qinghe.music163pro.manager.DownloadManager.OnDownloadProgressListener;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.model.Song;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Download list activity - shows all downloaded songs from /sdcard/163Music/Download/
 * Also displays active download progress with real-time byte-level updates.
 * Long press to delete with confirmation dialog.
 */
public class DownloadListActivity extends BaseWatchActivity {

    private final List<Song> downloadedSongs = new ArrayList<>();
    private ArrayAdapter<Song> adapter;
    private ListView lvDownloads;
    private TextView tvEmpty;
    private TextView tvActiveSection;

    // Active downloads section
    private LinearLayout activeDownloadsContainer;
    private View activeDivider;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, View> progressViews = new LinkedHashMap<>();
    private boolean hasActiveDownloads = false;

    // Periodic refresh runnable for speed updates
    private Runnable speedRefreshRunnable;

    private final OnDownloadProgressListener progressListener = new OnDownloadProgressListener() {
        @Override
        public void onProgressChanged(Map<String, DownloadProgress> activeDownloads) {
            updateActiveDownloadsUI(activeDownloads);
        }

        @Override
        public void onDownloadAdded(DownloadProgress progress) {
            // Will be handled in onProgressChanged
        }

        @Override
        public void onDownloadRemoved(String songKey) {
            // Will be handled in onProgressChanged with removal
        }
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(MusicApp.wrapWithDpiScale(newBase));
    }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_list);

        lvDownloads = findViewById(R.id.lv_downloads);
        tvEmpty = findViewById(R.id.tv_empty);

        // Build the active downloads section above the ListView
        LinearLayout rootLayout = (LinearLayout) lvDownloads.getParent();

        // Active downloads header
        tvActiveSection = new TextView(this);
        tvActiveSection.setText("正在下载");
        tvActiveSection.setTextColor(0xFF03DAC6);
        tvActiveSection.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 12));
        tvActiveSection.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tvActiveSection.setPadding(
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 6),
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 6),
                0, 0);
        tvActiveSection.setVisibility(View.GONE);

        // Container for active download items
        activeDownloadsContainer = new LinearLayout(this);
        activeDownloadsContainer.setOrientation(LinearLayout.VERTICAL);
        activeDownloadsContainer.setVisibility(View.GONE);

        // Divider between active and completed downloads
        activeDivider = new View(this);
        activeDivider.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        activeDivider.setLayoutParams(dividerParams);
        activeDivider.setVisibility(View.GONE);

        // Insert active section above the ListView
        int lvIndex = rootLayout.indexOfChild(lvDownloads);
        rootLayout.addView(tvActiveSection, lvIndex);
        rootLayout.addView(activeDownloadsContainer, lvIndex + 1);
        rootLayout.addView(activeDivider, lvIndex + 2);

        adapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, downloadedSongs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    tvName.setText(song.getName());
                    tvArtist.setText(song.getArtist());
                }
                return view;
            }
        };
        lvDownloads.setAdapter(adapter);

        lvDownloads.setOnItemClickListener((parent, view, position, id) -> {
            playDownloadedSong(position);
        });

        lvDownloads.setOnItemLongClickListener((parent, view, position, id) -> {
            Song song = downloadedSongs.get(position);
            showConfirmDialog("确认删除", "确定删除「" + song.getName() + "」？\n文件将被永久删除。", () -> {
                boolean deleted = DownloadManager.deleteDownload(song);
                if (deleted) {
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    loadDownloads();
                    updateEmptyState();
                } else {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
                }
            });
            return true;
        });

        // Register progress listener
        DownloadManager.addProgressListener(progressListener);

        // Start periodic speed refresh
        speedRefreshRunnable = () -> {
            if (hasActiveDownloads) {
                Map<String, DownloadProgress> active = DownloadManager.getActiveDownloads();
                updateActiveDownloadsUI(active);
                uiHandler.postDelayed(speedRefreshRunnable, 1000);
            }
        };

        loadDownloads();
        updateEmptyState();

        // Load initial active download state
        Map<String, DownloadProgress> initialActive = DownloadManager.getActiveDownloads();
        if (!initialActive.isEmpty()) {
            updateActiveDownloadsUI(initialActive);
            hasActiveDownloads = true;
            uiHandler.postDelayed(speedRefreshRunnable, 1000);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadDownloads();
        updateEmptyState();

        // Refresh active downloads state
        Map<String, DownloadProgress> active = DownloadManager.getActiveDownloads();
        updateActiveDownloadsUI(active);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DownloadManager.removeProgressListener(progressListener);
        uiHandler.removeCallbacks(speedRefreshRunnable);
    }

    /**
     * Update the active downloads section UI.
     */
    private void updateActiveDownloadsUI(Map<String, DownloadProgress> activeDownloads) {
        boolean wasEmpty = activeDownloadsContainer.getChildCount() == 0;
        boolean isEmpty = activeDownloads.isEmpty();

        if (isEmpty) {
            // All downloads finished - refresh the completed list after a brief delay
            if (!wasEmpty) {
                hasActiveDownloads = false;
                uiHandler.removeCallbacks(speedRefreshRunnable);
                uiHandler.postDelayed(() -> {
                    loadDownloads();
                    updateEmptyState();
                }, 500);
            }
            activeDownloadsContainer.removeAllViews();
            progressViews.clear();
            activeDownloadsContainer.setVisibility(View.GONE);
            tvActiveSection.setVisibility(View.GONE);
            activeDivider.setVisibility(View.GONE);
            return;
        }

        hasActiveDownloads = true;
        tvActiveSection.setVisibility(View.VISIBLE);
        activeDownloadsContainer.setVisibility(View.VISIBLE);
        tvActiveSection.setText("正在下载 (" + activeDownloads.size() + ")");
        activeDivider.setVisibility(View.VISIBLE);

        // Remove views for tasks no longer in the map
        List<String> toRemove = new ArrayList<>();
        for (String key : progressViews.keySet()) {
            if (!activeDownloads.containsKey(key)) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            View v = progressViews.remove(key);
            if (v != null) activeDownloadsContainer.removeView(v);
        }

        // Add or update views for each active download
        for (Map.Entry<String, DownloadProgress> entry : activeDownloads.entrySet()) {
            String songKey = entry.getKey();
            DownloadProgress progress = entry.getValue();

            View itemView = progressViews.get(songKey);
            if (itemView == null) {
                itemView = createProgressItemView();
                activeDownloadsContainer.addView(itemView);
                progressViews.put(songKey, itemView);
            }

            updateProgressItemView(itemView, progress);
        }

        // Start speed refresh if not already running
        if (!hasActiveDownloads || !isSpeedRefreshRunning()) {
            uiHandler.removeCallbacks(speedRefreshRunnable);
            uiHandler.postDelayed(speedRefreshRunnable, 1000);
        }

        // Ensure ListView is still visible when there are active downloads
        lvDownloads.setVisibility(View.VISIBLE);
    }

    private boolean isSpeedRefreshRunning() {
        // We can't directly check, but we just re-post
        return false;
    }

    /**
     * Create a progress item view programmatically.
     */
    private View createProgressItemView() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(0xFF1A1A2E);
        int px6 = com.qinghe.music163pro.util.WatchUiUtils.px(this, 6);
        int px8 = com.qinghe.music163pro.util.WatchUiUtils.px(this, 8);
        int px10 = com.qinghe.music163pro.util.WatchUiUtils.px(this, 10);
        container.setPadding(px10, px8, px10, px8);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        containerParams.topMargin = 2;
        containerParams.bottomMargin = 2;
        container.setLayoutParams(containerParams);

        // Song name row
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        // Status indicator bar
        View indicator = new View(this);
        indicator.setId(R.id.status_indicator);
        LinearLayout.LayoutParams indicatorParams = new LinearLayout.LayoutParams(
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 3),
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 24));
        indicatorParams.setMarginEnd(px6);
        indicator.setBackgroundColor(0xFF03DAC6);
        indicator.setLayoutParams(indicatorParams);
        nameRow.addView(indicator);

        // Name + artist column
        LinearLayout nameColumn = new LinearLayout(this);
        nameColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nameColumnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameColumn.setLayoutParams(nameColumnParams);

        TextView tvName = new TextView(this);
        tvName.setId(R.id.tv_download_song_name);
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 12));
        tvName.setSingleLine(true);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameColumn.addView(tvName);

        TextView tvArtist = new TextView(this);
        tvArtist.setId(R.id.tv_download_artist);
        tvArtist.setTextColor(0xB3FFFFFF);
        tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 10));
        tvArtist.setSingleLine(true);
        tvArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvArtist.setVisibility(View.GONE);
        nameColumn.addView(tvArtist);

        nameRow.addView(nameColumn);

        // Percentage
        TextView tvPercent = new TextView(this);
        tvPercent.setId(R.id.tv_download_percent);
        tvPercent.setTextColor(0xFFBB86FC);
        tvPercent.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 12));
        tvPercent.setTypeface(null, android.graphics.Typeface.BOLD);
        tvPercent.setMinWidth(com.qinghe.music163pro.util.WatchUiUtils.px(this, 36));
        tvPercent.setGravity(Gravity.END);
        nameRow.addView(tvPercent);

        container.addView(nameRow);

        // Progress bar
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setId(R.id.progress_bar);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setIndeterminate(false);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 4));
        barParams.topMargin = px6;
        barParams.bottomMargin = com.qinghe.music163pro.util.WatchUiUtils.px(this, 2);
        // Apply custom progress drawable
        progressBar.setProgressDrawable(getResources().getDrawable(R.drawable.download_progress_bar));
        progressBar.setLayoutParams(barParams);
        container.addView(progressBar);

        // Info row (size + speed + status)
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvSize = new TextView(this);
        tvSize.setId(R.id.tv_download_size);
        tvSize.setTextColor(0x80FFFFFF);
        tvSize.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 10));
        tvSize.setText("0B / 0B");
        LinearLayout.LayoutParams sizeParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvSize.setLayoutParams(sizeParams);
        infoRow.addView(tvSize);

        TextView tvSpeed = new TextView(this);
        tvSpeed.setId(R.id.tv_download_speed);
        tvSpeed.setTextColor(0xFF03DAC6);
        tvSpeed.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 10));
        tvSpeed.setVisibility(View.GONE);
        infoRow.addView(tvSpeed);

        TextView tvStatus = new TextView(this);
        tvStatus.setId(R.id.tv_download_status);
        tvStatus.setTextColor(0x80FFFFFF);
        tvStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 10));
        tvStatus.setVisibility(View.GONE);
        infoRow.addView(tvStatus);

        container.addView(infoRow);

        return container;
    }

    /**
     * Update a progress item view with current progress data.
     */
    private void updateProgressItemView(View itemView, DownloadProgress progress) {
        TextView tvName = itemView.findViewById(R.id.tv_download_song_name);
        TextView tvArtist = itemView.findViewById(R.id.tv_download_artist);
        TextView tvPercent = itemView.findViewById(R.id.tv_download_percent);
        ProgressBar progressBar = itemView.findViewById(R.id.progress_bar);
        TextView tvSize = itemView.findViewById(R.id.tv_download_size);
        TextView tvSpeed = itemView.findViewById(R.id.tv_download_speed);
        TextView tvStatus = itemView.findViewById(R.id.tv_download_status);
        View indicator = itemView.findViewById(R.id.status_indicator);

        if (tvName != null) {
            tvName.setText(progress.song.getName());
        }

        if (tvArtist != null) {
            String artist = progress.song.getArtist();
            if (artist != null && !artist.isEmpty() && !"null".equals(artist)) {
                tvArtist.setText(artist);
                tvArtist.setVisibility(View.VISIBLE);
            } else {
                tvArtist.setVisibility(View.GONE);
            }
        }

        if (tvPercent != null) {
            if ("complete".equals(progress.status)) {
                tvPercent.setText("完成");
            } else if ("error".equals(progress.status)) {
                tvPercent.setText("失败");
            } else if ("transcoding".equals(progress.status)) {
                tvPercent.setText("转码");
            } else {
                tvPercent.setText(progress.percent + "%");
            }
        }

        if (progressBar != null) {
            if ("downloading".equals(progress.status)) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(progress.percent);
            } else if ("transcoding".equals(progress.status)) {
                progressBar.setIndeterminate(true);
            } else if ("complete".equals(progress.status)) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(100);
            } else if ("error".equals(progress.status)) {
                progressBar.setIndeterminate(false);
                // Keep current progress on error
            }
        }

        if (tvSize != null) {
            if (progress.totalBytes > 0) {
                tvSize.setText(progress.getFormattedSize() + " / " + progress.getTotalFormattedSize());
            } else {
                tvSize.setText(progress.getFormattedSize());
            }
        }

        if (tvSpeed != null) {
            String speedText = progress.getSpeedText();
            if (speedText != null && !speedText.isEmpty()) {
                tvSpeed.setText(speedText);
                tvSpeed.setVisibility(View.VISIBLE);
            } else {
                tvSpeed.setVisibility(View.GONE);
            }
        }

        if (tvStatus != null) {
            if ("complete".equals(progress.status)) {
                tvStatus.setText("下载完成");
                tvStatus.setTextColor(0xFF4CAF50);
                tvStatus.setVisibility(View.VISIBLE);
            } else if ("error".equals(progress.status)) {
                tvStatus.setText("失败");
                tvStatus.setTextColor(0xFFCF6679);
                tvStatus.setVisibility(View.VISIBLE);
            } else if ("transcoding".equals(progress.status)) {
                tvStatus.setText("转码中...");
                tvStatus.setTextColor(0xFFFF9800);
                tvStatus.setVisibility(View.VISIBLE);
            } else {
                tvStatus.setVisibility(View.GONE);
            }
        }

        if (indicator != null) {
            if ("downloading".equals(progress.status)) {
                indicator.setBackgroundColor(0xFF03DAC6); // Teal/cyan
            } else if ("complete".equals(progress.status)) {
                indicator.setBackgroundColor(0xFF4CAF50); // Green
            } else if ("error".equals(progress.status)) {
                indicator.setBackgroundColor(0xFFCF6679); // Red
            } else if ("transcoding".equals(progress.status)) {
                indicator.setBackgroundColor(0xFFFF9800); // Orange
            }
        }
    }

    private void loadDownloads() {
        downloadedSongs.clear();

        // Load from new subfolder format (with info.json)
        List<File> songDirs = DownloadManager.getDownloadedSongDirs();
        for (File dir : songDirs) {
            Song song = DownloadManager.loadSongInfo(dir);
            if (song != null) {
                downloadedSongs.add(song);
            }
        }

        // Load legacy flat .mp3 files (backward compatibility)
        List<File> legacyFiles = DownloadManager.getDownloadedFiles();
        for (File file : legacyFiles) {
            Song song = fileToSong(file);
            downloadedSongs.add(song);
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void updateEmptyState() {
        boolean hasActive = !DownloadManager.getActiveDownloads().isEmpty();
        boolean hasCompleted = !downloadedSongs.isEmpty();

        if (!hasActive && !hasCompleted) {
            tvEmpty.setVisibility(View.VISIBLE);
            lvDownloads.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            lvDownloads.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Build a full playlist from all downloaded songs and play the selected one.
     */
    private void playDownloadedSong(int position) {
        try {
            List<Song> playlist = new ArrayList<>(downloadedSongs);

            MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
            playerManager.setPlaylist(playlist, position);
            playerManager.playCurrent();

            // Navigate back to MainActivity
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "播放失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Convert a legacy flat .mp3 file to a Song (id=0, parsed from filename).
     */
    private Song fileToSong(File file) {
        String name = file.getName();
        if (name.endsWith(".mp3")) {
            name = name.substring(0, name.length() - 4);
        }
        String songName = name;
        String artist = "";
        int dashIdx = name.indexOf(" - ");
        if (dashIdx > 0) {
            songName = name.substring(0, dashIdx);
            artist = name.substring(dashIdx + 3);
        }
        Song song = new Song(0, songName, artist, "");
        song.setUrl(file.getAbsolutePath());
        return song;
    }
    /**
     * Show a confirmation dialog adapted for watch (360x320 px screen).
     */
    private void showConfirmDialog(String title, String message, Runnable onConfirm) {
        WatchConfirmDialog.show(this, title, message, onConfirm,
                new WatchConfirmDialog.Options(0xFF424242, 0xFFBB86FC, true));
    }

}
