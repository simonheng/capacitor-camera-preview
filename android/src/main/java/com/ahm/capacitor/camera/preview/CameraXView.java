package com.ahm.capacitor.camera.preview;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.ViewGroup;
import android.webkit.WebView;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.ahm.capacitor.camera.preview.model.CameraSessionConfiguration;
import android.widget.FrameLayout;
import com.ahm.capacitor.camera.preview.model.LensInfo;
import com.ahm.capacitor.camera.preview.model.ZoomFactors;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import android.hardware.camera2.CameraCharacteristics;
import java.util.Set;
import androidx.camera.core.ZoomState;
import androidx.camera.core.ResolutionInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import androidx.exifinterface.media.ExifInterface;
import org.json.JSONObject;
import java.nio.file.Files;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import java.io.ByteArrayOutputStream;
import android.location.Location;
import android.util.Rational;

public class CameraXView implements LifecycleOwner {
    private static final String TAG = "CameraPreview CameraXView";

    public interface CameraXViewListener {
        void onPictureTaken(String base64, JSONObject exif);
        void onPictureTakenError(String message);
        void onSampleTaken(String result);
        void onSampleTakenError(String message);
        void onCameraStarted();
        void onCameraStartError(String message);
    }

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private ImageCapture sampleImageCapture;
    private PreviewView previewView;
    private GridOverlayView gridOverlayView;
    private FrameLayout previewContainer;
    private CameraSelector currentCameraSelector;
    private String currentDeviceId;
    private int currentFlashMode = ImageCapture.FLASH_MODE_OFF;
    private CameraSessionConfiguration sessionConfig;
    private CameraXViewListener listener;
    private final Context context;
    private final WebView webView;
    private final LifecycleRegistry lifecycleRegistry;
    private final Executor mainExecutor;
    private ExecutorService cameraExecutor;
    private boolean isRunning = false;

    public CameraXView(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        this.lifecycleRegistry = new LifecycleRegistry(this);
        this.mainExecutor = ContextCompat.getMainExecutor(context);

        mainExecutor.execute(() -> lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED));
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

    private void saveImageToGallery(byte[] data) {
        try {
            File photo = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date()) + ".jpg"
            );
            FileOutputStream fos = new FileOutputStream(photo);
            fos.write(data);
            fos.close();

