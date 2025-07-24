import { WebPlugin } from "@capacitor/core";

import type {
  CameraDevice,
  CameraOpacityOptions,
  CameraPreviewFlashMode,
  CameraPreviewOptions,
  CameraPreviewPictureOptions,
  CameraPreviewPlugin,
  CameraSampleOptions,
  GridMode,
  FlashMode,
  LensInfo,
} from "./definitions";
import { DeviceType } from "./definitions";

const DEFAULT_VIDEO_ID = "capgo_video";
export class CameraPreviewWeb extends WebPlugin implements CameraPreviewPlugin {
  /**
   *  track which camera is used based on start options
   *  used in capture
   */
  private isBackCamera = false;
  private currentDeviceId: string | null = null;
  private videoElement: HTMLVideoElement | null = null;
  private isStarted = false;

  constructor() {
    super();
  }

  async getSupportedPictureSizes(): Promise<any> {
    throw new Error(
      "getSupportedPictureSizes not supported under the web platform",
    );
  }

  async start(options: CameraPreviewOptions): Promise<{ width: number; height: number; x: number; y: number }> {
    if (this.isStarted) {
      throw new Error("camera already started");
    }

    this.isBackCamera = true;
    this.isStarted = false;
    const parent = document.getElementById(options?.parent || "");
    const gridMode = options?.gridMode || "none";

    if (options.position) {
      this.isBackCamera = options.position === "rear";
    }

    const video = document.getElementById(DEFAULT_VIDEO_ID);
    if (video) {
      video.remove();
    }
    const container = options.parent ? document.getElementById(options.parent) : document.body;
    if (!container) {
      throw new Error("container not found");
    }
    this.videoElement = document.createElement("video");
    this.videoElement.id = DEFAULT_VIDEO_ID;
    this.videoElement.className = options.className || "";
    this.videoElement.playsInline = true;
    this.videoElement.muted = true;
    this.videoElement.autoplay = true;

    container.appendChild(this.videoElement);
    if (options.toBack) {
      this.videoElement.style.zIndex = "-1";
    }

    if (options.width) {
      this.videoElement.width = options.width;
    }

    if (options.height) {
      this.videoElement.height = options.height;
    }

    if (options.x) {
      this.videoElement.style.left = `${options.x}px`;
    }
      // Create and add grid overlay if needed
      if (gridMode !== "none") {
        const gridOverlay = this.createGridOverlay(gridMode);
        gridOverlay.id = "camera-grid-overlay";
        parent?.appendChild(gridOverlay);
      }

    if (options.y) {
      this.videoElement.style.top = `${options.y}px`;
    }

    if (options.aspectRatio) {
      const [widthRatio, heightRatio] = options.aspectRatio.split(':').map(Number);
      const ratio = widthRatio / heightRatio;

      if (options.width) {
        this.videoElement.height = options.width / ratio;
      } else if (options.height) {
        this.videoElement.width = options.height * ratio;
      }
    } else {
      this.videoElement.style.objectFit = 'cover';
    }

    const constraints: MediaStreamConstraints = {
      video: {
        width: { ideal: this.videoElement.width || 640 },
        height: { ideal: this.videoElement.height || window.innerHeight },
        facingMode: this.isBackCamera ? "environment" : "user",
      },
    };

    const stream = await navigator.mediaDevices.getUserMedia(constraints);
    if (!stream) {
      throw new Error("could not acquire stream");
    }
    if (!this.videoElement) {
      throw new Error("video element not found");
    }
    this.videoElement.srcObject = stream;
    if (!this.isBackCamera) {
      this.videoElement.style.transform = "scaleX(-1)";
    }
    this.isStarted = true;
    return {
      width: this.videoElement.width,
      height: this.videoElement.height,
      x: this.videoElement.getBoundingClientRect().x,
      y: this.videoElement.getBoundingClientRect().y,
    };
  }

  private stopStream(stream: any) {
    if (stream) {
      const tracks = stream.getTracks();

      for (const track of tracks) track.stop();
    }
  }

