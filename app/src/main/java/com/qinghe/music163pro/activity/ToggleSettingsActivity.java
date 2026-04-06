package com.qinghe.music163pro.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.qinghe.music163pro.R;
import com.qinghe.music163pro.player.MusicPlayerManager;

/**
 * Toggle settings activity - contains boolean/toggle options:
 * - Keep screen on (SwitchMaterial)
 * - Favorites mode local/cloud (SwitchMaterial)
 * - Speed mode 3-value cyclic row (tap to cycle)
 */
public class ToggleSettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "music163_settings";

    private SwitchMaterial switchKeepScreenOn;
    private SwitchMaterial switchFavMode;
    private TextView tvSpeedModeValue;
    private SharedPreferences prefs;

    /** Suppress listener callbacks while programmatically setting switch state. */
    private boolean updatingSwitch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_toggle_settings);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Apply keep screen on setting
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        switchKeepScreenOn = findViewById(R.id.switch_keep_screen_on);
        switchFavMode = findViewById(R.id.switch_fav_mode);
        tvSpeedModeValue = findViewById(R.id.tv_speed_mode_value);
        LinearLayout rowSpeedMode = findViewById(R.id.row_speed_mode);

        // Initialise switch states from prefs
        syncSwitchStates();

        // Keep screen on: use checked-change listener
        switchKeepScreenOn.setOnCheckedChangeListener((btn, isChecked) -> {
            if (updatingSwitch) return;
            prefs.edit().putBoolean("keep_screen_on", isChecked).apply();
            if (isChecked) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });

        // Favorites mode: use checked-change listener
        switchFavMode.setOnCheckedChangeListener((btn, isChecked) -> {
            if (updatingSwitch) return;
            prefs.edit().putBoolean("fav_mode_cloud", isChecked).apply();
            String mode = isChecked ? "云端" : "本地";
            Toast.makeText(this, "收藏模式已切换为: " + mode, Toast.LENGTH_SHORT).show();
        });

        // Speed mode: tap row to cycle through 3 values
        rowSpeedMode.setOnClickListener(v -> cycleSpeedMode());
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncSwitchStates();
    }

    private void syncSwitchStates() {
        updatingSwitch = true;
        switchKeepScreenOn.setChecked(prefs.getBoolean("keep_screen_on", false));
        switchFavMode.setChecked(prefs.getBoolean("fav_mode_cloud", false));
        updatingSwitch = false;
        updateSpeedModeValue();
    }

    private void cycleSpeedMode() {
        int current = prefs.getInt("speed_mode", 0);
        int next = (current + 1) % 3;
        prefs.edit().putInt("speed_mode", next).apply();
        MusicPlayerManager.getInstance().setSpeedMode(next);
        updateSpeedModeValue();
        String[] modeNames = {"音调不变", "音调改变且速度改变", "音调改变但速度不变"};
        Toast.makeText(this, "变速模式: " + modeNames[next], Toast.LENGTH_SHORT).show();
        if (next == 2) {
            Toast.makeText(this, "注意：此模式变速幅度不能太大，否则播放可能会出问题（如有杂音等）", Toast.LENGTH_LONG).show();
        }
    }

    private void updateSpeedModeValue() {
        int mode = prefs.getInt("speed_mode", 0);
        String[] labels = {"音调不变", "音调改变且速度改变", "音调改变但速度不变"};
        tvSpeedModeValue.setText(labels[mode]);
    }
}
