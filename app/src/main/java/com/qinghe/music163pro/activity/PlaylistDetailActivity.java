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
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.manager.PlaylistManager;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.WatchConfirmDialog;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Playlist detail activity - shows songs in a playlist and allows playing them.
 * Supports cloud mode (subscribe/unsubscribe via API) and local mode (local save).
 * Rules:
 * - Cloud mode: "我喜欢的音乐" → no unsub/delete; my created → no unsub, can delete;
 *   others' → can unsub, no delete.
 * - Local mode: all can unsub (local remove), no delete.
 * Designed for watch screen (320x360 dpi).
 */
public class PlaylistDetailActivity extends BaseWatchActivity {

    private ListView lvSongs;
    private final List<Song> displayList = new ArrayList<>();
    private ArrayAdapter<Song> adapter;
    private MusicPlayerManager playerManager;
    private PlaylistManager playlistManager;
    private TextView tvStatus;
    private TextView tvTitle;
    private TextView tvCreatorLabel;
    private ImageView btnFav;
    private ImageView btnDelete;
    private ImageView btnDownloadAll;
    private boolean isBatchDownloading = false;
    private long playlistId;
    private String playlistName;
    private int trackCount;
    private String creator;
    private long creatorUserId;
    private boolean isLikedPlaylist;
    private long currentUserId = -1;
    private boolean isCloudMode;
    private boolean isSubscribed = false;

    // Multi-select state
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

        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        isCloudMode = prefs.getBoolean("fav_mode_cloud", false);

        playlistId = getIntent().getLongExtra("playlist_id", 0);
        playlistName = getIntent().getStringExtra("playlist_name");
        trackCount = getIntent().getIntExtra("track_count", 0);
        creator = getIntent().getStringExtra("creator");
        creatorUserId = getIntent().getLongExtra("creator_user_id", 0);
        isLikedPlaylist = getIntent().getBooleanExtra("is_liked_playlist", false);

        playerManager = MusicPlayerManager.getInstance();
        playlistManager = new PlaylistManager();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(px(6), px(6), px(6), px(6));

        // Title row with heart + delete buttons on the right
        android.widget.RelativeLayout titleRow = new android.widget.RelativeLayout(this);
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Title
        tvTitle = new TextView(this);
        updateTitleText();
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(px(56), 0, px(56), px(4));
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.RelativeLayout.LayoutParams titleParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        tvTitle.setLayoutParams(titleParams);
        titleRow.addView(tvTitle);

        // Delete button - top right
        btnDelete = new ImageView(this);
        btnDelete.setImageResource(R.drawable.ic_delete);
        btnDelete.setPadding(px(4), px(4), px(2), px(4));
        btnDelete.setClickable(true);
        btnDelete.setFocusable(true);
        btnDelete.setId(View.generateViewId());
        android.widget.RelativeLayout.LayoutParams delParams = new android.widget.RelativeLayout.LayoutParams(
                px(26), px(28));
        delParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        delParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        btnDelete.setLayoutParams(delParams);
        btnDelete.setVisibility(View.GONE);
        btnDelete.setOnClickListener(v -> onDeletePlaylist());
        titleRow.addView(btnDelete);

        // Download all button - far left
        btnDownloadAll = new ImageView(this);
        btnDownloadAll.setImageResource(R.drawable.ic_get_app);
        btnDownloadAll.setPadding(px(2), px(4), px(4), px(4));
        btnDownloadAll.setClickable(true);
        btnDownloadAll.setFocusable(true);
        btnDownloadAll.setId(View.generateViewId());
        android.widget.RelativeLayout.LayoutParams dlParams = new android.widget.RelativeLayout.LayoutParams(
                px(26), px(28));
        dlParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        dlParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        btnDownloadAll.setLayoutParams(dlParams);
        btnDownloadAll.setOnClickListener(v -> startBatchDownload());
        titleRow.addView(btnDownloadAll);

        // Heart button - left of delete
        btnFav = new ImageView(this);
        btnFav.setPadding(px(2), px(4), px(4), px(4));
        btnFav.setClickable(true);
        btnFav.setFocusable(true);
        android.widget.RelativeLayout.LayoutParams favParams = new android.widget.RelativeLayout.LayoutParams(
                px(26), px(28));
        favParams.addRule(android.widget.RelativeLayout.LEFT_OF, btnDelete.getId());
        favParams.addRule(android.widget.RelativeLayout.RIGHT_OF, btnDownloadAll.getId());
        favParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        btnFav.setLayoutParams(favParams);
        btnFav.setOnClickListener(v -> togglePlaylistFav());
        titleRow.addView(btnFav);

        root.addView(titleRow);

