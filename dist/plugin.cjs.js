'use strict';

var core = require('@capacitor/core');

exports.DeviceType = void 0;
(function (DeviceType) {
    DeviceType["ULTRA_WIDE"] = "ultraWide";
    DeviceType["WIDE_ANGLE"] = "wideAngle";
    DeviceType["TELEPHOTO"] = "telephoto";
    DeviceType["TRUE_DEPTH"] = "trueDepth";
    DeviceType["DUAL"] = "dual";
    DeviceType["DUAL_WIDE"] = "dualWide";
    DeviceType["TRIPLE"] = "triple";
})(exports.DeviceType || (exports.DeviceType = {}));

const CameraPreview = core.registerPlugin("CameraPreview", {
    web: () => Promise.resolve().then(function () { return web; }).then((m) => new m.CameraPreviewWeb()),
});
async function getBase64FromFilePath(filePath) {
    const url = core.Capacitor.convertFileSrc(filePath);
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`Failed to read file at path: ${filePath} (status ${response.status})`);
    }
    const blob = await response.blob();
    return await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onloadend = () => {
            const dataUrl = reader.result;
            const commaIndex = dataUrl.indexOf(",");
            resolve(commaIndex >= 0 ? dataUrl.substring(commaIndex + 1) : dataUrl);
        };
        reader.onerror = () => reject(reader.error);
        reader.readAsDataURL(blob);
    });
}
async function deleteFile(path) {
    // Use native bridge to delete file to handle platform-specific permissions/URIs
    const { success } = await CameraPreview.deleteFile({ path });
    return !!success;
}

