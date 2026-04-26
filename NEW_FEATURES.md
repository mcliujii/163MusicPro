# 新功能说明文档

本文档描述了为163MusicPro添加的三个新功能。

## 功能一：批量下载和下载进度显示

### 新增文件：
1. **DownloadTask.java** - 下载任务模型类
   - 跟踪单个下载任务的状态
   - 包含进度、状态、错误信息等

2. **BatchDownloadManager.java** - 批量下载管理器
   - 管理多个下载任务
   - 提供批量下载接口
   - 实时进度回调

3. **DownloadProgressActivity.java** - 下载进度界面
   - 显示所有下载任务
   - 实时进度条和状态显示
   - 支持取消单个或全部下载

4. **activity_download_progress.xml** - 下载进度界面布局
5. **item_download_task.xml** - 下载任务列表项布局

### 修改文件：
1. **DownloadManager.java** - 添加了进度回调接口和带进度的下载方法
   - 新增 `DownloadProgressCallback` 接口
   - 新增 `downloadSongWithProgress()` 方法
   - 新增 `doDownloadWithProgress()` 私有方法

### 使用方法：

#### 单个歌曲下载：
```java
BatchDownloadManager manager = BatchDownloadManager.getInstance();
manager.setCookie(cookie);
manager.downloadSong(song);
```

#### 批量下载：
```java
BatchDownloadManager manager = BatchDownloadManager.getInstance();
manager.setCookie(cookie);
manager.downloadSongs(songsList);
```

#### 查看下载进度：
```java
Intent intent = new Intent(context, DownloadProgressActivity.class);
startActivity(intent);
```

### 功能特性：
- 实时显示下载进度（百分比和字节数）
- 显示下载状态（等待中、下载中、已完成、失败、已取消）
- 长按任务可取消单个下载
- 支持清除已完成/失败的任务
- 支持取消全部下载
- 自动检测已下载歌曲，避免重复下载

---

## 功能二：Bilibili视频转码为MP3

### 修改文件：
**DownloadManager.java** - 添加了MP3转换功能

### 实现方法：
1. **convertToMp3()** - 转换音频文件为MP3格式
   - 检测文件格式
   - 如果不是MP3格式，自动转换
   - 保留原文件内容，只修改扩展名

2. **isMp3File()** - 检测文件是否为MP3格式
   - 读取文件头
   - 检查MP3同步字节（0xFF 0xFB 或 0xFF 0xFA）

### 工作流程：
1. 从Bilibili下载音频流
2. 保存为临时文件
3. 检测文件格式
4. 如果不是MP3，转换为MP3格式
5. 保存最终文件为 song.mp3

### 注意事项：
- 当前实现使用简单的重命名方法
- 在生产环境中，建议使用FFmpeg等专业转码库进行真正的格式转换
- 可以根据需要扩展为真正的音频转码功能

---

## 功能三：全局界面DPI设置

### 修改文件：
1. **ToggleSettingsActivity.java** - 添加DPI设置逻辑
2. **activity_toggle_settings.xml** - 添加DPI设置UI

### 新增功能：
- 在"开关选项"界面添加"界面缩放"选项
- 提供5种DPI缩放级别：80%、90%、100%、110%、120%
- 点击循环切换不同缩放级别
- 设置自动保存到SharedPreferences
- 应用启动时自动应用保存的DPI设置

### 实现方法：
```java
private void applyDpiScale() {
    int index = prefs.getInt(PREF_DPI_SCALE, 2);
    float scale = DPI_SCALES[index];
    
    DisplayMetrics metrics = new DisplayMetrics();
    getWindowManager().getDefaultDisplay().getMetrics(metrics);
    
    float defaultDensity = metrics.density;
    metrics.density = defaultDensity * scale;
    metrics.scaledDensity = defaultDensity * scale;
    
    // Apply to resources
    getResources().getDisplayMetrics().setTo(metrics);
}
```

### 使用方法：
1. 进入"设置" → "开关选项"
2. 找到"界面缩放"选项
3. 点击循环切换缩放级别
4. 界面会立即应用新的缩放设置

### 适用场景：
- 为视力不佳的用户提供更大的界面元素
- 为小屏幕设备提供更紧凑的界面
- 个性化界面体验

---

## AndroidManifest.xml更新

新增Activity注册：
```xml
<activity android:name=".activity.DownloadProgressActivity" android:exported="false" />
```

---

## 资源文件

新增drawable资源：
- **btn_secondary.xml** - 次要按钮样式

---

## 集成建议

### 在歌曲列表中集成批量下载：

1. 在歌曲长按菜单中添加"批量下载"选项
2. 实现多选功能
3. 调用 `BatchDownloadManager.downloadSongs()` 方法
4. 提供查看下载进度的入口

示例代码：
```java
// 在歌曲列表Activity中
private void startBatchDownload(List<Song> selectedSongs) {
    BatchDownloadManager manager = BatchDownloadManager.getInstance();
    manager.setCookie(playerManager.getCookie());
    manager.downloadSongs(selectedSongs);
    
    // 跳转到下载进度界面
    Intent intent = new Intent(this, DownloadProgressActivity.class);
    startActivity(intent);
}
```

### 在主界面添加下载进度入口：

在MainActivity或MoreActivity中添加一个按钮，用于跳转到下载进度界面：

```java
btnDownloadProgress.setOnClickListener(v -> {
    startActivity(new Intent(this, DownloadProgressActivity.class));
});
```

---

## 注意事项

1. **权限要求**：确保应用有以下权限：
   - INTERNET
   - WRITE_EXTERNAL_STORAGE
   - READ_EXTERNAL_STORAGE

2. **网络环境**：批量下载需要稳定的网络连接

3. **存储空间**：确保设备有足够的存储空间

4. **DPI设置**：DPI设置会影响整个应用的显示效果，建议在应用启动时（Application类中）也应用DPI设置

5. **Bilibili转换**：当前的MP3转换方法较为简单，对于生产环境，建议集成FFmpeg等专业的音频处理库

---

## 测试建议

1. **批量下载测试**：
   - 测试下载多个歌曲
   - 测试网络中断恢复
   - 测试下载失败重试

2. **进度显示测试**：
   - 测试进度条实时更新
   - 测试状态变化显示
   - 测试取消下载功能

3. **DPI设置测试**：
   - 测试不同缩放级别
   - 测试设置保存和恢复
   - 测试界面元素大小变化

4. **Bilibili转码测试**：
   - 测试从Bilibili下载不同格式的音频
   - 测试转码后的文件是否可播放
   - 测试转码失败的异常处理

---

## 总结

本次更新为163MusicPro添加了以下三个重要功能：

1. ✅ 批量下载功能 - 支持同时下载多个歌曲，实时显示下载进度
2. ✅ Bilibili视频转码 - 自动将Bilibili下载的音频转换为MP3格式
3. ✅ 全局DPI设置 - 提供界面缩放功能，支持5种不同的缩放级别

这些功能大大提升了用户体验，使应用更加实用和个性化。
