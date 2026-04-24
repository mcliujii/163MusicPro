package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Top List Detail activity - shows songs in a specific chart.
 * Designed for watch screen (320x360 dpi).
 * Supports multi-select batch download via long-press to enter select mode.
 */
public class TopListDetailActivity extends AppCompatActivity {

    private ListView lvSongs;
    private final List<Song> displayList = new ArrayList<>();
    private ArrayAdapter<Song> adapter;
    private MusicPlayerManager playerManager;
    private ImageView btnDownloadAll;
    private boolean isBatchDownloading = false;

    private final Set<Integer> selectedPositions = new HashSet<>();
    private boolean isSelectMode = false;
    private LinearLayout selectBar;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        long playlistId = getIntent().getLongExtra("playlist_id", 0);
        String playlistName = getIntent().getStringExtra("playlist_name");

        playerManager = MusicPlayerManager.getInstance();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(px(6), px(6), px(6), px(6));

        // Title row with download button
        android.widget.RelativeLayout titleRow = new android.widget.RelativeLayout(this);
        android.widget.LinearLayout.LayoutParams rowParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        titleRow.setLayoutParams(rowParams);
        root.addView(titleRow);

        // Download all button
        btnDownloadAll = new ImageView(this);
        btnDownloadAll.setImageResource(R.drawable.ic_get_app);
        btnDownloadAll.setPadding(px(2), px(4), px(4), px(4));
        btnDownloadAll.setClickable(true);
        btnDownloadAll.setFocusable(true);
        btnDownloadAll.setColorFilter(0x80FFFFFF);
        btnDownloadAll.setId(View.generateViewId());
        btnDownloadAll.setOnClickListener(v -> startBatchDownload());
        android.widget.RelativeLayout.LayoutParams dlParams = new android.widget.RelativeLayout.LayoutParams(
                px(22), px(22));
        dlParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        dlParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        btnDownloadAll.setLayoutParams(dlParams);
        titleRow.addView(btnDownloadAll);

