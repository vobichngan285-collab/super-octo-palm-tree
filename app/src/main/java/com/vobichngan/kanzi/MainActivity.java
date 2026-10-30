package com.vobichngan.kanzi;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.util.TypedValue;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.base.CameraActivity;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.callback.IPreviewDataCallBack;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.jiangdg.ausbc.widget.IAspectRatio;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends CameraActivity {
    private static final String APP_VERSION = "0.3.1";
    private final Handler diagnosticHandler = new Handler(Looper.getMainLooper());
    private FrameLayout previewContainer;
    private TextView status, result;
    private Button startButton, pauseButton;
    private TextToSpeech tts;
    private TextRecognizer recognizer;
    private boolean running = false;
    private boolean ttsReady = false;
    private boolean previewCallbackAdded = false;
    private String lastSpoken = "";
    private long lastProcess = 0;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private final IPreviewDataCallBack previewCallback = new IPreviewDataCallBack() {
        @Override public void onPreviewData(byte[] data, int width, int height, DataFormat format) {
            if (!running || data == null || format != DataFormat.NV21) return;
            long now = System.currentTimeMillis();
            if (now - lastProcess < 900 || !busy.compareAndSet(false, true)) return;
            lastProcess = now;
            byte[] frame = data.clone();
            InputImage image = InputImage.fromByteArray(frame, width, height, 0, InputImage.IMAGE_FORMAT_NV21);
            recognizer.process(image)
                .addOnSuccessListener(MainActivity.this::onRecognized)
                .addOnCompleteListener(task -> busy.set(false));
        }
    };

    @Override protected View getRootView(LayoutInflater inflater) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundColor(0xFFF4F6FA);

        TextView title = new TextView(this);
        title.setText("看字助手  " + APP_VERSION + "（UVC诊断版）");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTextColor(0xFF101828);
        title.setTypeface(null, 1);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("正在检查 USB 设备…");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        status.setTextColor(0xFF344054);
        root.addView(status);

        previewContainer = new FrameLayout(this);
        previewContainer.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, 0, 1);
        previewParams.topMargin = dp(10);
        previewParams.bottomMargin = dp(10);
        root.addView(previewContainer, previewParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(this);
        startButton.setText("开始识别");
        buttons.addView(startButton, new LinearLayout.LayoutParams(0, -2, 1));
        pauseButton = new Button(this);
        pauseButton.setText("暂停朗读");
        buttons.addView(pauseButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(buttons);

        TextView label = new TextView(this);
        label.setText("识别到的文字");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        label.setTypeface(null, 1);
        label.setTextColor(0xFF101828);
        root.addView(label);

        ScrollView scroll = new ScrollView(this);
        result = new TextView(this);
        result.setText("识别结果会显示在这里");
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        result.setTextColor(0xFF101828);
        result.setPadding(dp(4), dp(8), dp(4), dp(8));
        scroll.addView(result);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(145)));
        return root;
    }

    @Override protected IAspectRatio getCameraView() {
        return new AspectRatioTextureView(this);
    }

    @Override protected ViewGroup getCameraViewContainer() {
        return previewContainer;
    }

    @Override protected int getGravity() {
        return Gravity.CENTER;
    }

    @Override protected CameraRequest getCameraRequest() {
        return new CameraRequest.Builder()
            .setPreviewWidth(1280)
            .setPreviewHeight(720)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setRawPreviewData(true)
            .setAspectRatioShow(true)
            .create();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        diagnosticHandler.postDelayed(this::diagnoseUsbDevices, 1800);
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        tts = new TextToSpeech(this, code -> {
            if (code == TextToSpeech.SUCCESS) {
                int language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                ttsReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED;
                if (!ttsReady) status.setText("系统没有可用的中文语音，请先安装中文语音包");
            }
        });
        startButton.setOnClickListener(v -> {
            running = true;
            lastSpoken = "";
            status.setText(previewCallbackAdded ? "正在识别并朗读新出现的中文文字" : "正在等待 USB 摄像头连接…");
        });
        pauseButton.setOnClickListener(v -> {
            running = false;
            if (tts != null) tts.stop();
            status.setText("已暂停朗读和识别");
        });
    }

    @Override public void onCameraState(MultiCameraClient.ICamera self, ICameraStateCallBack.State code, String msg) {
        if (code == ICameraStateCallBack.State.OPENED) {
            if (!previewCallbackAdded) {
                addPreviewDataCallBack(previewCallback);
                previewCallbackAdded = true;
            }
            runOnUiThread(() -> status.setText("USB 摄像头已连接。点击“开始识别”"));
        } else if (code == ICameraStateCallBack.State.CLOSED) {
            previewCallbackAdded = false;
            runOnUiThread(() -> status.setText("USB 摄像头已断开"));
        } else if (code == ICameraStateCallBack.State.ERROR) {
            runOnUiThread(() -> status.setText("USB 摄像头启动失败：" + (msg == null ? "请重新插拔摄像头" : msg)));
        }
    }


    private void diagnoseUsbDevices() {
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        Map<String, UsbDevice> devices = manager == null ? null : manager.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            status.setText(APP_VERSION + "：安卓系统未枚举到 USB 设备。请确认 OTG 已开启、摄像头已插好。");
            return;
        }
        StringBuilder info = new StringBuilder(APP_VERSION + "：安卓已检测到 " + devices.size() + " 个 USB 设备：");
        for (UsbDevice device : devices.values()) {
            info.append("\\n").append(device.getDeviceName())
                .append("  VID:").append(String.format(Locale.US, "%04X", device.getVendorId()))
                .append(" PID:").append(String.format(Locale.US, "%04X", device.getProductId()))
                .append(" class:").append(device.getDeviceClass())
                .append("/").append(device.getDeviceSubclass());
        }
        info.append("\\n但 UVC 摄像头模块尚未报告连接。请把这段检测结果发给我。");
        status.setText(info.toString());
    }

    private void onRecognized(Text text) {
        StringBuilder builder = new StringBuilder();
        for (Text.TextBlock block : text.getTextBlocks()) {
            String value = block.getText().trim();
            if (!value.isEmpty()) {
                if (builder.length() > 0) builder.append("\n");
                builder.append(value);
            }
        }
        String found = builder.toString().trim();
        if (found.isEmpty()) return;
        runOnUiThread(() -> result.setText(found));
        String normalized = found.replaceAll("\\s+", "");
        if (running && ttsReady && normalized.length() > 1 && !normalized.equals(lastSpoken)) {
            lastSpoken = normalized;
            runOnUiThread(() -> {
                if (running) tts.speak(found, TextToSpeech.QUEUE_ADD, null, "ocr-" + System.currentTimeMillis());
            });
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        running = false;
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (recognizer != null) recognizer.close();
        diagnosticHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
