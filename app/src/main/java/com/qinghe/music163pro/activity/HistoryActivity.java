package com.qinghe.music163pro.activity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.qinghe.music163pro.MusicApp;
import com.qinghe.music163pro.R;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.manager.HistoryManager;
import com.qinghe.music163pro.util.WatchConfirmDialog;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * History activity - shows play history sorted by time (newest first).
 * Designed for watch screen (320x360 dpi).
 */
public class HistoryActivity extends BaseWatchActivity {

    private ListView lvHistory;
    private final List<Song> displayList = new ArrayList<>();
    private ArrayAdapter<Song> adapter;
    private MusicPlayerManager playerManager;
    private TextView tvEmpty;
    private ImageView btnDownloadAll;
    private TextView btnClear;
    private boolean isBatchDownloading = false;

    // Multi-select mode fields
    private final Set<Integer> selectedPositions = new HashSet<>();
    private boolean isSelectMode = false;
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(MusicApp.wrapWithDpiScale(newBase));
    }

    private LinearLayout selectBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        playerManager = MusicPlayerManager.getInstance();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF212121);
        root.setPadding(px(6), px(6), px(6), px(6));

        // Title bar with clear button - use RelativeLayout for true centering
        android.widget.RelativeLayout titleBar = new android.widget.RelativeLayout(this);
        titleBar.setPadding(0, 0, 0, px(6));
        root.addView(titleBar);

        // Download all button - left side
        btnDownloadAll = new ImageView(this);
        btnDownloadAll.setImageResource(R.drawable.ic_get_app);
        btnDownloadAll.setPadding(px(4), px(4), px(4), px(4));
        btnDownloadAll.setClickable(true);
        btnDownloadAll.setFocusable(true);
        btnDownloadAll.setColorFilter(0x80FFFFFF);
        btnDownloadAll.setId(View.generateViewId());
        btnDownloadAll.setOnClickListener(v -> startBatchDownload());
        android.widget.RelativeLayout.LayoutParams dlParams = new android.widget.RelativeLayout.LayoutParams(
                px(24), px(24));
        dlParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        dlParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        btnDownloadAll.setLayoutParams(dlParams);
        titleBar.addView(btnDownloadAll);

        // Adjust title to center between download button and clear button
        btnClear = new TextView(this);
        btnClear.setText("清空");
        btnClear.setTextColor(0xFFBB86FC);
        btnClear.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        btnClear.setPadding(px(8), px(4), px(8), px(4));
        btnClear.setClickable(true);
        btnClear.setFocusable(true);
        btnClear.setId(View.generateViewId());
        btnClear.setOnClickListener(v -> showClearConfirmDialog());
        android.widget.RelativeLayout.LayoutParams clearParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        clearParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        clearParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        btnClear.setLayoutParams(clearParams);
        titleBar.addView(btnClear);

        TextView title = new TextView(this);
        title.setText("历史记录");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        title.setGravity(Gravity.CENTER);
        android.widget.RelativeLayout.LayoutParams titleParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_IN_PARENT);
        title.setLayoutParams(titleParams);
        titleBar.addView(title);

        tvEmpty = new TextView(this);
        tvEmpty.setText("暂无播放记录");
        tvEmpty.setTextColor(0x80FFFFFF);
        tvEmpty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(0, px(40), 0, 0);
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty);

        lvHistory = new ListView(this);
        lvHistory.setDividerHeight(1);
        lvHistory.setDivider(getResources().getDrawable(android.R.color.transparent));
        LinearLayout.LayoutParams lvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        lvHistory.setLayoutParams(lvParams);
        root.addView(lvHistory);

        // Select bar - bottom bar with "全选" | "下载(N)" | "取消"
        selectBar = new LinearLayout(this);
        selectBar.setOrientation(LinearLayout.HORIZONTAL);
        selectBar.setBackgroundColor(0xFF1E1E1E);
        selectBar.setGravity(Gravity.CENTER_VERTICAL);
        selectBar.setPadding(px(4), px(4), px(4), px(4));
        selectBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        selectBar.setLayoutParams(barParams);
        root.addView(selectBar);

        // "全选" button
        selectBar.addView(makeBarBtn("全选", v -> toggleSelectAll()));
        // "下载(N)" button - stored in a local reference to update text later
        TextView btnDownloadSelected = makeBarBtn("下载(0)", v -> downloadSelected());
        btnDownloadSelected.setTag("btn_download_selected");
        selectBar.addView(btnDownloadSelected);
        // "取消" button
        selectBar.addView(makeBarBtn("取消", v -> exitSelectMode()));

        setContentView(root);

        adapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, displayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    tvName.setText(song.getName());
                    tvArtist.setText(song.getArtist());
                    // Selection highlight
                    if (isSelectMode && selectedPositions.contains(position)) {
                        tvName.setTextColor(0xFFBB86FC);
                        tvArtist.setTextColor(0xBB88C0F0);
                        view.setBackgroundColor(0x22BB86FC);
                    } else {
                        tvName.setTextColor(0xFFFFFFFF);
                        tvArtist.setTextColor(0xB3FFFFFF);
                        view.setBackgroundColor(0x00000000);
                    }
                }
                return view;
            }
        };
        lvHistory.setAdapter(adapter);

        lvHistory.setOnItemClickListener((parent, view, position, id) -> {
            if (isSelectMode) {
                // In select mode, toggle selection instead of playing
                toggleSelection(position);
                return;
            }
            Song song = displayList.get(position);
            List<Song> playlist = new ArrayList<>(displayList);
            playerManager.setPlaylist(playlist, position);
            playerManager.playCurrent();
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        lvHistory.setOnItemLongClickListener((parent, view, position, id) -> {
            if (!isSelectMode) {
                // Enter select mode with the long-pressed item pre-selected
                enterSelectMode(position);
                return true;
            }
            // Already in select mode, do nothing extra
            return true;
        });

        loadHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
    }

    private void loadHistory() {
        displayList.clear();
        displayList.addAll(HistoryManager.getInstance().getHistory());
        adapter.notifyDataSetChanged();

        if (displayList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            lvHistory.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            lvHistory.setVisibility(View.VISIBLE);
        }
    }

    private void startBatchDownload() {
        if (isBatchDownloading) {
            DownloadManager.cancelBatchDownload();
            isBatchDownloading = false;
            btnDownloadAll.setColorFilter(0x80FFFFFF);
            Toast.makeText(this, "已取消批量下载", Toast.LENGTH_SHORT).show();
            return;
        }
        if (displayList.isEmpty()) {
            Toast.makeText(this, "暂无播放记录", Toast.LENGTH_SHORT).show();
            return;
        }
        String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        isBatchDownloading = true;
        btnDownloadAll.setColorFilter(0xFFFF4081);
        Toast.makeText(this, "开始批量下载（再次点击可取消）", Toast.LENGTH_SHORT).show();

        DownloadManager.batchDownloadSongs(new ArrayList<>(displayList), cookie,
                new DownloadManager.BatchDownloadCallback() {
                    @Override
                    public void onProgress(int current, int total, String songName) {
                        // Could update tvEmpty as status if needed
                    }

                    @Override
                    public void onAllComplete(int successCount, int skipCount, int failCount) {
                        isBatchDownloading = false;
                        btnDownloadAll.setColorFilter(0x80FFFFFF);
                        String msg = "下载完成: 成功" + successCount;
                        if (skipCount > 0) msg += " 跳过" + skipCount;
                        if (failCount > 0) msg += " 失败" + failCount;
                        Toast.makeText(HistoryActivity.this, msg, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onSingleError(String songName, String message) {
                    }
                });
    }

    private void showClearConfirmDialog() {
        showConfirmDialog("确认清空", "确定清空所有播放记录？", () -> {
            HistoryManager.getInstance().clearHistory();
            loadHistory();
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Show a confirmation dialog adapted for watch (360x320 px screen).
     */
    private void showConfirmDialog(String title, String message, Runnable onConfirm) {
        WatchConfirmDialog.show(this, title, message, onConfirm,
                new WatchConfirmDialog.Options(0xFF424242, 0xFFBB86FC, true));
    }

    // ──────────────────────────────────────────────
    // Multi-select mode methods
    // ──────────────────────────────────────────────

    /**
     * Enter multi-select mode, pre-selecting the item at the given position.
     * Hides the download-all button and clear button in the title bar.
     */
    private void enterSelectMode(int initialPosition) {
        isSelectMode = true;
        selectedPositions.clear();
        selectedPositions.add(initialPosition);
        btnDownloadAll.setVisibility(View.GONE);
        btnClear.setVisibility(View.GONE);
        selectBar.setVisibility(View.VISIBLE);
        updateSelectBarCount();
        adapter.notifyDataSetChanged();
    }

    /**
     * Exit multi-select mode, restore all UI elements.
     */
    private void exitSelectMode() {
        isSelectMode = false;
        selectedPositions.clear();
        btnDownloadAll.setVisibility(View.VISIBLE);
        btnClear.setVisibility(View.VISIBLE);
        selectBar.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    /**
     * Toggle selection for the item at the given position.
     * If the last item is deselected, automatically exit select mode.
     */
    private void toggleSelection(int position) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        updateSelectBarCount();
        adapter.notifyDataSetChanged();

        // If nothing is selected anymore, exit select mode
        if (selectedPositions.isEmpty()) {
            exitSelectMode();
        }
    }

    /**
     * Toggle select-all: if all items are selected, deselect all; otherwise select all.
     */
    private void toggleSelectAll() {
        if (selectedPositions.size() == displayList.size()) {
            // All selected -> deselect all
            selectedPositions.clear();
        } else {
            // Select all
            selectedPositions.clear();
            for (int i = 0; i < displayList.size(); i++) {
                selectedPositions.add(i);
            }
        }
        updateSelectBarCount();
        adapter.notifyDataSetChanged();
    }

    /**
     * Download only the selected songs.
     */
    private void downloadSelected() {
        if (selectedPositions.isEmpty()) {
            Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Song> selectedSongs = new ArrayList<>();
        // Collect selected songs in order of their position
        List<Integer> sortedPositions = new ArrayList<>(selectedPositions);
        java.util.Collections.sort(sortedPositions);
        for (int pos : sortedPositions) {
            if (pos < displayList.size()) {
                selectedSongs.add(displayList.get(pos));
            }
        }

        Toast.makeText(this, "开始下载 " + selectedSongs.size() + " 首歌曲", Toast.LENGTH_SHORT).show();

        DownloadManager.batchDownloadSongs(selectedSongs, cookie,
                new DownloadManager.BatchDownloadCallback() {
                    @Override
                    public void onProgress(int current, int total, String songName) {
                    }

                    @Override
                    public void onAllComplete(int successCount, int skipCount, int failCount) {
                        String msg = "下载完成: 成功" + successCount;
                        if (skipCount > 0) msg += " 跳过" + skipCount;
                        if (failCount > 0) msg += " 失败" + failCount;
                        Toast.makeText(HistoryActivity.this, msg, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onSingleError(String songName, String message) {
                    }
                });

        // Exit select mode after starting download
        exitSelectMode();
    }

    /**
     * Update the "下载(N)" button text in the select bar.
     */
    private void updateSelectBarCount() {
        View btnTag = selectBar.findViewWithTag("btn_download_selected");
        if (btnTag instanceof TextView) {
            ((TextView) btnTag).setText("下载(" + selectedPositions.size() + ")");
        }
    }

    /**
     * Helper to create a bar button with weight=1, centered text, and click listener.
     */
    private TextView makeBarBtn(String text, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(px(8), px(6), px(8), px(6));
        btn.setClickable(true);
        btn.setFocusable(true);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btn.setLayoutParams(btnParams);
        btn.setOnClickListener(listener);
        return btn;
    }
}
