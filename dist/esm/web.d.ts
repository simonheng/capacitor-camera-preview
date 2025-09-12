import { WebPlugin } from "@capacitor/core";
import type { CameraDevice, CameraOpacityOptions, CameraPreviewFlashMode, CameraPreviewOptions, CameraPreviewPictureOptions, CameraPreviewPlugin, CameraSampleOptions, DeviceOrientation, GridMode, ExposureMode, FlashMode, LensInfo, SafeAreaInsets } from "./definitions";
export declare class CameraPreviewWeb extends WebPlugin implements CameraPreviewPlugin {
    /**
     *  track which camera is used based on start options
     *  used in capture
     */
    private isBackCamera;
    private currentDeviceId;
    private videoElement;
    private isStarted;
    private orientationListenerBound;
    constructor();
    private getCurrentOrientation;
    private ensureOrientationListener;
    getOrientation(): Promise<{
        orientation: DeviceOrientation;
    }>;
    getSafeAreaInsets(): Promise<SafeAreaInsets>;
    getZoomButtonValues(): Promise<{
        values: number[];
    }>;
    getSupportedPictureSizes(): Promise<any>;
    start(options: CameraPreviewOptions): Promise<{
        width: number;
        height: number;
        x: number;
        y: number;
    }>;
    private stopStream;
    stop(): Promise<void>;
    capture(options: CameraPreviewPictureOptions): Promise<any>;
    captureSample(_options: CameraSampleOptions): Promise<any>;
    stopRecordVideo(): Promise<any>;
    startRecordVideo(_options: CameraPreviewOptions): Promise<any>;
    getSupportedFlashModes(): Promise<{
        result: CameraPreviewFlashMode[];
    }>;
    getHorizontalFov(): Promise<{
        result: any;
    }>;
    setFlashMode(_options: {
        flashMode: CameraPreviewFlashMode | string;
    }): Promise<void>;
    flip(): Promise<void>;
    setOpacity(_options: CameraOpacityOptions): Promise<any>;
    isRunning(): Promise<{
        isRunning: boolean;
    }>;
    getAvailableDevices(): Promise<{
        devices: CameraDevice[];
    }>;
    getZoom(): Promise<{
        min: number;
        max: number;
        current: number;
        lens: LensInfo;
    }>;
    setZoom(options: {
        level: number;
        ramp?: boolean;
        autoFocus?: boolean;
    }): Promise<void>;
    getFlashMode(): Promise<{
        flashMode: FlashMode;
    }>;
    getDeviceId(): Promise<{
        deviceId: string;
    }>;
    setDeviceId(options: {
        deviceId: string;
    }): Promise<void>;
    getAspectRatio(): Promise<{
        aspectRatio: "4:3" | "16:9";
    }>;
    setAspectRatio(options: {
        aspectRatio: "4:3" | "16:9";
        x?: number;
        y?: number;
    }): Promise<{
        width: number;
        height: number;
        x: number;
        y: number;
    }>;
    private createGridOverlay;
    setGridMode(options: {
        gridMode: GridMode;
    }): Promise<void>;
    getGridMode(): Promise<{
        gridMode: GridMode;
    }>;
    getPreviewSize(): Promise<{
        x: number;
        y: number;
        width: number;
        height: number;
    }>;
    setPreviewSize(options: {
        x: number;
        y: number;
        width: number;
        height: number;
    }): Promise<{
        width: number;
        height: number;
        x: number;
        y: number;
    }>;
    setFocus(options: {
        x: number;
        y: number;
    }): Promise<void>;
    getExposureModes(): Promise<{
        modes: ExposureMode[];
    }>;
    getExposureMode(): Promise<{
        mode: ExposureMode;
    }>;
    setExposureMode(_options: {
        mode: ExposureMode;
    }): Promise<void>;
    getExposureCompensationRange(): Promise<{
        min: number;
        max: number;
        step: number;
    }>;
    getExposureCompensation(): Promise<{
        value: number;
    }>;
    setExposureCompensation(_options: {
        value: number;
    }): Promise<void>;
    deleteFile(_options: {
        path: string;
    }): Promise<{
        success: boolean;
    }>;
}
