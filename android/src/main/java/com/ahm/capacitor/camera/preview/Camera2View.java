package com.ahm.capacitor.camera.preview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.app.Activity;

import androidx.annotation.NonNull;

import com.ahm.capacitor.camera.preview.model.CameraSessionConfiguration;
import com.ahm.capacitor.camera.preview.model.ZoomFactors;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.io.FileOutputStream;
import java.io.File;

public class Camera2View {
    private static final String TAG = "Camera2View";

    public interface Camera2ViewListener {
        void onPictureTaken(String result);
        void onPictureTakenError(String message);
        void onSampleTaken(String result);
        void onSampleTakenError(String message);
        void onCameraStarted();
        void onCameraStartError(String message);
    }

    // Camera components
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private ImageReader sampleImageReader;
    
    // Surface and preview
    private SurfaceView surfaceView;
    private Surface previewSurface;
    
    // Camera state
    private String currentCameraId;
    private CameraCharacteristics cameraCharacteristics;
    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;
    private int currentFlashMode = CameraMetadata.CONTROL_AE_MODE_ON;
    private float currentZoomRatio = 1.0f;
    
    // Threading
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    
    // Configuration
    private CameraSessionConfiguration sessionConfig;
    private Camera2ViewListener listener;
    private Context context;
    private WebView webView;
    
    // State tracking
    private boolean isRunning = false;

