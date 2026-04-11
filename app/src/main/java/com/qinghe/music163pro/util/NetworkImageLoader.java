package com.qinghe.music163pro.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal network image loader for watch UI covers.
 */
public final class NetworkImageLoader {

    private static final String TAG = "NetworkImageLoader";
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> MEMORY_CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 16)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value != null ? value.getByteCount() : 0;
                }
            };

    private NetworkImageLoader() {
    }

    public static void load(ImageView imageView, String imageUrl) {
        if (imageView == null) {
            return;
        }
        imageView.setTag(imageUrl);
        imageView.setImageDrawable(null);
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            return;
        }
        Bitmap cached = MEMORY_CACHE.get(imageUrl);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }
        EXECUTOR.execute(() -> {
            Bitmap bitmap = downloadBitmap(imageUrl);
            if (bitmap != null) {
                MEMORY_CACHE.put(imageUrl, bitmap);
            }
            imageView.post(() -> {
                Object tag = imageView.getTag();
                if (bitmap != null && imageUrl.equals(tag)) {
                    imageView.setImageBitmap(bitmap);
                }
            });
        });
    }

    private static Bitmap downloadBitmap(String imageUrl) {
        HttpURLConnection conn = null;
        InputStream inputStream = null;
        try {
            URL url = new URL(imageUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 8.1.0) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
            conn.connect();
            inputStream = conn.getInputStream();
            return BitmapFactory.decodeStream(inputStream);
        } catch (Exception e) {
            MusicLog.w(TAG, "加载图片失败: " + imageUrl, e);
            return null;
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
