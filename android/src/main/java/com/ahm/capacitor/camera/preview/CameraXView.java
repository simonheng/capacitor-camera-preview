package com.ahm.capacitor.camera.preview;

import android.content.Context;
import android.os.Build;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.ahm.capacitor.camera.preview.model.CameraSessionConfiguration;
import com.ahm.capacitor.camera.preview.model.LensInfo;
import com.ahm.capacitor.camera.preview.model.ZoomFactors;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import android.hardware.camera2.CameraCharacteristics;

public class CameraXView implements LifecycleOwner {
    private static final String TAG = "CameraPreview CameraXView";

    public interface CameraXViewListener {
        void onPictureTaken(String result);
        void onPictureTakenError(String message);
        void onSampleTaken(String result);
        void onSampleTakenError(String message);
        void onCameraStarted();
        void onCameraStartError(String message);
    }

    // CameraX components
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private ImageCapture sampleImageCapture;
    private PreviewView previewView;

    // Camera state
    private CameraSelector currentCameraSelector;
    private String currentDeviceId;
    private int currentFlashMode = ImageCapture.FLASH_MODE_OFF;

    // Configuration
    private CameraSessionConfiguration sessionConfig;
    private CameraXViewListener listener;
    private final Context context;
    private final WebView webView;

    // Lifecycle
    private final LifecycleRegistry lifecycleRegistry;

    // Threading
    private HandlerThread backgroundThread;
    private final Executor mainExecutor;

    // State tracking
    private boolean isRunning = false;