    public Camera2View(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    public void setListener(Camera2ViewListener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void startSession(CameraSessionConfiguration config) {
        Log.d(TAG, "startSession: Starting camera session with config");
        Log.d(TAG, "startSession: DeviceId=" + config.getDeviceId() + 
                   ", Position=" + config.getPosition() + 
                   ", Dimensions=" + config.getWidth() + "x" + config.getHeight());
        
        this.sessionConfig = config;
        
        try {
            Log.d(TAG, "startSession: Starting background thread");
            startBackgroundThread();
            
            Log.d(TAG, "startSession: Setting up surface view");
            setupSurfaceView();
            
            Log.d(TAG, "startSession: Opening camera");
            openCamera();
        } catch (Exception e) {
            Log.e(TAG, "startSession: Error during initialization", e);
            if (listener != null) {
                listener.onCameraStartError("Camera initialization failed: " + e.getMessage());
            }
        }
    }

    public void stopSession() {
        Log.d(TAG, "stopSession: Stopping camera session");
        isRunning = false;
        closeCamera();
        stopBackgroundThread();
        removeSurfaceView();
        Log.d(TAG, "stopSession: Camera session stopped");
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
    }

    private void setupSurfaceView() {
        // Make WebView transparent if needed
        if (sessionConfig.isToBack()) {
            webView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }

        surfaceView = new SurfaceView(context);
        ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(
            sessionConfig.getWidth(),
            sessionConfig.getHeight() - sessionConfig.getPaddingBottom()
        );
        
        if (surfaceView.getLayoutParams() == null) {
            surfaceView.setLayoutParams(layoutParams);
        }

        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            if (sessionConfig.isToBack()) {
                parent.addView(surfaceView, 0);
                parent.bringChildToFront(webView);
            } else {
                parent.addView(surfaceView);
            }
        }

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.d(TAG, "surfaceCreated: Preview surface created");
                previewSurface = holder.getSurface();
                Log.d(TAG, "surfaceCreated: CameraDevice available: " + (cameraDevice != null));
                if (cameraDevice != null) {
                    createCameraPreviewSession();
                } else {
                    Log.w(TAG, "surfaceCreated: Camera device not ready, waiting for camera opening");
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.d(TAG, "surfaceChanged: Surface format=" + format + ", size=" + width + "x" + height);
                // Handle surface changes if needed
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.d(TAG, "surfaceDestroyed: Preview surface destroyed");
                previewSurface = null;
            }
        });
    }

    private void removeSurfaceView() {
        if (surfaceView != null) {
            ViewGroup parent = (ViewGroup) surfaceView.getParent();
            if (parent != null) {
                parent.removeView(surfaceView);
            }
            surfaceView = null;
        }
        
        // Reset WebView background
        webView.setBackgroundColor(android.graphics.Color.WHITE);
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        Log.d(TAG, "openCamera: Attempting to open camera");
        try {
            Log.d(TAG, "openCamera: Trying to acquire camera lock");
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "openCamera: Timeout waiting to lock camera opening");
                if (listener != null) {
                    listener.onCameraStartError("Time out waiting to lock camera opening.");
                }
                return;
            }
            Log.d(TAG, "openCamera: Camera lock acquired");

            // Determine which camera to use
            if (sessionConfig.getDeviceId() != null && !sessionConfig.getDeviceId().isEmpty()) {
                currentCameraId = sessionConfig.getDeviceId();
                Log.d(TAG, "openCamera: Using specified device ID: " + currentCameraId);
            } else {
                Log.d(TAG, "openCamera: Looking for camera by position: " + sessionConfig.getPosition());
                currentCameraId = getCameraIdByPosition(sessionConfig.getPosition());
                Log.d(TAG, "openCamera: Found camera ID for position: " + currentCameraId);
            }

            if (currentCameraId == null) {
                Log.e(TAG, "openCamera: No camera found for position: " + sessionConfig.getPosition());
                if (listener != null) {
                    listener.onCameraStartError("No camera found for position: " + sessionConfig.getPosition());
                }
                cameraOpenCloseLock.release();
                return;
            }

            Log.d(TAG, "openCamera: Getting camera characteristics for: " + currentCameraId);
            cameraCharacteristics = cameraManager.getCameraCharacteristics(currentCameraId);
            
            StreamConfigurationMap streamMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (streamMap == null) {
                Log.e(TAG, "openCamera: No stream configuration map available");
                if (listener != null) {
                    listener.onCameraStartError("Camera configuration not available");
                }
                cameraOpenCloseLock.release();
                return;
            }
            
            Size[] jpegSizes = streamMap.getOutputSizes(ImageFormat.JPEG);
            Log.d(TAG, "openCamera: Available JPEG sizes: " + (jpegSizes != null ? jpegSizes.length : 0));
            
            int width = 640;
            int height = 480;
            if (jpegSizes != null && jpegSizes.length > 0) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
                Log.d(TAG, "openCamera: Using JPEG size: " + width + "x" + height);
            } else {
                Log.w(TAG, "openCamera: No JPEG sizes available, using defaults: " + width + "x" + height);
            }

            Log.d(TAG, "openCamera: Creating image readers");
            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);

            // Setup sample image reader for lower quality captures
            sampleImageReader = ImageReader.newInstance(width / 2, height / 2, ImageFormat.JPEG, 1);
            sampleImageReader.setOnImageAvailableListener(onSampleImageAvailableListener, backgroundHandler);

            Log.d(TAG, "openCamera: Opening camera device: " + currentCameraId);
            cameraManager.openCamera(currentCameraId, deviceStateCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error opening camera", e);
            if (listener != null) {
                listener.onCameraStartError("Error opening camera: " + e.getMessage());
            }
        } catch (InterruptedException e) {
            if (listener != null) {
                listener.onCameraStartError("Interrupted while trying to lock camera opening.");
            }
        }
    }

    private String getCameraIdByPosition(String position) {
        Log.d(TAG, "getCameraIdByPosition: Looking for position: " + position);
        try {
            String[] cameraIdList = cameraManager.getCameraIdList();
            Log.d(TAG, "getCameraIdByPosition: Found " + cameraIdList.length + " cameras");
            
            for (String cameraId : cameraIdList) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                
                Log.d(TAG, "getCameraIdByPosition: Camera " + cameraId + " facing: " + facing);
                
                if (facing != null) {
                    if ("front".equals(position) && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        Log.d(TAG, "getCameraIdByPosition: Found front camera: " + cameraId);
                        return cameraId;
                    } else if (("rear".equals(position) || "back".equals(position)) && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        Log.d(TAG, "getCameraIdByPosition: Found rear camera: " + cameraId);
                        return cameraId;
                    }
                }
            }
            Log.w(TAG, "getCameraIdByPosition: No camera found for position: " + position);
        } catch (CameraAccessException e) {
            Log.e(TAG, "getCameraIdByPosition: Error getting camera ID by position", e);
        }
        return null;
    }

    private final CameraDevice.StateCallback deviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "deviceStateCallback.onOpened: Camera device opened successfully");
            cameraOpenCloseLock.release();
            cameraDevice = camera;
            
            Log.d(TAG, "deviceStateCallback.onOpened: PreviewSurface available: " + (previewSurface != null));
            if (previewSurface != null) {
                createCameraPreviewSession();
            } else {
                Log.w(TAG, "deviceStateCallback.onOpened: Preview surface not ready, waiting for surface creation");
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.w(TAG, "deviceStateCallback.onDisconnected: Camera device disconnected");
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "deviceStateCallback.onError: Camera device error: " + error);
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            if (listener != null) {
                listener.onCameraStartError("Camera device error: " + error);
            }
        }
    };

    private void createCameraPreviewSession() {
        Log.d(TAG, "createCameraPreviewSession: Starting camera preview session creation");
        try {
            if (cameraDevice == null || previewSurface == null) {
                Log.e(TAG, "createCameraPreviewSession: Cannot create session - camera device: " + 
                      (cameraDevice != null) + ", preview surface: " + (previewSurface != null));
                return;
            }

            Log.d(TAG, "createCameraPreviewSession: Creating capture request");
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);

            Log.d(TAG, "createCameraPreviewSession: Creating capture session with preview surface only");
            // Start with preview surface only to avoid stream configuration conflicts
            cameraDevice.createCaptureSession(
                Arrays.asList(previewSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        Log.d(TAG, "captureSession.onConfigured: Camera session configured successfully");
                        if (cameraDevice == null) {
                            Log.e(TAG, "captureSession.onConfigured: Camera device became null");
                            return;
                        }

                        captureSession = session;
                        try {
                            Log.d(TAG, "captureSession.onConfigured: Setting up preview request");
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, currentFlashMode);
                            
                            // Set initial zoom
                            if (sessionConfig.getZoomFactor() != 1.0f) {
                                Log.d(TAG, "captureSession.onConfigured: Setting initial zoom: " + sessionConfig.getZoomFactor());
                                setZoomInternal(sessionConfig.getZoomFactor());
                            }

                            previewRequest = previewRequestBuilder.build();
                            Log.d(TAG, "captureSession.onConfigured: Starting repeating capture request");
                            captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
                            
                            isRunning = true;
                            Log.d(TAG, "captureSession.onConfigured: Camera preview started successfully");
                            if (listener != null) {
                                listener.onCameraStarted();
                            }
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "captureSession.onConfigured: Error starting preview", e);
                            if (listener != null) {
                                listener.onCameraStartError("Error starting preview: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.e(TAG, "captureSession.onConfigureFailed: Camera session configuration failed");
                        if (listener != null) {
                            listener.onCameraStartError("Camera configuration failed");
                        }
                    }
                },
                null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCameraPreviewSession: Error creating session", e);
            if (listener != null) {
                listener.onCameraStartError("Error creating preview session: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "createCameraPreviewSession: Unexpected error", e);
            if (listener != null) {
                listener.onCameraStartError("Unexpected error creating preview session: " + e.getMessage());
            }
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            // Handle capture completion if needed
        }
    };

    public void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (sampleImageReader != null) {
                sampleImageReader.close();
                sampleImageReader = null;
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    public void capturePhoto(int quality) {
        if (cameraDevice == null || captureSession == null) {
            if (listener != null) {
                listener.onPictureTakenError("Camera not ready");
            }
            return;
        }

        try {
            Log.d(TAG, "capturePhoto: Creating capture session with image reader");
            // Create a new session with both preview and image reader surfaces for capture
            cameraDevice.createCaptureSession(
                Arrays.asList(previewSurface, imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        try {
                            Log.d(TAG, "capturePhoto: Capture session configured, taking photo");
                            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                            captureBuilder.addTarget(imageReader.getSurface());
                            
                            // Auto-focus
                            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, currentFlashMode);
                            
                            // Set zoom if available
                            if (previewRequestBuilder.get(CaptureRequest.SCALER_CROP_REGION) != null) {
                                captureBuilder.set(CaptureRequest.SCALER_CROP_REGION, previewRequestBuilder.get(CaptureRequest.SCALER_CROP_REGION));
                            }

                            session.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                                @Override
                                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                                    Log.d(TAG, "capturePhoto: Photo capture completed, restoring preview session");
                                    // Restore preview-only session
                                    createCameraPreviewSession();
                                }
                            }, backgroundHandler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "capturePhoto: Error during capture", e);
                            if (listener != null) {
                                listener.onPictureTakenError("Error capturing photo: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.e(TAG, "capturePhoto: Failed to configure capture session");
                        if (listener != null) {
                            listener.onPictureTakenError("Failed to configure capture session");
                        }
                    }
                },
                backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "capturePhoto: Error creating capture session", e);
            if (listener != null) {
                listener.onPictureTakenError("Error creating capture session: " + e.getMessage());
            }
        }
    }

    public void captureSample(int quality) {
        if (cameraDevice == null || captureSession == null) {
            if (listener != null) {
                listener.onSampleTakenError("Camera not ready");
            }
            return;
        }

        try {
            Log.d(TAG, "captureSample: Creating capture session with sample image reader");
            // Create a new session with both preview and sample image reader surfaces for capture
            cameraDevice.createCaptureSession(
                Arrays.asList(previewSurface, sampleImageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        try {
                            Log.d(TAG, "captureSample: Capture session configured, taking sample");
                            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                            captureBuilder.addTarget(sampleImageReader.getSurface());
                            
                            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, currentFlashMode);
                            
                            // Set zoom if available
                            if (previewRequestBuilder.get(CaptureRequest.SCALER_CROP_REGION) != null) {
                                captureBuilder.set(CaptureRequest.SCALER_CROP_REGION, previewRequestBuilder.get(CaptureRequest.SCALER_CROP_REGION));
                            }

                            session.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                                @Override
                                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                                    Log.d(TAG, "captureSample: Sample capture completed, restoring preview session");
                                    // Restore preview-only session
                                    createCameraPreviewSession();
                                }
                            }, backgroundHandler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "captureSample: Error during capture", e);
                            if (listener != null) {
                                listener.onSampleTakenError("Error capturing sample: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.e(TAG, "captureSample: Failed to configure capture session");
                        if (listener != null) {
                            listener.onSampleTakenError("Failed to configure capture session");
                        }
                    }
                },
                backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "captureSample: Error creating capture session", e);
            if (listener != null) {
                listener.onSampleTakenError("Error creating capture session: " + e.getMessage());
            }
        }
    }

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    
                    // Apply rotation correction
                    bytes = correctImageRotation(bytes);
                    
                    String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    if (listener != null) {
                        listener.onPictureTaken(base64);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing captured image", e);
                if (listener != null) {
                    listener.onPictureTakenError("Error processing image: " + e.getMessage());
                }
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }
    };

    private final ImageReader.OnImageAvailableListener onSampleImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    
                    // Apply rotation correction
                    bytes = correctImageRotation(bytes);
                    
                    String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    if (listener != null) {
                        listener.onSampleTaken(base64);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing sample image", e);
                if (listener != null) {
                    listener.onSampleTakenError("Error processing sample: " + e.getMessage());
                }
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }
    };

    public List<com.ahm.capacitor.camera.preview.model.CameraDevice> getAvailableDevices() {
        Log.d(TAG, "getAvailableDevices: Starting camera enumeration");
        try {
            if (cameraManager == null) {
                Log.e(TAG, "getAvailableDevices: CameraManager is null");
                return Collections.emptyList();
            }

            String[] cameraIdList = cameraManager.getCameraIdList();
            Log.d(TAG, "getAvailableDevices: Found " + cameraIdList.length + " camera IDs");
            
            java.util.List<com.ahm.capacitor.camera.preview.model.CameraDevice> devices = new java.util.ArrayList<>();
            
            for (String cameraId : cameraIdList) {
                Log.d(TAG, "getAvailableDevices: Processing camera ID: " + cameraId);
                
                try {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    
                    String position = "rear";
                    String deviceType = "wideAngle";
                    String label = "Camera " + cameraId;
                    
                    Log.d(TAG, "getAvailableDevices: Camera " + cameraId + " lens facing: " + lensFacing);
                    
                    if (lensFacing != null) {
                        switch (lensFacing) {
                            case CameraCharacteristics.LENS_FACING_FRONT:
                                position = "front";
                                label = "Front Camera " + cameraId;
                                break;
                            case CameraCharacteristics.LENS_FACING_BACK:
                                position = "rear";
                                label = "Rear Camera " + cameraId;
                                break;
                            case CameraCharacteristics.LENS_FACING_EXTERNAL:
                                position = "external";
                                label = "External Camera " + cameraId;
                                break;
                        }
                    }

                    // Enhanced device type detection
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                        if (capabilities != null) {
                            Log.d(TAG, "getAvailableDevices: Camera " + cameraId + " capabilities count: " + capabilities.length);
                            boolean isLogicalMultiCamera = false;
                            for (int capability : capabilities) {
                                if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                                    isLogicalMultiCamera = true;
                                    Log.d(TAG, "getAvailableDevices: Camera " + cameraId + " is logical multi-camera");
                                    break;
                                }
                            }
                            
                            // For logical multi-cameras, also add their physical cameras
                            if (isLogicalMultiCamera && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                java.util.Set<String> physicalCameraIds = characteristics.getPhysicalCameraIds();
                                Log.d(TAG, "getAvailableDevices: Logical camera " + cameraId + " has " + physicalCameraIds.size() + " physical cameras");
                                
                                for (String physicalId : physicalCameraIds) {
                                    try {
                                        CameraCharacteristics physicalCharacteristics = cameraManager.getCameraCharacteristics(physicalId);
                                        String physicalDeviceType = detectDeviceType(physicalCharacteristics, physicalId);
                                        String physicalLabel = createLabel(physicalId, position, physicalDeviceType);
                                        
                                        Log.d(TAG, "getAvailableDevices: Adding physical camera - ID: " + physicalId + 
                                                 ", Label: " + physicalLabel + ", Type: " + physicalDeviceType);
                                        
                                        devices.add(new com.ahm.capacitor.camera.preview.model.CameraDevice(physicalId, physicalLabel, position, physicalDeviceType));
                                    } catch (Exception e) {
                                        Log.e(TAG, "getAvailableDevices: Error processing physical camera " + physicalId, e);
                                    }
                                }
                                
                                // Also add the logical camera itself
                                deviceType = "multi";
                                label = createLabel(cameraId, position, deviceType);
                            }
                        }
                    }

                    // Detect device type for regular cameras
                    if (!deviceType.equals("multi")) {
                        deviceType = detectDeviceType(characteristics, cameraId);
                        label = createLabel(cameraId, position, deviceType);
                    }

                    Log.d(TAG, "getAvailableDevices: Adding device - ID: " + cameraId + 
                             ", Label: " + label + ", Position: " + position + ", Type: " + deviceType);
                    
                    devices.add(new com.ahm.capacitor.camera.preview.model.CameraDevice(cameraId, label, position, deviceType));
                } catch (Exception e) {
                    Log.e(TAG, "getAvailableDevices: Error processing camera " + cameraId, e);
                    // Continue with next camera instead of failing completely
                }
            }
            
            Log.d(TAG, "getAvailableDevices: Successfully enumerated " + devices.size() + " cameras");
            return devices;
        } catch (CameraAccessException e) {
            Log.e(TAG, "getAvailableDevices: Error getting camera list", e);
            return Collections.emptyList();
        } catch (Exception e) {
            Log.e(TAG, "getAvailableDevices: Unexpected error", e);
            return Collections.emptyList();
        }
    }

    private String detectDeviceType(CameraCharacteristics characteristics, String cameraId) {
        // Try to detect camera types based on focal length
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focalLengths != null && focalLengths.length > 0) {
                float focalLength = focalLengths[0];
                Log.d(TAG, "detectDeviceType: Camera " + cameraId + " focal length: " + focalLength);
                
                // Typical focal lengths (in mm equivalent):
                // Ultra-wide: ~13-16mm (smartphone equivalent ~2.2-2.6mm)
                // Wide: ~24-28mm (smartphone equivalent ~4-5mm)  
                // Telephoto: ~52-85mm (smartphone equivalent ~8.5-14mm)
                if (focalLength < 3.0f) {
                    return "ultraWide";
                } else if (focalLength > 7.0f) {
                    return "telephoto";
                } else {
                    return "wideAngle";
                }
            }
        }
        return "wideAngle";
    }

    private String createLabel(String cameraId, String position, String deviceType) {
        String baseLabel = position.equals("front") ? "Front Camera" : "Rear Camera";
        String typeLabel = "";
        
        switch (deviceType) {
            case "ultraWide":
                typeLabel = " (Ultra-wide)";
                break;
            case "telephoto":
                typeLabel = " (Telephoto)";
                break;
            case "wideAngle":
                typeLabel = " (Wide)";
                break;
            case "multi":
                typeLabel = " (Multi)";
                break;
        }
        
        return baseLabel + " " + cameraId + typeLabel;
    }

    public ZoomFactors getZoomFactors() {
        // Use current camera characteristics if available
        if (cameraCharacteristics != null) {
            Float maxZoom = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (maxZoom == null) {
                maxZoom = 1.0f;
            }
            return new ZoomFactors(1.0f, maxZoom, currentZoomRatio);
        }
        
        // If no active session, check first available camera
        try {
            String[] cameraIdList = cameraManager.getCameraIdList();
            if (cameraIdList.length > 0) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraIdList[0]);
                Float maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                if (maxZoom == null) {
                    maxZoom = 1.0f;
                }
                return new ZoomFactors(1.0f, maxZoom, 1.0f); // Default current zoom to 1.0
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "getZoomFactors: Error checking zoom capabilities", e);
        }
        
        return new ZoomFactors(1.0f, 1.0f, 1.0f);
    }

    public void setZoom(float zoomRatio) throws Exception {
        if (cameraCharacteristics == null) {
            throw new Exception("Camera not initialized");
        }

        Float maxZoom = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        if (maxZoom == null) {
            maxZoom = 1.0f;
        }

        if (zoomRatio < 1.0f || zoomRatio > maxZoom) {
            throw new Exception("Zoom ratio out of range: " + zoomRatio);
        }

        setZoomInternal(zoomRatio);
    }

    private void setZoomInternal(float zoomRatio) {
        if (previewRequestBuilder == null || captureSession == null || cameraCharacteristics == null) {
            return;
        }

        try {
            android.graphics.Rect activeArraySize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (activeArraySize != null) {
                int cropW = Math.round((float) activeArraySize.width() / zoomRatio);
                int cropH = Math.round((float) activeArraySize.height() / zoomRatio);
                int cropX = (activeArraySize.width() - cropW) / 2;
                int cropY = (activeArraySize.height() - cropH) / 2;
                
                android.graphics.Rect cropRegion = new android.graphics.Rect(cropX, cropY, cropX + cropW, cropY + cropH);
                previewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
                
                previewRequest = previewRequestBuilder.build();
                captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
                
                currentZoomRatio = zoomRatio;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error setting zoom", e);
        }
    }

    public String getFlashMode() {
        switch (currentFlashMode) {
            case CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH:
                return "on";
            case CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH:
                return "auto";
            default:
                return "off";
        }
    }

    public List<String> getSupportedFlashModes() {
        // Try to use current camera characteristics if available
        if (cameraCharacteristics != null) {
            Boolean flashAvailable = cameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flashAvailable != null && flashAvailable) {
                return Arrays.asList("off", "on", "auto");
            } else {
                return Arrays.asList("off");
            }
        }
        
        // If no active session, check first available camera
        try {
            String[] cameraIdList = cameraManager.getCameraIdList();
            if (cameraIdList.length > 0) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraIdList[0]);
                Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flashAvailable != null && flashAvailable) {
                    return Arrays.asList("off", "on", "auto");
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "getSupportedFlashModes: Error checking flash availability", e);
        }
        
        return Arrays.asList("off");
    }

    public void setFlashMode(String mode) throws Exception {
        int newFlashMode;
        switch (mode) {
            case "on":
                newFlashMode = CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
                break;
            case "auto":
                newFlashMode = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
                break;
            default:
                newFlashMode = CameraMetadata.CONTROL_AE_MODE_ON;
                break;
        }

        if (previewRequestBuilder == null || captureSession == null) {
            throw new Exception("Camera not ready");
        }

        try {
            currentFlashMode = newFlashMode;
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, currentFlashMode);
            previewRequest = previewRequestBuilder.build();
            captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            throw new Exception("Error setting flash mode: " + e.getMessage());
        }
    }

    public String getCurrentDeviceId() {
        return currentCameraId;
    }

    public void switchToDevice(String deviceId) throws Exception {
        if (deviceId == null || deviceId.equals(currentCameraId)) {
            return;
        }

        // Verify the device exists
        boolean deviceExists = false;
        try {
            for (String id : cameraManager.getCameraIdList()) {
                if (id.equals(deviceId)) {
                    deviceExists = true;
                    break;
                }
            }
        } catch (CameraAccessException e) {
            throw new Exception("Error checking device existence: " + e.getMessage());
        }

        if (!deviceExists) {
            throw new Exception("Device not found: " + deviceId);
        }

        // Close current camera and open new one
        closeCamera();
        currentCameraId = deviceId;
        openCamera();
    }

    public void flipCamera() throws Exception {
        Log.d(TAG, "flipCamera: Starting camera flip");
        String currentPosition = getCurrentPosition();
        String targetPosition = "front".equals(currentPosition) ? "rear" : "front";
        
        Log.d(TAG, "flipCamera: Current position: " + currentPosition + ", target: " + targetPosition);
        
        String newCameraId = getCameraIdByPosition(targetPosition);
        if (newCameraId == null) {
            Log.e(TAG, "flipCamera: No camera found for position: " + targetPosition);
            throw new Exception("No camera found for position: " + targetPosition);
        }

        Log.d(TAG, "flipCamera: Found target camera ID: " + newCameraId);
        
        // Store current session config for restart
        CameraSessionConfiguration currentConfig = sessionConfig;
        if (currentConfig != null) {
            // Update position in config
            currentConfig = new CameraSessionConfiguration(
                newCameraId,
                targetPosition.equals("rear") ? "back" : targetPosition,
                currentConfig.getX(),
                currentConfig.getY(),
                currentConfig.getWidth(),
                currentConfig.getHeight(),
                currentConfig.getPaddingBottom(),
                currentConfig.isToBack(),
                currentConfig.isStoreToFile(),
                currentConfig.isEnableOpacity(),
                currentConfig.isEnableZoom(),
                currentConfig.isDisableExifHeaderStripping(),
                currentConfig.isDisableAudio(),
                currentConfig.getZoomFactor()
            );
        }

        final CameraSessionConfiguration finalConfig = currentConfig;
        
        // Ensure UI operations run on main thread
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.d(TAG, "flipCamera: Stopping current session on UI thread");
                        stopSession();
                        
                        // Start new session after a brief delay
                        android.os.Handler handler = new android.os.Handler();
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Log.d(TAG, "flipCamera: Starting new session with camera: " + newCameraId);
                                startSession(finalConfig);
                            }
                        }, 150);
                        
                    } catch (Exception e) {
                        Log.e(TAG, "flipCamera: Error during UI thread execution", e);
                        if (listener != null) {
                            listener.onCameraStartError("Failed to flip camera: " + e.getMessage());
                        }
                    }
                }
            });
        } else {
            throw new Exception("Context is not an Activity - cannot access UI thread");
        }
    }

    private String getCurrentPosition() {
        if (cameraCharacteristics == null) {
            return "rear";
        }

        Integer lensFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
        if (lensFacing != null) {
            switch (lensFacing) {
                case CameraCharacteristics.LENS_FACING_FRONT:
                    return "front";
                case CameraCharacteristics.LENS_FACING_BACK:
                    return "rear";
                default:
                    return "external";
            }
        }
        return "rear";
    }

    private byte[] correctImageRotation(byte[] imageBytes) {
        try {
            if (cameraCharacteristics == null) {
                return imageBytes;
            }

            // Get sensor orientation
            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorOrientation == null) {
                return imageBytes;
            }

            // Decode bitmap
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            if (bitmap == null) {
                return imageBytes;
            }

            // Calculate rotation needed
            int rotation = 0;
            boolean isFrontCamera = false;
            Integer lensFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
            
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                isFrontCamera = true;
                // Front camera: mirror the sensor orientation
                rotation = (360 - sensorOrientation) % 360;
            } else {
                // Back camera: use sensor orientation directly
                rotation = sensorOrientation;
            }

            Log.d(TAG, "correctImageRotation: Sensor orientation=" + sensorOrientation + 
                  ", front camera=" + isFrontCamera + ", applying rotation=" + rotation);

            // Apply transformations
            Matrix matrix = new Matrix();
            
            // Apply rotation
            if (rotation != 0) {
                matrix.postRotate(rotation);
            }
            
            // Mirror front camera horizontally (like a mirror)
            if (isFrontCamera) {
                matrix.postScale(-1, 1);
            }

            // Apply transformations
            if (!matrix.isIdentity()) {
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }

            // Convert back to byte array
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
            return stream.toByteArray();

        } catch (Exception e) {
            Log.e(TAG, "correctImageRotation: Error correcting rotation", e);
            return imageBytes; // Return original if correction fails
        }
    }
} 
