package com.qinghe.music163pro;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Custom Application class.
 * Applies global display DPI scaling from user preferences.
 * The scale factor is read once when each Activity is created
 * (via attachBaseContext wrapper), so changing the setting
 * requires restarting the app to take full effect.
 */
public class MusicApp extends Application {

    private static final String TAG = "MusicApp";
    private static final String PREFS_NAME = "music163_settings";
    private static final String KEY_DPI_SCALE = "dpi_scale";

    /** Available scale options: label -> scale factor. */
    public static final Map<String, Float> SCALE_OPTIONS = new HashMap<>();
    public static final String[] SCALE_LABELS;

    static {
        SCALE_OPTIONS.put("默认", 1.0f);
        SCALE_OPTIONS.put("紧凑 (85%)", 0.85f);
        SCALE_OPTIONS.put("较小 (90%)", 0.90f);
        SCALE_OPTIONS.put("较大 (110%)", 1.10f);
        SCALE_OPTIONS.put("放大 (120%)", 1.20f);
        SCALE_OPTIONS.put("超大 (130%)", 1.30f);
        SCALE_LABELS = SCALE_OPTIONS.keySet().toArray(new String[0]);
    }

    /**
     * Wrap the base context with a scaled density configuration.
     * Called automatically by every Activity via their attachBaseContext override.
     */
    public static Context wrapWithDpiScale(Context base) {
        try {
            float scale = getDpiScale(base);
            if (scale == 1.0f) {
                return base; // No change needed
            }
            Configuration config = new Configuration(base.getResources().getConfiguration());
            int originalDpi = config.densityDpi;
            if (originalDpi <= 0) {
                // Fallback to display metrics if densityDpi is not set
                originalDpi = base.getResources().getDisplayMetrics().densityDpi;
            }
            config.densityDpi = Math.max(80, (int) (originalDpi * scale));
            Log.d(TAG, "Applying DPI scale: " + scale + "x (" + originalDpi + " -> " + config.densityDpi + ")");
            return base.createConfigurationContext(config);
        } catch (Exception e) {
            Log.w(TAG, "Failed to apply DPI scale", e);
            return base;
        }
    }

    /**
     * Read the user's DPI scale preference.
     * @return scale factor (e.g., 0.85f, 1.0f, 1.2f)
     */
    public static float getDpiScale(Context context) {
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getFloat(KEY_DPI_SCALE, 1.0f);
        } catch (Exception e) {
            return 1.0f;
        }
    }

    /**
     * Get the current scale label for display.
     * @return label string like "默认", "紧凑 (85%)", etc.
     */
    public static String getDpiScaleLabel(Context context) {
        float scale = getDpiScale(context);
        for (Map.Entry<String, Float> entry : SCALE_OPTIONS.entrySet()) {
            if (Math.abs(entry.getValue() - scale) < 0.001f) {
                return entry.getKey();
            }
        }
        return "自定义";
    }

    /**
     * Save the DPI scale preference.
     */
    public static void setDpiScale(Context context, float scale) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_DPI_SCALE, scale)
                .apply();
    }
}
