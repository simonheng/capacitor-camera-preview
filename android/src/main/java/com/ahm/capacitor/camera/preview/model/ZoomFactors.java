package com.ahm.capacitor.camera.preview.model;

/**
 * Represents zoom factor information for a camera.
 */
public class ZoomFactors {
    private final float min;
    private final float max;
    private final float current;

    public ZoomFactors(float min, float max, float current) {
        this.min = min;
        this.max = max;
        this.current = current;
    }

    public float getMin() {
        return min;
    }

    public float getMax() {
        return max;
    }

    public float getCurrent() {
        return current;
    }
} 
