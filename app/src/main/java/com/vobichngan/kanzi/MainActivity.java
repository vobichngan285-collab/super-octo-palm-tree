package com.vobichngan.kanzi;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.util.Size;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private TextureView preview;
    private TextView status, result;
    private Button startButton, pauseButton;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private TextRecognizer recognizer;
    private TextToSpeech tts;
    private boolean running = false, processing = false, ttsReady = false;
    private String lastSpoken = "";
    private long lastProcess = 0;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private static final int REQ_CAMERA = 10;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        tts = new TextToSpeech(this, code -> {
            if (code == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                if (!ttsReady) status.setText("系统没有可用的中文语音，请先安装中文语音包");
            }
        });
        cameraThread = new HandlerThread("uvc-camera"); cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        preview.setSurfaceTextureListener(this);
        startButton.setOnClickListener(v -> startReading());
        pauseButton.setOnClickListener(v -> stopReading());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20,16,20,12); root.setBackgroundColor(0xFFF4F6FA);
        TextView title = new TextView(this); title.setText("看字助手"); title.setTextSize(26); title.setTextColor(0xFF101828); title.setTypeface(null,1);
        root.addView(title, new LinearLayout.LayoutParams(-1,-2));
        status = new TextView(this); status.setText("连接 USB 摄像头后，点击“开始识别”"); status.setTextSize(17); status.setTextColor(0xFF344054);
        root.addView(status);
        preview = new TextureView(this); preview.setSurfaceTextureListener(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1,0,1); pp.topMargin=12; pp.bottomMargin=12; root.addView(preview,pp);
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(this); startButton.setText("开始识别"); buttons.addView(startButton,new LinearLayout.LayoutParams(0,-2,1));
        pauseButton = new Button(this); pauseButton.setText("暂停朗读"); buttons.addView(pauseButton,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(buttons);
        TextView label = new TextView(this); label.setText("识别到的文字"); label.setTextSize(18); label.setTypeface(null,1); label.setTextColor(0xFF101828); root.addView(label);
        ScrollView scroll = new ScrollView(this); result = new TextView(this); result.setText("识别结果会显示在这里"); result.setTextSize(20); result.setTextColor(0xFF101828); result.setPadding(4,8,4,8); scroll.addView(result);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(150)));
        setContentView(root);
    }
    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density); }

    private void startReading() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.CAMERA},REQ_CAMERA); return;
        }
        running=true; lastSpoken=""; status.setText("正在寻找 USB 摄像头…");
        if (preview.isAvailable()) openExternalCamera(preview.getSurfaceTexture());
    }
    private void stopReading() {
        running=false; status.setText("已暂停"); if(tts!=null) tts.stop();
        closeCamera();
    }
    @Override public void onRequestPermissionsResult(int requestCode,@NonNull String[] p,@NonNull int[] g) {
        super.onRequestPermissionsResult(requestCode,p,g); if(requestCode==REQ_CAMERA && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) startReading();
    }
    private void openExternalCamera(SurfaceTexture texture) {
        try {
            CameraManager manager=(CameraManager)getSystemService(Context.CAMERA_SERVICE);
            String selected=null;
            for(String id:manager.getCameraIdList()) {
                CameraCharacteristics c=manager.getCameraCharacteristics(id);
                Integer facing=c.get(CameraCharacteristics.LENS_FACING);
                if(facing!=null && facing==CameraCharacteristics.LENS_FACING_EXTERNAL){selected=id;break;}
            }
            if(selected==null){status.setText("系统未向应用提供 USB 摄像头。请确认手机支持 UVC 外接摄像头；如果 USB 摄像头 App 能用但这里找不到，需切换专用 UVC 接入方案。");return;}
            CameraCharacteristics chars=manager.getCameraCharacteristics(selected);
            Size[] sizes=chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(android.graphics.SurfaceTexture.class);
            Size size=(sizes!=null&&sizes.length>0)?sizes[0]:new Size(1280,720);
            texture.setDefaultBufferSize(size.getWidth(),size.getHeight());
            reader=ImageReader.newInstance(1280,720,android.graphics.ImageFormat.YUV_420_888,2);
            reader.setOnImageAvailableListener(r -> {
                Image image=null;
                try {
                    image=r.acquireLatestImage();
                    if(image==null||!running||!busy.compareAndSet(false,true)) return;
                    long now=System.currentTimeMillis(); if(now-lastProcess<850){busy.set(false);return;} lastProcess=now;
                    Image finalImage=image;
                    InputImage input=InputImage.fromMediaImage(finalImage,0);
                    recognizer.process(input).addOnSuccessListener(this::onRecognized)
                      .addOnCompleteListener(task->{finalImage.close();busy.set(false);});
                    image=null;
                } catch(Exception e){ if(image!=null) image.close(); busy.set(false); }
            },cameraHandler);
            manager.openCamera(selected,new CameraDevice.StateCallback(){
                @Override public void onOpened(@NonNull CameraDevice c){
                    camera=c; try{
                        Surface previewSurface=new Surface(texture); Surface ocrSurface=reader.getSurface();
                        CaptureRequest.Builder request=c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        request.addTarget(previewSurface); request.addTarget(ocrSurface);
                        c.createCaptureSession(Arrays.asList(previewSurface,ocrSurface),new CameraCaptureSession.StateCallback(){
                            @Override public void onConfigured(@NonNull CameraCaptureSession s){session=s;try{s.setRepeatingRequest(request.build(),null,cameraHandler);runOnUiThread(()->status.setText("USB 摄像头已连接，正在识别中文文字"));}catch(Exception e){showError("摄像头启动失败："+e.getMessage());}}
                            @Override public void onConfigureFailed(@NonNull CameraCaptureSession s){showError("摄像头预览配置失败");}
                        },cameraHandler);
                    }catch(Exception e){showError("无法启动摄像头："+e.getMessage());}
                }
                @Override public void onDisconnected(@NonNull CameraDevice c){c.close();camera=null;showError("USB 摄像头已断开");}
                @Override public void onError(@NonNull CameraDevice c,int error){c.close();camera=null;showError("摄像头错误："+error);}
            },cameraHandler);
        }catch(Exception e){showError("打开摄像头失败："+e.getMessage());}
    }
    private void onRecognized(Text text) {
        StringBuilder sb=new StringBuilder();
        for(Text.TextBlock block:text.getTextBlocks()){String s=block.getText().trim();if(!s.isEmpty()){if(sb.length()>0)sb.append("\n");sb.append(s);}}
        String found=sb.toString().trim(); if(found.isEmpty())return;
        runOnUiThread(()->result.setText(found));
        String normalized=found.replaceAll("\\s+","");
        if(running && ttsReady && normalized.length()>1 && !normalized.equals(lastSpoken)){
            lastSpoken=normalized;
            runOnUiThread(()->{if(running)tts.speak(found,TextToSpeech.QUEUE_ADD,null,"ocr-"+System.currentTimeMillis());});
        }
    }
    private void showError(String message){runOnUiThread(()->status.setText(message));}
    private void closeCamera(){
        try{if(session!=null){session.close();session=null;}if(camera!=null){camera.close();camera=null;}if(reader!=null){reader.close();reader=null;}}catch(Exception ignored){}
    }
    @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st,int w,int h){if(running)openExternalCamera(st);}
    @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st,int w,int h){}
    @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st){closeCamera();return true;}
    @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st){}
    @Override protected void onDestroy(){stopReading();if(recognizer!=null)recognizer.close();if(tts!=null)tts.shutdown();if(cameraThread!=null)cameraThread.quitSafely();super.onDestroy();}
}