    public CameraXView(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        this.lifecycleRegistry = new LifecycleRegistry(this);
        this.mainExecutor = ContextCompat.getMainExecutor(context);
        
        // Initialize lifecycle on main thread
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        } else {
            mainExecutor.execute(() -> lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED));
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    public void setListener(CameraXViewListener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void startSession(CameraSessionConfiguration config) {
        Log.d(TAG, "startSession: Starting CameraX session with config");
        Log.d(TAG, "startSession: DeviceId=" + config.getDeviceId() +
                   ", Position=" + config.getPosition() +
                   ", Dimensions=" + config.getWidth() + "x" + config.getHeight());

        this.sessionConfig = config;

        try {
            startBackgroundThread();
            lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
            setupCamera();
        } catch (Exception e) {
            Log.e(TAG, "startSession: Error during initialization", e);
            if (listener != null) {
                listener.onCameraStartError("Camera initialization failed: " + e.getMessage());
            }
        }
    }

    public void stopSession() {
        Log.d(TAG, "stopSession: Stopping CameraX session");
        isRunning = false;
        
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        stopBackgroundThread();
        removePreviewView();
        Log.d(TAG, "stopSession: CameraX session stopped");
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraXBackground");
        backgroundThread.start();
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
    }

    private void setupCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
            ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                setupPreviewView();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "setupCamera: Error initializing camera", e);
                if (listener != null) {
                    listener.onCameraStartError("Error initializing camera: " + e.getMessage());
                }
            }
        }, mainExecutor);
    }

    private void setupPreviewView() {
        Log.d(TAG, "setupPreviewView: Setting up CameraX preview view");

        // Remove existing preview view if any
        if (previewView != null) {
            removePreviewView();
        }

        // Make WebView transparent if needed
        if (sessionConfig.isToBack()) {
            webView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }

        previewView = new PreviewView(context);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        // Set layout parameters
        ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(
            sessionConfig.getWidth(),
            sessionConfig.getHeight() - sessionConfig.getPaddingBottom()
        );
        previewView.setLayoutParams(layoutParams);

        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            if (sessionConfig.isToBack()) {
                parent.addView(previewView, 0);
                parent.bringChildToFront(webView);
            } else {
                parent.addView(previewView);
            }
        }
    }

    private void removePreviewView() {
        Log.d(TAG, "removePreviewView: Removing preview view");

        if (previewView != null) {
            ViewGroup parent = (ViewGroup) previewView.getParent();
            if (parent != null) {
                parent.removeView(previewView);
            }
            previewView = null;
        }

        // Reset WebView background
        webView.setBackgroundColor(android.graphics.Color.WHITE);
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) {
            Log.e(TAG, "bindCameraUseCases: Camera provider is null");
            return;
        }

        try {
            // Build camera selector
            currentCameraSelector = buildCameraSelector();

            // Define the resolution strategy
            ResolutionStrategy resolutionStrategy = new ResolutionStrategy(
                new Size(1920, 1080), 
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
            );
            ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                .setResolutionStrategy(resolutionStrategy)
                .build();

            // Build use cases with the new ResolutionSelector
            Preview preview = new Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build();

            imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(currentFlashMode)
                .setResolutionSelector(resolutionSelector)
                .build();

            // For sample capture, we'll reuse the same imageCapture instance
            sampleImageCapture = imageCapture;

            // Connect preview to PreviewView
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            // Unbind any existing use cases and bind new ones
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, currentCameraSelector, preview, imageCapture);

            // Set initial zoom if specified
            if (sessionConfig.getZoomFactor() != 1.0f) {
                setZoomInternal(sessionConfig.getZoomFactor());
            }

            isRunning = true;
            Log.d(TAG, "bindCameraUseCases: Camera bound successfully");
            
            if (listener != null) {
                listener.onCameraStarted();
            }

        } catch (Exception e) {
            Log.e(TAG, "bindCameraUseCases: Error binding camera", e);
            if (listener != null) {
                listener.onCameraStartError("Error binding camera: " + e.getMessage());
            }
        }
    }

    private CameraSelector buildCameraSelector() {
        CameraSelector.Builder builder = new CameraSelector.Builder();

        if (sessionConfig.getDeviceId() != null && !sessionConfig.getDeviceId().isEmpty()) {
            // Try to find camera by device ID
            try {
                List<androidx.camera.core.CameraInfo> cameras = cameraProvider.getAvailableCameraInfos();
                for (androidx.camera.core.CameraInfo cameraInfo : cameras) {
                    if (sessionConfig.getDeviceId().equals(getCameraId(cameraInfo))) {
                        currentDeviceId = sessionConfig.getDeviceId();
                        // Build selector based on camera characteristics
                        if (isBackCamera(cameraInfo)) {
                            builder.requireLensFacing(CameraSelector.LENS_FACING_BACK);
                        } else {
                            builder.requireLensFacing(CameraSelector.LENS_FACING_FRONT);
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "buildCameraSelector: Error finding camera by ID, using position fallback", e);
            }
        }

        // Fallback to position-based selection
        if (currentDeviceId == null) {
            String position = sessionConfig.getPosition();
            int requiredFacing;
            if ("front".equals(position)) {
                requiredFacing = CameraSelector.LENS_FACING_FRONT;
            } else {
                requiredFacing = CameraSelector.LENS_FACING_BACK;
            }
            
            // CRITICAL: Don't use requireLensFacing() - use addCameraFilter() instead
            // This allows CameraX to switch between multiple cameras of the same direction
            Log.d(TAG, "buildCameraSelector: Enabling automatic lens switching for " + position);
            
            builder.addCameraFilter(cameraInfos -> {
                // Return ALL cameras of the required facing direction
                // This is key for automatic lens switching
                List<androidx.camera.core.CameraInfo> filteredCameras = new ArrayList<>();
                
                for (androidx.camera.core.CameraInfo cameraInfo : cameraInfos) {
                    if (cameraInfo.getLensFacing() == requiredFacing) {
                        filteredCameras.add(cameraInfo);
                    }
                }
                
                Log.d(TAG, "buildCameraSelector: Found " + filteredCameras.size() + " cameras for automatic lens switching");
                return filteredCameras;
            });
        } else {
            // For specific device ID, still use requireLensFacing
            if ("front".equals(sessionConfig.getPosition())) {
                builder.requireLensFacing(CameraSelector.LENS_FACING_FRONT);
            } else {
                builder.requireLensFacing(CameraSelector.LENS_FACING_BACK);
            }
        }

        return builder.build();
    }

    private static String getCameraId(androidx.camera.core.CameraInfo cameraInfo) {
        try {
            // Generate a stable ID based on camera characteristics
            boolean isBack = isBackCamera(cameraInfo);
            float minZoom = Objects.requireNonNull(cameraInfo.getZoomState().getValue()).getMinZoomRatio();
            float maxZoom = cameraInfo.getZoomState().getValue().getMaxZoomRatio();
            
            // Create a unique ID based on camera properties
            String position = isBack ? "back" : "front";
            return position + "_" +  minZoom + "_" + maxZoom;
        } catch (Exception e) {
            return "unknown_camera";
        }
    }

    private static boolean isBackCamera(androidx.camera.core.CameraInfo cameraInfo) {
        try {
            // Check if this camera matches the back camera selector
            CameraSelector backSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();
            
            // Try to filter cameras with back selector - if this camera is included, it's a back camera
            List<androidx.camera.core.CameraInfo> backCameras = backSelector.filter(Collections.singletonList(cameraInfo));
            return !backCameras.isEmpty();
        } catch (Exception e) {
            Log.w(TAG, "Error determining camera direction, assuming back camera", e);
            return true; // Default to back camera
        }
    }

    public void capturePhoto(int quality) {
        Log.d(TAG, "capturePhoto: Starting photo capture with quality: " + quality);
        
        if (imageCapture == null) {
            if (listener != null) {
                listener.onPictureTakenError("Camera not ready");
            }
            return;
        }

        ImageCapture.OutputFileOptions outputFileOptions = new ImageCapture.OutputFileOptions.Builder(
            new java.io.File(context.getCacheDir(), "temp_image.jpg")
        ).build();

        imageCapture.takePicture(
            outputFileOptions,
            ContextCompat.getMainExecutor(context),
            new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    Log.e(TAG, "capturePhoto: Photo capture failed", exception);
                    if (listener != null) {
                        listener.onPictureTakenError("Photo capture failed: " + exception.getMessage());
                    }
                }

                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                    // Convert to base64
                    try {
                        java.io.File tempFile = new java.io.File(context.getCacheDir(), "temp_image.jpg");
                        byte[] bytes;
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            bytes = Files.readAllBytes(tempFile.toPath());
                        } else {
                            // Fallback for older Android versions
                            java.io.FileInputStream fis = new java.io.FileInputStream(tempFile);
                            bytes = new byte[(int) tempFile.length()];
                            fis.read(bytes);
                            fis.close();
                        }

                        String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                        
                        // Clean up temp file
                        tempFile.delete();

                        if (listener != null) {
                            listener.onPictureTaken(base64);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "capturePhoto: Error converting to base64", e);
                        if (listener != null) {
                            listener.onPictureTakenError("Error processing image: " + e.getMessage());
                        }
                    }
                }
            }
        );
    }

    public void captureSample(int quality) {
        Log.d(TAG, "captureSample: Starting sample capture with quality: " + quality);
        
        if (sampleImageCapture == null) {
            if (listener != null) {
                listener.onSampleTakenError("Camera not ready");
            }
            return;
        }

        sampleImageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            new ImageCapture.OnImageCapturedCallback() {
                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    Log.e(TAG, "captureSample: Sample capture failed", exception);
                    if (listener != null) {
                        listener.onSampleTakenError("Sample capture failed: " + exception.getMessage());
                    }
                }

                @Override
                public void onCaptureSuccess(@NonNull ImageProxy image) {
                    try {
                        // Convert ImageProxy to byte array
                        byte[] bytes = imageProxyToByteArray(image);
                        String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                        
                        if (listener != null) {
                            listener.onSampleTaken(base64);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "captureSample: Error processing sample", e);
                        if (listener != null) {
                            listener.onSampleTakenError("Error processing sample: " + e.getMessage());
                        }
                    } finally {
                        image.close();
                    }
                }
            }
        );
    }

    private byte[] imageProxyToByteArray(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    public static List<com.ahm.capacitor.camera.preview.model.CameraDevice> getAvailableDevicesStatic(Context context) {
        Log.d(TAG, "getAvailableDevicesStatic: Starting CameraX device enumeration");
        List<com.ahm.capacitor.camera.preview.model.CameraDevice> devices = new ArrayList<>();
        try {
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);
            ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

            for (androidx.camera.core.CameraInfo cameraInfo : cameraProvider.getAvailableCameraInfos()) {
                List<LensInfo> lenses = new ArrayList<>();
                String position = isBackCamera(cameraInfo) ? "rear" : "front";
                float minZoom = Objects.requireNonNull(cameraInfo.getZoomState().getValue()).getMinZoomRatio();
                float maxZoom = cameraInfo.getZoomState().getValue().getMaxZoomRatio();
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Use Camera2 interop to get physical devices
                    Camera2CameraInfo camera2Info = Camera2CameraInfo.from(cameraInfo);
                    String logicalCameraId = camera2Info.getCameraId();
                    android.hardware.camera2.CameraManager cameraManager = (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                    
                    for (String physicalId : cameraManager.getCameraCharacteristics(logicalCameraId).getPhysicalCameraIds()) {
                        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(physicalId);
                        int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                        String deviceType = "wideAngle"; // default
                        assert capabilities != null;
                        if (Arrays.stream(capabilities).anyMatch(c -> c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)) {
                            deviceType = "ultraWide";
                        } else if (Arrays.stream(capabilities).anyMatch(c -> c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                             // Heuristic: check focal lengths to guess type
                            float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                            if (focalLengths != null && focalLengths.length > 0) {
                                if (focalLengths[0] < 3.0) deviceType = "ultraWide";
                                else if (focalLengths[0] > 5.0) deviceType = "telephoto";
                            }
                        }
                        // Note: Android doesn't have direct enums for dual/triple/etc. like iOS.
                        // This logic is a best-effort approximation.
                        lenses.add(new LensInfo(4.25f, deviceType, 1.0f, 1.0f));
                    }
                }

                if (lenses.isEmpty()) {
                    // Fallback for older devices or single-lens cameras
                    lenses.add(new LensInfo(4.25f, "wideAngle", 1.0f, maxZoom));
                }
                
                devices.add(new com.ahm.capacitor.camera.preview.model.CameraDevice(
                    getCameraId(cameraInfo), "Camera " + getCameraId(cameraInfo), position, lenses, minZoom, maxZoom
                ));
            }

            return devices;
        } catch (Exception e) {
            Log.e(TAG, "getAvailableDevicesStatic: Error getting devices", e);
            return Collections.emptyList();
        }
    }

    public static ZoomFactors getZoomFactorsStatic(Context context) {
        try {
            // For static method, return default zoom factors
            // We can try to detect if ultra-wide is available by checking device list
            List<com.ahm.capacitor.camera.preview.model.CameraDevice> devices = getAvailableDevicesStatic(context);
            
            float minZoom = 1.0f;
            float maxZoom = 10.0f;
            
            // Check if any device has ultra-wide support (0.5x)
            for (com.ahm.capacitor.camera.preview.model.CameraDevice device : devices) {
                Log.d(TAG, "getZoomFactorsStatic: Device " + device.getDeviceId() + " - minZoom: " + device.getMinZoom() + ", maxZoom: " + device.getMaxZoom());
                if (device.getMinZoom() < 1.0f) {
                    minZoom = device.getMinZoom();
                }
                if (device.getMaxZoom() > maxZoom) {
                    maxZoom = device.getMaxZoom();
                }
            }
            
            Log.d(TAG, "getZoomFactorsStatic: Final range - minZoom: " + minZoom + ", maxZoom: " + maxZoom);
            LensInfo defaultLens = new LensInfo(4.25f, "wideAngle", 1.0f, 1.0f);
            return new ZoomFactors(minZoom, maxZoom, 1.0f, defaultLens);
        } catch (Exception e) {
            Log.e(TAG, "getZoomFactorsStatic: Error getting zoom factors", e);
            LensInfo defaultLens = new LensInfo(4.25f, "wideAngle", 1.0f, 1.0f);
            return new ZoomFactors(1.0f, 10.0f, 1.0f, defaultLens);
        }
    }

    public ZoomFactors getZoomFactors() {
        if (camera == null) {
            return getZoomFactorsStatic(context);
        }

        try {
            // Get the current zoom from active camera
            float currentZoom = Objects.requireNonNull(camera.getCameraInfo().getZoomState().getValue()).getZoomRatio();
            
            // Get the full zoom range across all cameras for current position (front/back)
            List<androidx.camera.core.CameraInfo> availableCameras = getAvailableCamerasForCurrentPosition();
            
            float minZoom = 1.0f;
            float maxZoom = 1.0f;
            
            if (!availableCameras.isEmpty()) {
                // Find the absolute min and max zoom across all cameras
                for (androidx.camera.core.CameraInfo cameraInfo : availableCameras) {
                    try {
                        float cameraMinZoom = Objects.requireNonNull(cameraInfo.getZoomState().getValue()).getMinZoomRatio();
                        float cameraMaxZoom = cameraInfo.getZoomState().getValue().getMaxZoomRatio();
                        
                        // For multi-camera, we need to consider the effective zoom ranges
                        // If a camera has minZoom < 1.0, it likely supports ultra-wide (0.5x)
                        if (cameraMinZoom < 1.0f) {
                            minZoom = 0.5f; // Ultra-wide capability
                        }
                        
                        // Samsung devices often hide ultra-wide in logical cameras
                        // If maxZoom >= 4.0, assume ultra-wide is available even if not exposed
                        if (cameraMaxZoom >= 4.0f && minZoom >= 1.0f) {
                            Log.d(TAG, "getZoomFactors: Detected Samsung-style multi-camera (maxZoom: " + cameraMaxZoom + "), forcing ultra-wide support");
                            minZoom = 0.5f; // Force ultra-wide support
                        }
                        
                        maxZoom = Math.max(maxZoom, cameraMaxZoom);
                        
                        Log.d(TAG, "getZoomFactors: Camera " + getCameraId(cameraInfo) + " - range: " + cameraMinZoom + "-" + cameraMaxZoom);
                    } catch (Exception e) {
                        Log.w(TAG, "getZoomFactors: Error getting zoom info for camera", e);
                    }
                }
            }

            Log.d(TAG, "getZoomFactors: Combined range - minZoom: " + minZoom + ", maxZoom: " + maxZoom + ", currentZoom: " + currentZoom);
            
            return new ZoomFactors(minZoom, maxZoom, currentZoom, getCurrentLensInfo());
        } catch (Exception e) {
            Log.e(TAG, "getZoomFactors: Error getting zoom factors", e);
            return new ZoomFactors(1.0f, 1.0f, 1.0f, getCurrentLensInfo());
        }
    }



    private LensInfo getCurrentLensInfo() {
        if (camera == null) {
            return new LensInfo(4.25f, "wideAngle", 1.0f, 1.0f);
        }

        try {
            float currentZoom = Objects.requireNonNull(camera.getCameraInfo().getZoomState().getValue()).getZoomRatio();
            float minZoom = camera.getCameraInfo().getZoomState().getValue().getMinZoomRatio();
            float maxZoom = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();

            // Determine device type based on zoom capabilities
            String deviceType = "wideAngle";
            float baseZoomRatio = 1.0f;

            if (minZoom < 1.0f) {
                deviceType = "ultraWide";
                baseZoomRatio = 0.5f;
            } else if (maxZoom > 5.0f) {
                deviceType = "telephoto";
                baseZoomRatio = 2.0f;
            }

            float digitalZoom = currentZoom / baseZoomRatio;

            return new LensInfo(4.25f, deviceType, baseZoomRatio, digitalZoom);
        } catch (Exception e) {
            Log.e(TAG, "getCurrentLensInfo: Error getting lens info", e);
            return new LensInfo(4.25f, "wideAngle", 1.0f, 1.0f);
        }
    }

    public void setZoom(float zoomRatio) throws Exception {
        if (camera == null) {
            throw new Exception("Camera not initialized");
        }

        Log.d(TAG, "setZoom: Requested zoom ratio: " + zoomRatio);
        
        // Just let CameraX handle everything - it should automatically switch lenses
        try {
            ListenableFuture<Void> zoomFuture = camera.getCameraControl().setZoomRatio(zoomRatio);
            
            // Add callback to see what actually happened
            zoomFuture.addListener(() -> {
                try {
                    float actualZoom = Objects.requireNonNull(camera.getCameraInfo().getZoomState().getValue()).getZoomRatio();
                    Log.d(TAG, "setZoom: CameraX set zoom to " + actualZoom + " (requested: " + zoomRatio + ")");
                    if (Math.abs(actualZoom - zoomRatio) > 0.1f) {
                        Log.w(TAG, "setZoom: CameraX clamped zoom from " + zoomRatio + " to " + actualZoom);
                    } else {
                        Log.d(TAG, "setZoom: CameraX successfully set requested zoom");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "setZoom: Error checking final zoom", e);
                }
            }, ContextCompat.getMainExecutor(context));
            
        } catch (Exception e) {
            Log.e(TAG, "setZoom: Failed to set zoom to " + zoomRatio, e);
            throw e;
        }
    }

    private List<androidx.camera.core.CameraInfo> getAvailableCamerasForCurrentPosition() {
        if (cameraProvider == null) {
            Log.w(TAG, "getAvailableCamerasForCurrentPosition: cameraProvider is null");
            return Collections.emptyList();
        }
        
        List<androidx.camera.core.CameraInfo> allCameras = cameraProvider.getAvailableCameraInfos();
        List<androidx.camera.core.CameraInfo> sameFacingCameras = new ArrayList<>();
        
        Log.d(TAG, "getAvailableCamerasForCurrentPosition: Total cameras available: " + allCameras.size());
        
        // Determine current facing direction from the session config to avoid restricted API call
        boolean isCurrentBack = "back".equals(sessionConfig.getPosition());
        Log.d(TAG, "getAvailableCamerasForCurrentPosition: Looking for " + (isCurrentBack ? "back" : "front") + " cameras");
        
        for (int i = 0; i < allCameras.size(); i++) {
            androidx.camera.core.CameraInfo cameraInfo = allCameras.get(i);
            boolean isCameraBack = isBackCamera(cameraInfo);
            String cameraId = getCameraId(cameraInfo);
            
            Log.d(TAG, "getAvailableCamerasForCurrentPosition: Camera " + i + " - ID: " + cameraId + ", isBack: " + isCameraBack);
            
            try {
                float minZoom = Objects.requireNonNull(cameraInfo.getZoomState().getValue()).getMinZoomRatio();
                float maxZoom = cameraInfo.getZoomState().getValue().getMaxZoomRatio();
                Log.d(TAG, "getAvailableCamerasForCurrentPosition: Camera " + i + " zoom range: " + minZoom + "-" + maxZoom);
            } catch (Exception e) {
                Log.w(TAG, "getAvailableCamerasForCurrentPosition: Cannot get zoom info for camera " + i + ": " + e.getMessage());
            }
            
            if (isCameraBack == isCurrentBack) {
                sameFacingCameras.add(cameraInfo);
                Log.d(TAG, "getAvailableCamerasForCurrentPosition: Added camera " + i + " (" + cameraId + ") to same-facing list");
            }
        }
        
        Log.d(TAG, "getAvailableCamerasForCurrentPosition: Found " + sameFacingCameras.size() + " cameras for " + (isCurrentBack ? "back" : "front"));
        return sameFacingCameras;
    }

    public static List<Size> getSupportedPictureSizes(Context context, String facing) {
        List<Size> sizes = new ArrayList<>();
        try {
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);
            CameraSelector.Builder builder = new CameraSelector.Builder();
            if ("front".equals(facing)) {
                builder.requireLensFacing(CameraSelector.LENS_FACING_FRONT);
            } else {
                builder.requireLensFacing(CameraSelector.LENS_FACING_BACK);
            }

            // This part is complex because we need characteristics, which are not directly on CameraInfo.
            // For now, returning a static list of common sizes.
            // A more advanced implementation would use Camera2interop to get StreamConfigurationMap.
            sizes.add(new Size(4032, 3024));
            sizes.add(new Size(1920, 1080));
            sizes.add(new Size(1280, 720));
            sizes.add(new Size(640, 480));
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting supported picture sizes", e);
        }
        return sizes;
    }

    private void setZoomInternal(float zoomRatio) {
        if (camera != null) {
            try {
                float minZoom = Objects.requireNonNull(camera.getCameraInfo().getZoomState().getValue()).getMinZoomRatio();
                float maxZoom = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
                float currentZoom = camera.getCameraInfo().getZoomState().getValue().getZoomRatio();
                
                Log.d(TAG, "setZoomInternal: Current camera range: " + minZoom + "-" + maxZoom + ", current: " + currentZoom);
                Log.d(TAG, "setZoomInternal: Requesting zoom: " + zoomRatio);
                
                // Try to set zoom directly - let CameraX handle lens switching
                ListenableFuture<Void> zoomFuture = camera.getCameraControl().setZoomRatio(zoomRatio);
                
                zoomFuture.addListener(() -> {
                    try {
                        zoomFuture.get(); // Check if zoom was successful
                        float newZoom = Objects.requireNonNull(camera.getCameraInfo().getZoomState().getValue()).getZoomRatio();
                        Log.d(TAG, "setZoomInternal: Zoom set successfully to " + newZoom + " (requested: " + zoomRatio + ")");
                        
                        // Check if CameraX switched cameras
                        String newCameraId = getCameraId(camera.getCameraInfo());
                        if (!newCameraId.equals(currentDeviceId)) {
                            currentDeviceId = newCameraId;
                            Log.d(TAG, "setZoomInternal: CameraX switched to camera: " + newCameraId);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "setZoomInternal: Zoom operation failed: " + e.getMessage());
                        // Fallback: clamp to current camera's range
                        float clampedZoom = Math.max(minZoom, Math.min(zoomRatio, maxZoom));
                        camera.getCameraControl().setZoomRatio(clampedZoom);
                        Log.d(TAG, "setZoomInternal: Fallback - clamped zoom to " + clampedZoom);
                    }
                }, mainExecutor);

            } catch (Exception e) {
                Log.e(TAG, "setZoomInternal: Error setting zoom", e);
            }
        }
    }

    public static List<String> getSupportedFlashModesStatic() {
        try {
            // For static method, we can return common flash modes
            // Most modern cameras support these modes
            return Arrays.asList("off", "on", "auto");
        } catch (Exception e) {
            Log.e(TAG, "getSupportedFlashModesStatic: Error getting flash modes", e);
            return Collections.singletonList("off");
        }
    }

    public List<String> getSupportedFlashModes() {
        if (camera == null) {
            return getSupportedFlashModesStatic();
        }

        try {
            boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
            if (hasFlash) {
                return Arrays.asList("off", "on", "auto");
            } else {
                return Collections.singletonList("off");
            }
        } catch (Exception e) {
            Log.e(TAG, "getSupportedFlashModes: Error getting flash modes", e);
            return Collections.singletonList("off");
        }
    }

    public String getFlashMode() {
        switch (currentFlashMode) {
            case ImageCapture.FLASH_MODE_ON:
                return "on";
            case ImageCapture.FLASH_MODE_AUTO:
                return "auto";
            default:
                return "off";
        }
    }

    public void setFlashMode(String mode) {
        int flashMode;
        switch (mode) {
            case "on":
                flashMode = ImageCapture.FLASH_MODE_ON;
                break;
            case "auto":
                flashMode = ImageCapture.FLASH_MODE_AUTO;
                break;
            default:
                flashMode = ImageCapture.FLASH_MODE_OFF;
                break;
        }

        currentFlashMode = flashMode;
        
        if (imageCapture != null) {
            imageCapture.setFlashMode(flashMode);
        }
        if (sampleImageCapture != null) {
            sampleImageCapture.setFlashMode(flashMode);
        }
    }

    public String getCurrentDeviceId() {
        return currentDeviceId != null ? currentDeviceId : "unknown";
    }

    public void switchToDevice(String deviceId) {
        Log.d(TAG, "switchToDevice: Switching to device " + deviceId);
        currentDeviceId = deviceId;
        
        // Camera operations must run on main thread
        mainExecutor.execute(() -> {
            // For CameraX, we need to rebuild the camera selector and rebind
            currentCameraSelector = buildCameraSelector();
            bindCameraUseCases();
        });
    }

    public void flipCamera() {
        Log.d(TAG, "flipCamera: Flipping camera");
        
        // Determine current position based on session config and flip it
        String currentPosition = sessionConfig.getPosition();
        String newPosition = "front".equals(currentPosition) ? "rear" : "front";
        
        Log.d(TAG, "flipCamera: Switching from " + currentPosition + " to " + newPosition);
        
        sessionConfig = new CameraSessionConfiguration(
            null, // deviceId - clear device ID to force position-based selection
            newPosition, // position
            sessionConfig.getX(), // x
            sessionConfig.getY(), // y
            sessionConfig.getWidth(), // width
            sessionConfig.getHeight(), // height
            sessionConfig.getPaddingBottom(), // paddingBottom
            sessionConfig.isToBack(), // toBack
            sessionConfig.isStoreToFile(), // storeToFile
            sessionConfig.isEnableOpacity(), // enableOpacity
            sessionConfig.isEnableZoom(), // enableZoom
            sessionConfig.isDisableExifHeaderStripping(), // disableExifHeaderStripping
            sessionConfig.isDisableAudio(), // disableAudio
            sessionConfig.getZoomFactor() // zoomFactor
        );
        
        // Clear current device ID to force position-based selection
        currentDeviceId = null;
        
        // Camera operations must run on main thread
        mainExecutor.execute(() -> {
            currentCameraSelector = buildCameraSelector();
            bindCameraUseCases();
        });
    }

    public void setOpacity(float opacity) {
        if (previewView != null) {
            previewView.setAlpha(opacity);
        }
    }
} 
