package com.ahm.capacitor.camera.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.otaliastudios.cameraview.CameraListener;
import com.otaliastudios.cameraview.CameraOptions;
import com.otaliastudios.cameraview.CameraView;
import com.otaliastudios.cameraview.PictureResult;
import com.otaliastudios.cameraview.controls.Facing;
import com.otaliastudios.cameraview.controls.Flash;
import com.otaliastudios.cameraview.frame.Frame;
import com.otaliastudios.cameraview.frame.FrameProcessor;

import java.io.ByteArrayOutputStream;

public class NatarioCameraView {
    private static final String TAG = "NatarioCameraView";

    public interface NatarioCameraViewListener {
        void onPictureTaken(String base64);
        void onPictureTakenError(String message);
        void onCameraStarted();
        void onCameraStartError(String message);
    }

    private CameraView cameraView;
    private Context context;
    private NatarioCameraViewListener listener;
    private java.util.concurrent.ExecutorService backgroundExecutor;

    public NatarioCameraView(Context context, NatarioCameraViewListener listener) {
        this.context = context;
        this.listener = listener;
        this.cameraView = new CameraView(context);
        this.backgroundExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    }

    public void start(FrameLayout container, String position, int x, int y, int width, int height, boolean toBack) {
        cameraView.setLifecycleOwner(null); // We will manage the lifecycle manually

        if ("front".equals(position)) {
            cameraView.setFacing(Facing.FRONT);
        } else {
            cameraView.setFacing(Facing.BACK);
        }

        cameraView.addCameraListener(new CameraListener() {
            @Override
            public void onCameraOpened(@NonNull CameraOptions options) {
                if (listener != null) {
                    listener.onCameraStarted();
                }
            }

            @Override
            public void onCameraError(@NonNull com.otaliastudios.cameraview.CameraException exception) {
                if (listener != null) {
                    listener.onCameraStartError(exception.getMessage());
                }
            }

            @Override
            public void onPictureTaken(@NonNull PictureResult result) {
                result.toBitmap(bitmap -> {
                    if (bitmap == null) {
                        if (listener != null) {
                            listener.onPictureTakenError("Failed to create bitmap from picture.");
                        }
                        return;
                    }
                    // The expensive compression part is now on a background thread.
                    backgroundExecutor.execute(() -> {
                        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, byteArrayOutputStream);
                        byte[] byteArray = byteArrayOutputStream.toByteArray();
                        String encoded = Base64.encodeToString(byteArray, Base64.NO_WRAP);
                        if (listener != null) {
                            listener.onPictureTaken(encoded);
                        }
                    });
                });
            }
        });
        
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
        layoutParams.leftMargin = x;
        layoutParams.topMargin = y;
        cameraView.setLayoutParams(layoutParams);
        
        if (toBack) {
            container.addView(cameraView, 0);
        } else {
            container.addView(cameraView);
        }
        
        cameraView.open();
    }
    
    public void stop(FrameLayout container) {
        if (cameraView != null) {
            cameraView.close();
            if (container != null) {
                container.removeView(cameraView);
            }
            cameraView = null;
        }
    }

    public void takePicture() {
        if (cameraView != null && cameraView.isOpened()) {
            cameraView.takePicture();
        }
    }

    public void setZoom(float factor) {
        if (cameraView != null && cameraView.isOpened()) {
            cameraView.setZoom(factor); // factor is 0.0 to 1.0
        }
    }
    
    public float getZoom() {
        if (cameraView != null && cameraView.isOpened()) {
            return cameraView.getZoom();
        }
        return 0;
    }

    public float getMaxZoom() {
         if (cameraView != null && cameraView.isOpened()) {
            return 1.0f; // Natario zoom is always 0.0 to 1.0
        }
        return 1.0f;
    }

    public void setFlash(String mode) {
        if (cameraView != null && cameraView.isOpened()) {
            switch (mode) {
                case "on":
                    cameraView.setFlash(Flash.ON);
                    break;
                case "auto":
                    cameraView.setFlash(Flash.AUTO);
                    break;
                case "torch":
                    cameraView.setFlash(Flash.TORCH);
                    break;
                default:
                    cameraView.setFlash(Flash.OFF);
                    break;
            }
        }
    }

    public void flip() {
        if (cameraView != null && cameraView.isOpened()) {
            cameraView.toggleFacing();
        }
    }

    public void destroy() {
        if(cameraView != null) {
            cameraView.destroy();
        }
    }
} 
