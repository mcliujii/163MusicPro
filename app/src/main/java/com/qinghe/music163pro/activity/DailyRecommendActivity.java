package com.qinghe.music163pro.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.MusicApp;
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
 * Daily recommend songs screen.
 */
public class DailyRecommendActivity extends BaseWatchActivity {

    private final List<Song> songs = new ArrayList<>();
    private ArrayAdapter<Song> adapter;
    private TextView tvStatus;
    private TextView tvTitle;
    private ImageView btnDownloadAll;
    private ListView listView;
    private boolean isBatchDownloading = false;

    // Multi-select fields
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
        setContentView(R.layout.activity_daily_recommend);

        tvTitle = findViewById(R.id.tv_title);
        tvStatus = findViewById(R.id.tv_status);
        listView = findViewById(R.id.lv_daily_songs);

        // Add download all button to the title area
        android.widget.LinearLayout titleParent = (android.widget.LinearLayout) tvTitle.getParent();
        int titleIndex = titleParent.indexOfChild(tvTitle);
        titleParent.removeView(tvTitle);
        android.widget.RelativeLayout titleRow = new android.widget.RelativeLayout(this);
        android.widget.LinearLayout.LayoutParams rowParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        titleRow.setLayoutParams(rowParams);
        titleParent.addView(titleRow, titleIndex);

        btnDownloadAll = new ImageView(this);
        btnDownloadAll.setImageResource(R.drawable.ic_get_app);
        btnDownloadAll.setPadding(px(2), px(2), px(4), px(2));
        btnDownloadAll.setClickable(true);
        btnDownloadAll.setFocusable(true);
        btnDownloadAll.setColorFilter(0x80FFFFFF);
        btnDownloadAll.setOnClickListener(v -> startBatchDownload());
        android.widget.RelativeLayout.LayoutParams dlParams = new android.widget.RelativeLayout.LayoutParams(
                px(22), px(22));
        dlParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        dlParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        btnDownloadAll.setLayoutParams(dlParams);
        titleRow.addView(btnDownloadAll);

