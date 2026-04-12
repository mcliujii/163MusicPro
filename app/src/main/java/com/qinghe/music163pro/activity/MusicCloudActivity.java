package com.qinghe.music163pro.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.CloudItem;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.WatchConfirmDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Music cloud screen.
 */
public class MusicCloudActivity extends BaseWatchActivity {

    private static final int REQUEST_PICK_UPLOAD = 2001;

    private final List<CloudItem> musicItems = new ArrayList<>();
    private final List<CloudItem> fileItems = new ArrayList<>();
    private final List<CloudItem> displayItems = new ArrayList<>();

    private ArrayAdapter<CloudItem> adapter;
    private TextView tvStatus;
    private TextView tabMusic;
    private TextView tabFiles;
    private boolean showingMusicTab = true;
    private boolean uploadAsMusic = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music_cloud);

        tabMusic = findViewById(R.id.tab_music);
        tabFiles = findViewById(R.id.tab_files);
        tvStatus = findViewById(R.id.tv_cloud_status);
        ListView listView = findViewById(R.id.lv_cloud_items);
        View btnAdd = findViewById(R.id.btn_add_cloud);

        adapter = new ArrayAdapter<CloudItem>(this, R.layout.item_cloud_entry, R.id.tv_cloud_name, displayItems) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView;
                if (view == null) {
                    view = getLayoutInflater().inflate(R.layout.item_cloud_entry, parent, false);
                }
                CloudItem item = getItem(position);
                if (item != null) {
                    ImageView icon = view.findViewById(R.id.iv_cloud_icon);
                    TextView name = view.findViewById(R.id.tv_cloud_name);
                    TextView detail = view.findViewById(R.id.tv_cloud_detail);
                    icon.setImageResource(item.isMusic() ? R.drawable.ic_music_note : R.drawable.ic_insert_drive_file);
                    icon.setColorFilter(item.isMusic() ? 0xFFBB86FC : 0x80FFFFFF);
                    name.setText(item.getDisplayName());
                    detail.setText(item.getSubtitle());
                }
                return view;
            }
        };
        listView.setAdapter(adapter);

        tabMusic.setOnClickListener(v -> switchTab(true));
        tabFiles.setOnClickListener(v -> switchTab(false));
        btnAdd.setOnClickListener(v -> showUploadTypeDialog());
        listView.setOnItemClickListener((parent, view, position, id) -> onCloudItemClick(displayItems.get(position)));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            CloudItem item = displayItems.get(position);
            showConfirmDialog("确认删除", "确定删除「" + item.getDisplayName() + "」？", () -> deleteCloudItem(item));
            return true;
        });

        switchTab(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCloudItems();
    }

    private void loadCloudItems() {
        String cookie = MusicPlayerManager.getInstance().getCookie();
        if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
            tvStatus.setText("请先登录");
            displayItems.clear();
            adapter.notifyDataSetChanged();
            return;
        }
        tvStatus.setText("正在加载...");
        MusicApiHelper.getCloudItems(cookie, new MusicApiHelper.CloudItemsCallback() {
            @Override
            public void onResult(List<CloudItem> items) {
                musicItems.clear();
                fileItems.clear();
                for (CloudItem item : items) {
                    if (item.isMusic()) {
                        musicItems.add(item);
                    } else {
                        fileItems.add(item);
                    }
                }
                applyCurrentTab();
            }

            @Override
            public void onError(String message) {
                tvStatus.setText("加载失败: " + message);
            }
        });
    }

    private void switchTab(boolean music) {
        showingMusicTab = music;
        tabMusic.setTextColor(getResources().getColor(music ? R.color.text_primary : R.color.text_secondary));
        tabMusic.setBackgroundColor(music ? 0x332196F3 : 0x00000000);
        tabFiles.setTextColor(getResources().getColor(music ? R.color.text_secondary : R.color.text_primary));
        tabFiles.setBackgroundColor(music ? 0x00000000 : 0x332196F3);
        applyCurrentTab();
    }

    private void applyCurrentTab() {
        displayItems.clear();
        displayItems.addAll(showingMusicTab ? musicItems : fileItems);
        adapter.notifyDataSetChanged();
        if (displayItems.isEmpty()) {
            tvStatus.setText(showingMusicTab ? "暂无云盘音乐" : "暂无云盘文件");
        } else {
            tvStatus.setText(displayItems.size() + (showingMusicTab ? " 首音乐" : " 个文件"));
        }
    }

    private void onCloudItemClick(CloudItem item) {
        if (item.isMusic()) {
            playCloudMusic(item);
        } else {
            downloadCloudFile(item);
        }
    }

    private void playCloudMusic(CloudItem item) {
        int index = musicItems.indexOf(item);
        if (index < 0) {
            index = 0;
        }
        ArrayList<Song> playlist = new ArrayList<>();
        for (CloudItem cloudItem : musicItems) {
            playlist.add(cloudItem.toSong());
        }
        MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
        playerManager.setPlaylist(playlist, index);
        playerManager.playCurrent();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void downloadCloudFile(CloudItem item) {
        String cookie = MusicPlayerManager.getInstance().getCookie();
        if (item.getDownloadUrl() != null && !item.getDownloadUrl().isEmpty()) {
            doDownload(item.getFileName() != null && !item.getFileName().isEmpty()
                    ? item.getFileName() : item.getDisplayName(), item.getDownloadUrl());
            return;
        }
        Toast.makeText(this, "正在获取下载链接...", Toast.LENGTH_SHORT).show();
        MusicApiHelper.getCloudItemDetail(item.getCloudSongId(), cookie, new MusicApiHelper.CloudItemCallback() {
            @Override
            public void onResult(CloudItem detail) {
                if (detail.getDownloadUrl() == null || detail.getDownloadUrl().isEmpty()) {
                    Toast.makeText(MusicCloudActivity.this, "文件暂无下载链接", Toast.LENGTH_SHORT).show();
                    return;
                }
                doDownload(item.getFileName() != null && !item.getFileName().isEmpty()
                        ? item.getFileName() : item.getDisplayName(), detail.getDownloadUrl());
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MusicCloudActivity.this, "获取失败: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void doDownload(String name, String url) {
        Toast.makeText(this, "开始下载...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "Download");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建下载目录");
                }
                String safeName = sanitizeFileName(name);
                if (!safeName.contains(".")) {
                    safeName += ".bin";
                }
                File outputFile = new File(dir, safeName);
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                try (InputStream is = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    fos.flush();
                }
                runOnUiThread(() -> Toast.makeText(this,
                        "已下载到 " + outputFile.getAbsolutePath(), Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "下载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }

    private void deleteCloudItem(CloudItem item) {
        String cookie = MusicPlayerManager.getInstance().getCookie();
        if (cookie == null || cookie.isEmpty()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在删除...", Toast.LENGTH_SHORT).show();
        MusicApiHelper.deleteCloudItem(item.getCloudSongId(), cookie, new MusicApiHelper.PlaylistActionCallback() {
            @Override
            public void onResult(boolean success) {
                if (success) {
                    musicItems.remove(item);
                    fileItems.remove(item);
                    applyCurrentTab();
                    Toast.makeText(MusicCloudActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MusicCloudActivity.this, "删除失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MusicCloudActivity.this, "删除失败: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showUploadTypeDialog() {
        new AlertDialog.Builder(this)
                .setTitle("上传到云盘")
                .setItems(new String[]{"音乐", "文件"}, (dialog, which) -> {
                    uploadAsMusic = which == 0;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_PICK_UPLOAD);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_UPLOAD || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            Toast.makeText(this, "未选择文件", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, CloudUploadProgressActivity.class);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra("upload_name", queryDisplayName(uri));
        intent.putExtra("upload_is_music", uploadAsMusic);
        startActivity(intent);
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "upload.bin";
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "cloud_file.bin";
        }
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void showConfirmDialog(String title, String message, Runnable onConfirm) {
        WatchConfirmDialog.show(this, title, message, onConfirm,
                new WatchConfirmDialog.Options(0xFF424242, 0xFFBB86FC, true));
    }
}
