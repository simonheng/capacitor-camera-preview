package com.ahm.capacitor.camera.preview;

import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.RECORD_AUDIO;

import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import androidx.annotation.NonNull;
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
import com.ahm.capacitor.camera.preview.model.CameraDevice;
import com.ahm.capacitor.camera.preview.model.CameraSessionConfiguration;
import com.ahm.capacitor.camera.preview.model.ZoomFactors;
import java.util.List;
import java.util.Objects;

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
  }
)
public class CameraPreview
  extends Plugin
  implements CameraXView.CameraXViewListener {

  static final String CAMERA_WITH_AUDIO_PERMISSION_ALIAS = "cameraWithAudio";
  static final String CAMERA_ONLY_PERMISSION_ALIAS = "cameraOnly";

  private String captureCallbackId = "";
  private String snapshotCallbackId = "";
  private String cameraStartCallbackId = "";
  private int previousOrientationRequest = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
  private CameraXView cameraXView;

  @PluginMethod
  public void start(PluginCall call) {
    boolean disableAudio = Boolean.TRUE.equals(call.getBoolean("disableAudio", true));
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
  public void capture(PluginCall call) {
    if (cameraXView == null || !cameraXView.isRunning()) {
      call.reject("Camera is not running");
      return;
    }
    bridge.saveCall(call);
    captureCallbackId = call.getCallbackId();
    Integer quality = Objects.requireNonNull(call.getInt("quality", 85));
    cameraXView.capturePhoto(quality);
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
      .runOnUiThread(
              () -> {
                getBridge()
                  .getActivity()
                  .setRequestedOrientation(previousOrientationRequest);
                if (cameraXView != null && cameraXView.isRunning()) {
                  cameraXView.stopSession();
                  cameraXView = null;
                  call.resolve();
                } else {
                  call.reject("camera already stopped");
                }
              }
      );
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
    List<CameraDevice> devices = CameraXView.getAvailableDevicesStatic(getContext());
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
  public void switchToDevice(PluginCall call) {
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

  @PermissionCallback
  private void handleCameraPermissionResult(PluginCall call) {
    if (PermissionState.GRANTED.equals(getPermissionState(CAMERA_ONLY_PERMISSION_ALIAS)) ||
        PermissionState.GRANTED.equals(getPermissionState(CAMERA_WITH_AUDIO_PERMISSION_ALIAS))) {
      startCamera(call);
    } else {
      call.reject("Permission failed");
    }
  }

  private void startCamera(final PluginCall call) {
    String positionParam = call.getString("position");
    final String deviceId = call.getString("deviceId");
    final String position = (positionParam == null || positionParam.isEmpty() || "rear".equals(positionParam) || "back".equals(positionParam)) ? "back" : "front";
    final int x = call.getInt("x", 0);
    final int y = call.getInt("y", 0);
    final int width = call.getInt("width", 0);
    final int height = call.getInt("height", 0);
    final int paddingBottom = call.getInt("paddingBottom", 0);
    final boolean toBack = call.getBoolean("toBack", "back".equals(position));
    final boolean storeToFile = call.getBoolean("storeToFile", false);
    final boolean enableOpacity = call.getBoolean("enableOpacity", false);
    final boolean enableZoom = call.getBoolean("enableZoom", false);
    final boolean disableExifHeaderStripping = call.getBoolean("disableExifHeaderStripping", false);
    final boolean lockOrientation = call.getBoolean("lockAndroidOrientation", false);
    final boolean disableAudio = call.getBoolean("disableAudio", true);

    previousOrientationRequest = getBridge().getActivity().getRequestedOrientation();
    cameraXView = new CameraXView(getContext(), getBridge().getWebView());
    cameraXView.setListener(this);

    getBridge().getActivity().runOnUiThread(() -> {
        DisplayMetrics metrics = getBridge().getActivity().getResources().getDisplayMetrics();
        if (lockOrientation) {
          getBridge().getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        }
        int computedX = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, metrics);
        int computedY = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, y, metrics);
        int computedWidth = width != 0 ? (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, width, metrics) : (int) getBridge().getWebView().getWidth();
        int computedHeight = height != 0 ? (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, height, metrics) : (int) getBridge().getWebView().getHeight();
        computedHeight -= (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, paddingBottom, metrics);

        CameraSessionConfiguration config = new CameraSessionConfiguration(deviceId, position, computedX, computedY, computedWidth, computedHeight, paddingBottom, toBack, storeToFile, enableOpacity, enableZoom, disableExifHeaderStripping, disableAudio, 1.0f);
        bridge.saveCall(call);
        cameraStartCallbackId = call.getCallbackId();
        cameraXView.startSession(config);
      }
    );
  }

  @Override
  public void onPictureTaken(String result) {
    JSObject jsObject = new JSObject();
    jsObject.put("value", result);
    bridge.getSavedCall(captureCallbackId).resolve(jsObject);
  }

  @Override
  public void onPictureTakenError(String message) {
    bridge.getSavedCall(captureCallbackId).reject(message);
  }

  @Override
  public void onSampleTaken(String result) {
    JSObject jsObject = new JSObject();
    jsObject.put("value", result);
    bridge.getSavedCall(snapshotCallbackId).resolve(jsObject);
  }

  @Override
  public void onSampleTakenError(String message) {
    bridge.getSavedCall(snapshotCallbackId).reject(message);
  }

  @Override
  public void onCameraStarted() {
    PluginCall pluginCall = bridge.getSavedCall(cameraStartCallbackId);
    if (pluginCall != null) {
      pluginCall.resolve();
      bridge.releaseCall(pluginCall);
    }
  }

  @Override
  public void onCameraStartError(String message) {
    PluginCall pluginCall = bridge.getSavedCall(cameraStartCallbackId);
    if (pluginCall != null) {
      pluginCall.reject(message);
      bridge.releaseCall(pluginCall);
    }
  }
}
