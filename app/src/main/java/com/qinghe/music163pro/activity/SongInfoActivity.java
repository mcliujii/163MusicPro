package com.qinghe.music163pro.activity;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.qinghe.music163pro.api.MusicApiHelper;

import org.json.JSONArray;
import org.json.JSONObject;

public class SongInfoActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "music163_settings";

    private static final int COLOR_BG = 0xFF212121;
    private static final int COLOR_TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int COLOR_TEXT_SECONDARY = 0xFF888888;
    private static final int COLOR_TEXT_DESC = 0xFFCCCCCC;
    private static final int COLOR_ACCENT = 0xFFFF5252;
    private static final int COLOR_DIVIDER = 0xFF424242;

    private long songId;
    private String songName;
    private String artistName;
    private long artistId;
    private String cookie;

    private LinearLayout contentLayout;
    private TextView tvLoading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        songId = getIntent().getLongExtra("song_id", 0);
        songName = getIntent().getStringExtra("song_name");
        artistName = getIntent().getStringExtra("artist_name");
        artistId = getIntent().getLongExtra("artist_id", 0);
        cookie = getIntent().getStringExtra("cookie");

        buildUi();
        fetchSongWiki();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_BG);
        scrollView.setFillViewport(true);

        contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(px(12), px(10), px(12), px(16));
        scrollView.addView(contentLayout);

        // Title
        TextView tvTitle = makeText("音乐信息", COLOR_TEXT_PRIMARY, px(18), true, Gravity.CENTER);
        contentLayout.addView(tvTitle);
        contentLayout.addView(makeSpacer(px(10)));

        // Song section header
        contentLayout.addView(makeText("\uD83C\uDFB5 歌曲信息", COLOR_ACCENT, px(16), true, Gravity.START));
        contentLayout.addView(makeSpacer(px(6)));

        // Basic song info from intent extras
        if (songName != null && !songName.isEmpty()) {
            contentLayout.addView(makeText(songName, COLOR_TEXT_PRIMARY, px(18), true, Gravity.START));
            contentLayout.addView(makeSpacer(px(4)));
        }
        if (artistName != null && !artistName.isEmpty()) {
            contentLayout.addView(makeDetailRow("歌手", artistName));
        }

        contentLayout.addView(makeSpacer(px(8)));

        // Loading indicator
        tvLoading = makeText("加载中...", COLOR_TEXT_SECONDARY, px(14), false, Gravity.CENTER);
        contentLayout.addView(tvLoading);

        setContentView(scrollView);
    }

    private void fetchSongWiki() {
        MusicApiHelper.getSongWikiSummary(songId, cookie, new MusicApiHelper.SongWikiCallback() {
            @Override
            public void onResult(JSONObject wikiJson) {
                contentLayout.removeView(tvLoading);
                parseSongWiki(wikiJson);
            }

            @Override
            public void onError(String message) {
                tvLoading.setText("歌曲百科加载失败");
                // Still try to load artist info if we have an ID
                if (artistId > 0) {
                    addDividerAndArtistSection();
                    fetchArtistDesc(artistId);
                }
            }
        });
    }

    private void parseSongWiki(JSONObject wikiJson) {
        try {
            if (wikiJson.optInt("code") != 200) {
                contentLayout.addView(makeText("暂无歌曲百科信息", COLOR_TEXT_SECONDARY, px(14), false, Gravity.START));
                if (artistId > 0) {
                    addDividerAndArtistSection();
                    fetchArtistDesc(artistId);
                }
                return;
            }

            JSONObject data = wikiJson.optJSONObject("data");
            if (data == null) {
                if (artistId > 0) {
                    addDividerAndArtistSection();
                    fetchArtistDesc(artistId);
                }
                return;
            }

            JSONArray blocks = data.optJSONArray("blocks");
            if (blocks == null) {
                if (artistId > 0) {
                    addDividerAndArtistSection();
                    fetchArtistDesc(artistId);
                }
                return;
            }

            long extractedArtistId = 0;
            String albumName = null;
            String songDesc = null;
            String artistDescFromWiki = null;

            for (int i = 0; i < blocks.length(); i++) {
                JSONObject block = blocks.optJSONObject(i);
                if (block == null) continue;

                String blockCode = block.optString("blockCode", "");

                if ("SONG_PLAY_ABOUT_SONG_BLOCK".equals(blockCode)) {
                    JSONArray creatives = block.optJSONArray("creatives");
                    if (creatives != null && creatives.length() > 0) {
                        JSONObject creative = creatives.optJSONObject(0);
                        if (creative != null) {
                            JSONArray resources = creative.optJSONArray("resources");
                            if (resources != null && resources.length() > 0) {
                                JSONObject resource = resources.optJSONObject(0);
                                if (resource != null) {
                                    JSONObject resInfo = resource.optJSONObject("resourceInfo");
                                    if (resInfo != null) {
                                        // Extract album name
                                        JSONObject album = resInfo.optJSONObject("album");
                                        if (album != null) {
                                            albumName = album.optString("name", null);
                                        }
                                        // Extract artist ID
                                        JSONArray artists = resInfo.optJSONArray("artist");
                                        if (artists != null && artists.length() > 0) {
                                            JSONObject firstArtist = artists.optJSONObject(0);
                                            if (firstArtist != null) {
                                                extractedArtistId = firstArtist.optLong("id", 0);
                                            }
                                        }
                                    }
                                }
                            }
                            // Check for description text in creative
                            songDesc = creative.optString("desc", null);
                        }
                    }
                } else if ("SONG_PLAY_ABOUT_ARTIST_BLOCK".equals(blockCode)) {
                    JSONArray creatives = block.optJSONArray("creatives");
                    if (creatives != null && creatives.length() > 0) {
                        JSONObject creative = creatives.optJSONObject(0);
                        if (creative != null) {
                            JSONArray resources = creative.optJSONArray("resources");
                            if (resources != null && resources.length() > 0) {
                                JSONObject resource = resources.optJSONObject(0);
                                if (resource != null) {
                                    JSONObject resInfo = resource.optJSONObject("resourceInfo");
                                    if (resInfo != null) {
                                        artistDescFromWiki = resInfo.optString("desc", null);
                                        if (extractedArtistId == 0) {
                                            extractedArtistId = resInfo.optLong("id", 0);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Display album name if found
            if (albumName != null && !albumName.isEmpty()) {
                contentLayout.addView(makeDetailRow("专辑", albumName));
            }

            // Display song description if found
            if (songDesc != null && !songDesc.isEmpty()) {
                contentLayout.addView(makeSpacer(px(6)));
                contentLayout.addView(makeText(songDesc, COLOR_TEXT_DESC, px(14), false, Gravity.START));
            }

            // Determine artist ID: prefer intent extra, fall back to wiki extraction
            if (artistId <= 0 && extractedArtistId > 0) {
                artistId = extractedArtistId;
            }

            // Add artist section
            addDividerAndArtistSection();

            if (artistDescFromWiki != null && !artistDescFromWiki.isEmpty()) {
                contentLayout.addView(makeSpacer(px(4)));
                contentLayout.addView(makeText(artistDescFromWiki, COLOR_TEXT_DESC, px(14), false, Gravity.START));
            }

            // Fetch detailed artist info if we have an ID
            if (artistId > 0) {
                fetchArtistDesc(artistId);
            }

        } catch (Exception e) {
            contentLayout.addView(makeText("解析歌曲信息失败", COLOR_TEXT_SECONDARY, px(14), false, Gravity.START));
        }
    }

    private void addDividerAndArtistSection() {
        contentLayout.addView(makeSpacer(px(10)));
        contentLayout.addView(makeDivider());
        contentLayout.addView(makeSpacer(px(10)));

        contentLayout.addView(makeText("\uD83C\uDFA4 歌手信息", COLOR_ACCENT, px(16), true, Gravity.START));
        contentLayout.addView(makeSpacer(px(6)));

        if (artistName != null && !artistName.isEmpty()) {
            contentLayout.addView(makeText(artistName, COLOR_TEXT_PRIMARY, px(16), true, Gravity.START));
        }
    }

    private void fetchArtistDesc(long id) {
        final TextView tvArtistLoading = makeText("加载歌手详情...", COLOR_TEXT_SECONDARY, px(14), false, Gravity.START);
        contentLayout.addView(makeSpacer(px(4)));
        contentLayout.addView(tvArtistLoading);

        MusicApiHelper.getArtistDesc(id, cookie, new MusicApiHelper.ArtistDescCallback() {
            @Override
            public void onResult(String briefDesc, JSONArray introduction) {
                contentLayout.removeView(tvArtistLoading);
                displayArtistDesc(briefDesc, introduction);
            }

            @Override
            public void onError(String message) {
                tvArtistLoading.setText("歌手详情加载失败");
            }
        });
    }

    private void displayArtistDesc(String briefDesc, JSONArray introduction) {
        if (briefDesc != null && !briefDesc.isEmpty()) {
            contentLayout.addView(makeSpacer(px(6)));
            contentLayout.addView(makeText(briefDesc, COLOR_TEXT_DESC, px(14), false, Gravity.START));
        }

        if (introduction != null && introduction.length() > 0) {
            for (int i = 0; i < introduction.length(); i++) {
                JSONObject section = introduction.optJSONObject(i);
                if (section == null) continue;

                String ti = section.optString("ti", "");
                String txt = section.optString("txt", "");

                if (!ti.isEmpty()) {
                    contentLayout.addView(makeSpacer(px(8)));
                    contentLayout.addView(makeText(ti, COLOR_TEXT_PRIMARY, px(15), true, Gravity.START));
                }
                if (!txt.isEmpty()) {
                    contentLayout.addView(makeSpacer(px(4)));
                    contentLayout.addView(makeText(txt, COLOR_TEXT_DESC, px(14), false, Gravity.START));
                }
            }
        }

        // Bottom padding
        contentLayout.addView(makeSpacer(px(16)));
    }

    // ── UI helpers ──────────────────────────────────────────────────────

    private TextView makeText(String text, int color, int sizePx, boolean bold, int gravity) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        if (bold) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setGravity(gravity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(params);
        return tv;
    }

    private LinearLayout makeDetailRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label + "：");
        tvLabel.setTextColor(COLOR_TEXT_SECONDARY);
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextColor(COLOR_TEXT_PRIMARY);
        tvValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));
        tvValue.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(tvLabel);
        row.addView(tvValue);
        return row;
    }

    private View makeSpacer(int heightPx) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        return spacer;
    }

    private View makeDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(COLOR_DIVIDER);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(1)));
        return divider;
    }

    private int px(int baseValue) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return (int) (baseValue * screenWidth / 320f + 0.5f);
    }
}
