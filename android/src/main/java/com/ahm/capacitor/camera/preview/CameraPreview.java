package com.ahm.capacitor.camera.preview;

import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.RECORD_AUDIO;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.location.Location;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.ViewGroup;
import com.ahm.capacitor.camera.preview.model.CameraDevice;
import com.ahm.capacitor.camera.preview.model.CameraSessionConfiguration;
import com.ahm.capacitor.camera.preview.model.LensInfo;
import com.ahm.capacitor.camera.preview.model.ZoomFactors;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import java.util.List;
import java.util.Objects;
import org.json.JSONObject;

@CapacitorPlugin(
  name = "CameraPreview",
  permissions = {
    @Permission(
      strings = { CAMERA, RECORD_AUDIO },
      alias = CameraPreview.CAMERA_WITH_AUDIO_PERMISSION_ALIAS
    ),
    @Permission(
      strings = { CAMERA },
      alias = CameraPreview.CAMERA_ONLY_PERMISSION_ALIAS
    ),
    @Permission(
      strings = {
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
      },
      alias = CameraPreview.CAMERA_WITH_LOCATION_PERMISSION_ALIAS
    ),
  }
)
public class CameraPreview
  extends Plugin
  implements CameraXView.CameraXViewListener {

  static final String CAMERA_WITH_AUDIO_PERMISSION_ALIAS = "cameraWithAudio";
  static final String CAMERA_ONLY_PERMISSION_ALIAS = "cameraOnly";
  static final String CAMERA_WITH_LOCATION_PERMISSION_ALIAS =
    "cameraWithLocation";

  private String captureCallbackId = "";
  private String snapshotCallbackId = "";
  private String cameraStartCallbackId = "";
  private int previousOrientationRequest =
    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
  private CameraXView cameraXView;
  private FusedLocationProviderClient fusedLocationClient;
  private Location lastLocation;

  @PluginMethod
  public void start(PluginCall call) {
    boolean disableAudio = Boolean.TRUE.equals(
      call.getBoolean("disableAudio", true)
    );
    String permissionAlias = disableAudio
      ? CAMERA_ONLY_PERMISSION_ALIAS
      : CAMERA_WITH_AUDIO_PERMISSION_ALIAS;

    if (PermissionState.GRANTED.equals(getPermissionState(permissionAlias))) {
      startCamera(call);
    } else {
      requestPermissionForAlias(
        permissionAlias,
        call,
        "handleCameraPermissionResult"
      );
    }
  }

  @PluginMethod
  public void flip(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }
    cameraXView.flipCamera();
    call.resolve();
  }

  @PluginMethod
  public void capture(final PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }

    final boolean withExifLocation = call.getBoolean("withExifLocation", false);

    if (withExifLocation) {
      if (
        getPermissionState(CAMERA_WITH_LOCATION_PERMISSION_ALIAS) !=
        PermissionState.GRANTED
      ) {
        requestPermissionForAlias(
          CAMERA_WITH_LOCATION_PERMISSION_ALIAS,
          call,
          "captureWithLocationPermission"
        );
      } else {
        getLocationAndCapture(call);
      }
    } else {
      captureWithoutLocation(call);
    }
  }

  @PermissionCallback
  private void captureWithLocationPermission(PluginCall call) {
    if (
      getPermissionState(CAMERA_WITH_LOCATION_PERMISSION_ALIAS) ==
      PermissionState.GRANTED
    ) {
      getLocationAndCapture(call);
    } else {
      Logger.warn(
        "Location permission denied. Capturing photo without location data."
      );
      captureWithoutLocation(call);
    }
  }

  private void getLocationAndCapture(PluginCall call) {
    if (fusedLocationClient == null) {
      fusedLocationClient = LocationServices.getFusedLocationProviderClient(
        getContext()
      );
    }
    fusedLocationClient
      .getLastLocation()
      .addOnSuccessListener(getActivity(), location -> {
        lastLocation = location;
        proceedWithCapture(call, lastLocation);
      })
      .addOnFailureListener(e -> {
        Logger.error("Failed to get location: " + e.getMessage());
        proceedWithCapture(call, null);
      });
  }

  private void captureWithoutLocation(PluginCall call) {
    proceedWithCapture(call, null);
  }

  private void proceedWithCapture(PluginCall call, Location location) {
    bridge.saveCall(call);
    captureCallbackId = call.getCallbackId();

    Integer quality = Objects.requireNonNull(call.getInt("quality", 85));
    final boolean saveToGallery = call.getBoolean("saveToGallery", false);
    Integer width = call.getInt("width");
    Integer height = call.getInt("height");

    cameraXView.capturePhoto(quality, saveToGallery, width, height, location);
  }

  @PluginMethod
  public void captureSample(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }
    bridge.saveCall(call);
    snapshotCallbackId = call.getCallbackId();
    Integer quality = Objects.requireNonNull(call.getInt("quality", 85));
    cameraXView.captureSample(quality);
  }

  @PluginMethod
  public void stop(final PluginCall call) {
    bridge
      .getActivity()
      .runOnUiThread(() -> {
        getBridge()
          .getActivity()
          .setRequestedOrientation(previousOrientationRequest);

        if (cameraXView != null && cameraXView.isRunning()) {
          cameraXView.stopSession();
          cameraXView = null;
        }
        call.resolve();
      });
  }

  @PluginMethod
  public void getSupportedFlashModes(PluginCall call) {
    List<String> supportedFlashModes = cameraXView.getSupportedFlashModes();
    JSArray jsonFlashModes = new JSArray();
    for (String mode : supportedFlashModes) {
      jsonFlashModes.put(mode);
    }
    JSObject jsObject = new JSObject();
    jsObject.put("result", jsonFlashModes);
    call.resolve(jsObject);
  }

  @PluginMethod
  public void setFlashMode(PluginCall call) {
    String flashMode = call.getString("flashMode");
    if (flashMode == null || flashMode.isEmpty()) {
      call.reject("flashMode required parameter is missing");
      return;
    }
    cameraXView.setFlashMode(flashMode);
    call.resolve();
  }

  @PluginMethod
  public void getAvailableDevices(PluginCall call) {
    List<CameraDevice> devices = CameraXView.getAvailableDevicesStatic(
      getContext()
    );
    JSArray devicesArray = new JSArray();
    for (CameraDevice device : devices) {
      JSObject deviceJson = new JSObject();
      deviceJson.put("deviceId", device.getDeviceId());
      deviceJson.put("label", device.getLabel());
      deviceJson.put("position", device.getPosition());
      JSArray lensesArray = new JSArray();
      for (com.ahm.capacitor.camera.preview.model.LensInfo lens : device.getLenses()) {
        JSObject lensJson = new JSObject();
        lensJson.put("focalLength", lens.getFocalLength());
        lensJson.put("deviceType", lens.getDeviceType());
        lensJson.put("baseZoomRatio", lens.getBaseZoomRatio());
        lensJson.put("digitalZoom", lens.getDigitalZoom());
        lensesArray.put(lensJson);
      }
      deviceJson.put("lenses", lensesArray);
      deviceJson.put("minZoom", device.getMinZoom());
      deviceJson.put("maxZoom", device.getMaxZoom());
      devicesArray.put(deviceJson);
    }
    JSObject result = new JSObject();
    result.put("devices", devicesArray);
    call.resolve(result);
  }

  @PluginMethod
  public void getZoom(PluginCall call) {
    ZoomFactors zoomFactors = cameraXView.getZoomFactors();
    JSObject result = new JSObject();
    result.put("min", zoomFactors.getMin());
    result.put("max", zoomFactors.getMax());
    result.put("current", zoomFactors.getCurrent());
    call.resolve(result);
  }

  @PluginMethod
  public void setZoom(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }
    Float level = call.getFloat("level");
    if (level == null) {
      call.reject("level parameter is required");
      return;
    }
    try {
      cameraXView.setZoom(level);
      call.resolve();
    } catch (Exception e) {
      call.reject("Failed to set zoom: " + e.getMessage());
    }
  }

  @PluginMethod
  public void setFocus(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }
    Float x = call.getFloat("x");
    Float y = call.getFloat("y");
    if (x == null || y == null) {
      call.reject("x and y parameters are required");
      return;
    }
    // Ensure values are between 0 and 1
    float normalizedX = Math.max(0f, Math.min(1f, x));
    float normalizedY = Math.max(0f, Math.min(1f, y));

    getActivity()
      .runOnUiThread(() -> {
        try {
          cameraXView.setFocus(normalizedX, normalizedY);
          call.resolve();
        } catch (Exception e) {
          call.reject("Failed to set focus: " + e.getMessage());
        }
      });
  }

  @PluginMethod
  public void setDeviceId(PluginCall call) {
    String deviceId = call.getString("deviceId");
    if (deviceId == null || deviceId.isEmpty()) {
      call.reject("deviceId parameter is required");
      return;
    }
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }
    cameraXView.switchToDevice(deviceId);
    call.resolve();
  }

  @PluginMethod
  public void getSupportedPictureSizes(final PluginCall call) {
    JSArray supportedPictureSizesResult = new JSArray();
    List<Size> rearSizes = CameraXView.getSupportedPictureSizes("rear");
    JSObject rear = new JSObject();
    rear.put("facing", "rear");
    JSArray rearSizesJs = new JSArray();
    for (Size size : rearSizes) {
      JSObject sizeJs = new JSObject();
      sizeJs.put("width", size.getWidth());
      sizeJs.put("height", size.getHeight());
      rearSizesJs.put(sizeJs);
    }
    rear.put("supportedPictureSizes", rearSizesJs);
    supportedPictureSizesResult.put(rear);

    List<Size> frontSizes = CameraXView.getSupportedPictureSizes("front");
    JSObject front = new JSObject();
    front.put("facing", "front");
    JSArray frontSizesJs = new JSArray();
    for (Size size : frontSizes) {
      JSObject sizeJs = new JSObject();
      sizeJs.put("width", size.getWidth());
      sizeJs.put("height", size.getHeight());
      frontSizesJs.put(sizeJs);
    }
    front.put("supportedPictureSizes", frontSizesJs);
    supportedPictureSizesResult.put(front);

    JSObject ret = new JSObject();
    ret.put("supportedPictureSizes", supportedPictureSizesResult);
    call.resolve(ret);
  }

  @PluginMethod
  public void setOpacity(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }
    Float opacity = call.getFloat("opacity", 1.0f);
    cameraXView.setOpacity(opacity);
    call.resolve();
  }

  @PluginMethod
  public void getHorizontalFov(PluginCall call) {
    // CameraX does not provide a simple way to get FoV.
    // This would require Camera2 interop to access camera characteristics.
    // Returning a default/estimated value.
    JSObject ret = new JSObject();
    ret.put("result", 60.0); // A common default FoV
    call.resolve(ret);
  }

  @PluginMethod
  public void getDeviceId(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }
    JSObject ret = new JSObject();
    ret.put("deviceId", cameraXView.getCurrentDeviceId());
    call.resolve(ret);
  }

  @PluginMethod
  public void getFlashMode(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }
    JSObject ret = new JSObject();
    ret.put("flashMode", cameraXView.getFlashMode());
    call.resolve(ret);
  }

  @PluginMethod
  public void isRunning(PluginCall call) {
    boolean running = cameraXView != null && cameraXView.isRunning();
    JSObject jsObject = new JSObject();
    jsObject.put("isRunning", running);
    call.resolve(jsObject);
  }

  @PermissionCallback
  private void handleCameraPermissionResult(PluginCall call) {
    if (
      PermissionState.GRANTED.equals(
        getPermissionState(CAMERA_ONLY_PERMISSION_ALIAS)
      ) ||
      PermissionState.GRANTED.equals(
        getPermissionState(CAMERA_WITH_AUDIO_PERMISSION_ALIAS)
      )
    ) {
      startCamera(call);
    } else {
      call.reject("Permission failed");
    }
  }

  private void startCamera(final PluginCall call) {
    String positionParam = call.getString("position");
    String originalDeviceId = call.getString("deviceId");
    String deviceId = originalDeviceId; // Use a mutable variable

    final String position = (positionParam == null ||
        positionParam.isEmpty() ||
        "rear".equals(positionParam) ||
        "back".equals(positionParam))
      ? "back"
      : "front";
    final int x = call.getInt("x", 0);
    final int y = call.getInt("y", 0);
    final int width = call.getInt("width", 0);
    final int height = call.getInt("height", 0);
    final int paddingBottom = call.getInt("paddingBottom", 0);
    final boolean toBack = Boolean.TRUE.equals(call.getBoolean("toBack", true));
    final boolean storeToFile = Boolean.TRUE.equals(
      call.getBoolean("storeToFile", false)
    );
    final boolean enableOpacity = Boolean.TRUE.equals(
      call.getBoolean("enableOpacity", false)
    );
    final boolean enableZoom = Boolean.TRUE.equals(
      call.getBoolean("enableZoom", false)
    );
    final boolean disableExifHeaderStripping = Boolean.TRUE.equals(
      call.getBoolean("disableExifHeaderStripping", false)
    );
    final boolean lockOrientation = Boolean.TRUE.equals(
      call.getBoolean("lockAndroidOrientation", false)
    );
    final boolean disableAudio = Boolean.TRUE.equals(
      call.getBoolean("disableAudio", true)
    );
    final String aspectRatio = call.getString("aspectRatio", "4:3");
    final String gridMode = call.getString("gridMode", "none");

    // Check for conflict between aspectRatio and size
    if (
      call.getData().has("aspectRatio") &&
      (call.getData().has("width") || call.getData().has("height"))
    ) {
      call.reject(
        "Cannot set both aspectRatio and size (width/height). Use setPreviewSize after start."
      );
      return;
    }

    float targetZoom = 1.0f;
    // Check if the selected device is a physical ultra-wide
    if (originalDeviceId != null) {
      List<CameraDevice> devices = CameraXView.getAvailableDevicesStatic(
        getContext()
      );
      for (CameraDevice device : devices) {
        if (
          originalDeviceId.equals(device.getDeviceId()) && !device.isLogical()
        ) {
          for (LensInfo lens : device.getLenses()) {
            if ("ultraWide".equals(lens.getDeviceType())) {
              Log.d(
                "CameraPreview",
                "Ultra-wide lens selected. Targeting 0.5x zoom on logical camera."
              );
              targetZoom = 0.5f;
              // Force the use of the logical camera by clearing the specific deviceId
              deviceId = null;
              break;
            }
          }
        }
        if (deviceId == null) break; // Exit outer loop once we've made our decision
      }
    }

    previousOrientationRequest = getBridge()
      .getActivity()
      .getRequestedOrientation();
    cameraXView = new CameraXView(getContext(), getBridge().getWebView());
    cameraXView.setListener(this);

    String finalDeviceId = deviceId;
    float finalTargetZoom = targetZoom;
    getBridge()
      .getActivity()
      .runOnUiThread(() -> {
        DisplayMetrics metrics = getBridge()
          .getActivity()
          .getResources()
          .getDisplayMetrics();
        if (lockOrientation) {
          getBridge()
            .getActivity()
            .setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        }

        // Debug: Let's check all the positioning information
        ViewGroup webViewParent = (ViewGroup) getBridge()
          .getWebView()
          .getParent();

        // Get webview position in different coordinate systems
        int[] webViewLocationInWindow = new int[2];
        int[] webViewLocationOnScreen = new int[2];
        getBridge().getWebView().getLocationInWindow(webViewLocationInWindow);
        getBridge().getWebView().getLocationOnScreen(webViewLocationOnScreen);

        int webViewLeft = getBridge().getWebView().getLeft();
        int webViewTop = getBridge().getWebView().getTop();

        // Check parent position too
        int[] parentLocationInWindow = new int[2];
        int[] parentLocationOnScreen = new int[2];
        webViewParent.getLocationInWindow(parentLocationInWindow);
        webViewParent.getLocationOnScreen(parentLocationOnScreen);

        // Calculate pixel ratio
        float pixelRatio = metrics.density;

        // Try using just the pixel ratio without any webview offset for now
        int computedX = (int) (x * pixelRatio);
        int computedY = (int) (y * pixelRatio);

        Log.d("CameraPreview", "=== COORDINATE DEBUG ===");
        Log.d(
          "CameraPreview",
          "WebView getLeft/getTop: (" + webViewLeft + ", " + webViewTop + ")"
        );
        Log.d(
          "CameraPreview",
          "WebView locationInWindow: (" +
          webViewLocationInWindow[0] +
          ", " +
          webViewLocationInWindow[1] +
          ")"
        );
        Log.d(
          "CameraPreview",
          "WebView locationOnScreen: (" +
          webViewLocationOnScreen[0] +
          ", " +
          webViewLocationOnScreen[1] +
          ")"
        );
        Log.d(
          "CameraPreview",
          "Parent locationInWindow: (" +
          parentLocationInWindow[0] +
          ", " +
          parentLocationInWindow[1] +
          ")"
        );
        Log.d(
          "CameraPreview",
          "Parent locationOnScreen: (" +
          parentLocationOnScreen[0] +
          ", " +
          parentLocationOnScreen[1] +
          ")"
        );
        Log.d(
          "CameraPreview",
          "Parent class: " + webViewParent.getClass().getSimpleName()
        );
        Log.d(
          "CameraPreview",
          "Requested position (logical): (" + x + ", " + y + ")"
        );
        Log.d("CameraPreview", "Pixel ratio: " + pixelRatio);
        Log.d(
          "CameraPreview",
          "Final computed position (no offset): (" +
          computedX +
          ", " +
          computedY +
          ")"
        );
        Log.d("CameraPreview", "========================");
        int computedWidth = width != 0
          ? (int) (width * pixelRatio)
          : getBridge().getWebView().getWidth();
        int computedHeight = height != 0
          ? (int) (height * pixelRatio)
          : getBridge().getWebView().getHeight();
        computedHeight -= (int) (paddingBottom * pixelRatio);

        CameraSessionConfiguration config = new CameraSessionConfiguration(
          finalDeviceId,
          position,
          computedX,
          computedY,
          computedWidth,
          computedHeight,
          paddingBottom,
          toBack,
          storeToFile,
          enableOpacity,
          enableZoom,
          disableExifHeaderStripping,
          disableAudio,
          1.0f,
          aspectRatio,
          gridMode
        );
        config.setTargetZoom(finalTargetZoom);

        bridge.saveCall(call);
        cameraStartCallbackId = call.getCallbackId();
        cameraXView.startSession(config);
      });
  }

  @Override
  public void onPictureTaken(String base64, JSONObject exif) {
    PluginCall pluginCall = bridge.getSavedCall(captureCallbackId);
    if (pluginCall == null) {
      Log.e("CameraPreview", "onPictureTaken: captureCallbackId is null");
      return;
    }
    JSObject result = new JSObject();
    result.put("value", base64);
    result.put("exif", exif);
    pluginCall.resolve(result);
    bridge.releaseCall(pluginCall);
  }

  @Override
  public void onPictureTakenError(String message) {
    PluginCall pluginCall = bridge.getSavedCall(captureCallbackId);
    if (pluginCall == null) {
      Log.e("CameraPreview", "onPictureTakenError: captureCallbackId is null");
      return;
    }
    pluginCall.reject(message);
    bridge.releaseCall(pluginCall);
  }

  @Override
  public void onCameraStarted(int width, int height, int x, int y) {
    PluginCall call = bridge.getSavedCall(cameraStartCallbackId);
    if (call != null) {
      // Convert pixel values back to logical units
      DisplayMetrics metrics = getBridge()
        .getActivity()
        .getResources()
        .getDisplayMetrics();
      float pixelRatio = metrics.density;

      JSObject result = new JSObject();
      result.put("width", width / pixelRatio);
      result.put("height", height / pixelRatio);
      result.put("x", x / pixelRatio);
      result.put("y", y / pixelRatio);
      call.resolve(result);
      bridge.releaseCall(call);
      cameraStartCallbackId = null; // Prevent re-use
    }
  }

  @Override
  public void onSampleTaken(String result) {
    // Handle sample taken if needed
    Log.i("CameraPreview", "Sample taken: " + result);
  }

  @Override
  public void onSampleTakenError(String message) {
    // Handle sample taken error if needed
    Log.e("CameraPreview", "Sample taken error: " + message);
  }

  @Override
  public void onCameraStartError(String message) {
    PluginCall call = bridge.getSavedCall(cameraStartCallbackId);
    if (call != null) {
      call.reject(message);
      bridge.releaseCall(call);
      cameraStartCallbackId = null;
    }
  }

  @PluginMethod
  public void setAspectRatio(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }
    String aspectRatio = call.getString("aspectRatio", "4:3");
    Float x = call.getFloat("x");
    Float y = call.getFloat("y");

    getActivity()
      .runOnUiThread(() -> {
        cameraXView.setAspectRatio(aspectRatio, x, y, () -> {
          // Return the actual preview bounds after layout and camera operations are complete
          int[] bounds = cameraXView.getCurrentPreviewBounds();
          JSObject ret = new JSObject();
          ret.put("x", bounds[0]);
          ret.put("y", bounds[1]);
          ret.put("width", bounds[2]);
          ret.put("height", bounds[3]);
          call.resolve(ret);
        });
      });
  }

  @PluginMethod
  public void getAspectRatio(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }
    String aspectRatio = cameraXView.getAspectRatio();
    JSObject ret = new JSObject();
    ret.put("aspectRatio", aspectRatio);
    call.resolve(ret);
  }

  @PluginMethod
  public void setGridMode(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }
    String gridMode = call.getString("gridMode", "none");
    getActivity()
      .runOnUiThread(() -> {
        cameraXView.setGridMode(gridMode);
        call.resolve();
      });
  }

  @PluginMethod
  public void getGridMode(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }
    JSObject ret = new JSObject();
    ret.put("gridMode", cameraXView.getGridMode());
    call.resolve(ret);
  }

  @PluginMethod
  public void getPreviewSize(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }

    // Convert pixel values back to logical units
    DisplayMetrics metrics = getBridge()
      .getActivity()
      .getResources()
      .getDisplayMetrics();
    float pixelRatio = metrics.density;

    JSObject ret = new JSObject();
    ret.put("x", cameraXView.getPreviewX() / pixelRatio);
    ret.put("y", cameraXView.getPreviewY() / pixelRatio);
    ret.put("width", cameraXView.getPreviewWidth() / pixelRatio);
    ret.put("height", cameraXView.getPreviewHeight() / pixelRatio);
    call.resolve(ret);
  }

  @PluginMethod
  public void setPreviewSize(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }

    // Get values from call - null values will become 0
    Integer xParam = call.getInt("x");
    Integer yParam = call.getInt("y");
    Integer widthParam = call.getInt("width");
    Integer heightParam = call.getInt("height");

    // Apply pixel ratio conversion to non-null values
    DisplayMetrics metrics = getBridge()
      .getActivity()
      .getResources()
      .getDisplayMetrics();
    float pixelRatio = metrics.density;

    int x = (xParam != null && xParam > 0) ? (int) (xParam * pixelRatio) : 0;
    int y = (yParam != null && yParam > 0) ? (int) (yParam * pixelRatio) : 0;
    int width = (widthParam != null && widthParam > 0)
      ? (int) (widthParam * pixelRatio)
      : 0;
    int height = (heightParam != null && heightParam > 0)
      ? (int) (heightParam * pixelRatio)
      : 0;

    cameraXView.setPreviewSize(x, y, width, height, () -> {
      // Return the actual preview bounds after layout operations are complete
      int[] bounds = cameraXView.getCurrentPreviewBounds();
      JSObject ret = new JSObject();
      ret.put("x", bounds[0]);
      ret.put("y", bounds[1]);
      ret.put("width", bounds[2]);
      ret.put("height", bounds[3]);
      call.resolve(ret);
    });
  }
}
