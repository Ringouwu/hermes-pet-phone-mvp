package com.yangshuo.petphone;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Movie;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.MotionEvent;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
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
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int RECORD_PERMISSION = 7;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status, answer;
    private Button talk, mute;
    private TextToSpeech tts;
    private boolean muted = false;
    private MediaRecorder recorder;
    private File audioFile;
    private boolean recording = false;
    private TextView clock, calendar;
    private final Runnable clockTicker = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            if (clock != null) clock.setText(new SimpleDateFormat("HH:mm", Locale.CHINA).format(now));
            if (calendar != null) calendar.setText(new SimpleDateFormat("M月d日  EEEE", Locale.CHINA).format(now));
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(makeUi());
        clockTicker.run();
        tts = new TextToSpeech(this, code -> main.post(() -> { if (tts != null) tts.setLanguage(Locale.SIMPLIFIED_CHINESE); }));
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_PERMISSION);
        }
    }

    private View makeUi() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(14), dp(20), dp(18));
        box.setBackgroundColor(0xff15110d);

        status = text("猫鸡·在线", 18, 0xffd7b785);
        status.setPadding(0, 0, 0, dp(8));
        box.addView(status, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout dashboard = new LinearLayout(this);
        dashboard.setOrientation(LinearLayout.HORIZONTAL);
        dashboard.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        TextView nowLabel = text("现在", 13, 0xffa69886);
        clock = text("--:--", 40, 0xfff5eee3);
        calendar = text("加载日期…", 15, 0xffd7b785);
        TextView hint = text("按住说话\n松开就发送", 13, 0xffa69886);
        hint.setPadding(0, dp(18), 0, 0);
        info.addView(nowLabel);
        info.addView(clock);
        info.addView(calendar);
        info.addView(hint);
        dashboard.addView(info, new LinearLayout.LayoutParams(0, -1, 0.78f));
        GifView pet = new GifView(this, com.yangshuo.petphone.R.raw.idle);
        dashboard.addView(pet, new LinearLayout.LayoutParams(0, -1, 1.22f));
        box.addView(dashboard, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView replyLabel = text("猫鸡说", 14, 0xffd7b785);
        replyLabel.setPadding(0, dp(6), 0, 0);
        box.addView(replyLabel, new LinearLayout.LayoutParams(-1, dp(32)));
        ScrollView replyScroll = new ScrollView(this);
        answer = text("按住下方按钮，说一句话。", 18, 0xfff5eee3);
        answer.setGravity(Gravity.TOP | Gravity.START);
        answer.setLineSpacing(dp(4), 1.05f);
        answer.setPadding(dp(2), dp(6), dp(2), dp(6));
        replyScroll.addView(answer, new ScrollView.LayoutParams(-1, -2));
        box.addView(replyScroll, new LinearLayout.LayoutParams(-1, dp(180)));
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER_VERTICAL); actions.setPadding(0, dp(12), 0, 0);
        talk = new Button(this); talk.setText("按住说话"); talk.setTextSize(21); talk.setAllCaps(false);
        actions.addView(talk, new LinearLayout.LayoutParams(0, dp(76), 1f));
        mute = new Button(this); mute.setText("🔊"); mute.setTextSize(22); mute.setAllCaps(false);
        LinearLayout.LayoutParams muteParams = new LinearLayout.LayoutParams(dp(76), dp(76));
        muteParams.leftMargin = dp(10); actions.addView(mute, muteParams);
        box.addView(actions);
        talk.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) startRecording();
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) stopRecording();
            return true;
        });
        mute.setOnClickListener(v -> { muted = !muted; mute.setText(muted ? "🔇" : "🔊"); if (muted) tts.stop(); });
        return box;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, 8, 0, 8); return view;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

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
            recording = true; talk.setText("松开发送"); status.setText("正在录音…"); answer.setText("我在听。");
        } catch (Exception e) { status.setText("无法启动录音：" + e.getMessage()); releaseRecorder(); }
    }
    private void stopRecording() {
        if (!recording) return;
        recording = false; talk.setText("发送中…"); talk.setEnabled(false); status.setText("正在上传并识别语音…");
        try { recorder.stop(); releaseRecorder(); sendAudio(audioFile); }
        catch (RuntimeException e) { releaseRecorder(); talk.setEnabled(true); talk.setText("按住说话"); status.setText("录音太短，请按住至少一秒"); }
    }
    private void releaseRecorder() { if (recorder != null) { recorder.reset(); recorder.release(); recorder = null; } }

    private void send(String words) {
        status.setText("猫鸡在思考…"); answer.setText(words);
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
                main.post(() -> { answer.setText(screen); status.setText("猫鸡·在线"); if (!muted) tts.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "pet-answer"); });
            } catch (Exception e) {
                main.post(() -> { answer.setText("连接失败：" + e.getMessage()); status.setText("请检查 Tailscale 和 Gateway"); });
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
                main.post(() -> { answer.setText(screen); status.setText("猫鸡·在线"); talk.setEnabled(true); talk.setText("按住说话"); if (!muted) tts.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "pet-answer"); });
            } catch (Exception e) { main.post(() -> { answer.setText("语音请求失败：" + e.getMessage()); status.setText("请再试一次"); talk.setEnabled(true); talk.setText("按住说话"); }); }
            finally { if (file != null) file.delete(); }
        }).start();
    }
    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) { super.onRequestPermissionsResult(r,p,g); }
    @Override protected void onDestroy() { main.removeCallbacks(clockTicker); releaseRecorder(); if (tts != null) tts.shutdown(); super.onDestroy(); }
}