  async stop(): Promise<void> {
    const video = document.getElementById(DEFAULT_VIDEO_ID) as HTMLVideoElement;
    if (video) {
      video.pause();

      this.stopStream(video.srcObject);

      video.remove();
      this.isStarted = false;
    }

    // Remove grid overlay if it exists
    const gridOverlay = document.getElementById("camera-grid-overlay");
    gridOverlay?.remove();
  }

  async capture(options: CameraPreviewPictureOptions): Promise<any> {
    return new Promise((resolve, reject) => {
      const video = document.getElementById(DEFAULT_VIDEO_ID) as HTMLVideoElement;
      if (!video?.srcObject) {
        reject(new Error("camera is not running"));
        return;
      }

      // video.width = video.offsetWidth;

      let base64EncodedImage;

      if (video && video.videoWidth > 0 && video.videoHeight > 0) {
        const canvas = document.createElement("canvas");
        const context = canvas.getContext("2d");
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;

        // flip horizontally back camera isn't used
        if (!this.isBackCamera) {
          context?.translate(video.videoWidth, 0);
          context?.scale(-1, 1);
        }
        context?.drawImage(video, 0, 0, video.videoWidth, video.videoHeight);

        if (options.saveToGallery) {
          // saveToGallery is not supported on web
        }

        if (options.withExifLocation) {
          // withExifLocation is not supported on web
        }

        if ((options.format || "jpeg") === "jpeg") {
          base64EncodedImage = canvas
            .toDataURL("image/jpeg", (options.quality || 85) / 100.0)
            .replace("data:image/jpeg;base64,", "");
        } else {
          base64EncodedImage = canvas
            .toDataURL("image/png")
            .replace("data:image/png;base64,", "");
        }
      }

      resolve({
        value: base64EncodedImage,
        exif: {},
      });
    });
  }

  async captureSample(_options: CameraSampleOptions): Promise<any> {
    return this.capture(_options);
  }

  async stopRecordVideo(): Promise<any> {
    throw new Error("stopRecordVideo not supported under the web platform");
  }

  async startRecordVideo(_options: CameraPreviewOptions): Promise<any> {
    console.log("startRecordVideo", _options);
    throw new Error("startRecordVideo not supported under the web platform");
  }

  async getSupportedFlashModes(): Promise<{
    result: CameraPreviewFlashMode[];
  }> {
    throw new Error(
      "getSupportedFlashModes not supported under the web platform",
    );
  }

  async getHorizontalFov(): Promise<{
    result: any;
  }> {
    throw new Error("getHorizontalFov not supported under the web platform");
  }

  async setFlashMode(_options: {
    flashMode: CameraPreviewFlashMode | string;
  }): Promise<void> {
    throw new Error(
      `setFlashMode not supported under the web platform${_options}`,
    );
  }

  async flip(): Promise<void> {
    const video = document.getElementById(DEFAULT_VIDEO_ID) as HTMLVideoElement;
    if (!video?.srcObject) {
      throw new Error("camera is not running");
    }

    // Stop current stream
    this.stopStream(video.srcObject);

    // Toggle camera position
    this.isBackCamera = !this.isBackCamera;

    // Get new constraints
    const constraints: MediaStreamConstraints = {
      video: {
        facingMode: this.isBackCamera ? "environment" : "user",
        width: { ideal: video.videoWidth || 640 },
        height: { ideal: video.videoHeight || 480 },
      },
    };

    try {
      const stream = await navigator.mediaDevices.getUserMedia(constraints);
      video.srcObject = stream;

      // Update current device ID from the new stream
      const videoTrack = stream.getVideoTracks()[0];
      if (videoTrack) {
        this.currentDeviceId = videoTrack.getSettings().deviceId || null;
      }

      // Update video transform based on camera
      if (this.isBackCamera) {
        video.style.transform = "none";
        video.style.webkitTransform = "none";
      } else {
        video.style.transform = "scaleX(-1)";
        video.style.webkitTransform = "scaleX(-1)";
      }

      await video.play();
    } catch (error) {
      throw new Error(`Failed to flip camera: ${error}`);
    }
  }