        // Creator label (always created, visibility depends on data)
        tvCreatorLabel = new TextView(this);
        tvCreatorLabel.setTextColor(0xB3FFFFFF);
        tvCreatorLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        tvCreatorLabel.setGravity(Gravity.CENTER);
        tvCreatorLabel.setPadding(0, 0, 0, px(2));
        updateCreatorLabel();
        root.addView(tvCreatorLabel);

        // Status text
        tvStatus = new TextView(this);
        tvStatus.setText("正在加载...");
        tvStatus.setTextColor(0x80FFFFFF);
        tvStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, px(4), 0, px(4));
        root.addView(tvStatus);

        lvSongs = new ListView(this);
        lvSongs.setDividerHeight(1);
        lvSongs.setDivider(getResources().getDrawable(android.R.color.transparent));
        LinearLayout.LayoutParams lvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        lvSongs.setLayoutParams(lvParams);
        lvSongs.setVisibility(View.GONE);
        root.addView(lvSongs);

        // Multi-select bottom bar (hidden by default)
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
        btnDownloadSelected.setId(View.generateViewId());
        selectBar.addView(btnDownloadSelected);

        TextView btnCancel = makeBarBtn("取消", v -> exitSelectMode());
        btnCancel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        selectBar.addView(btnCancel);

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
            List<Song> playlist = new ArrayList<>(displayList);
            playerManager.setPlaylistFromSource(playlist, position,
                    playlistId, playlistName, trackCount, creator,
                    creatorUserId, isLikedPlaylist);
            playerManager.playCurrent();
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        // Long-press: enter multi-select mode (first long-pressed song auto-selected)
        // For user-created playlists that are not liked, also supports delete in non-select mode
        lvSongs.setOnItemLongClickListener((parent, view, position, id) -> {
            if (!isSelectMode) {
                enterSelectMode(position);
                return true;
            }
            // In select mode, long-press does nothing extra
            return true;
        });

        // Always get current user ID for playlist type identification (liked/created/others')
        String cookie = playerManager.getCookie();
        if (cookie != null && !cookie.isEmpty()) {
            MusicApiHelper.getUid(cookie, new MusicApiHelper.AccountCallback() {
                @Override
                public void onResult(JSONObject json) {
                    currentUserId = json.optLong("uid", -1);
                    updateActionButtons();
                }
                @Override
                public void onError(String message) {
                    updateActionButtons();
                }
            });
        }

        updateFavButton();
        updateActionButtons();

        if (playlistId > 0) {
            loadPlaylistDetail(playlistId);
        }
    }

    // ---- Multi-select methods ----

    private void enterSelectMode(int initialPosition) {
        isSelectMode = true;
        selectedPositions.clear();
        selectedPositions.add(initialPosition);
        selectBar.setVisibility(View.VISIBLE);
        tvStatus.setText("已选 1 首");
        tvTitle.setText("选择歌曲");
        tvCreatorLabel.setVisibility(View.GONE);
        btnDownloadAll.setVisibility(View.GONE);
        btnFav.setVisibility(View.GONE);
        btnDelete.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void exitSelectMode() {
        isSelectMode = false;
        selectedPositions.clear();
        selectBar.setVisibility(View.GONE);
        updateTitleText();
        updateCreatorLabel();
        tvStatus.setText(displayList.size() + " 首歌曲");
        updateActionButtons();
        adapter.notifyDataSetChanged();
    }

    private void toggleSelection(int position) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        int count = selectedPositions.size();
        tvStatus.setText("已选 " + count + " 首");
        // Update the download button text
        for (int i = 0; i < selectBar.getChildCount(); i++) {
            View child = selectBar.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getText().toString().startsWith("下载")) {
                ((TextView) child).setText("下载(" + count + ")");
                break;
            }
        }
        adapter.notifyDataSetChanged();
        // Auto-exit if nothing selected
        if (count == 0) {
            exitSelectMode();
        }
    }

    private void toggleSelectAll() {
        if (selectedPositions.size() == displayList.size()) {
            // Deselect all
            selectedPositions.clear();
        } else {
            // Select all
            for (int i = 0; i < displayList.size(); i++) {
                selectedPositions.add(i);
            }
        }
        int count = selectedPositions.size();
        tvStatus.setText(count > 0 ? "已选 " + count + " 首" : "已选 0 首");
        for (int i = 0; i < selectBar.getChildCount(); i++) {
            View child = selectBar.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getText().toString().startsWith("下载")) {
                ((TextView) child).setText("下载(" + count + ")");
                break;
            }
        }
        adapter.notifyDataSetChanged();
    }

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
        for (int pos : selectedPositions) {
            if (pos >= 0 && pos < displayList.size()) {
                selectedSongs.add(displayList.get(pos));
            }
        }
        exitSelectMode();
        isBatchDownloading = true;
        btnDownloadAll.setColorFilter(0xFFFF4081);
        tvStatus.setText("准备批量下载...");
        Toast.makeText(this, "开始下载 " + selectedSongs.size() + " 首歌曲", Toast.LENGTH_SHORT).show();

        DownloadManager.batchDownloadSongs(selectedSongs, cookie,
                new DownloadManager.BatchDownloadCallback() {
                    @Override
                    public void onProgress(int current, int total, String songName) {
                        tvStatus.setText("下载中 " + current + "/" + total + "\n" + songName);
                    }

                    @Override
                    public void onAllComplete(int successCount, int skipCount, int failCount) {
                        isBatchDownloading = false;
                        btnDownloadAll.setColorFilter(0x80FFFFFF);
                        tvStatus.setText(displayList.size() + " 首歌曲");
                        String msg = "下载完成: 成功" + successCount;
                        if (skipCount > 0) msg += " 跳过" + skipCount;
                        if (failCount > 0) msg += " 失败" + failCount;
                        Toast.makeText(PlaylistDetailActivity.this, msg, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onSingleError(String songName, String message) {
                    }
                });
    }

    private TextView makeBarBtn(String text, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(0xFFBB86FC);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(px(4), px(6), px(4), px(6));
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(listener);
        return btn;
    }

    // ---- Existing methods ----

    private void updateTitleText() {
        String titleText = playlistName != null ? playlistName : "歌单";
        if (trackCount > 0) {
            titleText += " (" + trackCount + "首)";
        }
        tvTitle.setText(titleText);
    }

    private void updateCreatorLabel() {
        if (creator != null && !creator.isEmpty()) {
            tvCreatorLabel.setText("创建者: " + creator);
            tvCreatorLabel.setVisibility(View.VISIBLE);
        } else {
            tvCreatorLabel.setVisibility(View.GONE);
        }
    }

    private void loadPlaylistDetail(long playlistId) {
        String cookie = playerManager.getCookie();
        MusicApiHelper.getPlaylistDetailWithMeta(playlistId, cookie, new MusicApiHelper.PlaylistDetailWithMetaCallback() {
            @Override
            public void onResult(List<Song> songs, int apiTrackCount, String apiCreator,
                                 long apiCreatorUserId, int specialType, boolean subscribed) {
                displayList.clear();
                displayList.addAll(songs);
                adapter.notifyDataSetChanged();

                // Update metadata from API response (self-correct for all entry points)
                trackCount = songs.size();
                if (apiCreatorUserId > 0) {
                    creatorUserId = apiCreatorUserId;
                }
                isLikedPlaylist = (specialType == 5);
                isSubscribed = subscribed;
                if (apiCreator != null && !apiCreator.isEmpty()) {
                    creator = apiCreator;
                }

                updateTitleText();
                updateCreatorLabel();
                if (songs.isEmpty()) {
                    tvStatus.setText("暂无歌曲");
                } else {
                    tvStatus.setText(songs.size() + " 首歌曲");
                    lvSongs.setVisibility(View.VISIBLE);
                }

                // Refresh buttons with corrected metadata
                updateActionButtons();
            }

            @Override
            public void onError(String message) {
                tvStatus.setText("加载失败: " + message);
            }
        });
    }

    /**
     * Update button visibility based on playlist type (always identified regardless of mode):
     *   - "我喜欢的音乐" (specialType=5) → no unsub, no delete
     *   - My created playlist → no unsub, can delete
     *   - Others' playlist → can sub/unsub (cloud) or local save/remove, no delete
     */
    private void updateActionButtons() {
        if (isSelectMode) return;
        if (isLikedPlaylist) {
            btnFav.setVisibility(View.GONE);
            btnDelete.setVisibility(View.GONE);
        } else if (creatorUserId > 0 && creatorUserId == currentUserId) {
            btnFav.setVisibility(View.GONE);
            btnDelete.setVisibility(View.VISIBLE);
        } else {
            btnFav.setVisibility(View.VISIBLE);
            btnDelete.setVisibility(View.GONE);
            updateFavButton();
        }
    }

    private void updateFavButton() {
        if (playlistId <= 0) {
            btnFav.setVisibility(View.GONE);
            return;
        }
        if (isCloudMode) {
            btnFav.setImageResource(isSubscribed ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            btnFav.setColorFilter(isSubscribed ? 0xFFFF4081 : 0x80FFFFFF);
        } else {
            boolean saved = playlistManager.isPlaylistSaved(playlistId);
            btnFav.setImageResource(saved ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            btnFav.setColorFilter(saved ? 0xFFFF4081 : 0x80FFFFFF);
        }
    }

    private void togglePlaylistFav() {
        if (playlistId <= 0) return;

        if (isCloudMode) {
            String cookie = playerManager.getCookie();
            if (cookie == null || cookie.isEmpty()) {
                Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isSubscribed) {
                Toast.makeText(this, "正在取消收藏...", Toast.LENGTH_SHORT).show();
                MusicApiHelper.subscribePlaylist(playlistId, false, cookie,
                        new MusicApiHelper.PlaylistActionCallback() {
                            @Override
                            public void onResult(boolean success) {
                                if (success) {
                                    isSubscribed = false;
                                    playlistManager.removePlaylist(playlistId);
                                    updateFavButton();
                                    Toast.makeText(PlaylistDetailActivity.this,
                                            "已取消云端收藏", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(PlaylistDetailActivity.this,
                                            "取消收藏失败", Toast.LENGTH_SHORT).show();
                                }
                            }
                            @Override
                            public void onError(String message) {
                                Toast.makeText(PlaylistDetailActivity.this,
                                        "取消收藏失败: " + message, Toast.LENGTH_SHORT).show();
                            }
                        });
            } else {
                Toast.makeText(this, "正在收藏...", Toast.LENGTH_SHORT).show();
                MusicApiHelper.subscribePlaylist(playlistId, true, cookie,
                        new MusicApiHelper.PlaylistActionCallback() {
                            @Override
                            public void onResult(boolean success) {
                                if (success) {
                                    isSubscribed = true;
                                    updateFavButton();
                                    Toast.makeText(PlaylistDetailActivity.this,
                                            "已收藏到云端", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(PlaylistDetailActivity.this,
                                            "收藏失败", Toast.LENGTH_SHORT).show();
                                }
                            }
                            @Override
                            public void onError(String message) {
                                Toast.makeText(PlaylistDetailActivity.this,
                                        "收藏失败: " + message, Toast.LENGTH_SHORT).show();
                            }
                        });
            }
        } else {
            boolean saved = playlistManager.isPlaylistSaved(playlistId);
            if (saved) {
                playlistManager.removePlaylist(playlistId);
                Toast.makeText(this, "已取消收藏歌单", Toast.LENGTH_SHORT).show();
            } else {
                PlaylistInfo info = new PlaylistInfo(playlistId,
                        playlistName != null ? playlistName : "",
                        trackCount, creator != null ? creator : "",
                        creatorUserId, true, isLikedPlaylist ? "5" : "0");
                playlistManager.addPlaylist(info);
                Toast.makeText(this, "已收藏歌单", Toast.LENGTH_SHORT).show();
            }
            updateFavButton();
        }
    }

    private void onDeletePlaylist() {
        if (playlistId <= 0) return;
        showConfirmDialog("确认删除", "确定从云端删除歌单「" + (playlistName != null ? playlistName : "") + "」？\n此操作不可恢复。", () -> {
            Toast.makeText(this, "正在删除...", Toast.LENGTH_SHORT).show();
            String cookie = playerManager.getCookie();
            MusicApiHelper.deletePlaylist(playlistId, cookie,
                    new MusicApiHelper.PlaylistActionCallback() {
                @Override
                public void onResult(boolean success) {
                    if (success) {
                        playlistManager.removePlaylist(playlistId);
                        Toast.makeText(PlaylistDetailActivity.this,
                                "已删除歌单", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(PlaylistDetailActivity.this,
                                "删除失败", Toast.LENGTH_SHORT).show();
                    }
                }
                @Override
                public void onError(String message) {
                    Toast.makeText(PlaylistDetailActivity.this,
                            "删除失败: " + message, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void startBatchDownload() {
        if (isBatchDownloading) {
            DownloadManager.cancelBatchDownload();
            isBatchDownloading = false;
            btnDownloadAll.setColorFilter(0x80FFFFFF);
            tvStatus.setText(displayList.size() + " 首歌曲");
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
        tvStatus.setText("准备批量下载...");
        Toast.makeText(this, "开始批量下载（再次点击可取消）", Toast.LENGTH_SHORT).show();

        DownloadManager.batchDownloadSongs(new ArrayList<>(displayList), cookie,
                new DownloadManager.BatchDownloadCallback() {
                    @Override
                    public void onProgress(int current, int total, String songName) {
                        tvStatus.setText("下载中 " + current + "/" + total + "\n" + songName);
                    }

                    @Override
                    public void onAllComplete(int successCount, int skipCount, int failCount) {
                        isBatchDownloading = false;
                        btnDownloadAll.setColorFilter(0x80FFFFFF);
                        tvStatus.setText(displayList.size() + " 首歌曲");
                        String msg = "下载完成: 成功" + successCount;
                        if (skipCount > 0) msg += " 跳过" + skipCount;
                        if (failCount > 0) msg += " 失败" + failCount;
                        Toast.makeText(PlaylistDetailActivity.this, msg, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onSingleError(String songName, String message) {
                    }
                });
    }

    private void showConfirmDialog(String title, String message, Runnable onConfirm) {
        WatchConfirmDialog.show(this, title, message, onConfirm,
                new WatchConfirmDialog.Options(0xFF1E1E1E, 0xFFBB86FC, true));
    }
}