            // Notify the gallery of the new image
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            Uri contentUri = Uri.fromFile(photo);
            mediaScanIntent.setData(contentUri);
            context.sendBroadcast(mediaScanIntent);
        } catch (IOException e) {
            Log.e(TAG, "Error saving image to gallery", e);
        }
    }

    public void startSession(CameraSessionConfiguration config) {
        this.sessionConfig = config;
        cameraExecutor = Executors.newSingleThreadExecutor();
        mainExecutor.execute(() -> {
            lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
            setupCamera();
        });
    }

    public void stopSession() {
        isRunning = false;
        mainExecutor.execute(() -> {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
            lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
            if (cameraExecutor != null) {
                cameraExecutor.shutdownNow();
            }
            removePreviewView();
        });
    }

    private void setupCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                setupPreviewView();
                bindCameraUseCases();
            } catch (Exception e) {
                if (listener != null) {
                    listener.onCameraStartError("Error initializing camera: " + e.getMessage());
                }
            }
        }, mainExecutor);
    }

    private void setupPreviewView() {
        if (previewView != null) {
            removePreviewView();
        }
        if (sessionConfig.isToBack()) {
            webView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }

        // Create a container to hold both the preview and grid overlay
        previewContainer = new FrameLayout(context);

        // Create and setup the preview view
        previewView = new PreviewView(context);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewContainer.addView(previewView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // Create and setup the grid overlay
        gridOverlayView = new GridOverlayView(context);
        gridOverlayView.setGridMode(sessionConfig.getGridMode());
        previewContainer.addView(gridOverlayView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            parent.addView(previewContainer, new ViewGroup.LayoutParams(sessionConfig.getWidth(), sessionConfig.getHeight()));
            if(sessionConfig.isToBack()) webView.bringToFront();
        }
    }

    private void removePreviewView() {
        if (previewContainer != null) {
            ViewGroup parent = (ViewGroup) previewContainer.getParent();
            if (parent != null) {
                parent.removeView(previewContainer);
            }
            previewContainer = null;
        }
        if (previewView != null) {
            previewView = null;
        }
        if (gridOverlayView != null) {
            gridOverlayView = null;
        }
        webView.setBackgroundColor(android.graphics.Color.WHITE);
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        mainExecutor.execute(() -> {
            try {
                Log.d(TAG, "Building camera selector with deviceId: " + sessionConfig.getDeviceId() + " and position: " + sessionConfig.getPosition());
                currentCameraSelector = buildCameraSelector();

                ResolutionSelector.Builder resolutionSelectorBuilder = new ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY);

                if (sessionConfig.getAspectRatio() != null) {
                    int aspectRatio;
                    if ("16:9".equals(sessionConfig.getAspectRatio())) {
                        aspectRatio = AspectRatio.RATIO_16_9;
                    } else { // "4:3"
                        aspectRatio = AspectRatio.RATIO_4_3;
                    }
                    resolutionSelectorBuilder.setAspectRatioStrategy(new AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO));
                }

                ResolutionSelector resolutionSelector = resolutionSelectorBuilder.build();

                Preview preview = new Preview.Builder().setResolutionSelector(resolutionSelector).build();
                imageCapture = new ImageCapture.Builder().setResolutionSelector(resolutionSelector).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setFlashMode(currentFlashMode).build();
                sampleImageCapture = imageCapture;
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                // Unbind any existing use cases and bind new ones
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, currentCameraSelector, preview, imageCapture);

                // Log details about the active camera
                Log.d(TAG, "Use cases bound. Inspecting active camera and use cases.");
                CameraInfo cameraInfo = camera.getCameraInfo();
                Log.d(TAG, "Bound Camera ID: " + Camera2CameraInfo.from(cameraInfo).getCameraId());

                // Log zoom state
                ZoomState zoomState = cameraInfo.getZoomState().getValue();
                if (zoomState != null) {
                    Log.d(TAG, "Active Zoom State: " +
                        "min=" + zoomState.getMinZoomRatio() + ", " +
                        "max=" + zoomState.getMaxZoomRatio() + ", " +
                        "current=" + zoomState.getZoomRatio());
                }

                // Log physical cameras of the active camera
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Set<CameraInfo> physicalCameras = cameraInfo.getPhysicalCameraInfos();
                    Log.d(TAG, "Active camera has " + physicalCameras.size() + " physical cameras.");
                    for (CameraInfo physical : physicalCameras) {
                        Log.d(TAG, "  - Physical camera ID: " + Camera2CameraInfo.from(physical).getCameraId());
                    }
                }

                // Log resolution info
                ResolutionInfo previewResolution = preview.getResolutionInfo();
                if (previewResolution != null) {
                    Log.d(TAG, "Preview resolution: " + previewResolution.getResolution());
                }
                ResolutionInfo imageCaptureResolution = imageCapture.getResolutionInfo();
                if (imageCaptureResolution != null) {
                    Log.d(TAG, "Image capture resolution: " + imageCaptureResolution.getResolution());
                }

                // Set initial zoom if specified, prioritizing targetZoom over default zoomFactor
                float initialZoom = sessionConfig.getTargetZoom() != 1.0f ? sessionConfig.getTargetZoom() : sessionConfig.getZoomFactor();
                if (initialZoom != 1.0f) {
                    Log.d(TAG, "Applying initial zoom of " + initialZoom);
                    setZoomInternal(initialZoom);
                }

                isRunning = true;
                Log.d(TAG, "bindCameraUseCases: Camera bound successfully");
                if (listener != null) listener.onCameraStarted();
            } catch (Exception e) {
                if (listener != null) listener.onCameraStartError("Error binding camera: " + e.getMessage());
            }
        });
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private CameraSelector buildCameraSelector() {
        CameraSelector.Builder builder = new CameraSelector.Builder();
        final String deviceId = sessionConfig.getDeviceId();

        if (deviceId != null && !deviceId.isEmpty()) {
            builder.addCameraFilter(cameraInfos -> {
                for (CameraInfo cameraInfo : cameraInfos) {
                    if (deviceId.equals(Camera2CameraInfo.from(cameraInfo).getCameraId())) {
                        return Collections.singletonList(cameraInfo);
                    }
                }
                return Collections.emptyList();
            });
        } else {
            String position = sessionConfig.getPosition();
            int requiredFacing = "front".equals(position) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            builder.requireLensFacing(requiredFacing);
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

    public void capturePhoto(int quality, final boolean saveToGallery, Integer width, Integer height, Location location) {
        Log.d(TAG, "capturePhoto: Starting photo capture with quality: " + quality);

        if (imageCapture == null) {
            if (listener != null) {
                listener.onPictureTakenError("Camera not ready");
            }
            return;
        }

        File tempFile = new File(context.getCacheDir(), "temp_image.jpg");
        ImageCapture.OutputFileOptions outputFileOptions = new ImageCapture.OutputFileOptions.Builder(tempFile).build();

        imageCapture.takePicture(
            outputFileOptions,
            cameraExecutor,
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
                    try {
                        byte[] bytes = Files.readAllBytes(tempFile.toPath());
                        ExifInterface exifInterface = new ExifInterface(tempFile.getAbsolutePath());

                        if (location != null) {
                            exifInterface.setGpsInfo(location);
                        }

                        JSONObject exifData = getExifData(exifInterface);

                        if (width != null && height != null) {
                            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            Bitmap resizedBitmap = resizeBitmap(bitmap, width, height);
                            ByteArrayOutputStream stream = new ByteArrayOutputStream();
                            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream);
                            bytes = stream.toByteArray();
                        }

                        if (saveToGallery) {
                            saveImageToGallery(bytes);
                        }

                        String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);

                        tempFile.delete();

                        if (listener != null) {
                            listener.onPictureTaken(base64, exifData);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "capturePhoto: Error processing image", e);
                        if (listener != null) {
                            listener.onPictureTakenError("Error processing image: " + e.getMessage());
                        }
                    }
                }
            }
        );
    }

    private Bitmap resizeBitmap(Bitmap bitmap, int width, int height) {
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
    }

    private JSONObject getExifData(ExifInterface exifInterface) {
        JSONObject exifData = new JSONObject();
        try {
            // Add all available exif tags to a JSON object
            for (String[] tag : EXIF_TAGS) {
                String value = exifInterface.getAttribute(tag[0]);
                if (value != null) {
                    exifData.put(tag[1], value);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getExifData: Error reading exif data", e);
        }
        return exifData;
    }

    private static final String[][] EXIF_TAGS = new String[][]{
        {ExifInterface.TAG_APERTURE_VALUE, "ApertureValue"},
        {ExifInterface.TAG_ARTIST, "Artist"},
        {ExifInterface.TAG_BITS_PER_SAMPLE, "BitsPerSample"},
        {ExifInterface.TAG_BRIGHTNESS_VALUE, "BrightnessValue"},
        {ExifInterface.TAG_CFA_PATTERN, "CFAPattern"},
        {ExifInterface.TAG_COLOR_SPACE, "ColorSpace"},
        {ExifInterface.TAG_COMPONENTS_CONFIGURATION, "ComponentsConfiguration"},
        {ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL, "CompressedBitsPerPixel"},
        {ExifInterface.TAG_COMPRESSION, "Compression"},
        {ExifInterface.TAG_CONTRAST, "Contrast"},
        {ExifInterface.TAG_COPYRIGHT, "Copyright"},
        {ExifInterface.TAG_CUSTOM_RENDERED, "CustomRendered"},
        {ExifInterface.TAG_DATETIME, "DateTime"},
        {ExifInterface.TAG_DATETIME_DIGITIZED, "DateTimeDigitized"},
        {ExifInterface.TAG_DATETIME_ORIGINAL, "DateTimeOriginal"},
        {ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION, "DeviceSettingDescription"},
        {ExifInterface.TAG_DIGITAL_ZOOM_RATIO, "DigitalZoomRatio"},
        {ExifInterface.TAG_DNG_VERSION, "DNGVersion"},
        {ExifInterface.TAG_EXIF_VERSION, "ExifVersion"},
        {ExifInterface.TAG_EXPOSURE_BIAS_VALUE, "ExposureBiasValue"},
        {ExifInterface.TAG_EXPOSURE_INDEX, "ExposureIndex"},
        {ExifInterface.TAG_EXPOSURE_MODE, "ExposureMode"},
        {ExifInterface.TAG_EXPOSURE_PROGRAM, "ExposureProgram"},
        {ExifInterface.TAG_EXPOSURE_TIME, "ExposureTime"},
        {ExifInterface.TAG_FILE_SOURCE, "FileSource"},
        {ExifInterface.TAG_FLASH, "Flash"},
        {ExifInterface.TAG_FLASHPIX_VERSION, "FlashpixVersion"},
        {ExifInterface.TAG_FLASH_ENERGY, "FlashEnergy"},
        {ExifInterface.TAG_FOCAL_LENGTH, "FocalLength"},
        {ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "FocalLengthIn35mmFilm"},
        {ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT, "FocalPlaneResolutionUnit"},
        {ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION, "FocalPlaneXResolution"},
        {ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION, "FocalPlaneYResolution"},
        {ExifInterface.TAG_F_NUMBER, "FNumber"},
        {ExifInterface.TAG_GAIN_CONTROL, "GainControl"},
        {ExifInterface.TAG_GPS_ALTITUDE, "GPSAltitude"},
        {ExifInterface.TAG_GPS_ALTITUDE_REF, "GPSAltitudeRef"},
        {ExifInterface.TAG_GPS_AREA_INFORMATION, "GPSAreaInformation"},
        {ExifInterface.TAG_GPS_DATESTAMP, "GPSDateStamp"},
        {ExifInterface.TAG_GPS_DEST_BEARING, "GPSDestBearing"},
        {ExifInterface.TAG_GPS_DEST_BEARING_REF, "GPSDestBearingRef"},
        {ExifInterface.TAG_GPS_DEST_DISTANCE, "GPSDestDistance"},
        {ExifInterface.TAG_GPS_DEST_DISTANCE_REF, "GPSDestDistanceRef"},
        {ExifInterface.TAG_GPS_DEST_LATITUDE, "GPSDestLatitude"},
        {ExifInterface.TAG_GPS_DEST_LATITUDE_REF, "GPSDestLatitudeRef"},
        {ExifInterface.TAG_GPS_DEST_LONGITUDE, "GPSDestLongitude"},
        {ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, "GPSDestLongitudeRef"},
        {ExifInterface.TAG_GPS_DIFFERENTIAL, "GPSDifferential"},
        {ExifInterface.TAG_GPS_DOP, "GPSDOP"},
        {ExifInterface.TAG_GPS_IMG_DIRECTION, "GPSImgDirection"},
        {ExifInterface.TAG_GPS_IMG_DIRECTION_REF, "GPSImgDirectionRef"},
        {ExifInterface.TAG_GPS_LATITUDE, "GPSLatitude"},
        {ExifInterface.TAG_GPS_LATITUDE_REF, "GPSLatitudeRef"},
        {ExifInterface.TAG_GPS_LONGITUDE, "GPSLongitude"},
        {ExifInterface.TAG_GPS_LONGITUDE_REF, "GPSLongitudeRef"},
        {ExifInterface.TAG_GPS_MAP_DATUM, "GPSMapDatum"},
        {ExifInterface.TAG_GPS_MEASURE_MODE, "GPSMeasureMode"},
        {ExifInterface.TAG_GPS_PROCESSING_METHOD, "GPSProcessingMethod"},
        {ExifInterface.TAG_GPS_SATELLITES, "GPSSatellites"},
        {ExifInterface.TAG_GPS_SPEED, "GPSSpeed"},
        {ExifInterface.TAG_GPS_SPEED_REF, "GPSSpeedRef"},
        {ExifInterface.TAG_GPS_STATUS, "GPSStatus"},
        {ExifInterface.TAG_GPS_TIMESTAMP, "GPSTimeStamp"},
        {ExifInterface.TAG_GPS_TRACK, "GPSTrack"},
        {ExifInterface.TAG_GPS_TRACK_REF, "GPSTrackRef"},
        {ExifInterface.TAG_GPS_VERSION_ID, "GPSVersionID"},
        {ExifInterface.TAG_IMAGE_DESCRIPTION, "ImageDescription"},
        {ExifInterface.TAG_IMAGE_LENGTH, "ImageLength"},
        {ExifInterface.TAG_IMAGE_UNIQUE_ID, "ImageUniqueID"},
        {ExifInterface.TAG_IMAGE_WIDTH, "ImageWidth"},
        {ExifInterface.TAG_INTEROPERABILITY_INDEX, "InteroperabilityIndex"},
        {ExifInterface.TAG_ISO_SPEED, "ISOSpeed"},
        {ExifInterface.TAG_ISO_SPEED_LATITUDE_YYY, "ISOSpeedLatitudeyyy"},
        {ExifInterface.TAG_ISO_SPEED_LATITUDE_ZZZ, "ISOSpeedLatitudezzz"},
        {ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT, "JPEGInterchangeFormat"},
        {ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH, "JPEGInterchangeFormatLength"},
        {ExifInterface.TAG_LIGHT_SOURCE, "LightSource"},
        {ExifInterface.TAG_MAKE, "Make"},
        {ExifInterface.TAG_MAKER_NOTE, "MakerNote"},
        {ExifInterface.TAG_MAX_APERTURE_VALUE, "MaxApertureValue"},
        {ExifInterface.TAG_METERING_MODE, "MeteringMode"},
        {ExifInterface.TAG_MODEL, "Model"},
        {ExifInterface.TAG_NEW_SUBFILE_TYPE, "NewSubfileType"},
        {ExifInterface.TAG_OECF, "OECF"},
        {ExifInterface.TAG_OFFSET_TIME, "OffsetTime"},
        {ExifInterface.TAG_OFFSET_TIME_DIGITIZED, "OffsetTimeDigitized"},
        {ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "OffsetTimeOriginal"},
        {ExifInterface.TAG_ORF_ASPECT_FRAME, "ORFAspectFrame"},
        {ExifInterface.TAG_ORF_PREVIEW_IMAGE_LENGTH, "ORFPreviewImageLength"},
        {ExifInterface.TAG_ORF_PREVIEW_IMAGE_START, "ORFPreviewImageStart"},
        {ExifInterface.TAG_ORF_THUMBNAIL_IMAGE, "ORFThumbnailImage"},
        {ExifInterface.TAG_ORIENTATION, "Orientation"},
        {ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION, "PhotometricInterpretation"},
        {ExifInterface.TAG_PIXEL_X_DIMENSION, "PixelXDimension"},
        {ExifInterface.TAG_PIXEL_Y_DIMENSION, "PixelYDimension"},
        {ExifInterface.TAG_PLANAR_CONFIGURATION, "PlanarConfiguration"},
        {ExifInterface.TAG_PRIMARY_CHROMATICITIES, "PrimaryChromaticities"},
        {ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX, "RecommendedExposureIndex"},
        {ExifInterface.TAG_REFERENCE_BLACK_WHITE, "ReferenceBlackWhite"},
        {ExifInterface.TAG_RELATED_SOUND_FILE, "RelatedSoundFile"},
        {ExifInterface.TAG_RESOLUTION_UNIT, "ResolutionUnit"},
        {ExifInterface.TAG_ROWS_PER_STRIP, "RowsPerStrip"},
        {ExifInterface.TAG_RW2_ISO, "RW2ISO"},
        {ExifInterface.TAG_RW2_JPG_FROM_RAW, "RW2JpgFromRaw"},
        {ExifInterface.TAG_RW2_SENSOR_BOTTOM_BORDER, "RW2SensorBottomBorder"},
        {ExifInterface.TAG_RW2_SENSOR_LEFT_BORDER, "RW2SensorLeftBorder"},
        {ExifInterface.TAG_RW2_SENSOR_RIGHT_BORDER, "RW2SensorRightBorder"},
        {ExifInterface.TAG_RW2_SENSOR_TOP_BORDER, "RW2SensorTopBorder"},
        {ExifInterface.TAG_SAMPLES_PER_PIXEL, "SamplesPerPixel"},
        {ExifInterface.TAG_SATURATION, "Saturation"},
        {ExifInterface.TAG_SCENE_CAPTURE_TYPE, "SceneCaptureType"},
        {ExifInterface.TAG_SCENE_TYPE, "SceneType"},
        {ExifInterface.TAG_SENSING_METHOD, "SensingMethod"},
        {ExifInterface.TAG_SENSITIVITY_TYPE, "SensitivityType"},
        {ExifInterface.TAG_SHARPNESS, "Sharpness"},
        {ExifInterface.TAG_SHUTTER_SPEED_VALUE, "ShutterSpeedValue"},
        {ExifInterface.TAG_SOFTWARE, "Software"},
        {ExifInterface.TAG_SPATIAL_FREQUENCY_RESPONSE, "SpatialFrequencyResponse"},
        {ExifInterface.TAG_SPECTRAL_SENSITIVITY, "SpectralSensitivity"},
        {ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY, "StandardOutputSensitivity"},
        {ExifInterface.TAG_STRIP_BYTE_COUNTS, "StripByteCounts"},
        {ExifInterface.TAG_STRIP_OFFSETS, "StripOffsets"},
        {ExifInterface.TAG_SUBFILE_TYPE, "SubfileType"},
        {ExifInterface.TAG_SUBJECT_AREA, "SubjectArea"},
        {ExifInterface.TAG_SUBJECT_DISTANCE, "SubjectDistance"},
        {ExifInterface.TAG_SUBJECT_DISTANCE_RANGE, "SubjectDistanceRange"},
        {ExifInterface.TAG_SUBJECT_LOCATION, "SubjectLocation"},
        {ExifInterface.TAG_SUBSEC_TIME, "SubSecTime"},
        {ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, "SubSecTimeDigitized"},
        {ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "SubSecTimeOriginal"},
        {ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, "ThumbnailImageLength"},
        {ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH, "ThumbnailImageWidth"},
        {ExifInterface.TAG_TRANSFER_FUNCTION, "TransferFunction"},
        {ExifInterface.TAG_USER_COMMENT, "UserComment"},
        {ExifInterface.TAG_WHITE_BALANCE, "WhiteBalance"},
        {ExifInterface.TAG_WHITE_POINT, "WhitePoint"},
        {ExifInterface.TAG_X_RESOLUTION, "XResolution"},
        {ExifInterface.TAG_Y_CB_CR_COEFFICIENTS, "YCbCrCoefficients"},
        {ExifInterface.TAG_Y_CB_CR_POSITIONING, "YCbCrPositioning"},
        {ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING, "YCbCrSubSampling"},
        {ExifInterface.TAG_Y_RESOLUTION, "YResolution"}
    };

    public void captureSample(int quality) {
        Log.d(TAG, "captureSample: Starting sample capture with quality: " + quality);

        if (sampleImageCapture == null) {
            if (listener != null) {
                listener.onSampleTakenError("Camera not ready");
            }
            return;
        }

        sampleImageCapture.takePicture(
            cameraExecutor,
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

// not workin for xiaomi https://xiaomi.eu/community/threads/mi-11-ultra-unable-to-access-camera-lenses-in-apps-camera2-api.61456/
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    public static List<com.ahm.capacitor.camera.preview.model.CameraDevice> getAvailableDevicesStatic(Context context) {
        Log.d(TAG, "getAvailableDevicesStatic: Starting CameraX device enumeration with getPhysicalCameraInfos.");
        List<com.ahm.capacitor.camera.preview.model.CameraDevice> devices = new ArrayList<>();
        try {
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);
            ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            for (CameraInfo cameraInfo : cameraProvider.getAvailableCameraInfos()) {
                String logicalCameraId = Camera2CameraInfo.from(cameraInfo).getCameraId();
                String position = isBackCamera(cameraInfo) ? "rear" : "front";

                // Add logical camera
                float minZoom = Objects.requireNonNull(cameraInfo.getZoomState().getValue()).getMinZoomRatio();
                float maxZoom = cameraInfo.getZoomState().getValue().getMaxZoomRatio();
                List<LensInfo> logicalLenses = new ArrayList<>();
                logicalLenses.add(new LensInfo(4.25f, "wideAngle", 1.0f, maxZoom));
                devices.add(new com.ahm.capacitor.camera.preview.model.CameraDevice(
                    logicalCameraId, "Logical Camera (" + position + ")", position, logicalLenses, minZoom, maxZoom, true
                ));
                Log.d(TAG, "Found logical camera: " + logicalCameraId + " (" + position + ") with zoom " + minZoom + "-" + maxZoom);

                // Get and add physical cameras
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Set<CameraInfo> physicalCameraInfos = cameraInfo.getPhysicalCameraInfos();
                    if (physicalCameraInfos.isEmpty()) continue;

                    Log.d(TAG, "Logical camera " + logicalCameraId + " has " + physicalCameraInfos.size() + " physical cameras.");

                    for (CameraInfo physicalCameraInfo : physicalCameraInfos) {
                        String physicalId = Camera2CameraInfo.from(physicalCameraInfo).getCameraId();
                        if (physicalId.equals(logicalCameraId)) continue; // Already added as logical

                        try {
                            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(physicalId);
                            String deviceType = "wideAngle";
                            float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                            android.util.SizeF sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);

                            if (focalLengths != null && focalLengths.length > 0 && sensorSize != null && sensorSize.getWidth() > 0) {
                                double fov = 2 * Math.toDegrees(Math.atan(sensorSize.getWidth() / (2 * focalLengths[0])));
                                if (fov > 90) deviceType = "ultraWide";
                                else if (fov < 40) deviceType = "telephoto";
                            } else if (focalLengths != null && focalLengths.length > 0) {
                                if (focalLengths[0] < 3.0f) deviceType = "ultraWide";
                                else if (focalLengths[0] > 5.0f) deviceType = "telephoto";
                            }

                            float physicalMinZoom = 1.0f;
                            float physicalMaxZoom = 1.0f;
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                android.util.Range<Float> zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                                if (zoomRange != null) {
                                    physicalMinZoom = zoomRange.getLower();
                                    physicalMaxZoom = zoomRange.getUpper();
                                }
                            }

                            String label = "Physical " + deviceType + " (" + position + ")";
                            List<LensInfo> physicalLenses = new ArrayList<>();
                            physicalLenses.add(new LensInfo(focalLengths != null ? focalLengths[0] : 4.25f, deviceType, 1.0f, physicalMaxZoom));

                            devices.add(new com.ahm.capacitor.camera.preview.model.CameraDevice(
                                physicalId, label, position, physicalLenses, physicalMinZoom, physicalMaxZoom, false
                            ));
                            Log.d(TAG, "Found physical camera: " + physicalId + " (" + label + ")");
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Failed to access characteristics for physical camera " + physicalId, e);
                        }
                    }
                }
            }
            return devices;
        } catch (Exception e) {
            Log.e(TAG, "getAvailableDevicesStatic: Error getting devices", e);
            return Collections.emptyList();
        }
    }

    public static ZoomFactors getZoomFactorsStatic() {
        try {
            // For static method, return default zoom factors
            // We can try to detect if ultra-wide is available by checking device list

            float minZoom = 1.0f;
            float maxZoom = 10.0f;

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
            return getZoomFactorsStatic();
        }

        try {
            // Get the current zoom from active camera
            float currentZoom = Objects.requireNonNull(camera.getCameraInfo().getZoomState().getValue()).getZoomRatio();
            float minZoom = camera.getCameraInfo().getZoomState().getValue().getMinZoomRatio();
            float maxZoom = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();

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

            // Determine device type based on zoom capabilities
            String deviceType = "wideAngle";
            float baseZoomRatio = 1.0f;

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
            }, mainExecutor);

        } catch (Exception e) {
            Log.e(TAG, "setZoom: Failed to set zoom to " + zoomRatio, e);
            throw e;
        }
    }

    public static List<Size> getSupportedPictureSizes(String facing) {
        List<Size> sizes = new ArrayList<>();
        try {
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

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    public void switchToDevice(String deviceId) {
        Log.d(TAG, "switchToDevice: Attempting to switch to device " + deviceId);

        mainExecutor.execute(() -> {
            try {
                // Standard physical device selection logic...
                List<CameraInfo> cameraInfos = cameraProvider.getAvailableCameraInfos();
                CameraInfo targetCameraInfo = null;
                for (CameraInfo cameraInfo : cameraInfos) {
                    if (deviceId.equals(Camera2CameraInfo.from(cameraInfo).getCameraId())) {
                        targetCameraInfo = cameraInfo;
                        break;
                    }
                }

                if (targetCameraInfo != null) {
                    Log.d(TAG, "switchToDevice: Found matching CameraInfo for deviceId: " + deviceId);
                    final CameraInfo finalTarget = targetCameraInfo;

                    // This filter will receive a list of all cameras and must return the one we want.

                    currentCameraSelector = new CameraSelector.Builder()
                        .addCameraFilter(cameras -> {
                            // This filter will receive a list of all cameras and must return the one we want.
                            return Collections.singletonList(finalTarget);
                        }).build();
                    currentDeviceId = deviceId;
                    bindCameraUseCases(); // Rebind with the new, highly specific selector
                } else {
                    Log.e(TAG, "switchToDevice: Could not find any CameraInfo matching deviceId: " + deviceId);
                }
            } catch (Exception e) {
                Log.e(TAG, "switchToDevice: Error switching camera", e);
            }
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
            sessionConfig.getZoomFactor(), // zoomFactor
            sessionConfig.getAspectRatio(), // aspectRatio
            sessionConfig.getGridMode() // gridMode
        );

        // Clear current device ID to force position-based selection
        currentDeviceId = null;

        // Camera operations must run on main thread
        cameraExecutor.execute(() -> {
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