        // Title - centered between download button and right edge
        TextView title = new TextView(this);
        title.setText(playlistName != null ? playlistName : "排行榜");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        title.setGravity(Gravity.CENTER);
        title.setPadding(px(30), 0, px(6), px(6));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.RelativeLayout.LayoutParams titleParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_IN_PARENT);
        titleParams.addRule(android.widget.RelativeLayout.RIGHT_OF, btnDownloadAll.getId());
        title.setLayoutParams(titleParams);
        titleRow.addView(title);

        // Status text for showing selection count / info
        tvStatus = new TextView(this);
        tvStatus.setText("");
        tvStatus.setTextColor(0x80FFFFFF);
        tvStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, 0, 0, px(2));
        root.addView(tvStatus);

        lvSongs = new ListView(this);
        lvSongs.setDividerHeight(1);
        lvSongs.setDivider(getResources().getDrawable(android.R.color.transparent));
        LinearLayout.LayoutParams lvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        lvSongs.setLayoutParams(lvParams);
        root.addView(lvSongs);

        // Select bar at the bottom (hidden by default)
        selectBar = new LinearLayout(this);
        selectBar.setOrientation(LinearLayout.HORIZONTAL);
        selectBar.setBackgroundColor(0xFF1E1E1E);
        selectBar.setGravity(Gravity.CENTER_VERTICAL);
        selectBar.setPadding(px(4), px(4), px(4), px(4));
        selectBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        selectBar.setLayoutParams(barParams);

        selectBar.addView(makeBarBtn("全选", v -> toggleSelectAll()));

        selectBar.addView(makeBarBtn("下载(0)", v -> downloadSelected()));

        selectBar.addView(makeBarBtn("取消", v -> exitSelectMode()));

        root.addView(selectBar);

        setContentView(root);

        adapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, displayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    tvName.setText((position + 1) + ". " + song.getName());
                    tvArtist.setText(song.getArtist());

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
        lvSongs.setAdapter(adapter);

        lvSongs.setOnItemClickListener((parent, view, position, id) -> {
            if (isSelectMode) {
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

        lvSongs.setOnItemLongClickListener((parent, view, position, id) -> {
            enterSelectMode(position);
            return true;
        });

        if (playlistId > 0) {
            loadPlaylistDetail(playlistId);
        }
    }

    private void loadPlaylistDetail(long playlistId) {
        String cookie = playerManager.getCookie();
        MusicApiHelper.getPlaylistDetail(playlistId, cookie, new MusicApiHelper.PlaylistDetailCallback() {
            @Override
            public void onResult(List<Song> songs) {
                displayList.clear();
                displayList.addAll(songs);
                adapter.notifyDataSetChanged();
                if (songs.isEmpty()) {
                    Toast.makeText(TopListDetailActivity.this, "暂无歌曲", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(TopListDetailActivity.this, "加载失败: " + message, Toast.LENGTH_SHORT).show();
            }
        });
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
            Toast.makeText(this, "歌曲列表为空", Toast.LENGTH_SHORT).show();
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
                    }

                    @Override
                    public void onAllComplete(int successCount, int skipCount, int failCount) {
                        isBatchDownloading = false;
                        btnDownloadAll.setColorFilter(0x80FFFFFF);
                        String msg = "下载完成: 成功" + successCount;
                        if (skipCount > 0) msg += " 跳过" + skipCount;
                        if (failCount > 0) msg += " 失败" + failCount;
                        Toast.makeText(TopListDetailActivity.this, msg, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onSingleError(String songName, String message) {
                    }
                });
    }

    // ---- Multi-select mode ----

    private void enterSelectMode(int initialPosition) {
        isSelectMode = true;
        selectedPositions.clear();
        selectedPositions.add(initialPosition);
        btnDownloadAll.setVisibility(View.GONE);
        tvStatus.setText("长按多选模式 — 已选 " + selectedPositions.size() + " 首");
        updateSelectBarLabel();
        selectBar.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private void exitSelectMode() {
        isSelectMode = false;
        selectedPositions.clear();
        btnDownloadAll.setVisibility(View.VISIBLE);
        tvStatus.setText("");
        selectBar.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void toggleSelection(int position) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        tvStatus.setText("长按多选模式 — 已选 " + selectedPositions.size() + " 首");
        updateSelectBarLabel();
        adapter.notifyDataSetChanged();
    }

    private void toggleSelectAll() {
        if (selectedPositions.size() == displayList.size()) {
            // Deselect all
            selectedPositions.clear();
        } else {
            // Select all
            selectedPositions.clear();
            for (int i = 0; i < displayList.size(); i++) {
                selectedPositions.add(i);
            }
        }
        tvStatus.setText("长按多选模式 — 已选 " + selectedPositions.size() + " 首");
        updateSelectBarLabel();
        adapter.notifyDataSetChanged();
    }

    private void downloadSelected() {
        if (selectedPositions.isEmpty()) {
            Toast.makeText(this, "未选择任何歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Song> toDownload = new ArrayList<>();
        for (Integer pos : selectedPositions) {
            if (pos >= 0 && pos < displayList.size()) {
                toDownload.add(displayList.get(pos));
            }
        }
        Toast.makeText(this, "开始下载 " + toDownload.size() + " 首歌曲", Toast.LENGTH_SHORT).show();

        DownloadManager.batchDownloadSongs(toDownload, cookie,
                new DownloadManager.BatchDownloadCallback() {
                    @Override
                    public void onProgress(int current, int total, String songName) {
                    }

                    @Override
                    public void onAllComplete(int successCount, int skipCount, int failCount) {
                        String msg = "下载完成: 成功" + successCount;
                        if (skipCount > 0) msg += " 跳过" + skipCount;
                        if (failCount > 0) msg += " 失败" + failCount;
                        Toast.makeText(TopListDetailActivity.this, msg, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onSingleError(String songName, String message) {
                    }
                });

        exitSelectMode();
    }

    private void updateSelectBarLabel() {
        if (selectBar.getChildCount() >= 2) {
            View btn = selectBar.getChildAt(1);
            if (btn instanceof TextView) {
                ((TextView) btn).setText("下载(" + selectedPositions.size() + ")");
            }
        }
    }

    private View makeBarBtn(String text, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(px(6), px(6), px(6), px(6));
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btn.setLayoutParams(lp);
        return btn;
    }

    private int px(int baseValue) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return (int) (baseValue * screenWidth / 320f + 0.5f);
    }
}