  async setOpacity(_options: CameraOpacityOptions): Promise<any> {
    const video = document.getElementById(DEFAULT_VIDEO_ID) as HTMLVideoElement;
    if (!!video && !!_options.opacity)
      video.style.setProperty("opacity", _options.opacity.toString());
  }

  async isRunning(): Promise<{ isRunning: boolean }> {
    const video = document.getElementById(DEFAULT_VIDEO_ID) as HTMLVideoElement;
    return { isRunning: !!video && !!video.srcObject };
  }

  async getAvailableDevices(): Promise<{ devices: CameraDevice[] }> {
    if (!navigator.mediaDevices?.enumerateDevices) {
      throw new Error("getAvailableDevices not supported under the web platform");
    }

    const devices = await navigator.mediaDevices.enumerateDevices();
    const videoDevices = devices.filter(device => device.kind === 'videoinput');

    // Group devices by position (front/back)
    const frontDevices: any[] = [];
    const backDevices: any[] = [];

    videoDevices.forEach((device, index) => {
      const label = device.label || `Camera ${index + 1}`;
      const labelLower = label.toLowerCase();

      // Determine device type based on label
      let deviceType: DeviceType = DeviceType.WIDE_ANGLE;
      let baseZoomRatio = 1.0;

      if (labelLower.includes('ultra') || labelLower.includes('0.5')) {
        deviceType = DeviceType.ULTRA_WIDE;
        baseZoomRatio = 0.5;
      } else if (labelLower.includes('telephoto') || labelLower.includes('tele') || labelLower.includes('2x') || labelLower.includes('3x')) {
        deviceType = DeviceType.TELEPHOTO;
        baseZoomRatio = 2.0;
      } else if (labelLower.includes('depth') || labelLower.includes('truedepth')) {
        deviceType = DeviceType.TRUE_DEPTH;
        baseZoomRatio = 1.0;
      }

      const lensInfo = {
        deviceId: device.deviceId,
        label,
        deviceType,
        focalLength: 4.25,
        baseZoomRatio,
        minZoom: 1.0,
        maxZoom: 1.0
      };

      // Determine position and add to appropriate array
      if (labelLower.includes('back') || labelLower.includes('rear')) {
        backDevices.push(lensInfo);
      } else {
        frontDevices.push(lensInfo);
      }
    });

    const result: CameraDevice[] = [];

    if (frontDevices.length > 0) {
      result.push({
        deviceId: frontDevices[0].deviceId,
        label: "Front Camera",
        position: "front",
        lenses: frontDevices,
        isLogical: false,
        minZoom: Math.min(...frontDevices.map(d => d.minZoom)),
        maxZoom: Math.max(...frontDevices.map(d => d.maxZoom))
      });
    }

    if (backDevices.length > 0) {
      result.push({
        deviceId: backDevices[0].deviceId,
        label: "Back Camera",
        position: "rear",
        lenses: backDevices,
        isLogical: false,
        minZoom: Math.min(...backDevices.map(d => d.minZoom)),
        maxZoom: Math.max(...backDevices.map(d => d.maxZoom))
      });
    }

    return { devices: result };
  }

