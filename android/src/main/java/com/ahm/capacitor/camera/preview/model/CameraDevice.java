package com.ahm.capacitor.camera.preview.model;

/**
 * Represents a camera device available on the Android device.
 */
public class CameraDevice {
    private final String deviceId;
    private final String label;
    private final String position;
    private final String deviceType;

    public CameraDevice(String deviceId, String label, String position, String deviceType) {
        this.deviceId = deviceId;
        this.label = label;
        this.position = position;
        this.deviceType = deviceType;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getLabel() {
        return label;
    }

    public String getPosition() {
        return position;
    }

    public String getDeviceType() {
        return deviceType;
    }
} 
