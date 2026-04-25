package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.BilibiliApiHelper;
import com.qinghe.music163pro.manager.BilibiliFavoritesManager;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.model.BilibiliFavorite;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BilibiliPlaylistActivity extends BaseWatchActivity {

    private String bvid;
    private String videoTitle;
    private String ownerName;

    private TextView tvSubtitle;
    private TextView tvEmpty;
    private ImageView btnFavorite;
    private ImageView btnDownloadAll;
    private LinearLayout listContainer;
    private LinearLayout selectBar;
    private MaterialButton btnPlayAll;

    private final List<BilibiliApiHelper.BilibiliPage> fetchedPages = new ArrayList<>();
    private BilibiliFavoritesManager favoritesManager;

    // Multi-select state
    private final Set<Integer> selectedPositions = new HashSet<>();
    private boolean isSelectMode = false;
    private boolean isBatchDownloading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        favoritesManager = new BilibiliFavoritesManager(this);
        bvid = getIntent().getStringExtra("bvid");
        videoTitle = getIntent().getStringExtra("video_title");
        ownerName = getIntent().getStringExtra("owner_name");

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(getResources().getColor(R.color.bg_dark));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(10), px(8), px(10), px(10));

        root.addView(createTitleBar());

        tvSubtitle = new TextView(this);
        tvSubtitle.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tvSubtitle.setTextColor(getResources().getColor(R.color.text_secondary));
        tvSubtitle.setTextSize(11);
        tvSubtitle.setPadding(0, 0, 0, px(8));
        root.addView(tvSubtitle);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buttonRow.setGravity(Gravity.CENTER_HORIZONTAL);

        btnPlayAll = createWatchButton("全部播放", false);
        LinearLayout.LayoutParams playAllParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnPlayAll.setLayoutParams(playAllParams);
        btnPlayAll.setOnClickListener(v -> playIndex(0));
        buttonRow.addView(btnPlayAll);

        MaterialButton btnDownloadAllBtn = createWatchButton("全部下载", false);
        LinearLayout.LayoutParams dlAllParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dlAllParams.setMarginStart(px(6));
        btnDownloadAllBtn.setLayoutParams(dlAllParams);
        btnDownloadAllBtn.setOnClickListener(v -> startBatchDownloadAll());
        buttonRow.addView(btnDownloadAllBtn);

        root.addView(buttonRow);

        tvEmpty = new TextView(this);
        tvEmpty.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tvEmpty.setTextColor(getResources().getColor(R.color.text_secondary));
        tvEmpty.setTextSize(12);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(0, px(24), 0, 0);
        root.addView(tvEmpty);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);

        // Bottom select bar
        selectBar = new LinearLayout(this);
        selectBar.setOrientation(LinearLayout.HORIZONTAL);
        selectBar.setGravity(Gravity.CENTER);
        selectBar.setBackgroundColor(getResources().getColor(R.color.surface_elevated));
        selectBar.setPadding(px(8), px(10), px(8), px(10));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = px(8);
        selectBar.setLayoutParams(barParams);
        selectBar.setVisibility(View.GONE);

        TextView tvSelectAll = new TextView(this);
        tvSelectAll.setText("全选");
        tvSelectAll.setTextColor(getResources().getColor(R.color.colorPrimary));
        tvSelectAll.setTextSize(13);
        tvSelectAll.setPadding(px(12), px(4), px(12), px(4));
        tvSelectAll.setGravity(Gravity.CENTER);
        tvSelectAll.setOnClickListener(v -> toggleSelectAll());
        selectBar.addView(tvSelectAll);

        TextView tvDownload = new TextView(this);
        tvDownload.setText("下载(0)");
        tvDownload.setTextColor(getResources().getColor(R.color.colorPrimary));
        tvDownload.setTextSize(13);
        tvDownload.setPadding(px(12), px(4), px(12), px(4));
        tvDownload.setGravity(Gravity.CENTER);
        tvDownload.setOnClickListener(v -> downloadSelected());
        selectBar.addView(tvDownload);

        TextView tvCancel = new TextView(this);
        tvCancel.setText("取消");
        tvCancel.setTextColor(getResources().getColor(R.color.text_secondary));
        tvCancel.setTextSize(13);
        tvCancel.setPadding(px(12), px(4), px(12), px(4));
        tvCancel.setGravity(Gravity.CENTER);
        tvCancel.setOnClickListener(v -> exitSelectMode());
        selectBar.addView(tvCancel);

        root.addView(selectBar);

        scrollView.addView(root);
        setContentView(scrollView);

        updateFavoriteIcon();
        loadPages();
    }

    private LinearLayout createTitleBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(38)));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView back = new ImageView(this);
        back.setLayoutParams(new LinearLayout.LayoutParams(px(22), px(22)));
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setColorFilter(getResources().getColor(R.color.text_primary));
        back.setOnClickListener(v -> {
            if (isSelectMode) {
                exitSelectMode();
            } else {
                finish();
            }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        title.setGravity(Gravity.CENTER);
        title.setText(TextUtils.isEmpty(videoTitle) ? "BV视频列表" : videoTitle);
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title);

        btnFavorite = new ImageView(this);
        btnFavorite.setLayoutParams(new LinearLayout.LayoutParams(px(22), px(22)));
        btnFavorite.setOnClickListener(v -> toggleFavorite());
        bar.addView(btnFavorite);

        btnDownloadAll = new ImageView(this);
        btnDownloadAll.setLayoutParams(new LinearLayout.LayoutParams(px(22), px(22)));
        btnDownloadAll.setImageResource(R.drawable.ic_get_app);
        btnDownloadAll.setColorFilter(0x80FFFFFF);
        btnDownloadAll.setOnClickListener(v -> startBatchDownloadAll());
        bar.addView(btnDownloadAll);

        return bar;
    }

    private void loadPages() {
        if (TextUtils.isEmpty(bvid)) {
            tvEmpty.setText("缺少 BV 号");
            btnPlayAll.setEnabled(false);
            return;
        }
        tvEmpty.setText("正在加载视频列表...");
        tvEmpty.setVisibility(View.VISIBLE);
        btnPlayAll.setEnabled(false);
        listContainer.removeAllViews();
        String cookie = getSharedPreferences("music163_settings", MODE_PRIVATE)
                .getString("bilibili_cookie", "");
        BilibiliApiHelper.getVideoInfo(bvid, cookie, new BilibiliApiHelper.VideoInfoCallback() {
            @Override
            public void onResult(List<BilibiliApiHelper.BilibiliPage> pages) {
                fetchedPages.clear();
                fetchedPages.addAll(pages);
                if (!pages.isEmpty()) {
                    videoTitle = pages.get(0).videoTitle;
                    ownerName = pages.get(0).ownerName;
                    tvSubtitle.setText(bvid + (TextUtils.isEmpty(ownerName) ? "" : " · " + ownerName));
                    btnPlayAll.setText("全部播放 (" + pages.size() + "集)");
                    btnPlayAll.setEnabled(true);
                    tvEmpty.setVisibility(View.GONE);
                    updateFavoriteIcon();
                    renderPageList();
                } else {
                    tvEmpty.setText("没有可播放分集");
                }
            }

            @Override
            public void onError(String message) {
                tvEmpty.setText("加载失败: " + message);
            }
        });
    }

    private void renderPageList() {
        listContainer.removeAllViews();
        int currentIndex = findCurrentIndexInPlayer();
        for (int i = 0; i < fetchedPages.size(); i++) {
            BilibiliApiHelper.BilibiliPage page = fetchedPages.get(i);
            final int index = i;

            LinearLayout item = new LinearLayout(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = px(6);
            item.setLayoutParams(params);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(px(12), px(10), px(12), px(10));
            boolean isSelected = selectedPositions.contains(index);
            boolean isCurrent = (i == currentIndex);
            if (isSelected) {
                item.setBackgroundColor(0x22BB86FC);
            } else if (isCurrent) {
                item.setBackgroundColor(getResources().getColor(R.color.colorPrimaryDark));
            } else {
                item.setBackgroundColor(getResources().getColor(R.color.surface_elevated));
            }
            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(v -> {
                if (isSelectMode) {
                    toggleSelection(index);
                } else {
                    playIndex(index);
                }
            });
            item.setOnLongClickListener(v -> {
                if (!isSelectMode) {
                    enterSelectMode(index);
                    return true;
                }
                return false;
            });

            TextView title = new TextView(this);
            String prefix = isCurrent && !isSelectMode ? "▶ " : (page.page + ". ");
            title.setText(prefix + (TextUtils.isEmpty(page.part) ? page.videoTitle : page.part));
            title.setTextColor(isSelected ? 0xFFBB86FC : getResources().getColor(R.color.text_primary));
            title.setTextSize(14);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            item.addView(title);

            TextView sub = new TextView(this);
            sub.setText(formatDuration(page.duration) + " · " + bvid);
            sub.setTextColor(getResources().getColor(R.color.text_secondary));
            sub.setTextSize(11);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(0, px(2), 0, 0);
            item.addView(sub);

            listContainer.addView(item);
        }
    }

    // ======================== Multi-Select ========================

    private void enterSelectMode(int initialPosition) {
        isSelectMode = true;
        selectedPositions.clear();
        selectedPositions.add(initialPosition);
        selectBar.setVisibility(View.VISIBLE);
        updateSelectBar();
        renderPageList();
    }

    private void exitSelectMode() {
        isSelectMode = false;
        selectedPositions.clear();
        selectBar.setVisibility(View.GONE);
        renderPageList();
    }

    private void toggleSelection(int position) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        if (selectedPositions.isEmpty()) {
            exitSelectMode();
            return;
        }
        updateSelectBar();
        // Update just the affected item's appearance
        updateItemAppearance(position);
    }

    private void toggleSelectAll() {
        if (selectedPositions.size() == fetchedPages.size()) {
            selectedPositions.clear();
        } else {
            for (int i = 0; i < fetchedPages.size(); i++) {
                selectedPositions.add(i);
            }
        }
        if (selectedPositions.isEmpty()) {
            exitSelectMode();
            return;
        }
        updateSelectBar();
        renderPageList();
    }

    private void updateSelectBar() {
        if (selectBar.getChildCount() >= 2) {
            TextView tvDownload = (TextView) selectBar.getChildAt(1);
            tvDownload.setText("下载(" + selectedPositions.size() + ")");
        }
    }

    private void updateItemAppearance(int position) {
        if (position < 0 || position >= listContainer.getChildCount()) return;
        View child = listContainer.getChildAt(position);
        if (child instanceof LinearLayout) {
            boolean isSelected = selectedPositions.contains(position);
            child.setBackgroundColor(isSelected ? 0x22BB86FC :
                    getResources().getColor(R.color.surface_elevated));
            if (((LinearLayout) child).getChildCount() > 0) {
                TextView tv = (TextView) ((LinearLayout) child).getChildAt(0);
                tv.setTextColor(isSelected ? 0xFFBB86FC : getResources().getColor(R.color.text_primary));
            }
        }
    }

    private void downloadSelected() {
        if (selectedPositions.isEmpty()) return;
        List<Song> songs = new ArrayList<>();
        for (Integer pos : selectedPositions) {
            if (pos >= 0 && pos < fetchedPages.size()) {
                songs.add(buildSongFromPage(fetchedPages.get(pos)));
            }
        }
        exitSelectMode();
        String cookie = getSharedPreferences("music163_settings", MODE_PRIVATE)
                .getString("bilibili_cookie", "");
        tvSubtitle.setText("正在下载 " + songs.size() + " 首...");
        DownloadManager.batchDownloadSongs(songs, cookie,
                new DownloadManager.BatchDownloadCallback() {
                    @Override
                    public void onProgress(int current, int total, String songName) {
                        tvSubtitle.setText("下载中 " + current + "/" + total + ": " + songName);
                    }

                    @Override
                    public void onAllComplete(int successCount, int skipCount, int failCount) {
                        tvSubtitle.setText("下载完成: 成功" + successCount + " 跳过" + skipCount + " 失败" + failCount);
                        isBatchDownloading = false;
                        if (btnDownloadAll != null) {
                            btnDownloadAll.setColorFilter(0x80FFFFFF);
                        }
                        renderPageList();
                    }

                    @Override
                    public void onSingleError(String songName, String message) {
                        // Progress shows in onProgress
                    }
                });
    }

    private void startBatchDownloadAll() {
        if (fetchedPages.isEmpty()) {
            Toast.makeText(this, "列表为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isBatchDownloading) {
            DownloadManager.cancelBatchDownload();
            isBatchDownloading = false;
            if (btnDownloadAll != null) {
                btnDownloadAll.setColorFilter(0x80FFFFFF);
            }
            tvSubtitle.setText("已取消下载");
            return;
        }
        isBatchDownloading = true;
        if (btnDownloadAll != null) {
            btnDownloadAll.setColorFilter(0xFFFF4081);
        }
        String cookie = getSharedPreferences("music163_settings", MODE_PRIVATE)
                .getString("bilibili_cookie", "");
        List<Song> songs = buildSongsFromPages(fetchedPages);
        tvSubtitle.setText("正在下载 " + songs.size() + " 首...");
        DownloadManager.batchDownloadSongs(songs, cookie,
                new DownloadManager.BatchDownloadCallback() {
                    @Override
                    public void onProgress(int current, int total, String songName) {
                        tvSubtitle.setText("下载中 " + current + "/" + total + ": " + songName);
                    }

                    @Override
                    public void onAllComplete(int successCount, int skipCount, int failCount) {
                        tvSubtitle.setText("下载完成: 成功" + successCount + " 跳过" + skipCount + " 失败" + failCount);
                        isBatchDownloading = false;
                        if (btnDownloadAll != null) {
                            btnDownloadAll.setColorFilter(0x80FFFFFF);
                        }
                        renderPageList();
                    }

                    @Override
                    public void onSingleError(String songName, String message) {
                    }
                });
    }

    // ======================== Playback ========================

    private int findCurrentIndexInPlayer() {
        Song currentSong = MusicPlayerManager.getInstance().getCurrentSong();
        if (currentSong == null || !currentSong.isBilibili() || !bvid.equalsIgnoreCase(currentSong.getBvid())) {
            return -1;
        }
        return MusicPlayerManager.getInstance().getCurrentIndex();
    }

    private void playIndex(int index) {
        if (index < 0 || index >= fetchedPages.size()) {
            return;
        }
        MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
        if (canUseCurrentPlaylist()) {
            playerManager.playFromCurrentPlaylist(index);
            finishToPlayer();
            return;
        }

        Toast.makeText(this, "正在获取音频...", Toast.LENGTH_SHORT).show();
        List<Song> songs = buildSongsFromPages(fetchedPages);
        playerManager.setPlaylist(songs, index);
        BilibiliApiHelper.BilibiliPage page = fetchedPages.get(index);
        String cookie = getSharedPreferences("music163_settings", MODE_PRIVATE)
                .getString("bilibili_cookie", "");
        BilibiliApiHelper.getAudioStreamUrl(page.bvid, page.cid, cookie,
                new BilibiliApiHelper.AudioStreamCallback() {
                    @Override
                    public void onResult(String audioUrl) {
                        songs.get(index).setUrl(audioUrl);
                        playerManager.playCurrent();
                        finishToPlayer();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(BilibiliPlaylistActivity.this,
                                "获取音频失败: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private boolean canUseCurrentPlaylist() {
        List<Song> playlist = MusicPlayerManager.getInstance().getPlaylist();
        if (playlist == null || playlist.size() != fetchedPages.size() || playlist.isEmpty()) {
            return false;
        }
        for (Song song : playlist) {
            if (!song.isBilibili() || !bvid.equalsIgnoreCase(song.getBvid())) {
                return false;
            }
        }
        return true;
    }

    // ======================== Helpers ========================

    private List<Song> buildSongsFromPages(List<BilibiliApiHelper.BilibiliPage> pages) {
        List<Song> songs = new ArrayList<>();
        for (BilibiliApiHelper.BilibiliPage page : pages) {
            songs.add(buildSongFromPage(page));
        }
        return songs;
    }

    private Song buildSongFromPage(BilibiliApiHelper.BilibiliPage page) {
        Song song = new Song();
        song.setId(buildBilibiliSongId(page.bvid, page.cid));
        song.setName(TextUtils.isEmpty(page.part) ? page.videoTitle : page.part);
        song.setArtist(page.ownerName);
        song.setAlbum(page.videoTitle);
        song.setSource("bilibili");
        song.setBvid(page.bvid);
        song.setCid(page.cid);
        return song;
    }

    private void finishToPlayer() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void toggleFavorite() {
        if (TextUtils.isEmpty(bvid)) {
            return;
        }
        if (favoritesManager.isFavorite(bvid)) {
            favoritesManager.removeFavorite(bvid);
            Toast.makeText(this, "已取消收藏BV号", Toast.LENGTH_SHORT).show();
        } else {
            favoritesManager.addFavorite(new BilibiliFavorite(bvid, videoTitle, ownerName));
            Toast.makeText(this, "已收藏BV号", Toast.LENGTH_SHORT).show();
        }
        updateFavoriteIcon();
    }

    private void updateFavoriteIcon() {
        if (btnFavorite == null) {
            return;
        }
        boolean favorite = !TextUtils.isEmpty(bvid) && favoritesManager.isFavorite(bvid);
        btnFavorite.setImageResource(favorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        btnFavorite.setColorFilter(favorite
                ? getResources().getColor(R.color.favorite_red)
                : getResources().getColor(R.color.text_primary));
    }

    private String formatDuration(int seconds) {
        int min = seconds / 60;
        int sec = seconds % 60;
        return String.format("%d:%02d", min, sec);
    }

    private long buildBilibiliSongId(String bvid, long cid) {
        long mixed = ((((long) (bvid != null ? bvid.hashCode() : 0)) & 0xFFFFFFFFL) << 32)
                ^ (cid & 0xFFFFFFFFL);
        return -Math.abs(mixed == 0 ? cid + 1 : mixed);
    }
}
