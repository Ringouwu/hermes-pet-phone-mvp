package com.yangshuo.petphone;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

public class MainActivity extends Activity {
    private static final int RECORD_PERMISSION = 7;
    private enum PetState { IDLE, LISTENING, THINKING, ANSWERING, ERROR }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private Typeface pixelTypeface;
    private TextView status, answer;
    private FrameLayout liveBubble;
    private ImageView welcomeBubble;
    private Button talk, mute, music;
    private GifView pet;
    private MediaPlayer player;
    private boolean voiceEnabled = false;
    private MediaPlayer bgmPlayer;
    private boolean bgmEnabled = false;
    private int bgmPositionMs = 0;
    private MediaRecorder recorder;
    private File audioFile;
    private boolean recording = false;
    private PetState petState = PetState.IDLE;
    private int currentGif = 0;
    private final int[] idleGifs = {R.raw.idle, R.raw.idle, R.raw.jumping, R.raw.running_left, R.raw.running_right};
    private final int[] thinkingGifs = {R.raw.waiting, R.raw.review, R.raw.running};
    private final Runnable idleTicker = new Runnable() {
        @Override public void run() {
            if (petState != PetState.IDLE) return;
            showDifferentGif(idleGifs);
            main.postDelayed(this, randomBetween(5000, 10000));
        }
    };
    private final Runnable thinkingTicker = new Runnable() {
        @Override public void run() {
            if (petState != PetState.THINKING) return;
            showDifferentGif(thinkingGifs);
            main.postDelayed(this, randomBetween(3000, 5000));
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // The 12px Chinese edition contains the full common Chinese glyph set.
        // The 16px subset does not include characters such as 猫 or 鸡.
        pixelTypeface = getResources().getFont(R.font.ark_pixel_12_zh_cn);
        setContentView(makeUi());
        setPetState(PetState.IDLE);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_PERMISSION);
        }
    }

    private View makeUi() {
        FrameLayout root = new FrameLayout(this);
        ImageView background = new ImageView(this);
        background.setImageResource(R.drawable.neon_magic_room);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(background, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(18));
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(0xff4ee46c);
        dot.setBackground(dotShape);
        status = text("🐱  貓雞·在線", 18, 0xffffc46b);
        status.setPadding(0, 0, 0, dp(8));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(9), dp(9));
        dotParams.rightMargin = dp(10);
        header.addView(dot, dotParams);
        header.addView(status, new LinearLayout.LayoutParams(-2, dp(40)));
        box.addView(header, new LinearLayout.LayoutParams(-1, dp(42)));

        // The bubble is traced directly from the selected final reference, including
        // its lower, right-of-centre tail. Text remains an independent live layer.
        FrameLayout bubbleStage = new FrameLayout(this);
        welcomeBubble = new ImageView(this);
        welcomeBubble.setImageResource(R.drawable.skin_bubble_reference_initial);
        welcomeBubble.setScaleType(ImageView.ScaleType.FIT_XY);
        // The reference image contains baked-in text, so it stays hidden: every
        // visible sentence must be rendered by Android with Ark Pixel instead.
        welcomeBubble.setVisibility(View.GONE);
        bubbleStage.addView(welcomeBubble, new FrameLayout.LayoutParams(-1, -1));

        liveBubble = new FrameLayout(this);
        liveBubble.setBackgroundResource(R.drawable.skin_bubble_final_reference);
        liveBubble.setVisibility(View.VISIBLE);
        ScrollView replyScroll = new ScrollView(this);
        replyScroll.setVerticalScrollBarEnabled(false);
        answer = text("喵～我在呢。\n有什麼想聊的、想問的，\n或者需要我陪你一下嗎？", 18, 0xffffe6cf);
        answer.setGravity(Gravity.TOP | Gravity.START);
        answer.setLineSpacing(dp(9), 1.0f);
        answer.setPadding(0, 0, 0, 0);
        replyScroll.addView(answer, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams replyParams = new FrameLayout.LayoutParams(-1, -1);
        replyParams.setMargins(dp(50), dp(46), dp(40), dp(78));
        liveBubble.addView(replyScroll, replyParams);
        // Matches the selected reference's 1484 x 1131 composition.
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(-1, dp(220));
        bubbleParams.topMargin = dp(1);
        bubbleStage.addView(liveBubble, new FrameLayout.LayoutParams(-1, -1));
        box.addView(bubbleStage, bubbleParams);

        pet = new GifView(this, R.raw.idle);
        pet.setTranslationY(-dp(24));
        pet.setOnClickListener(v -> reactToPetTap());
        box.addView(pet, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER_VERTICAL); actions.setPadding(0, dp(12), 0, 0);
        talk = new Button(this); talk.setText("按住命令貓雞"); talk.setTextSize(23); talk.setAllCaps(false);
        talk.setTextColor(0xffffca70); applyPixelTypeface(talk); talk.setBackgroundResource(R.drawable.skin_main_button);
        actions.addView(talk, new LinearLayout.LayoutParams(0, dp(104), 1f));

        LinearLayout soundControls = new LinearLayout(this); soundControls.setOrientation(LinearLayout.VERTICAL); soundControls.setGravity(Gravity.CENTER_HORIZONTAL);
        music = new Button(this); music.setText("♫"); music.setTextSize(18); music.setAllCaps(false); music.setGravity(Gravity.CENTER);
        applyPixelTypeface(music); music.setBackgroundResource(R.drawable.skin_mute_button);
        soundControls.addView(music, new LinearLayout.LayoutParams(dp(58), dp(34)));
        mute = new Button(this); mute.setText("🔊"); mute.setTextSize(24); mute.setAllCaps(false);
        mute.setText(""); mute.setGravity(Gravity.CENTER); mute.setTextColor(0xffffca70); applyPixelTypeface(mute); mute.setBackgroundResource(R.drawable.skin_mute_button);
        Drawable speaker = getDrawable(R.drawable.skin_speaker);
        speaker.setBounds(0, 0, dp(42), dp(42));
        mute.setCompoundDrawables(null, speaker, null, null);
        LinearLayout.LayoutParams muteParams = new LinearLayout.LayoutParams(dp(70), dp(64));
        muteParams.topMargin = dp(6); soundControls.addView(mute, muteParams);
        LinearLayout.LayoutParams soundParams = new LinearLayout.LayoutParams(dp(76), dp(104));
        soundParams.leftMargin = dp(10); actions.addView(soundControls, soundParams);
        box.addView(actions);
        talk.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) startRecording();
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) stopRecording();
            return true;
        });
        updateVoiceButton();
        mute.setOnClickListener(v -> {
            voiceEnabled = !voiceEnabled;
            if (!voiceEnabled) stopVoice();
            updateVoiceButton();
        });
        updateMusicButton();
        music.setOnClickListener(v -> {
            bgmEnabled = !bgmEnabled;
            if (bgmEnabled) startBgm(); else pauseBgm();
            updateMusicButton();
        });
        return root;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        applyPixelTypeface(view);
        view.setPadding(0, 8, 0, 8); return view;
    }

    private void applyPixelTypeface(TextView view) {
        if (pixelTypeface != null) view.setTypeface(pixelTypeface);
        view.setPaintFlags(view.getPaintFlags() & ~Paint.ANTI_ALIAS_FLAG);
        view.setIncludeFontPadding(false);
    }

    private void showReply(String value) {
        if (welcomeBubble != null) welcomeBubble.setVisibility(View.GONE);
        if (liveBubble != null) liveBubble.setVisibility(View.VISIBLE);
        answer.setText(value);
    }

    private void updateVoiceButton() {
        Drawable icon = getDrawable(R.drawable.skin_speaker).mutate();
        icon.setBounds(0, 0, dp(42), dp(42));
        icon.setTint(voiceEnabled ? 0xff62ff9c : 0xff806c45);
        mute.setCompoundDrawables(null, icon, null, null);
        mute.setAlpha(voiceEnabled ? 1f : .72f);
        mute.setContentDescription(voiceEnabled ? "語音已開啟" : "語音已關閉");
    }

    private void updateMusicButton() {
        music.setTextColor(bgmEnabled ? 0xff62ff9c : 0xff806c45);
        music.setAlpha(bgmEnabled ? 1f : .72f);
        music.setContentDescription(bgmEnabled ? "背景音樂已開啟" : "背景音樂已關閉");
    }

    private void startBgm() {
        if (bgmPlayer != null) {
            try {
                bgmPlayer.seekTo(bgmPositionMs);
                bgmPlayer.start();
                return;
            } catch (IllegalStateException ignored) { releaseBgm(); }
        }
        bgmPlayer = MediaPlayer.create(this, R.raw.chiptune_bgm);
        if (bgmPlayer == null) { bgmEnabled = false; return; }
        bgmPlayer.setLooping(true);
        bgmPlayer.setVolume(.28f, .28f);
        bgmPlayer.setOnErrorListener((item, what, extra) -> { releaseBgm(); bgmEnabled = false; main.post(this::updateMusicButton); return true; });
        if (bgmPositionMs > 0) bgmPlayer.seekTo(bgmPositionMs);
        bgmPlayer.start();
    }

    private void pauseBgm() {
        if (bgmPlayer == null) return;
        try {
            if (bgmPlayer.isPlaying()) {
                bgmPositionMs = bgmPlayer.getCurrentPosition();
                bgmPlayer.pause();
            }
        } catch (IllegalStateException ignored) { releaseBgm(); }
    }

    private void releaseBgm() {
        if (bgmPlayer == null) return;
        try { if (bgmPlayer.isPlaying()) bgmPlayer.stop(); } catch (IllegalStateException ignored) { }
        bgmPlayer.release(); bgmPlayer = null;
        bgmPositionMs = 0;
    }

    private void handleReply(String screen, String speech) {
        showReply(screen);
        status.setText("貓雞·在線");
        setPetState(PetState.ANSWERING);
        if (voiceEnabled) requestSpeech(speech);
    }

    private void requestSpeech(String words) {
        new Thread(() -> {
            File output = new File(getCacheDir(), "maoji-reply.mp3");
            try {
                URL url = new URL(BuildConfig.GATEWAY_URL + "/v1/tts");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST"); conn.setConnectTimeout(12000); conn.setReadTimeout(90000);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.GATEWAY_TOKEN);
                conn.setDoOutput(true);
                try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                    out.write(new JSONObject().put("text", words).toString().getBytes("UTF-8"));
                }
                int response = conn.getResponseCode();
                if (response >= 400) throw new Exception("語音服務暫時不可用");
                try (InputStream input = conn.getInputStream(); FileOutputStream file = new FileOutputStream(output)) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) file.write(buffer, 0, count);
                }
                main.post(() -> playVoice(output));
            } catch (Exception e) {
                if (output.exists()) output.delete();
                main.post(() -> { if (voiceEnabled) status.setText("語音暫時無法播放"); });
            }
        }).start();
    }

    private void playVoice(File file) {
        if (!voiceEnabled) { file.delete(); return; }
        stopVoice();
        try {
            player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(item -> { if (voiceEnabled) item.start(); else stopVoice(); });
            player.setOnCompletionListener(item -> { stopVoice(); file.delete(); });
            player.setOnErrorListener((item, what, extra) -> { stopVoice(); file.delete(); return true; });
            player.prepareAsync();
        } catch (Exception e) { file.delete(); stopVoice(); }
    }

    private void stopVoice() {
        if (player == null) return;
        try { if (player.isPlaying()) player.stop(); } catch (IllegalStateException ignored) { }
        player.release(); player = null;
    }

    private GradientDrawable neonButton(boolean compact) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xd9251722);
        drawable.setCornerRadius(dp(compact ? 24 : 30));
        drawable.setStroke(dp(2), 0xffffb34f);
        return drawable;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private int randomBetween(int low, int high) { return low + random.nextInt(high - low + 1); }

    private void showGif(int gif) {
        if (pet != null) { currentGif = gif; pet.setGif(gif); }
    }

    private void showDifferentGif(int[] options) {
        int next = currentGif;
        for (int i = 0; i < 6 && next == currentGif; i++) next = options[random.nextInt(options.length)];
        showGif(next);
    }

    private void clearPetTimers() {
        main.removeCallbacks(idleTicker);
        main.removeCallbacks(thinkingTicker);
    }

    private void setPetState(PetState next) {
        clearPetTimers();
        petState = next;
        if (next == PetState.IDLE) {
            showGif(R.raw.idle);
            main.postDelayed(idleTicker, randomBetween(5000, 10000));
        } else if (next == PetState.LISTENING) {
            showGif(R.raw.review);
        } else if (next == PetState.THINKING) {
            showGif(R.raw.waiting);
            main.postDelayed(thinkingTicker, randomBetween(3000, 5000));
        } else if (next == PetState.ANSWERING) {
            showGif(R.raw.jumping);
            main.postDelayed(() -> { if (petState == PetState.ANSWERING) showGif(R.raw.waving); }, 900);
            main.postDelayed(() -> { if (petState == PetState.ANSWERING) setPetState(PetState.IDLE); }, 2900);
        } else {
            showGif(R.raw.failed);
            main.postDelayed(() -> { if (petState == PetState.ERROR) setPetState(PetState.IDLE); }, 2200);
        }
    }

    private void reactToPetTap() {
        if (petState == PetState.LISTENING) return;
        if (petState == PetState.THINKING) {
            clearPetTimers();
            showGif(R.raw.waving);
            main.postDelayed(() -> {
                if (petState == PetState.THINKING) {
                    showDifferentGif(thinkingGifs);
                    main.postDelayed(thinkingTicker, randomBetween(3000, 5000));
                }
            }, 800);
            return;
        }
        if (petState == PetState.ERROR) { showGif(R.raw.failed); return; }
        clearPetTimers();
        showGif(random.nextBoolean() ? R.raw.waving : R.raw.jumping);
        main.postDelayed(() -> { if (petState == PetState.IDLE) setPetState(PetState.IDLE); }, 1400);
    }

    private void startRecording() {
        if (recording) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_PERMISSION); return;
        }
        try {
            audioFile = new File(getCacheDir(), "pet-utterance.m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000); recorder.setAudioSamplingRate(16000);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.prepare(); recorder.start();
            recording = true; talk.setText("鬆開發送"); status.setText("正在錄音…"); showReply("我在聽。"); setPetState(PetState.LISTENING);
        } catch (Exception e) { status.setText("無法啟動錄音：" + e.getMessage()); releaseRecorder(); setPetState(PetState.ERROR); }
    }
    private void stopRecording() {
        if (!recording) return;
        recording = false; talk.setText("發送中…"); talk.setEnabled(false); status.setText("正在上傳並識別語音…"); setPetState(PetState.THINKING);
        try { recorder.stop(); releaseRecorder(); sendAudio(audioFile); }
        catch (RuntimeException e) { releaseRecorder(); talk.setEnabled(true); talk.setText("按住命令貓雞"); status.setText("錄音太短，請按住至少一秒"); setPetState(PetState.ERROR); }
    }
    private void releaseRecorder() { if (recorder != null) { recorder.reset(); recorder.release(); recorder = null; } }

    private void send(String words) {
        status.setText("貓雞正在思考…"); showReply(words); setPetState(PetState.THINKING);
        new Thread(() -> {
            try {
                URL url = new URL(BuildConfig.GATEWAY_URL + "/v1/chat");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST"); conn.setConnectTimeout(12000); conn.setReadTimeout(360000);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.GATEWAY_TOKEN);
                conn.setDoOutput(true);
                try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                    out.write(new JSONObject().put("text", words).toString().getBytes("UTF-8"));
                }
                int response = conn.getResponseCode();
                BufferedReader in = new BufferedReader(new InputStreamReader(response < 400 ? conn.getInputStream() : conn.getErrorStream()));
                StringBuilder raw = new StringBuilder(); String line;
                while ((line = in.readLine()) != null) raw.append(line);
                if (response >= 400) throw new Exception(new JSONObject(raw.toString()).optString("error", "請求失敗"));
                JSONObject result = new JSONObject(raw.toString());
                String screen = result.getString("screen"); String speech = result.optString("speech", screen);
                main.post(() -> handleReply(screen, speech));
            } catch (Exception e) {
                main.post(() -> { showReply("連線失敗：" + e.getMessage()); status.setText("請檢查 Tailscale 和 Gateway"); setPetState(PetState.ERROR); });
            }
        }).start();
    }
    private void sendAudio(File file) {
        new Thread(() -> {
            try {
                URL url = new URL(BuildConfig.GATEWAY_URL + "/v1/audio");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST"); conn.setConnectTimeout(12000); conn.setReadTimeout(360000);
                conn.setRequestProperty("Content-Type", "audio/mp4"); conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.GATEWAY_TOKEN);
                conn.setFixedLengthStreamingMode((int) file.length()); conn.setDoOutput(true);
                try (FileInputStream input = new FileInputStream(file); DataOutputStream output = new DataOutputStream(conn.getOutputStream())) {
                    byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                int response = conn.getResponseCode(); BufferedReader in = new BufferedReader(new InputStreamReader(response < 400 ? conn.getInputStream() : conn.getErrorStream()));
                StringBuilder raw = new StringBuilder(); String line; while ((line = in.readLine()) != null) raw.append(line);
                if (response >= 400) throw new Exception(new JSONObject(raw.toString()).optString("error", "識別失敗"));
                JSONObject result = new JSONObject(raw.toString()); String screen = result.getString("screen"); String speech = result.optString("speech", screen);
                main.post(() -> { talk.setEnabled(true); talk.setText("按住命令貓雞"); handleReply(screen, speech); });
            } catch (Exception e) { main.post(() -> { showReply("語音請求失敗：" + e.getMessage()); status.setText("請再試一次"); talk.setEnabled(true); talk.setText("按住命令貓雞"); setPetState(PetState.ERROR); }); }
            finally { if (file != null) file.delete(); }
        }).start();
    }
    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) { super.onRequestPermissionsResult(r,p,g); }
    @Override protected void onDestroy() { clearPetTimers(); releaseRecorder(); stopVoice(); releaseBgm(); super.onDestroy(); }
}
