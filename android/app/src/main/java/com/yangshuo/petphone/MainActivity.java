package com.yangshuo.petphone;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
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
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
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
    private Button talk, mute;
    private GifView pet;
    private TextToSpeech tts;
    private boolean muted = false;
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
        // zpix does not contain the full Chinese glyph set on Android; use the system mono fallback
        // so dynamic Hermes replies always remain readable.
        pixelTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
        setContentView(makeUi());
        setPetState(PetState.IDLE);
        tts = new TextToSpeech(this, code -> main.post(() -> { if (tts != null) tts.setLanguage(Locale.SIMPLIFIED_CHINESE); }));
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
        status = text("🐱  猫鸡·在线", 20, 0xffffc46b);
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
        bubbleStage.addView(welcomeBubble, new FrameLayout.LayoutParams(-1, -1));

        liveBubble = new FrameLayout(this);
        liveBubble.setBackgroundResource(R.drawable.skin_bubble_final_reference);
        liveBubble.setVisibility(View.INVISIBLE);
        ScrollView replyScroll = new ScrollView(this);
        replyScroll.setVerticalScrollBarEnabled(false);
        answer = text("喵～我在呢。\n有什么想聊的、想问的，\n或者需要我陪你一下吗？", 20, 0xffffe6cf);
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
        talk = new Button(this); talk.setText("按住命令猫鸡"); talk.setTextSize(22); talk.setAllCaps(false);
        talk.setTextColor(0xffffca70); talk.setTypeface(pixelTypeface); talk.setBackgroundResource(R.drawable.skin_main_button);
        actions.addView(talk, new LinearLayout.LayoutParams(0, dp(76), 1f));
        mute = new Button(this); mute.setText("🔊"); mute.setTextSize(24); mute.setAllCaps(false);
        mute.setText(""); mute.setGravity(Gravity.CENTER); mute.setTextColor(0xffffca70); mute.setTypeface(pixelTypeface); mute.setBackgroundResource(R.drawable.skin_mute_button);
        Drawable speaker = getDrawable(R.drawable.skin_speaker);
        speaker.setBounds(0, 0, dp(42), dp(42));
        mute.setCompoundDrawables(null, speaker, null, null);
        LinearLayout.LayoutParams muteParams = new LinearLayout.LayoutParams(dp(76), dp(76));
        muteParams.leftMargin = dp(10); actions.addView(mute, muteParams);
        box.addView(actions);
        talk.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) startRecording();
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) stopRecording();
            return true;
        });
        mute.setOnClickListener(v -> { muted = !muted; mute.setText(muted ? "🔇" : "🔊"); if (muted) tts.stop(); });
        return root;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (pixelTypeface != null) view.setTypeface(pixelTypeface);
        view.setPadding(0, 8, 0, 8); return view;
    }

    private void showReply(String value) {
        if (welcomeBubble != null) welcomeBubble.setVisibility(View.GONE);
        if (liveBubble != null) liveBubble.setVisibility(View.VISIBLE);
        answer.setText(value);
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
            recording = true; talk.setText("松开发送"); status.setText("正在录音…"); showReply("我在听。"); setPetState(PetState.LISTENING);
        } catch (Exception e) { status.setText("无法启动录音：" + e.getMessage()); releaseRecorder(); setPetState(PetState.ERROR); }
    }
    private void stopRecording() {
        if (!recording) return;
        recording = false; talk.setText("发送中…"); talk.setEnabled(false); status.setText("正在上传并识别语音…"); setPetState(PetState.THINKING);
        try { recorder.stop(); releaseRecorder(); sendAudio(audioFile); }
        catch (RuntimeException e) { releaseRecorder(); talk.setEnabled(true); talk.setText("按住命令猫鸡"); status.setText("录音太短，请按住至少一秒"); setPetState(PetState.ERROR); }
    }
    private void releaseRecorder() { if (recorder != null) { recorder.reset(); recorder.release(); recorder = null; } }

    private void send(String words) {
        status.setText("猫鸡在思考…"); showReply(words); setPetState(PetState.THINKING);
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
                if (response >= 400) throw new Exception(new JSONObject(raw.toString()).optString("error", "请求失败"));
                JSONObject result = new JSONObject(raw.toString());
                String screen = result.getString("screen"); String speech = result.optString("speech", screen);
                main.post(() -> { showReply(screen); status.setText("猫鸡·在线"); setPetState(PetState.ANSWERING); if (!muted) tts.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "pet-answer"); });
            } catch (Exception e) {
                main.post(() -> { showReply("连接失败：" + e.getMessage()); status.setText("请检查 Tailscale 和 Gateway"); setPetState(PetState.ERROR); });
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
                if (response >= 400) throw new Exception(new JSONObject(raw.toString()).optString("error", "识别失败"));
                JSONObject result = new JSONObject(raw.toString()); String screen = result.getString("screen"); String speech = result.optString("speech", screen);
                main.post(() -> { showReply(screen); status.setText("猫鸡·在线"); talk.setEnabled(true); talk.setText("按住命令猫鸡"); setPetState(PetState.ANSWERING); if (!muted) tts.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "pet-answer"); });
            } catch (Exception e) { main.post(() -> { showReply("语音请求失败：" + e.getMessage()); status.setText("请再试一次"); talk.setEnabled(true); talk.setText("按住命令猫鸡"); setPetState(PetState.ERROR); }); }
            finally { if (file != null) file.delete(); }
        }).start();
    }
    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) { super.onRequestPermissionsResult(r,p,g); }
    @Override protected void onDestroy() { clearPetTimers(); releaseRecorder(); if (tts != null) tts.shutdown(); super.onDestroy(); }
}