const DEFAULT_VIDEO_ID = "capgo_video";
class CameraPreviewWeb extends core.WebPlugin {
    constructor() {
        super();
        /**
         *  track which camera is used based on start options
         *  used in capture
         */
        this.isBackCamera = false;
        this.currentDeviceId = null;
        this.videoElement = null;
        this.isStarted = false;
        this.orientationListenerBound = false;
    }
    getCurrentOrientation() {
        var _a, _b;
        try {
            const so = screen.orientation;
            const type = (so === null || so === void 0 ? void 0 : so.type) || (so === null || so === void 0 ? void 0 : so.mozOrientation) || (so === null || so === void 0 ? void 0 : so.msOrientation);
            if (typeof type === "string") {
                if (type.includes("portrait-primary"))
                    return "portrait";
                if (type.includes("portrait-secondary"))
                    return "portrait-upside-down";
                if (type.includes("landscape-primary"))
                    return "landscape-left";
                if (type.includes("landscape-secondary"))
                    return "landscape-right";
                if (type.includes("landscape"))
                    return "landscape-right"; // avoid generic landscape
                if (type.includes("portrait"))
                    return "portrait";
            }
            const angle = window.orientation;
            if (typeof angle === "number") {
                if (angle === 0)
                    return "portrait";
                if (angle === 180)
                    return "portrait-upside-down";
                if (angle === 90)
                    return "landscape-right";
                if (angle === -90)
                    return "landscape-left";
                if (angle === 270)
                    return "landscape-left";
            }
            if ((_a = window.matchMedia("(orientation: portrait)")) === null || _a === void 0 ? void 0 : _a.matches) {
                return "portrait";
            }
            if ((_b = window.matchMedia("(orientation: landscape)")) === null || _b === void 0 ? void 0 : _b.matches) {
                // Default to landscape-right when we can't distinguish primary/secondary
                return "landscape-right";
            }
        }
        catch (e) {
            console.error(e);
        }
        return "unknown";
    }
    ensureOrientationListener() {
        if (this.orientationListenerBound)
            return;
        const emit = () => {
            this.notifyListeners("orientationChange", {
                orientation: this.getCurrentOrientation(),
            });
        };
        window.addEventListener("orientationchange", emit);
        window.addEventListener("resize", emit);
        this.orientationListenerBound = true;
    }
    async getOrientation() {
        return { orientation: this.getCurrentOrientation() };
    }
    getSafeAreaInsets() {
        throw new Error("Method not implemented.");
    }
    async getZoomButtonValues() {
        throw new Error("getZoomButtonValues not supported under the web platform");
    }
    async getSupportedPictureSizes() {
        throw new Error("getSupportedPictureSizes not supported under the web platform");
    }
    async start(options) {
        if (options.aspectRatio && (options.width || options.height)) {
            throw new Error("Cannot set both aspectRatio and size (width/height). Use setPreviewSize after start.");
        }
        if (this.isStarted) {
            throw new Error("camera already started");
        }
        this.isBackCamera = true;
        this.isStarted = false;
        const parent = document.getElementById((options === null || options === void 0 ? void 0 : options.parent) || "");
        const gridMode = (options === null || options === void 0 ? void 0 : options.gridMode) || "none";
        const positioning = (options === null || options === void 0 ? void 0 : options.positioning) || "top";
        if (options.position) {
            this.isBackCamera = options.position === "rear";
        }
        const video = document.getElementById(DEFAULT_VIDEO_ID);
        if (video) {
            video.remove();
        }
        const container = options.parent
            ? document.getElementById(options.parent)
            : document.body;
        if (!container) {
            throw new Error("container not found");
        }
        this.videoElement = document.createElement("video");
        this.videoElement.id = DEFAULT_VIDEO_ID;
        this.videoElement.className = options.className || "";
        this.videoElement.playsInline = true;
        this.videoElement.muted = true;
        this.videoElement.autoplay = true;
        // Remove objectFit as we'll match camera's native aspect ratio
        this.videoElement.style.backgroundColor = "transparent";
        // Reset any default margins that might interfere
        this.videoElement.style.margin = "0";
        this.videoElement.style.padding = "0";
        container.appendChild(this.videoElement);
        if (options.toBack) {
            this.videoElement.style.zIndex = "-1";
        }
        // Default to 4:3 if no aspect ratio or size specified
        const useDefaultAspectRatio = !options.aspectRatio && !options.width && !options.height;
        const effectiveAspectRatio = options.aspectRatio || (useDefaultAspectRatio ? "4:3" : null);
        if (options.width) {
            this.videoElement.width = options.width;
            this.videoElement.style.width = `${options.width}px`;
        }
        if (options.height) {
            this.videoElement.height = options.height;
            this.videoElement.style.height = `${options.height}px`;
        }
        // Handle positioning - center if x or y not provided
        const centerX = options.x === undefined;
        const centerY = options.y === undefined;
        // Always set position to absolute for proper positioning
        this.videoElement.style.position = "absolute";
        console.log("Initial positioning flags:", {
            centerX,
            centerY,
            x: options.x,
            y: options.y,
        });
        if (options.x !== undefined) {
            this.videoElement.style.left = `${options.x}px`;
        }
        if (options.y !== undefined) {
            this.videoElement.style.top = `${options.y}px`;
        }
        // Create and add grid overlay if needed
        if (gridMode !== "none") {
            const gridOverlay = this.createGridOverlay(gridMode);
            gridOverlay.id = "camera-grid-overlay";
            parent === null || parent === void 0 ? void 0 : parent.appendChild(gridOverlay);
        }
        // Aspect ratio handling is now done after getting camera stream
        // Store centering flags for later use
        const needsCenterX = centerX;
        const needsCenterY = centerY;
        console.log("Centering flags stored:", { needsCenterX, needsCenterY });
        // First get the camera stream with basic constraints
        const constraints = {
            video: {
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
        // Get the actual camera dimensions from the video track
        const videoTrack = stream.getVideoTracks()[0];
        const settings = videoTrack.getSettings();
        const cameraWidth = settings.width || 640;
        const cameraHeight = settings.height || 480;
        const cameraAspectRatio = cameraWidth / cameraHeight;
        console.log("Camera native dimensions:", {
            width: cameraWidth,
            height: cameraHeight,
            aspectRatio: cameraAspectRatio,
        });
        console.log("Container dimensions:", {
            width: container.offsetWidth,
            height: container.offsetHeight,
            id: container.id,
        });
        // Now adjust video element size based on camera's native aspect ratio
        if (!options.width && !options.height && !options.aspectRatio) {
            // No size specified, fit camera view within container bounds
            const containerWidth = container.offsetWidth || window.innerWidth;
            const containerHeight = container.offsetHeight || window.innerHeight;
            // Calculate dimensions that fit within container while maintaining camera aspect ratio
            let targetWidth, targetHeight;
            // Try fitting to container width first
            targetWidth = containerWidth;
            targetHeight = targetWidth / cameraAspectRatio;
            // If height exceeds container, fit to height instead
            if (targetHeight > containerHeight) {
                targetHeight = containerHeight;
                targetWidth = targetHeight * cameraAspectRatio;
            }
            console.log("Video element dimensions:", {
                width: targetWidth,
                height: targetHeight,
                container: { width: containerWidth, height: containerHeight },
            });
            this.videoElement.width = targetWidth;
            this.videoElement.height = targetHeight;
            this.videoElement.style.width = `${targetWidth}px`;
            this.videoElement.style.height = `${targetHeight}px`;
            // Center the video element within its parent container
            if (needsCenterX || options.x === undefined) {
                const x = Math.round((containerWidth - targetWidth) / 2);
                this.videoElement.style.left = `${x}px`;
            }
            if (needsCenterY || options.y === undefined) {
                let y;
                switch (positioning) {
                    case "top":
                        y = 0;
                        break;
                    case "bottom":
                        y = window.innerHeight - targetHeight;
                        break;
                    case "center":
                    default:
                        y = Math.round((window.innerHeight - targetHeight) / 2);
                        break;
                }
                this.videoElement.style.setProperty("top", `${y}px`, "important");
                // Force a style recalculation
                this.videoElement.offsetHeight;
                console.log("Positioning video:", {
                    positioning,
                    viewportHeight: window.innerHeight,
                    targetHeight,
                    calculatedY: y,
                    actualTop: this.videoElement.style.top,
                    position: this.videoElement.style.position,
                });
            }
        }
        else if (effectiveAspectRatio && !options.width && !options.height) {
            // Aspect ratio specified but no size
            const [widthRatio, heightRatio] = effectiveAspectRatio
                .split(":")
                .map(Number);
            const targetRatio = widthRatio / heightRatio;
            const viewportWidth = window.innerWidth;
            const viewportHeight = window.innerHeight;
            let targetWidth, targetHeight;
            // Try fitting to viewport width first
            targetWidth = viewportWidth;
            targetHeight = targetWidth / targetRatio;
            // If height exceeds viewport, fit to height instead
            if (targetHeight > viewportHeight) {
                targetHeight = viewportHeight;
                targetWidth = targetHeight * targetRatio;
            }
            this.videoElement.width = targetWidth;
            this.videoElement.height = targetHeight;
            this.videoElement.style.width = `${targetWidth}px`;
            this.videoElement.style.height = `${targetHeight}px`;
            // Center the video element within its parent container
            if (needsCenterX || options.x === undefined) {
                const parentWidth = container.offsetWidth || viewportWidth;
                const x = Math.round((parentWidth - targetWidth) / 2);
                this.videoElement.style.left = `${x}px`;
            }
            if (needsCenterY || options.y === undefined) {
                const parentHeight = container.offsetHeight || viewportHeight;
                let y;
                switch (positioning) {
                    case "top":
                        y = 0;
                        break;
                    case "bottom":
                        y = parentHeight - targetHeight;
                        break;
                    case "center":
                    default:
                        y = Math.round((parentHeight - targetHeight) / 2);
                        break;
                }
                this.videoElement.style.top = `${y}px`;
            }
        }
        this.videoElement.srcObject = stream;
        if (!this.isBackCamera) {
            this.videoElement.style.transform = "scaleX(-1)";
        }
        // Set initial zoom level if specified and supported
        if (options.initialZoomLevel !== undefined &&
            options.initialZoomLevel !== 1.0) {
            // videoTrack already declared above
            if (videoTrack) {
                const capabilities = videoTrack.getCapabilities();
                if (capabilities.zoom) {
                    const zoomLevel = options.initialZoomLevel;
                    const minZoom = capabilities.zoom.min || 1;
                    const maxZoom = capabilities.zoom.max || 1;
                    if (zoomLevel < minZoom || zoomLevel > maxZoom) {
                        stream.getTracks().forEach((track) => track.stop());
                        throw new Error(`Initial zoom level ${zoomLevel} is not available. Valid range is ${minZoom} to ${maxZoom}`);
                    }
                    try {
                        await videoTrack.applyConstraints({
                            advanced: [{ zoom: zoomLevel }],
                        });
                    }
                    catch (error) {
                        console.warn(`Failed to set initial zoom level: ${error}`);
                        // Don't throw, just continue without zoom
                    }
                }
            }
        }
        this.isStarted = true;
        this.ensureOrientationListener();
        // Wait for video to be ready and get actual dimensions
        await new Promise((resolve) => {
            const videoEl = this.videoElement;
            if (!videoEl) {
                throw new Error("video element not found");
            }
            if (videoEl.readyState >= 2) {
                resolve();
            }
            else {
                videoEl.addEventListener("loadeddata", () => resolve(), {
                    once: true,
                });
            }
        });
        // Ensure centering is applied after DOM updates
        await new Promise((resolve) => requestAnimationFrame(resolve));
        console.log("About to re-center, flags:", { needsCenterX, needsCenterY });
        // Re-apply centering with correct parent dimensions
        if (needsCenterX) {
            const parentWidth = container.offsetWidth;
            const x = Math.round((parentWidth - this.videoElement.offsetWidth) / 2);
            this.videoElement.style.left = `${x}px`;
            console.log("Re-centering X:", {
                parentWidth,
                videoWidth: this.videoElement.offsetWidth,
                x,
            });
        }
        if (needsCenterY) {
            let y;
            switch (positioning) {
                case "top":
                    y = 0;
                    break;
                case "bottom":
                    y = window.innerHeight - this.videoElement.offsetHeight;
                    break;
                case "center":
                default:
                    y = Math.round((window.innerHeight - this.videoElement.offsetHeight) / 2);
                    break;
            }
            this.videoElement.style.setProperty("top", `${y}px`, "important");
            console.log("Re-positioning Y:", {
                positioning,
                viewportHeight: window.innerHeight,
                videoHeight: this.videoElement.offsetHeight,
                y,
                position: this.videoElement.style.position,
                top: this.videoElement.style.top,
            });
        }
        // Get the actual rendered dimensions after video is loaded
        const rect = this.videoElement.getBoundingClientRect();
        const computedStyle = window.getComputedStyle(this.videoElement);
        console.log("Final video element state:", {
            rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
            style: {
                position: computedStyle.position,
                left: computedStyle.left,
                top: computedStyle.top,
                width: computedStyle.width,
                height: computedStyle.height,
            },
        });
        return {
            width: Math.round(rect.width),
            height: Math.round(rect.height),
            x: Math.round(rect.x),
            y: Math.round(rect.y),
        };
    }
    stopStream(stream) {
        if (stream) {
            const tracks = stream.getTracks();
            for (const track of tracks)
                track.stop();
        }
    }
    async stop() {
        const video = document.getElementById(DEFAULT_VIDEO_ID);
        if (video) {
            video.pause();
            this.stopStream(video.srcObject);
            video.remove();
            this.isStarted = false;
        }
        // Remove grid overlay if it exists
        const gridOverlay = document.getElementById("camera-grid-overlay");
        gridOverlay === null || gridOverlay === void 0 ? void 0 : gridOverlay.remove();
    }
    async capture(options) {
        return new Promise((resolve, reject) => {
            const video = document.getElementById(DEFAULT_VIDEO_ID);
            if (!(video === null || video === void 0 ? void 0 : video.srcObject)) {
                reject(new Error("camera is not running"));
                return;
            }
            // video.width = video.offsetWidth;
            let base64EncodedImage;
            if (video && video.videoWidth > 0 && video.videoHeight > 0) {
                const canvas = document.createElement("canvas");
                const context = canvas.getContext("2d");
                // Calculate capture dimensions
                let captureWidth = video.videoWidth;
                let captureHeight = video.videoHeight;
                const sourceX = 0;
                const sourceY = 0;
                // If width or height is specified, resize to fit within both maximums while maintaining aspect ratio
                if (options.width || options.height) {
                    const originalAspectRatio = video.videoWidth / video.videoHeight;
                    const targetWidth = options.width || video.videoWidth;
                    const targetHeight = options.height || video.videoHeight;
                    // Calculate dimensions that fit within both maximums
                    if (options.width && options.height) {
                        // Both dimensions specified - fit within both
                        const maxAspectRatio = targetWidth / targetHeight;
                        if (originalAspectRatio > maxAspectRatio) {
                            // Original is wider - fit by width
                            captureWidth = targetWidth;
                            captureHeight = targetWidth / originalAspectRatio;
                        }
                        else {
                            // Original is taller - fit by height
                            captureWidth = targetHeight * originalAspectRatio;
                            captureHeight = targetHeight;
                        }
                    }
                    else if (options.width) {
                        // Only width specified - maintain aspect ratio
                        captureWidth = targetWidth;
                        captureHeight = targetWidth / originalAspectRatio;
                    }
                    else {
                        // Only height specified - maintain aspect ratio
                        captureWidth = targetHeight * originalAspectRatio;
                        captureHeight = targetHeight;
                    }
                }
                canvas.width = captureWidth;
                canvas.height = captureHeight;
                // flip horizontally back camera isn't used
                if (!this.isBackCamera) {
                    context === null || context === void 0 ? void 0 : context.translate(captureWidth, 0);
                    context === null || context === void 0 ? void 0 : context.scale(-1, 1);
                }
                context === null || context === void 0 ? void 0 : context.drawImage(video, sourceX, sourceY, captureWidth, captureHeight, 0, 0, captureWidth, captureHeight);
                if (options.saveToGallery) ;
                if (options.withExifLocation) ;
                if ((options.format || "jpeg") === "jpeg") {
                    base64EncodedImage = canvas
                        .toDataURL("image/jpeg", (options.quality || 85) / 100.0)
                        .replace("data:image/jpeg;base64,", "");
                }
                else {
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
    async captureSample(_options) {
        return this.capture(_options);
    }
    async stopRecordVideo() {
        throw new Error("stopRecordVideo not supported under the web platform");
    }
    async startRecordVideo(_options) {
        console.log("startRecordVideo", _options);
        throw new Error("startRecordVideo not supported under the web platform");
    }
    async getSupportedFlashModes() {
        throw new Error("getSupportedFlashModes not supported under the web platform");
    }
    async getHorizontalFov() {
        throw new Error("getHorizontalFov not supported under the web platform");
    }
    async setFlashMode(_options) {
        throw new Error(`setFlashMode not supported under the web platform${_options}`);
    }
    async flip() {
        const video = document.getElementById(DEFAULT_VIDEO_ID);
        if (!(video === null || video === void 0 ? void 0 : video.srcObject)) {
            throw new Error("camera is not running");
        }
        // Stop current stream
        this.stopStream(video.srcObject);
        // Toggle camera position
        this.isBackCamera = !this.isBackCamera;
        // Get new constraints
        const constraints = {
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
            }
            else {
                video.style.transform = "scaleX(-1)";
                video.style.webkitTransform = "scaleX(-1)";
            }
            await video.play();
        }
        catch (error) {
            throw new Error(`Failed to flip camera: ${error}`);
        }
    }
    async setOpacity(_options) {
        const video = document.getElementById(DEFAULT_VIDEO_ID);
        if (!!video && !!_options.opacity)
            video.style.setProperty("opacity", _options.opacity.toString());
    }
    async isRunning() {
        const video = document.getElementById(DEFAULT_VIDEO_ID);
        return { isRunning: !!video && !!video.srcObject };
    }
    async getAvailableDevices() {
        var _a;
        if (!((_a = navigator.mediaDevices) === null || _a === void 0 ? void 0 : _a.enumerateDevices)) {
            throw new Error("getAvailableDevices not supported under the web platform");
        }
        const devices = await navigator.mediaDevices.enumerateDevices();
        const videoDevices = devices.filter((device) => device.kind === "videoinput");
        // Group devices by position (front/back)
        const frontDevices = [];
        const backDevices = [];
        videoDevices.forEach((device, index) => {
            const label = device.label || `Camera ${index + 1}`;
            const labelLower = label.toLowerCase();
            // Determine device type based on label
            let deviceType = exports.DeviceType.WIDE_ANGLE;
            let baseZoomRatio = 1.0;
            if (labelLower.includes("ultra") || labelLower.includes("0.5")) {
                deviceType = exports.DeviceType.ULTRA_WIDE;
                baseZoomRatio = 0.5;
            }
            else if (labelLower.includes("telephoto") ||
                labelLower.includes("tele") ||
                labelLower.includes("2x") ||
                labelLower.includes("3x")) {
                deviceType = exports.DeviceType.TELEPHOTO;
                baseZoomRatio = 2.0;
            }
            else if (labelLower.includes("depth") ||
                labelLower.includes("truedepth")) {
                deviceType = exports.DeviceType.TRUE_DEPTH;
                baseZoomRatio = 1.0;
            }
            const lensInfo = {
                deviceId: device.deviceId,
                label,
                deviceType,
                focalLength: 4.25,
                baseZoomRatio,
                minZoom: 1.0,
                maxZoom: 1.0,
            };
            // Determine position and add to appropriate array
            if (labelLower.includes("back") || labelLower.includes("rear")) {
                backDevices.push(lensInfo);
            }
            else {
                frontDevices.push(lensInfo);
            }
        });
        const result = [];
        if (frontDevices.length > 0) {
            result.push({
                deviceId: frontDevices[0].deviceId,
                label: "Front Camera",
                position: "front",
                lenses: frontDevices,
                isLogical: false,
                minZoom: Math.min(...frontDevices.map((d) => d.minZoom)),
                maxZoom: Math.max(...frontDevices.map((d) => d.maxZoom)),
            });
        }
        if (backDevices.length > 0) {
            result.push({
                deviceId: backDevices[0].deviceId,
                label: "Back Camera",
                position: "rear",
                lenses: backDevices,
                isLogical: false,
                minZoom: Math.min(...backDevices.map((d) => d.minZoom)),
                maxZoom: Math.max(...backDevices.map((d) => d.maxZoom)),
            });
        }
        return { devices: result };
    }
    async getZoom() {
        const video = document.getElementById(DEFAULT_VIDEO_ID);
        if (!(video === null || video === void 0 ? void 0 : video.srcObject)) {
            throw new Error("camera is not running");
        }
        const stream = video.srcObject;
        const videoTrack = stream.getVideoTracks()[0];
        if (!videoTrack) {
            throw new Error("no video track found");
        }
        const capabilities = videoTrack.getCapabilities();
        const settings = videoTrack.getSettings();
        if (!capabilities.zoom) {
            throw new Error("zoom not supported by this device");
        }
        // Get current device info to determine lens type
        let deviceType = exports.DeviceType.WIDE_ANGLE;
        let baseZoomRatio = 1.0;
        if (this.currentDeviceId) {
            const devices = await navigator.mediaDevices.enumerateDevices();
            const device = devices.find((d) => d.deviceId === this.currentDeviceId);
            if (device) {
                const labelLower = device.label.toLowerCase();
                if (labelLower.includes("ultra") || labelLower.includes("0.5")) {
                    deviceType = exports.DeviceType.ULTRA_WIDE;
                    baseZoomRatio = 0.5;
                }
                else if (labelLower.includes("telephoto") ||
                    labelLower.includes("tele") ||
                    labelLower.includes("2x") ||
                    labelLower.includes("3x")) {
                    deviceType = exports.DeviceType.TELEPHOTO;
                    baseZoomRatio = 2.0;
                }
                else if (labelLower.includes("depth") ||
                    labelLower.includes("truedepth")) {
                    deviceType = exports.DeviceType.TRUE_DEPTH;
                    baseZoomRatio = 1.0;
                }
            }
        }
        const currentZoom = settings.zoom || 1;
        const lensInfo = {
            focalLength: 4.25,
            deviceType,
            baseZoomRatio,
            digitalZoom: currentZoom / baseZoomRatio,
        };
        return {
            min: capabilities.zoom.min || 1,
            max: capabilities.zoom.max || 1,
            current: currentZoom,
            lens: lensInfo,
        };
    }
    async setZoom(options) {
        const video = document.getElementById(DEFAULT_VIDEO_ID);
        if (!(video === null || video === void 0 ? void 0 : video.srcObject)) {
            throw new Error("camera is not running");
        }
        const stream = video.srcObject;
        const videoTrack = stream.getVideoTracks()[0];
        if (!videoTrack) {
            throw new Error("no video track found");
        }
        const capabilities = videoTrack.getCapabilities();
        if (!capabilities.zoom) {
            throw new Error("zoom not supported by this device");
        }
        const zoomLevel = Math.max(capabilities.zoom.min || 1, Math.min(capabilities.zoom.max || 1, options.level));
        // Note: autoFocus is not supported on web platform
        try {
            await videoTrack.applyConstraints({
                advanced: [{ zoom: zoomLevel }],
            });
        }
        catch (error) {
            throw new Error(`Failed to set zoom: ${error}`);
        }
    }
    async getFlashMode() {
        throw new Error("getFlashMode not supported under the web platform");
    }
    async getDeviceId() {
        return { deviceId: this.currentDeviceId || "" };
    }
    async setDeviceId(options) {
        const video = document.getElementById(DEFAULT_VIDEO_ID);
        if (!(video === null || video === void 0 ? void 0 : video.srcObject)) {
            throw new Error("camera is not running");
        }
        // Stop current stream
        this.stopStream(video.srcObject);
        // Update current device ID
        this.currentDeviceId = options.deviceId;
        // Get new constraints with specific device ID
        const constraints = {
            video: {
                deviceId: { exact: options.deviceId },
                width: { ideal: video.videoWidth || 640 },
                height: { ideal: video.videoHeight || 480 },
            },
        };
        try {
            // Try to determine camera position from device
            const devices = await navigator.mediaDevices.enumerateDevices();
            const device = devices.find((d) => d.deviceId === options.deviceId);
            this.isBackCamera =
                (device === null || device === void 0 ? void 0 : device.label.toLowerCase().includes("back")) ||
                    (device === null || device === void 0 ? void 0 : device.label.toLowerCase().includes("rear")) ||
                    false;
            const stream = await navigator.mediaDevices.getUserMedia(constraints);
            video.srcObject = stream;
            // Update video transform based on camera
            if (this.isBackCamera) {
                video.style.transform = "none";
                video.style.webkitTransform = "none";
            }
            else {
                video.style.transform = "scaleX(-1)";
                video.style.webkitTransform = "scaleX(-1)";
            }
            await video.play();
        }
        catch (error) {
            throw new Error(`Failed to swap to device ${options.deviceId}: ${error}`);
        }
    }
    async getAspectRatio() {
        const video = document.getElementById(DEFAULT_VIDEO_ID);
        if (!video) {
            throw new Error("camera is not running");
        }
        const width = video.offsetWidth;
        const height = video.offsetHeight;
        if (width && height) {
            const ratio = width / height;
            // Check for portrait camera ratios: 4:3 -> 3:4, 16:9 -> 9:16
            if (Math.abs(ratio - 3 / 4) < 0.01) {
                return { aspectRatio: "4:3" };
            }
            if (Math.abs(ratio - 9 / 16) < 0.01) {
                return { aspectRatio: "16:9" };
            }
        }
        // Default to 4:3 if no specific aspect ratio is matched
        return { aspectRatio: "4:3" };
    }
    async setAspectRatio(options) {
        const video = document.getElementById(DEFAULT_VIDEO_ID);
        if (!video) {
            throw new Error("camera is not running");
        }
        if (options.aspectRatio) {
            const [widthRatio, heightRatio] = options.aspectRatio
                .split(":")
                .map(Number);
            // For camera, use portrait orientation: 4:3 becomes 3:4, 16:9 becomes 9:16
            const ratio = heightRatio / widthRatio;
            // Get current position and size
            const rect = video.getBoundingClientRect();
            const currentWidth = rect.width;
            const currentHeight = rect.height;
            const currentRatio = currentWidth / currentHeight;
            let newWidth;
            let newHeight;
            if (currentRatio > ratio) {
                // Width is larger, fit by height and center horizontally
                newWidth = currentHeight * ratio;
                newHeight = currentHeight;
            }
            else {
                // Height is larger, fit by width and center vertically
                newWidth = currentWidth;
                newHeight = currentWidth / ratio;
            }
            // Calculate position
            let x, y;
            if (options.x !== undefined && options.y !== undefined) {
                // Use provided coordinates, ensuring they stay within screen boundaries
                x = Math.max(0, Math.min(options.x, window.innerWidth - newWidth));
                y = Math.max(0, Math.min(options.y, window.innerHeight - newHeight));
            }
            else {
                // Auto-center the view
                x = (window.innerWidth - newWidth) / 2;
                y = (window.innerHeight - newHeight) / 2;
            }
            video.style.width = `${newWidth}px`;
            video.style.height = `${newHeight}px`;
            video.style.left = `${x}px`;
            video.style.top = `${y}px`;
            video.style.position = "absolute";
            const offsetX = newWidth / 8;
            const offsetY = newHeight / 8;
            return {
                width: Math.round(newWidth),
                height: Math.round(newHeight),
                x: Math.round(x + offsetX),
                y: Math.round(y + offsetY),
            };
        }
        else {
            video.style.objectFit = "cover";
            const rect = video.getBoundingClientRect();
            const offsetX = rect.width / 8;
            const offsetY = rect.height / 8;
            return {
                width: Math.round(rect.width),
                height: Math.round(rect.height),
                x: Math.round(rect.left + offsetX),
                y: Math.round(rect.top + offsetY),
            };
        }
    }
    createGridOverlay(gridMode) {
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
    async setGridMode(options) {
        // Web implementation of grid mode would need to be implemented
        // For now, just resolve as a no-op
        console.warn(`Grid mode '${options.gridMode}' is not yet implemented for web platform`);
    }
    async getGridMode() {
        // Web implementation - default to none
        return { gridMode: "none" };
    }
    async getPreviewSize() {
        const video = document.getElementById(DEFAULT_VIDEO_ID);
        if (!video) {
            throw new Error("camera is not running");
        }
        const offsetX = video.width / 8;
        const offsetY = video.height / 8;
        return {
            x: video.offsetLeft + offsetX,
            y: video.offsetTop + offsetY,
            width: video.width,
            height: video.height,
        };
    }
    async setPreviewSize(options) {
        const video = document.getElementById(DEFAULT_VIDEO_ID);
        if (!video) {
            throw new Error("camera is not running");
        }
        video.style.left = `${options.x}px`;
        video.style.top = `${options.y}px`;
        video.width = options.width;
        video.height = options.height;
        const offsetX = options.width / 8;
        const offsetY = options.height / 8;
        return {
            width: options.width,
            height: options.height,
            x: options.x + offsetX,
            y: options.y + offsetY,
        };
    }
    async setFocus(options) {
        // Reject if values are outside 0-1 range
        if (options.x < 0 || options.x > 1 || options.y < 0 || options.y > 1) {
            throw new Error("Focus coordinates must be between 0 and 1");
        }
        const video = document.getElementById(DEFAULT_VIDEO_ID);
        if (!(video === null || video === void 0 ? void 0 : video.srcObject)) {
            throw new Error("camera is not running");
        }
        const stream = video.srcObject;
        const videoTrack = stream.getVideoTracks()[0];
        if (!videoTrack) {
            throw new Error("no video track found");
        }
        const capabilities = videoTrack.getCapabilities();
        // Check if focusing is supported
        if (capabilities.focusMode) {
            try {
                // Web API supports focus mode settings but not coordinate-based focus
                // Setting to manual mode allows for coordinate focus if supported
                await videoTrack.applyConstraints({
                    advanced: [
                        {
                            focusMode: "manual",
                            focusDistance: 0.5, // Mid-range focus as fallback
                        },
                    ],
                });
            }
            catch (error) {
                console.warn(`setFocus is not fully supported on this device: ${error}. Focus coordinates (${options.x}, ${options.y}) were provided but cannot be applied.`);
            }
        }
        else {
            console.warn("Focus control is not supported on this device. Focus coordinates were provided but cannot be applied.");
        }
    }
    // Exposure stubs (unsupported on web)
    async getExposureModes() {
        throw new Error("getExposureModes not supported under the web platform");
    }
    async getExposureMode() {
        throw new Error("getExposureMode not supported under the web platform");
    }
    async setExposureMode(_options) {
        throw new Error("setExposureMode not supported under the web platform");
    }
    async getExposureCompensationRange() {
        throw new Error("getExposureCompensationRange not supported under the web platform");
    }
    async getExposureCompensation() {
        throw new Error("getExposureCompensation not supported under the web platform");
    }
    async setExposureCompensation(_options) {
        throw new Error("setExposureCompensation not supported under the web platform");
    }
    async deleteFile(_options) {
        throw new Error("deleteFile not supported under the web platform");
    }
}

var web = /*#__PURE__*/Object.freeze({
    __proto__: null,
    CameraPreviewWeb: CameraPreviewWeb
});

exports.CameraPreview = CameraPreview;
exports.deleteFile = deleteFile;
exports.getBase64FromFilePath = getBase64FromFilePath;
//# sourceMappingURL=plugin.cjs.js.map