  async getZoom(): Promise<{ min: number; max: number; current: number; lens: LensInfo }> {
    const video = document.getElementById(DEFAULT_VIDEO_ID) as HTMLVideoElement;
    if (!video?.srcObject) {
      throw new Error("camera is not running");
    }

    const stream = video.srcObject as MediaStream;
    const videoTrack = stream.getVideoTracks()[0];

    if (!videoTrack) {
      throw new Error("no video track found");
    }

    const capabilities = videoTrack.getCapabilities() as any;
    const settings = videoTrack.getSettings() as any;

    if (!capabilities.zoom) {
      throw new Error("zoom not supported by this device");
    }

    // Get current device info to determine lens type
    let deviceType: DeviceType = DeviceType.WIDE_ANGLE;
    let baseZoomRatio = 1.0;

    if (this.currentDeviceId) {
      const devices = await navigator.mediaDevices.enumerateDevices();
      const device = devices.find(d => d.deviceId === this.currentDeviceId);
      if (device) {
        const labelLower = device.label.toLowerCase();
        if (labelLower.includes('ultra') || labelLower.includes('0.5')) {
          deviceType = DeviceType.ULTRA_WIDE;
          baseZoomRatio = 0.5;
        } else if (labelLower.includes('telephoto') || labelLower.includes('tele') || labelLower.includes('2x') || labelLower.includes('3x')) {
          deviceType = DeviceType.TELEPHOTO;
          baseZoomRatio = 2.0;
        } else if (labelLower.includes('depth') || labelLower.includes('truedepth')) {
          deviceType = DeviceType.TRUE_DEPTH;
          baseZoomRatio = 1.0;
        }
      }
    }

    const currentZoom = settings.zoom || 1;
    const lensInfo: LensInfo = {
      focalLength: 4.25,
      deviceType,
      baseZoomRatio,
      digitalZoom: currentZoom / baseZoomRatio
    };

    return {
      min: capabilities.zoom.min || 1,
      max: capabilities.zoom.max || 1,
      current: currentZoom,
      lens: lensInfo,
    };
  }

  async setZoom(options: { level: number; ramp?: boolean }): Promise<void> {
    const video = document.getElementById(DEFAULT_VIDEO_ID) as HTMLVideoElement;
    if (!video?.srcObject) {
      throw new Error("camera is not running");
    }

    const stream = video.srcObject as MediaStream;
    const videoTrack = stream.getVideoTracks()[0];

    if (!videoTrack) {
      throw new Error("no video track found");
    }

    const capabilities = videoTrack.getCapabilities() as any;

    if (!capabilities.zoom) {
      throw new Error("zoom not supported by this device");
    }

    const zoomLevel = Math.max(
      capabilities.zoom.min || 1,
      Math.min(capabilities.zoom.max || 1, options.level)
    );

    try {
      await videoTrack.applyConstraints({
        advanced: [{ zoom: zoomLevel } as any]
      });
    } catch (error) {
      throw new Error(`Failed to set zoom: ${error}`);
    }
  }

  async getFlashMode(): Promise<{ flashMode: FlashMode }> {
    throw new Error("getFlashMode not supported under the web platform");
  }

  async getDeviceId(): Promise<{ deviceId: string }> {
    return { deviceId: this.currentDeviceId || "" };
  }

  async setDeviceId(options: { deviceId: string }): Promise<void> {
    const video = document.getElementById(DEFAULT_VIDEO_ID) as HTMLVideoElement;
    if (!video?.srcObject) {
      throw new Error("camera is not running");
    }

    // Stop current stream
    this.stopStream(video.srcObject);

    // Update current device ID
    this.currentDeviceId = options.deviceId;

    // Get new constraints with specific device ID
    const constraints: MediaStreamConstraints = {
      video: {
        deviceId: { exact: options.deviceId },
        width: { ideal: video.videoWidth || 640 },
        height: { ideal: video.videoHeight || 480 },
      },
    };

    try {
      // Try to determine camera position from device
      const devices = await navigator.mediaDevices.enumerateDevices();
      const device = devices.find(d => d.deviceId === options.deviceId);
      this.isBackCamera = device?.label.toLowerCase().includes('back') || device?.label.toLowerCase().includes('rear') || false;

      const stream = await navigator.mediaDevices.getUserMedia(constraints);
      video.srcObject = stream;

      // Update video transform based on camera
      if (this.isBackCamera) {
        video.style.transform = "none";
        video.style.webkitTransform = "none";
      } else {
        video.style.transform = "scaleX(-1)";
        video.style.webkitTransform = "scaleX(-1)";
      }

      await video.play();
    } catch (error) {
      throw new Error(`Failed to swap to device ${options.deviceId}: ${error}`);
    }
  }