        android.widget.RelativeLayout.LayoutParams titleParams =
                new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_IN_PARENT);
        titleParams.addRule(android.widget.RelativeLayout.RIGHT_OF, btnDownloadAll.getId());
        tvTitle.setLayoutParams(titleParams);
        titleRow.addView(tvTitle);

        adapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, songs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    tvName.setText((position + 1) + ". " + song.getName());
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
        listView.setAdapter(adapter);

        // Click: in select mode toggle selection, otherwise play
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (isSelectMode) {
                toggleSelection(position);
            } else {
                playFromDailyRecommend(position);
            }
        });

        // Long press: enter select mode with that position auto-selected
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            enterSelectMode(position);
            return true;
        });

        // Build and add the select bar to the root layout
        buildSelectBar();
        android.widget.LinearLayout rootLayout = (android.widget.LinearLayout) listView.getParent();
        rootLayout.addView(selectBar);

        loadDailyRecommend();
    }

    // -----------------------------------------------------------------------
    // Select bar construction
    // -----------------------------------------------------------------------

    private void buildSelectBar() {
        selectBar = new LinearLayout(this);
        selectBar.setOrientation(LinearLayout.HORIZONTAL);
        selectBar.setBackgroundColor(0xFF1E1E1E);
        selectBar.setGravity(Gravity.CENTER_VERTICAL);
        selectBar.setPadding(px(4), px(4), px(4), px(4));
        selectBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        selectBar.setLayoutParams(barParams);

        TextView btnSelectAll = makeBarBtn("全选", v -> toggleSelectAll());
        btnSelectAll.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        selectBar.addView(btnSelectAll);

        TextView btnDownloadSelected = makeBarBtn("下载(0)", v -> downloadSelected());
        btnDownloadSelected.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        selectBar.addView(btnDownloadSelected);

        TextView btnCancel = makeBarBtn("取消", v -> exitSelectMode());
        btnCancel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        selectBar.addView(btnCancel);
    }

    /**
     * Helper to create a styled button for the bottom select bar.
     */
    private TextView makeBarBtn(String text, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(px(8), px(6), px(8), px(6));
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(13);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(listener);
        return btn;
    }

    // -----------------------------------------------------------------------
    // Select mode logic
    // -----------------------------------------------------------------------

    private void enterSelectMode(int initialPosition) {
        isSelectMode = true;
        selectedPositions.clear();
        selectedPositions.add(initialPosition);
        btnDownloadAll.setVisibility(View.GONE);
        tvTitle.setText("选择歌曲");
        updateSelectStatus();
        selectBar.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private void exitSelectMode() {
        isSelectMode = false;
        selectedPositions.clear();
        selectBar.setVisibility(View.GONE);
        btnDownloadAll.setVisibility(View.VISIBLE);
        tvTitle.setText("每日推荐");
        tvStatus.setText(songs.isEmpty() ? "暂无推荐歌曲" : songs.size() + " 首歌曲");
        adapter.notifyDataSetChanged();
    }

    private void toggleSelection(int position) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        updateSelectStatus();
        adapter.notifyDataSetChanged();
    }

    private void toggleSelectAll() {
        if (selectedPositions.size() == songs.size()) {
            // All selected → deselect all
            selectedPositions.clear();
        } else {
            // Select all
            selectedPositions.clear();
            for (int i = 0; i < songs.size(); i++) {
                selectedPositions.add(i);
            }
        }
        updateSelectStatus();
        adapter.notifyDataSetChanged();
    }

    private void updateSelectStatus() {
        tvStatus.setText("已选 " + selectedPositions.size() + " 首");
        // Update the download button text to reflect current selection count
        if (selectBar.getChildCount() >= 2) {
            TextView btnDownloadSelected = (TextView) selectBar.getChildAt(1);
            btnDownloadSelected.setText("下载(" + selectedPositions.size() + ")");
        }
    }

    private void downloadSelected() {
        if (selectedPositions.isEmpty()) {
            Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        String cookie = MusicPlayerManager.getInstance().getCookie();
        if (cookie == null || cookie.isEmpty()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Song> selectedSongs = new ArrayList<>();
        for (int pos : selectedPositions) {
            if (pos >= 0 && pos < songs.size()) {
                selectedSongs.add(songs.get(pos));
            }
        }

        int totalSelected = selectedSongs.size();
        tvStatus.setText("准备下载 " + totalSelected + " 首...");
        Toast.makeText(this, "开始下载 " + totalSelected + " 首歌曲", Toast.LENGTH_SHORT).show();

        DownloadManager.batchDownloadSongs(selectedSongs, cookie,
                new DownloadManager.BatchDownloadCallback() {
                    @Override
                    public void onProgress(int current, int total, String songName) {
                        tvStatus.setText("下载中 " + current + "/" + total + "\n" + songName);
                    }

                    @Override
                    public void onAllComplete(int successCount, int skipCount, int failCount) {
                        String msg = "下载完成: 成功" + successCount;
                        if (skipCount > 0) msg += " 跳过" + skipCount;
                        if (failCount > 0) msg += " 失败" + failCount;
                        Toast.makeText(DailyRecommendActivity.this, msg, Toast.LENGTH_LONG).show();
                        exitSelectMode();
                    }

                    @Override
                    public void onSingleError(String songName, String message) {
                    }
                });
    }

    // -----------------------------------------------------------------------
    // Existing batch download (download all)
    // -----------------------------------------------------------------------

    private void startBatchDownload() {
        if (isBatchDownloading) {
            DownloadManager.cancelBatchDownload();
            isBatchDownloading = false;
            btnDownloadAll.setColorFilter(0x80FFFFFF);
            tvStatus.setText(songs.isEmpty() ? "暂无推荐歌曲" : songs.size() + " 首歌曲");
            Toast.makeText(this, "已取消批量下载", Toast.LENGTH_SHORT).show();
            return;
        }
        if (songs.isEmpty()) {
            Toast.makeText(this, "歌曲列表为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String cookie = MusicPlayerManager.getInstance().getCookie();
        if (cookie == null || cookie.isEmpty()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        isBatchDownloading = true;
        btnDownloadAll.setColorFilter(0xFFFF4081);
        tvStatus.setText("准备批量下载...");
        Toast.makeText(this, "开始批量下载（再次点击可取消）", Toast.LENGTH_SHORT).show();

        DownloadManager.batchDownloadSongs(new ArrayList<>(songs), cookie,
                new DownloadManager.BatchDownloadCallback() {
                    @Override
                    public void onProgress(int current, int total, String songName) {
                        tvStatus.setText("下载中 " + current + "/" + total + "\n" + songName);
                    }

                    @Override
                    public void onAllComplete(int successCount, int skipCount, int failCount) {
                        isBatchDownloading = false;
                        btnDownloadAll.setColorFilter(0x80FFFFFF);
                        tvStatus.setText(songs.isEmpty() ? "暂无推荐歌曲" : songs.size() + " 首歌曲");
                        String msg = "下载完成: 成功" + successCount;
                        if (skipCount > 0) msg += " 跳过" + skipCount;
                        if (failCount > 0) msg += " 失败" + failCount;
                        Toast.makeText(DailyRecommendActivity.this, msg, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onSingleError(String songName, String message) {
                    }
                });
    }

    private void loadDailyRecommend() {
        String cookie = MusicPlayerManager.getInstance().getCookie();
        if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
            tvStatus.setText("请先登录");
            listView.setVisibility(View.GONE);
            return;
        }
        tvStatus.setText("正在加载...");
        MusicApiHelper.getDailyRecommendSongs(cookie, new MusicApiHelper.SearchCallback() {
            @Override
            public void onResult(List<Song> result) {
                songs.clear();
                songs.addAll(result);
                adapter.notifyDataSetChanged();
                tvStatus.setText(result.isEmpty() ? "暂无推荐歌曲" : result.size() + " 首歌曲");
                listView.setVisibility(result.isEmpty() ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onError(String message) {
                tvStatus.setText("加载失败: " + message);
                listView.setVisibility(View.GONE);
            }
        });
    }

    private void playFromDailyRecommend(int index) {
        if (songs.isEmpty() || index < 0 || index >= songs.size()) {
            return;
        }
        MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
        playerManager.setPlaylist(new ArrayList<>(songs), index);
        playerManager.playCurrent();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }
}
