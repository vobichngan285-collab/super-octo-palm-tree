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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends CameraActivity {
    private static final String APP_VERSION = "0.3.7";
    private final Handler diagnosticHandler = new Handler(Looper.getMainLooper());
    private int diagnosticAttempts = 0;
    private final Runnable diagnosticRunnable = new Runnable() {
        @Override public void run() { diagnoseUsbDevices(); }
    };
    private FrameLayout previewContainer;
    private TextView status, diagnostic, result;
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

        diagnostic = new TextView(this);
        diagnostic.setText("USB 设备诊断信息正在加载…");
        diagnostic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        diagnostic.setTextColor(0xFF475467);
        root.addView(diagnostic);

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

    // CameraActivity owns the complete USB lifecycle. When its preview
    // surface becomes available it registers the USB monitor, creates the
    // camera entry in onAttachDev(), requests permission for getDefaultCamera(),
    // and opens the camera from onConnectDev(). Do not manually seed its map
    // or request permission outside that lifecycle.

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        diagnosticAttempts = 0;
        diagnosticHandler.postDelayed(diagnosticRunnable, 1200);
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

    // Prefer the camera reported by the user's phone diagnostics.
    @Override protected UsbDevice getDefaultCamera() {
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        if (manager == null) return null;
        Map<String, UsbDevice> devices = manager.getDeviceList();
        if (devices == null) return null;
        for (UsbDevice device : devices.values()) {
            if (device.getVendorId() == 0xEBA4 && device.getProductId() == 0x6579) {
                return device;
            }
        }
        return null;
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
        diagnosticAttempts++;
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        Map<String, UsbDevice> systemDevices = manager == null ? null : manager.getDeviceList();
        List<UsbDevice> uvcDevices = null;
        try {
            // CameraActivity registers its USB monitor after the preview surface is ready.
            uvcDevices = getDeviceList();
        } catch (Exception e) {
            diagnostic.setText(APP_VERSION + "：UVC 设备列表读取异常：" + e.getClass().getSimpleName());
            return;
        }

        int systemCount = systemDevices == null ? 0 : systemDevices.size();
        int uvcCount = uvcDevices == null ? -1 : uvcDevices.size();
        StringBuilder info = new StringBuilder(APP_VERSION)
            .append("：安卓 USB 设备 ").append(systemCount)
            .append(" 个；UVC 库设备列表 ")
            .append(uvcCount < 0 ? "尚未初始化" : uvcCount + " 个");

        if (systemDevices != null) {
            for (UsbDevice device : systemDevices.values()) appendDeviceInfo(info, "安卓", device);
        }
        if (uvcDevices != null) {
            for (UsbDevice device : uvcDevices) appendDeviceInfo(info, "UVC", device);
        }

        if (systemCount == 0 && uvcCount <= 0) {
            info.append("\n暂时没有读到设备。正在等待 USB 监控初始化/设备插入…");
            diagnostic.setText(info.toString());
            if (diagnosticAttempts < 15) {
                diagnosticHandler.postDelayed(diagnosticRunnable, 1000);
            } else {
                diagnostic.setText(info.append("\n等待 15 秒后仍无设备。请把此结果发给我。").toString());
            }
            return;
        }
        boolean permission = false;
        if (systemDevices != null) {
            for (UsbDevice device : systemDevices.values()) {
                if (device.getVendorId() == 0xEBA4 && device.getProductId() == 0x6579) {
                    permission = manager != null && manager.hasPermission(device);
                }
            }
        }
        boolean cameraOpened = false;
        try { cameraOpened = isCameraOpened(); } catch (Exception ignored) {}
        info.append("\\nUSB权限：").append(permission ? "已授予" : "未授予");
        info.append("\\n预览容器：").append(previewContainer == null ? "未创建" :
            previewContainer.getWidth() + "×" + previewContainer.getHeight() +
            "，子视图 " + previewContainer.getChildCount() + " 个");
        info.append("\\n摄像头状态：").append(cameraOpened ? "已打开" : "未打开");
        info.append("\\n预览回调：").append(previewCallbackAdded ? "已注册" : "未注册");
        if (uvcCount > 0 && !permission) {
            info.append("\\n判断：设备已枚举，但 USB 权限未授予，重点检查授权阶段。");
        } else if (uvcCount > 0 && permission && !cameraOpened) {
            info.append("\\n判断：USB 权限已具备，但摄像头未打开，重点检查连接/打开阶段。");
        } else if (cameraOpened) {
            info.append("\\n判断：摄像头已打开，继续检查预览数据回调。");
        } else if (systemCount > 0) {
            info.append("\\n判断：安卓已发现设备，UVC 库连接状态仍需确认。");
        }
        diagnostic.setText(info.toString());
        if (diagnosticAttempts < 15) diagnosticHandler.postDelayed(diagnosticRunnable, 1500);
    }

    private void appendDeviceInfo(StringBuilder info, String source, UsbDevice device) {
        info.append("\n[").append(source).append("] ")
            .append(device.getDeviceName())
            .append(" VID:").append(String.format(Locale.US, "%04X", device.getVendorId()))
            .append(" PID:").append(String.format(Locale.US, "%04X", device.getProductId()))
            .append(" class:").append(device.getDeviceClass())
            .append("/").append(device.getDeviceSubclass())
            .append(" interfaces:").append(device.getInterfaceCount());
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            android.hardware.usb.UsbInterface intf = device.getInterface(i);
            info.append(" [").append(i)
                .append(":class=").append(intf.getInterfaceClass())
                .append(",sub=").append(intf.getInterfaceSubclass())
                .append(",proto=").append(intf.getInterfaceProtocol())
                .append(",eps=").append(intf.getEndpointCount()).append("]");
        }
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