  async getAspectRatio(): Promise<{ aspectRatio: '4:3' | '16:9' }> {
    const video = document.getElementById(DEFAULT_VIDEO_ID) as HTMLVideoElement;
    if (!video) {
      throw new Error("camera is not running");
    }

    const width = video.offsetWidth;
    const height = video.offsetHeight;

    if (width && height) {
      const ratio = width / height;
      if (Math.abs(ratio - (4 / 3)) < 0.01) {
        return { aspectRatio: '4:3' };
      }
      if (Math.abs(ratio - (16 / 9)) < 0.01) {
        return { aspectRatio: '16:9' };
      }
    }

    // Default to 4:3 if no specific aspect ratio is matched
    return { aspectRatio: '4:3' };
  }

  async setAspectRatio(options: { aspectRatio: '4:3' | '16:9'}): Promise<void> {
    const video = document.getElementById(DEFAULT_VIDEO_ID) as HTMLVideoElement;
    if (!video) {
      throw new Error("camera is not running");
    }

    if (options.aspectRatio) {
      const [widthRatio, heightRatio] = options.aspectRatio.split(':').map(Number);
      const ratio = widthRatio / heightRatio;
      const width = video.offsetWidth;
      const height = video.offsetHeight;

      if (width) {
        video.height = width / ratio;
      } else if (height) {
        video.width = height * ratio;
      }
    } else {
      video.style.objectFit = 'cover';
    }
  }

  private createGridOverlay(gridMode: string): HTMLElement {
    const overlay = document.createElement("div");
    overlay.style.position = "absolute";
    overlay.style.top = "0";
    overlay.style.left = "0";
    overlay.style.width = "100%";
    overlay.style.height = "100%";
    overlay.style.pointerEvents = "none";
    overlay.style.zIndex = "10";

    const divisions = gridMode === "3x3" ? 3 : 4;

    // Create SVG for grid lines
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.style.width = "100%";
    svg.style.height = "100%";
    svg.style.position = "absolute";
    svg.style.top = "0";
    svg.style.left = "0";

    // Create grid lines
    for (let i = 1; i < divisions; i++) {
      // Vertical lines
      const verticalLine = document.createElementNS("http://www.w3.org/2000/svg", "line");
      verticalLine.setAttribute("x1", `${(i / divisions) * 100}%`);
      verticalLine.setAttribute("y1", "0%");
      verticalLine.setAttribute("x2", `${(i / divisions) * 100}%`);
      verticalLine.setAttribute("y2", "100%");
      verticalLine.setAttribute("stroke", "rgba(255, 255, 255, 0.5)");
      verticalLine.setAttribute("stroke-width", "1");
      svg.appendChild(verticalLine);

      // Horizontal lines
      const horizontalLine = document.createElementNS("http://www.w3.org/2000/svg", "line");
      horizontalLine.setAttribute("x1", "0%");
      horizontalLine.setAttribute("y1", `${(i / divisions) * 100}%`);
      horizontalLine.setAttribute("x2", "100%");
      horizontalLine.setAttribute("y2", `${(i / divisions) * 100}%`);
      horizontalLine.setAttribute("stroke", "rgba(255, 255, 255, 0.5)");
      horizontalLine.setAttribute("stroke-width", "1");
      svg.appendChild(horizontalLine);
    }

    overlay.appendChild(svg);
    return overlay;
  }

  async setGridMode(options: { gridMode: GridMode }): Promise<void> {
    // Web implementation of grid mode would need to be implemented
    // For now, just resolve as a no-op
    console.warn(`Grid mode '${options.gridMode}' is not yet implemented for web platform`);
  }

  async getGridMode(): Promise<{ gridMode: GridMode }> {
    // Web implementation - default to none
    return { gridMode: 'none' };
  }

}
