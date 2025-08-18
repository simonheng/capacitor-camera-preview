import Foundation
import AVFoundation
import Photos
import Capacitor
import CoreImage
import CoreLocation
import MobileCoreServices

extension UIWindow {
    static var isLandscape: Bool {
        if #available(iOS 13.0, *) {
            return UIApplication.shared.windows
                .first?
                .windowScene?
                .interfaceOrientation
                .isLandscape ?? false
        } else {
            return UIApplication.shared.statusBarOrientation.isLandscape
        }
    }
    static var isPortrait: Bool {
        if #available(iOS 13.0, *) {
            return UIApplication.shared.windows
                .first?
                .windowScene?
                .interfaceOrientation
                .isPortrait ?? false
        } else {
            return UIApplication.shared.statusBarOrientation.isPortrait
        }
    }
}

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitor.ionicframework.com/docs/plugins/ios
 */
@objc(CameraPreview)
public class CameraPreview: CAPPlugin, CAPBridgedPlugin, CLLocationManagerDelegate {
    public let identifier = "CameraPreviewPlugin"
    public let jsName = "CameraPreview"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "flip", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "capture", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "captureSample", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSupportedFlashModes", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getHorizontalFov", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setFlashMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startRecordVideo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopRecordVideo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getTempFilePath", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSupportedPictureSizes", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isRunning", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAvailableDevices", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getZoom", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getZoomButtonValues", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setZoom", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getFlashMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setDeviceId", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDeviceId", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setAspectRatio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAspectRatio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setGridMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getGridMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPreviewSize", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setPreviewSize", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setFocus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deleteFile", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getOrientation", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSafeAreaInsets", returnType: CAPPluginReturnPromise)

    ]
    // Camera state tracking
    private var isInitializing: Bool = false
    private var isInitialized: Bool = false
    private var backgroundSession: AVCaptureSession?

    var previewView: UIView!
    var cameraPosition = String()
    let cameraController = CameraController()
    var posX: CGFloat?
    var posY: CGFloat?
    var width: CGFloat?
    var height: CGFloat?
    var paddingBottom: CGFloat?
    var rotateWhenOrientationChanged: Bool?
    var toBack: Bool?
    var storeToFile: Bool?
    var enableZoom: Bool?
    var disableAudio: Bool = false
    var locationManager: CLLocationManager?
    var currentLocation: CLLocation?
    private var aspectRatio: String?
    private var gridMode: String = "none"
    private var positioning: String = "center"
    private var permissionCallID: String?
    private var waitingForLocation: Bool = false

    // MARK: - Transparency Methods

    private func makeWebViewTransparent() {
        guard let webView = self.webView else { return }

        DispatchQueue.main.async {
            // Define a recursive function to traverse the view hierarchy
            func makeSubviewsTransparent(_ view: UIView) {
                // Set the background color to clear
                view.backgroundColor = .clear

                // Recurse for all subviews
                for subview in view.subviews {
                    makeSubviewsTransparent(subview)
                }
            }

            // Set the main webView to be transparent
            webView.isOpaque = false
            webView.backgroundColor = .clear

            // Recursively make all subviews transparent
            makeSubviewsTransparent(webView)

            // Also ensure the webview's container is transparent
            webView.superview?.backgroundColor = .clear

            // Force a layout pass to apply changes
            webView.setNeedsLayout()
            webView.layoutIfNeeded()
        }
    }

    @objc func getZoomButtonValues(_ call: CAPPluginCall) {
        guard isInitialized else {
            call.reject("Camera not initialized")
            return
        }

        // Determine current device based on active position
        var currentDevice: AVCaptureDevice?
        switch self.cameraController.currentCameraPosition {
        case .front:
            currentDevice = self.cameraController.frontCamera
        case .rear:
            currentDevice = self.cameraController.rearCamera
        default:
            currentDevice = nil
        }

        guard let device = currentDevice else {
            call.reject("No active camera device")
            return
        }

        var hasUltraWide = false
        var hasWide = false
        var hasTele = false

        let lenses = device.isVirtualDevice ? device.constituentDevices : [device]
        for lens in lenses {
            switch lens.deviceType {
            case .builtInUltraWideCamera:
                hasUltraWide = true
            case .builtInWideAngleCamera:
                hasWide = true
            case .builtInTelephotoCamera:
                hasTele = true
            default:
                break
            }
        }

        var values: [Float] = []
        if hasUltraWide {
            values.append(0.5)
        }
        if hasWide {
            values.append(1.0)
            if self.isProModelSupportingOptical2x() {
                values.append(2.0)
            }
        }
        if hasTele {
            // Use the virtual device's switch-over zoom factors when available
            let displayMultiplier = self.cameraController.getDisplayZoomMultiplier()
            var teleStep: Float

            if #available(iOS 13.0, *) {
                let switchFactors = device.virtualDeviceSwitchOverVideoZoomFactors
                if !switchFactors.isEmpty {
                    // Choose the highest switch-over factor (typically the wide->tele threshold)
                    let maxSwitch = switchFactors.map { $0.floatValue }.max() ?? Float(device.maxAvailableVideoZoomFactor)
                    teleStep = maxSwitch * displayMultiplier
                } else {
                    teleStep = Float(device.maxAvailableVideoZoomFactor) * displayMultiplier
                }
            } else {
                teleStep = Float(device.maxAvailableVideoZoomFactor) * displayMultiplier
            }
            values.append(teleStep)
        }

        // Deduplicate and sort
        let uniqueSorted = Array(Set(values)).sorted()
        call.resolve(["values": uniqueSorted])
    }

    private func isProModelSupportingOptical2x() -> Bool {
        // Detects iPhone 14 Pro/Pro Max, 15 Pro/Pro Max, and 16 Pro/Pro Max
        var systemInfo = utsname()
        uname(&systemInfo)
        let mirror = Mirror(reflecting: systemInfo.machine)
        let identifier = mirror.children.reduce("") { partialResult, element in
            guard let value = element.value as? Int8, value != 0 else { return partialResult }
            return partialResult + String(UnicodeScalar(UInt8(value)))
        }

        // Known identifiers: 14 Pro (iPhone15,2), 14 Pro Max (iPhone15,3),
        // 15 Pro (iPhone16,1), 15 Pro Max (iPhone16,2),
        // 16 Pro (iPhone17,1), 16 Pro Max (iPhone17,2),
        // 17 Pro (iPhone18,1), 17 Pro Max (iPhone18,2)
        let supportedIdentifiers: Set<String> = [
            "iPhone15,2", "iPhone15,3", // 14 Pro / 14 Pro Max
            "iPhone16,1", "iPhone16,2", // 15 Pro / 15 Pro Max
            "iPhone17,1", "iPhone17,2" // 16 Pro / 16 Pro Max
        ]
        return supportedIdentifiers.contains(identifier)
    }

    @objc func rotated() {
        guard let previewView = self.previewView,
              let posX = self.posX,
              let posY = self.posY,
              let width = self.width,
              let heightValue = self.height else {
            return
        }
        let paddingBottom = self.paddingBottom ?? 0
        let height = heightValue - paddingBottom

        // Handle auto-centering during rotation
        // Always use the factorized method for consistent positioning
        self.updateCameraFrame()

        // Centralize orientation update to use interface orientation consistently
        cameraController.updateVideoOrientation()

        // Update grid overlay frame if it exists - no animation
        if let gridOverlay = self.cameraController.gridOverlayView {
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            gridOverlay.frame = previewView.bounds
            CATransaction.commit()
        }

        // Ensure webview remains transparent after rotation
        if self.isInitialized {
            self.makeWebViewTransparent()
        }
    }

    @objc func setAspectRatio(_ call: CAPPluginCall) {
        guard self.isInitialized else {
            call.reject("camera not started")
            return
        }

        guard let newAspectRatio = call.getString("aspectRatio") else {
            call.reject("aspectRatio parameter is required")
            return
        }

        self.aspectRatio = newAspectRatio
        DispatchQueue.main.async {
            call.resolve(self.rawSetAspectRatio())
        }
    }

    func rawSetAspectRatio() -> JSObject {
        // When aspect ratio changes, always auto-center the view
        // This ensures consistent behavior where changing aspect ratio recenters the view
        self.posX = -1
        self.posY = -1

        // Calculate maximum size based on aspect ratio
        let webViewWidth = self.webView?.frame.width ?? UIScreen.main.bounds.width
        let webViewHeight = self.webView?.frame.height ?? UIScreen.main.bounds.height
        let paddingBottom = self.paddingBottom ?? 0
        let isPortrait = self.isPortrait()

        // Calculate available space
        let availableWidth: CGFloat
        let availableHeight: CGFloat

        if self.posX == -1 || self.posY == -1 {
            // Auto-centering mode - use full dimensions
            availableWidth = webViewWidth
            availableHeight = webViewHeight - paddingBottom
        } else {
            // Manual positioning - calculate remaining space
            availableWidth = webViewWidth - self.posX!
            availableHeight = webViewHeight - self.posY! - paddingBottom
        }

        // Parse aspect ratio - convert to portrait orientation for camera use
        let ratioParts = self.aspectRatio?.split(separator: ":").map { Double($0) ?? 1.0 } ?? [1.0, 1.0]

        // For camera (portrait), we want portrait orientation: 4:3 becomes 3:4, 16:9 becomes 9:16
        let ratio = !isPortrait ? ratioParts[0] / ratioParts[1] : ratioParts[1] / ratioParts[0]

        // Calculate maximum size that fits the aspect ratio in available space
        let maxWidthByHeight = availableHeight * CGFloat(ratio)
        let maxHeightByWidth = availableWidth / CGFloat(ratio)

        if maxWidthByHeight <= availableWidth {
            // Height is the limiting factor
            self.width = maxWidthByHeight
            self.height = availableHeight
        } else {
            // Width is the limiting factor
            self.width = availableWidth
            self.height = maxHeightByWidth
        }

        self.updateCameraFrame()

        // Return the actual preview bounds
        var result = JSObject()
        result["x"] = Double(self.previewView.frame.origin.x)
        result["y"] = Double(self.previewView.frame.origin.y)
        result["width"] = Double(self.previewView.frame.width)
        result["height"] = Double(self.previewView.frame.height)
        return result
    }

    @objc func getAspectRatio(_ call: CAPPluginCall) {
        guard self.isInitialized else {
            call.reject("camera not started")
            return
        }
        call.resolve(["aspectRatio": self.aspectRatio ?? "4:3"])
    }

    @objc func setGridMode(_ call: CAPPluginCall) {
        guard self.isInitialized else {
            call.reject("camera not started")
            return
        }

        guard let gridMode = call.getString("gridMode") else {
            call.reject("gridMode parameter is required")
            return
        }

        self.gridMode = gridMode

        // Update grid overlay
        DispatchQueue.main.async {
            if gridMode == "none" {
                self.cameraController.removeGridOverlay()
            } else {
                self.cameraController.addGridOverlay(to: self.previewView, gridMode: gridMode)
            }
        }

        call.resolve()
    }

    @objc func getGridMode(_ call: CAPPluginCall) {
        guard self.isInitialized else {
            call.reject("camera not started")
            return
        }
        call.resolve(["gridMode": self.gridMode])
    }

    @objc func appDidBecomeActive() {
        if self.isInitialized {
            DispatchQueue.main.async {
                self.makeWebViewTransparent()
            }
        }
    }

    @objc func appWillEnterForeground() {
        if self.isInitialized {
            DispatchQueue.main.async {
                self.makeWebViewTransparent()
            }
        }
    }

    struct CameraInfo {
        let deviceID: String
        let position: String
        let pictureSizes: [CGSize]
    }

    func getSupportedPictureSizes() -> [CameraInfo] {
        var cameraInfos = [CameraInfo]()

        // Discover all available cameras
        let deviceTypes: [AVCaptureDevice.DeviceType] = [
            .builtInWideAngleCamera,
            .builtInUltraWideCamera,
            .builtInTelephotoCamera,
            .builtInDualCamera,
            .builtInDualWideCamera,
            .builtInTripleCamera,
            .builtInTrueDepthCamera
        ]

        let session = AVCaptureDevice.DiscoverySession(
            deviceTypes: deviceTypes,
            mediaType: .video,
            position: .unspecified
        )

        let devices = session.devices

        for device in devices {
            // Determine the position of the camera
            var position = "Unknown"
            switch device.position {
            case .front:
                position = "Front"
            case .back:
                position = "Back"
            case .unspecified:
                position = "Unspecified"
            @unknown default:
                position = "Unknown"
            }

            var pictureSizes = [CGSize]()

            // Get supported formats
            for format in device.formats {
                let description = format.formatDescription
                let dimensions = CMVideoFormatDescriptionGetDimensions(description)
                let size = CGSize(width: CGFloat(dimensions.width), height: CGFloat(dimensions.height))
                if !pictureSizes.contains(size) {
                    pictureSizes.append(size)
                }
            }

            // Sort sizes in descending order (largest to smallest)
            pictureSizes.sort { $0.width * $0.height > $1.width * $1.height }

            let cameraInfo = CameraInfo(deviceID: device.uniqueID, position: position, pictureSizes: pictureSizes)
            cameraInfos.append(cameraInfo)
        }

        return cameraInfos
    }

    @objc func getSupportedPictureSizes(_ call: CAPPluginCall) {
        let cameraInfos = getSupportedPictureSizes()
        call.resolve([
            "supportedPictureSizes": cameraInfos.map {
                return [
                    "facing": $0.position,
                    "supportedPictureSizes": $0.pictureSizes.map { size in
                        return [
                            "width": String(describing: size.width),
                            "height": String(describing: size.height)
                        ]
                    }
                ]
            }
        ])
    }

    @objc func start(_ call: CAPPluginCall) {
        let startTime = CFAbsoluteTimeGetCurrent()
        print("[CameraPreview] 🚀 START CALLED at \(Date())")

        if self.isInitializing {
            call.reject("camera initialization in progress")
            return
        }
        if self.isInitialized {
            call.reject("camera already started")
            return
        }
        self.isInitializing = true

        self.cameraPosition = call.getString("position") ?? "rear"
        let deviceId = call.getString("deviceId")
        let cameraMode = call.getBool("cameraMode") ?? false

        // Set width - use screen width if not provided or if 0
        if let width = call.getInt("width"), width > 0 {
            self.width = CGFloat(width)
        } else {
            self.width = UIScreen.main.bounds.size.width
        }

        // Set height - use screen height if not provided or if 0
        if let height = call.getInt("height"), height > 0 {
            self.height = CGFloat(height)
        } else {
            self.height = UIScreen.main.bounds.size.height
        }

        // Set x position - use exact CSS pixel value from web view, or mark for centering
        if let x = call.getInt("x") {
            self.posX = CGFloat(x)
        } else {
            self.posX = -1 // Use -1 to indicate auto-centering
        }

        // Set y position - use exact CSS pixel value from web view, or mark for centering
        if let y = call.getInt("y") {
            self.posY = CGFloat(y)
        } else {
            self.posY = -1 // Use -1 to indicate auto-centering
        }
        if call.getInt("paddingBottom") != nil {
            self.paddingBottom = CGFloat(call.getInt("paddingBottom")!)
        }

        self.rotateWhenOrientationChanged = call.getBool("rotateWhenOrientationChanged") ?? true
        self.toBack = call.getBool("toBack") ?? true
        self.storeToFile = call.getBool("storeToFile") ?? false
        self.enableZoom = call.getBool("enableZoom") ?? false
        self.disableAudio = call.getBool("disableAudio") ?? true
        // Default to 4:3 aspect ratio if not provided
        self.aspectRatio = call.getString("aspectRatio") ?? "4:3"
        self.gridMode = call.getString("gridMode") ?? "none"
        self.positioning = call.getString("positioning") ?? "top"

        let initialZoomLevel = call.getFloat("initialZoomLevel")

        if self.aspectRatio != nil && (call.getInt("width") != nil || call.getInt("height") != nil) {
            call.reject("Cannot set both aspectRatio and size (width/height). Use setPreviewSize after start.")
            return
        }

        AVCaptureDevice.requestAccess(for: .video, completionHandler: { (granted: Bool) in

            guard granted else {
                call.reject("permission failed")
                return
            }

            if self.cameraController.captureSession?.isRunning ?? false {
                call.reject("camera already started")
            } else {
                self.cameraController.prepare(cameraPosition: self.cameraPosition, deviceId: deviceId, disableAudio: self.disableAudio, cameraMode: cameraMode, aspectRatio: self.aspectRatio, initialZoomLevel: initialZoomLevel) {error in
                    if let error = error {
                        print(error)
                        call.reject(error.localizedDescription)
                        return
                    }

                    DispatchQueue.main.async {
                        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
                        NotificationCenter.default.addObserver(self,
                                                               selector: #selector(self.handleOrientationChange),
                                                               name: UIDevice.orientationDidChangeNotification,
                                                               object: nil)
                        self.completeStartCamera(call: call)
                    }
                }
            }
        })
    }

    private func completeStartCamera(call: CAPPluginCall) {
        // Create and configure the preview view first
        self.updateCameraFrame()

        // Make webview transparent - comprehensive approach
        self.makeWebViewTransparent()

        // Add the preview view to the webview itself to use same coordinate system
        self.webView?.addSubview(self.previewView)
        if self.toBack! {
            self.webView?.sendSubviewToBack(self.previewView)
        }

        // Display the camera preview on the configured view
        try? self.cameraController.displayPreview(on: self.previewView)

        // Ensure the preview orientation matches the current interface orientation at startup
        self.cameraController.updateVideoOrientation()

        self.cameraController.setupGestures(target: self.previewView, enableZoom: self.enableZoom!)

        // Add grid overlay if enabled
        if self.gridMode != "none" {
            self.cameraController.addGridOverlay(to: self.previewView, gridMode: self.gridMode)
        }

        if self.rotateWhenOrientationChanged == true {
            NotificationCenter.default.addObserver(self, selector: #selector(CameraPreview.rotated), name: UIDevice.orientationDidChangeNotification, object: nil)
        }

        // Add observers for app state changes to maintain transparency
        NotificationCenter.default.addObserver(self, selector: #selector(CameraPreview.appDidBecomeActive), name: UIApplication.didBecomeActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(CameraPreview.appWillEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil)

        self.isInitializing = false
        self.isInitialized = true

        // Set up callback to wait for first frame before resolving
        self.cameraController.firstFrameReadyCallback = { [weak self] in
            guard let self = self else { return }

            DispatchQueue.main.async {
                var returnedObject = JSObject()
                returnedObject["width"] = self.previewView.frame.width as any JSValue
                returnedObject["height"] = self.previewView.frame.height as any JSValue
                returnedObject["x"] = self.previewView.frame.origin.x as any JSValue
                returnedObject["y"] = self.previewView.frame.origin.y as any JSValue
                call.resolve(returnedObject)
            }
        }

        // If already received first frame (unlikely but possible), resolve immediately on main thread
        if self.cameraController.hasReceivedFirstFrame {
            DispatchQueue.main.async {
                var returnedObject = JSObject()
                returnedObject["width"] = self.previewView.frame.width as any JSValue
                returnedObject["height"] = self.previewView.frame.height as any JSValue
                returnedObject["x"] = self.previewView.frame.origin.x as any JSValue
                returnedObject["y"] = self.previewView.frame.origin.y as any JSValue
                call.resolve(returnedObject)
            }
        }
    }

    @objc func flip(_ call: CAPPluginCall) {
        guard isInitialized else {
            call.reject("Camera not initialized")
            return
        }

        // Disable user interaction during flip
        self.previewView.isUserInteractionEnabled = false

        do {
            try self.cameraController.switchCameras()

            // Update preview layer frame without animation
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            self.cameraController.previewLayer?.frame = self.previewView.bounds
            self.cameraController.previewLayer?.videoGravity = .resizeAspectFill
            CATransaction.commit()

            self.previewView.isUserInteractionEnabled = true

            // Ensure webview remains transparent after flip
            self.makeWebViewTransparent()

            call.resolve()
        } catch {
            self.previewView.isUserInteractionEnabled = true
            print("Failed to flip camera: \(error.localizedDescription)")
            call.reject("Failed to flip camera: \(error.localizedDescription)")
        }
    }

    @objc func stop(_ call: CAPPluginCall) {
        if self.isInitializing {
            call.reject("cannot stop camera while initialization is in progress")
            return
        }
        if !self.isInitialized {
            call.reject("camera not initialized")
            return
        }

        // UI operations must be on main thread
        DispatchQueue.main.async {
            // Always attempt to stop and clean up, regardless of captureSession state
            self.cameraController.removeGridOverlay()
            if let previewView = self.previewView {
                previewView.removeFromSuperview()
                self.previewView = nil
            }

            self.webView?.isOpaque = true
            self.isInitialized = false
            self.isInitializing = false
            self.cameraController.cleanup()

            // Remove notification observers
            NotificationCenter.default.removeObserver(self)

            NotificationCenter.default.removeObserver(self, name: UIDevice.orientationDidChangeNotification, object: nil)
            UIDevice.current.endGeneratingDeviceOrientationNotifications()

            call.resolve()
        }
    }
    // Get user's cache directory path
    @objc func getTempFilePath() -> URL {
        let path = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let identifier = UUID()
        let randomIdentifier = identifier.uuidString.replacingOccurrences(of: "-", with: "")
        let finalIdentifier = String(randomIdentifier.prefix(8))
        let fileName="cpcp_capture_"+finalIdentifier+".jpg"
        let fileUrl=path.appendingPathComponent(fileName)
        return fileUrl
    }

    @objc func capture(_ call: CAPPluginCall) {
        print("[CameraPreview] capture called with options: \(call.options)")
        let withExifLocation = call.getBool("withExifLocation", false)
        print("[CameraPreview] capture called, withExifLocation: \(withExifLocation)")

        if withExifLocation {
            print("[CameraPreview] Location required for capture")

            // Check location services before main thread dispatch
            guard CLLocationManager.locationServicesEnabled() else {
                print("[CameraPreview] Location services are disabled")
                call.reject("Location services are disabled")
                return
            }

            // Check if Info.plist has the required key
            guard Bundle.main.object(forInfoDictionaryKey: "NSLocationWhenInUseUsageDescription") != nil else {
                print("[CameraPreview] ERROR: NSLocationWhenInUseUsageDescription key missing from Info.plist")
                call.reject("NSLocationWhenInUseUsageDescription key missing from Info.plist. Add this key with a description of how your app uses location.")
                return
            }

            // Ensure location manager setup happens on main thread
            DispatchQueue.main.async {
                if self.locationManager == nil {
                    self.locationManager = CLLocationManager()
                    self.locationManager?.delegate = self
                    self.locationManager?.desiredAccuracy = kCLLocationAccuracyBest
                }

                // Check current authorization status
                let currentStatus = self.locationManager?.authorizationStatus ?? .notDetermined

                switch currentStatus {
                case .authorizedWhenInUse, .authorizedAlways:
                    // Already authorized, get location and capture
                    self.getCurrentLocation { _ in
                        self.performCapture(call: call)
                    }

                case .denied, .restricted:
                    // Permission denied
                    print("[CameraPreview] Location permission denied")
                    call.reject("Location permission denied")

                case .notDetermined:
                    // Need to request permission
                    print("[CameraPreview] Location permission not determined, requesting...")
                    // Save the call for the delegate callback
                    print("[CameraPreview] Saving call for location authorization flow")
                    self.bridge?.saveCall(call)
                    self.permissionCallID = call.callbackId
                    self.waitingForLocation = true

                    // Request authorization - this will trigger locationManagerDidChangeAuthorization
                    print("[CameraPreview] Requesting location authorization...")
                    self.locationManager?.requestWhenInUseAuthorization()
                // The delegate will handle the rest

                @unknown default:
                    print("[CameraPreview] Unknown authorization status")
                    call.reject("Unknown location permission status")
                }
            }
        } else {
            print("[CameraPreview] No location required, performing capture directly")
            self.performCapture(call: call)
        }
    }

    private func performCapture(call: CAPPluginCall) {
        print("[CameraPreview] performCapture called")
        print("[CameraPreview] Call parameters: \(call.options)")
        let quality = call.getFloat("quality", 85)
        let saveToGallery = call.getBool("saveToGallery", false)
        let withExifLocation = call.getBool("withExifLocation", false)
        let width = call.getInt("width")
        let height = call.getInt("height")
        let aspectRatio = call.getString("aspectRatio")

        print("[CameraPreview] Raw parameter values - width: \(String(describing: width)), height: \(String(describing: height)), aspectRatio: \(String(describing: aspectRatio))")

        // Check for conflicting parameters
        if aspectRatio != nil && (width != nil || height != nil) {
            print("[CameraPreview] Error: Cannot set both aspectRatio and size (width/height)")
            call.reject("Cannot set both aspectRatio and size (width/height). Use setPreviewSize after start.")
            return
        }

        // When no dimensions are specified, we should capture exactly what's visible in the preview
        // Don't pass aspectRatio in this case - let the capture method handle preview matching
        print("[CameraPreview] Capture decision - width: \(width == nil), height: \(height == nil), aspectRatio param: \(aspectRatio == nil)")
        print("[CameraPreview] Stored aspectRatio: \(self.aspectRatio ?? "nil")")

        // Only pass aspectRatio if explicitly provided in the capture call
        // Never use the stored aspectRatio when capturing without dimensions
        let captureAspectRatio: String? = aspectRatio

        print("[CameraPreview] Capture params - quality: \(quality), saveToGallery: \(saveToGallery), withExifLocation: \(withExifLocation), width: \(width ?? -1), height: \(height ?? -1), aspectRatio: \(aspectRatio ?? "nil"), using aspectRatio: \(captureAspectRatio ?? "nil")")
        print("[CameraPreview] Current location: \(self.currentLocation?.description ?? "nil")")
        // Safely read frame from main thread for logging
        let (previewWidth, previewHeight): (CGFloat, CGFloat) = {
            if Thread.isMainThread {
                return (self.previewView.frame.width, self.previewView.frame.height)
            }
            var w: CGFloat = 0
            var h: CGFloat = 0
            DispatchQueue.main.sync {
                w = self.previewView.frame.width
                h = self.previewView.frame.height
            }
            return (w, h)
        }()
        print("[CameraPreview] Preview dimensions: \(previewWidth)x\(previewHeight)")

        self.cameraController.captureImage(width: width, height: height, aspectRatio: captureAspectRatio, quality: quality, gpsLocation: self.currentLocation) { (image, originalPhotoData, _, error) in
            print("[CameraPreview] captureImage callback received")
            DispatchQueue.main.async {
                print("[CameraPreview] Processing capture on main thread")
                if let error = error {
                    print("[CameraPreview] Capture error: \(error.localizedDescription)")
                    call.reject(error.localizedDescription)
                    return
                }

                guard let image = image,
                      let imageDataWithExif = self.createImageDataWithExif(
                        from: image,
                        quality: Int(quality),
                        location: withExifLocation ? self.currentLocation : nil,
                        originalPhotoData: originalPhotoData
                      )
                else {
                    print("[CameraPreview] Failed to create image data with EXIF")
                    call.reject("Failed to create image data with EXIF")
                    return
                }

                print("[CameraPreview] Image data created, size: \(imageDataWithExif.count) bytes")

                if saveToGallery {
                    print("[CameraPreview] Saving to gallery...")
                    self.saveImageDataToGallery(imageData: imageDataWithExif) { success, error in
                        print("[CameraPreview] Save to gallery completed, success: \(success), error: \(error?.localizedDescription ?? "none")")
                        let exifData = self.getExifData(from: imageDataWithExif)
                        let base64Image = imageDataWithExif.base64EncodedString()

                        var result = JSObject()
                        result["exif"] = exifData
                        result["gallerySaved"] = success
                        if !success, let error = error {
                            result["galleryError"] = error.localizedDescription
                        }

                        if self.storeToFile == false {
                            let base64Image = imageDataWithExif.base64EncodedString()
                            result["value"] = base64Image
                        } else {
                            do {
                                let fileUrl = self.getTempFilePath()
                                try imageDataWithExif.write(to: fileUrl)
                                result["value"] = fileUrl.absoluteString
                            } catch {
                                call.reject("Error writing image to file")
                            }
                        }

                        print("[CameraPreview] Resolving capture call with gallery save")
                        call.resolve(result)
                    }
                } else {
                    print("[CameraPreview] Not saving to gallery, returning image data")
                    let exifData = self.getExifData(from: imageDataWithExif)

                    if self.storeToFile == false {
                        let base64Image = imageDataWithExif.base64EncodedString()
                        var result = JSObject()
                        result["value"] = base64Image
                        result["exif"] = exifData

                        print("[CameraPreview] base64 - Resolving capture call")
                        call.resolve(result)
                    } else {
                        do {
                            let fileUrl = self.getTempFilePath()
                            try imageDataWithExif.write(to: fileUrl)
                            var result = JSObject()
                            result["value"] = fileUrl.absoluteString
                            result["exif"] = exifData
                            print("[CameraPreview] filePath - Resolving capture call")
                            call.resolve(result)
                        } catch {
                            call.reject("Error writing image to file")
                        }
                    }

                }
            }
        }
    }

    private func getExifData(from imageData: Data) -> JSObject {
        guard let imageSource = CGImageSourceCreateWithData(imageData as CFData, nil),
              let imageProperties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [String: Any],
              let exifDict = imageProperties[kCGImagePropertyExifDictionary as String] as? [String: Any] else {
            return [:]
        }

        var exifData = JSObject()
        for (key, value) in exifDict {
            // Convert value to JSValue-compatible type
            if let stringValue = value as? String {
                exifData[key] = stringValue
            } else if let numberValue = value as? NSNumber {
                exifData[key] = numberValue
            } else if let boolValue = value as? Bool {
                exifData[key] = boolValue
            } else if let arrayValue = value as? [Any] {
                exifData[key] = arrayValue
            } else if let dictValue = value as? [String: Any] {
                exifData[key] = JSObject(_immutableCocoaDictionary: NSMutableDictionary(dictionary: dictValue))
            } else {
                // Convert other types to string as fallback
                exifData[key] = String(describing: value)
            }
        }

        return exifData
    }
    
    @objc func getSafeAreaInsets(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            var notchInset: CGFloat = 0
            var orientation: Int = 0
            
            // Get the current interface orientation
            let interfaceOrientation: UIInterfaceOrientation? = {
                return (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.interfaceOrientation
            }()
            
            // Convert to orientation number (matching Android values for consistency)
            switch interfaceOrientation {
            case .portrait, .portraitUpsideDown:
                orientation = 1 // Portrait
            case .landscapeLeft, .landscapeRight:
                orientation = 2 // Landscape
            default:
                orientation = 0 // Unknown
            }
            
            // Get safe area insets
            if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
               let window = windowScene.windows.first {
                let safeAreaInsets = window.safeAreaInsets
                
                switch interfaceOrientation {
                case .portrait:
                    // Portrait: notch is at the top
                    notchInset = safeAreaInsets.top
                case .portraitUpsideDown:
                    // Portrait upside down: notch is at the bottom (but we still call it "top" for consistency)
                    notchInset = safeAreaInsets.bottom
                case .landscapeLeft:
                    // Landscape left: notch is typically on the left
                    notchInset = safeAreaInsets.left
                case .landscapeRight:
                    // Landscape right: notch is typically on the right (but we use left for consistency with Android)
                    notchInset = safeAreaInsets.right
                default:
                    // Unknown orientation, default to top
                    notchInset = safeAreaInsets.top
                }
            } else {
                // Fallback: use status bar height as approximation
                notchInset = UIApplication.shared.statusBarFrame.height
            }
            
            let result: [String: Any] = [
                "orientation": orientation,
                "top": Double(notchInset)
            ]
            
            call.resolve(result)
        }
    }

    private func createImageDataWithExif(from image: UIImage, quality: Int, location: CLLocation?, originalPhotoData: Data?) -> Data? {
        guard let jpegDataAtQuality = image.jpegData(compressionQuality: CGFloat(Double(quality) / 100.0)) else {
            return nil
        }

        // Prefer metadata from the original AVCapturePhoto file data to preserve lens/EXIF
        let sourceDataForMetadata = (originalPhotoData ?? jpegDataAtQuality) as CFData
        guard let imageSource = CGImageSourceCreateWithData(sourceDataForMetadata, nil),
              let imageProperties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [String: Any],
              let cgImage = image.cgImage else {
            return jpegDataAtQuality
        }

        let mutableData = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(mutableData, kUTTypeJPEG, 1, nil) else {
            return jpegDataAtQuality
        }

        var finalProperties = imageProperties

        // Ensure orientation reflects the pixel data (we pass an orientation-fixed UIImage)
        finalProperties[kCGImagePropertyOrientation as String] = 1

        // Add GPS location if available
        if let location = location {
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ssZ"
            formatter.timeZone = TimeZone(abbreviation: "UTC")

            let gpsDict: [String: Any] = [
                kCGImagePropertyGPSLatitude as String: abs(location.coordinate.latitude),
                kCGImagePropertyGPSLatitudeRef as String: location.coordinate.latitude >= 0 ? "N" : "S",
                kCGImagePropertyGPSLongitude as String: abs(location.coordinate.longitude),
                kCGImagePropertyGPSLongitudeRef as String: location.coordinate.longitude >= 0 ? "E" : "W",
                kCGImagePropertyGPSTimeStamp as String: formatter.string(from: location.timestamp),
                kCGImagePropertyGPSAltitude as String: location.altitude,
                kCGImagePropertyGPSAltitudeRef as String: location.altitude >= 0 ? 0 : 1
            ]

            finalProperties[kCGImagePropertyGPSDictionary as String] = gpsDict
        }

        // Create or update TIFF dictionary for device info and set orientation to Up
        var tiffDict = finalProperties[kCGImagePropertyTIFFDictionary as String] as? [String: Any] ?? [:]
        tiffDict[kCGImagePropertyTIFFMake as String] = "Apple"
        tiffDict[kCGImagePropertyTIFFModel as String] = UIDevice.current.model
        tiffDict[kCGImagePropertyTIFFOrientation as String] = 1
        finalProperties[kCGImagePropertyTIFFDictionary as String] = tiffDict

        CGImageDestinationAddImage(destination, cgImage, finalProperties as CFDictionary)

        if CGImageDestinationFinalize(destination) {
            return mutableData as Data
        }

        return jpegDataAtQuality
    }

    @objc func captureSample(_ call: CAPPluginCall) {
        let quality: Int? = call.getInt("quality", 85)

        self.cameraController.captureSample { image, error in
            guard let image = image else {
                print("Image capture error: \(String(describing: error))")
                call.reject("Image capture error: \(String(describing: error))")
                return
            }

            let imageData: Data?
            if self.cameraPosition == "front" {
                let flippedImage = image.withHorizontallyFlippedOrientation()
                imageData = flippedImage.jpegData(compressionQuality: CGFloat(quality!/100))
            } else {
                imageData = image.jpegData(compressionQuality: CGFloat(quality!/100))
            }

            if self.storeToFile == false {
                let imageBase64 = imageData?.base64EncodedString()
                call.resolve(["value": imageBase64!])
            } else {
                do {
                    let fileUrl = self.getTempFilePath()
                    try imageData?.write(to: fileUrl)
                    call.resolve(["value": fileUrl.absoluteString])
                } catch {
                    call.reject("Error writing image to file")
                }
            }
        }
    }

    @objc func getSupportedFlashModes(_ call: CAPPluginCall) {
        do {
            let supportedFlashModes = try self.cameraController.getSupportedFlashModes()
            call.resolve(["result": supportedFlashModes])
        } catch {
            call.reject("failed to get supported flash modes")
        }
    }

    @objc func getHorizontalFov(_ call: CAPPluginCall) {
        do {
            let horizontalFov = try self.cameraController.getHorizontalFov()
            call.resolve(["result": horizontalFov])
        } catch {
            call.reject("failed to get FOV")
        }
    }

    @objc func setFlashMode(_ call: CAPPluginCall) {
        guard let flashMode = call.getString("flashMode") else {
            call.reject("failed to set flash mode. required parameter flashMode is missing")
            return
        }
        do {
            var flashModeAsEnum: AVCaptureDevice.FlashMode?
            switch flashMode {
            case "off":
                flashModeAsEnum = AVCaptureDevice.FlashMode.off
            case "on":
                flashModeAsEnum = AVCaptureDevice.FlashMode.on
            case "auto":
                flashModeAsEnum = AVCaptureDevice.FlashMode.auto
            default: break
            }
            if flashModeAsEnum != nil {
                try self.cameraController.setFlashMode(flashMode: flashModeAsEnum!)
            } else if flashMode == "torch" {
                try self.cameraController.setTorchMode()
            } else {
                call.reject("Flash Mode not supported")
                return
            }
            call.resolve()
        } catch {
            call.reject("failed to set flash mode")
        }
    }

    @objc func startRecordVideo(_ call: CAPPluginCall) {
        do {
            try self.cameraController.captureVideo()
            call.resolve()
        } catch {
            call.reject(error.localizedDescription)
        }
    }

    @objc func stopRecordVideo(_ call: CAPPluginCall) {
        self.cameraController.stopRecording { (fileURL, error) in
            guard let fileURL = fileURL else {
                print(error ?? "Video capture error")
                guard let error = error else {
                    call.reject("Video capture error")
                    return
                }
                call.reject(error.localizedDescription)
                return
            }

            call.resolve(["videoFilePath": fileURL.absoluteString])
        }
    }

    @objc func isRunning(_ call: CAPPluginCall) {
        let isRunning = self.isInitialized && (self.cameraController.captureSession?.isRunning ?? false)
        call.resolve(["isRunning": isRunning])
    }

    @objc func getAvailableDevices(_ call: CAPPluginCall) {
        let deviceTypes: [AVCaptureDevice.DeviceType] = [
            .builtInWideAngleCamera,
            .builtInUltraWideCamera,
            .builtInTelephotoCamera,
            .builtInDualCamera,
            .builtInDualWideCamera,
            .builtInTripleCamera,
            .builtInTrueDepthCamera
        ]

        let session = AVCaptureDevice.DiscoverySession(
            deviceTypes: deviceTypes,
            mediaType: .video,
            position: .unspecified
        )

        var devices: [[String: Any]] = []

        // Collect all devices by position
        for device in session.devices {
            var lenses: [[String: Any]] = []

            let constituentDevices = device.isVirtualDevice ? device.constituentDevices : [device]

            for lensDevice in constituentDevices {
                var deviceType: String
                switch lensDevice.deviceType {
                case .builtInWideAngleCamera: deviceType = "wideAngle"
                case .builtInUltraWideCamera: deviceType = "ultraWide"
                case .builtInTelephotoCamera: deviceType = "telephoto"
                case .builtInDualCamera: deviceType = "dual"
                case .builtInDualWideCamera: deviceType = "dualWide"
                case .builtInTripleCamera: deviceType = "triple"
                case .builtInTrueDepthCamera: deviceType = "trueDepth"
                default: deviceType = "unknown"
                }

                var baseZoomRatio: Float = 1.0
                if lensDevice.deviceType == .builtInUltraWideCamera {
                    baseZoomRatio = 0.5
                } else if lensDevice.deviceType == .builtInTelephotoCamera {
                    baseZoomRatio = 2.0 // A common value for telephoto lenses
                }

                let lensInfo: [String: Any] = [
                    "label": lensDevice.localizedName,
                    "deviceType": deviceType,
                    "focalLength": 4.25, // Placeholder
                    "baseZoomRatio": baseZoomRatio,
                    "minZoom": Float(lensDevice.minAvailableVideoZoomFactor),
                    "maxZoom": Float(lensDevice.maxAvailableVideoZoomFactor)
                ]
                lenses.append(lensInfo)
            }

            let deviceData: [String: Any] = [
                "deviceId": device.uniqueID,
                "label": device.localizedName,
                "position": device.position == .front ? "front" : "rear",
                "lenses": lenses,
                "minZoom": Float(device.minAvailableVideoZoomFactor),
                "maxZoom": Float(device.maxAvailableVideoZoomFactor),
                "isLogical": device.isVirtualDevice
            ]

            devices.append(deviceData)
        }

        call.resolve(["devices": devices])
    }

    @objc func getZoom(_ call: CAPPluginCall) {
        guard isInitialized else {
            call.reject("Camera not initialized")
            return
        }

        do {
            let zoomInfo = try self.cameraController.getZoom()
            let lensInfo = try self.cameraController.getCurrentLensInfo()
            let displayMultiplier = self.cameraController.getDisplayZoomMultiplier()

            var minZoom = zoomInfo.min
            var maxZoom = zoomInfo.max
            var currentZoom = zoomInfo.current

            // Apply iOS 18+ display multiplier so UI sees the expected values
            if displayMultiplier != 1.0 {
                minZoom *= displayMultiplier
                maxZoom *= displayMultiplier
                currentZoom *= displayMultiplier
            }

            call.resolve([
                "min": minZoom,
                "max": maxZoom,
                "current": currentZoom,
                "lens": [
                    "focalLength": lensInfo.focalLength,
                    "deviceType": lensInfo.deviceType,
                    "baseZoomRatio": lensInfo.baseZoomRatio,
                    "digitalZoom": Float(currentZoom) / lensInfo.baseZoomRatio
                ]
            ])
        } catch {
            call.reject("Failed to get zoom: \(error.localizedDescription)")
        }
    }

    @objc func setZoom(_ call: CAPPluginCall) {
        guard isInitialized else {
            call.reject("Camera not initialized")
            return
        }

        guard var level = call.getFloat("level") else {
            call.reject("level parameter is required")
            return
        }

        // If using the multi-lens camera, translate the JS zoom value for the native layer
        // First, convert from UI/display zoom to native zoom using the iOS 18 multiplier
        let displayMultiplier = self.cameraController.getDisplayZoomMultiplier()
        if displayMultiplier != 1.0 {
            level = level / displayMultiplier
        }

        let ramp = call.getBool("ramp") ?? true
        let autoFocus = call.getBool("autoFocus") ?? false

        do {
            try self.cameraController.setZoom(level: CGFloat(level), ramp: ramp, autoFocus: autoFocus)
            call.resolve()
        } catch {
            call.reject("Failed to set zoom: \(error.localizedDescription)")
        }
    }

    @objc func getFlashMode(_ call: CAPPluginCall) {
        guard isInitialized else {
            call.reject("Camera not initialized")
            return
        }

        do {
            let flashMode = try self.cameraController.getFlashMode()
            call.resolve(["flashMode": flashMode])
        } catch {
            call.reject("Failed to get flash mode: \(error.localizedDescription)")
        }
    }

    @objc func setDeviceId(_ call: CAPPluginCall) {
        guard isInitialized else {
            call.reject("Camera not initialized")
            return
        }

        guard let deviceId = call.getString("deviceId") else {
            call.reject("deviceId parameter is required")
            return
        }

        // Disable user interaction during device swap
        self.previewView.isUserInteractionEnabled = false

        do {
            try self.cameraController.swapToDevice(deviceId: deviceId)

            // Update preview layer frame without animation
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            self.cameraController.previewLayer?.frame = self.previewView.bounds
            self.cameraController.previewLayer?.videoGravity = .resizeAspectFill
            CATransaction.commit()

            self.previewView.isUserInteractionEnabled = true

            // Ensure webview remains transparent after device switch
            self.makeWebViewTransparent()

            call.resolve()
        } catch {
            self.previewView.isUserInteractionEnabled = true
            call.reject("Failed to swap to device \(deviceId): \(error.localizedDescription)")
        }
    }

    @objc func getDeviceId(_ call: CAPPluginCall) {
        guard isInitialized else {
            call.reject("Camera not initialized")
            return
        }

        do {
            let deviceId = try self.cameraController.getCurrentDeviceId()
            call.resolve(["deviceId": deviceId])
        } catch {
            call.reject("Failed to get device ID: \(error.localizedDescription)")
        }
    }

    // MARK: - Capacitor Permissions

    private func requestLocationPermission(completion: @escaping (Bool) -> Void) {
        print("[CameraPreview] requestLocationPermission called")
        if self.locationManager == nil {
            print("[CameraPreview] Creating location manager")
            self.locationManager = CLLocationManager()
            self.locationManager?.delegate = self
        }

        let authStatus = self.locationManager?.authorizationStatus
        print("[CameraPreview] Current authorization status: \(String(describing: authStatus))")

        switch authStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            print("[CameraPreview] Location already authorized")
            completion(true)
        case .notDetermined:
            print("[CameraPreview] Location not determined, requesting authorization...")
            self.permissionCompletion = completion
            self.locationManager?.requestWhenInUseAuthorization()
        case .denied, .restricted:
            print("[CameraPreview] Location denied or restricted")
            completion(false)
        case .none:
            print("[CameraPreview] Location manager authorization status is nil")
            completion(false)
        @unknown default:
            print("[CameraPreview] Unknown authorization status")
            completion(false)
        }
    }

    private var permissionCompletion: ((Bool) -> Void)?

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        print("[CameraPreview] locationManagerDidChangeAuthorization called, status: \(status.rawValue), thread: \(Thread.current)")

        // Handle pending capture call if we have one
        if let callID = self.permissionCallID, self.waitingForLocation {
            print("[CameraPreview] Found pending capture call ID: \(callID)")

            let handleAuthorization = {
                print("[CameraPreview] Getting saved call on thread: \(Thread.current)")
                guard let call = self.bridge?.savedCall(withID: callID) else {
                    print("[CameraPreview] ERROR: Could not retrieve saved call")
                    self.permissionCallID = nil
                    self.waitingForLocation = false
                    return
                }
                print("[CameraPreview] Successfully retrieved saved call")

                switch status {
                case .authorizedWhenInUse, .authorizedAlways:
                    print("[CameraPreview] Location authorized, getting location for capture")
                    self.getCurrentLocation { _ in
                        self.performCapture(call: call)
                        self.bridge?.releaseCall(call)
                        self.permissionCallID = nil
                        self.waitingForLocation = false
                    }
                case .denied, .restricted:
                    print("[CameraPreview] Location denied, rejecting capture")
                    call.reject("Location permission denied")
                    self.bridge?.releaseCall(call)
                    self.permissionCallID = nil
                    self.waitingForLocation = false
                case .notDetermined:
                    print("[CameraPreview] Authorization not determined yet")
                // Don't do anything, wait for user response
                @unknown default:
                    print("[CameraPreview] Unknown status, rejecting capture")
                    call.reject("Unknown location permission status")
                    self.bridge?.releaseCall(call)
                    self.permissionCallID = nil
                    self.waitingForLocation = false
                }
            }

            // Check if we're already on main thread
            if Thread.isMainThread {
                print("[CameraPreview] Already on main thread")
                handleAuthorization()
            } else {
                print("[CameraPreview] Not on main thread, dispatching")
                DispatchQueue.main.async(execute: handleAuthorization)
            }
        } else {
            print("[CameraPreview] No pending capture call")
        }
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("[CameraPreview] locationManager didFailWithError: \(error.localizedDescription)")
    }

    private func getCurrentLocation(completion: @escaping (CLLocation?) -> Void) {
        print("[CameraPreview] getCurrentLocation called")
        self.locationCompletion = completion
        self.locationManager?.startUpdatingLocation()
        print("[CameraPreview] Started updating location")
    }

    private var locationCompletion: ((CLLocation?) -> Void)?

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        print("[CameraPreview] locationManager didUpdateLocations called, locations count: \(locations.count)")
        self.currentLocation = locations.last
        if let completion = locationCompletion {
            print("[CameraPreview] Calling location completion with location: \(self.currentLocation?.description ?? "nil")")
            self.locationManager?.stopUpdatingLocation()
            completion(self.currentLocation)
            locationCompletion = nil
        } else {
            print("[CameraPreview] No location completion handler found")
        }
    }

    private func saveImageDataToGallery(imageData: Data, completion: @escaping (Bool, Error?) -> Void) {
        // Check if NSPhotoLibraryUsageDescription is present in Info.plist
        guard Bundle.main.object(forInfoDictionaryKey: "NSPhotoLibraryUsageDescription") != nil else {
            let error = NSError(domain: "CameraPreview", code: 2, userInfo: [
                NSLocalizedDescriptionKey: "NSPhotoLibraryUsageDescription key missing from Info.plist. Add this key with a description of how your app uses photo library access."
            ])
            completion(false, error)
            return
        }

        let status = PHPhotoLibrary.authorizationStatus()

        switch status {
        case .authorized:
            performSaveDataToGallery(imageData: imageData, completion: completion)
        case .notDetermined:
            PHPhotoLibrary.requestAuthorization { newStatus in
                if newStatus == .authorized {
                    self.performSaveDataToGallery(imageData: imageData, completion: completion)
                } else {
                    completion(false, NSError(domain: "CameraPreview", code: 1, userInfo: [NSLocalizedDescriptionKey: "Photo library access denied"]))
                }
            }
        case .denied, .restricted:
            completion(false, NSError(domain: "CameraPreview", code: 1, userInfo: [NSLocalizedDescriptionKey: "Photo library access denied"]))
        case .limited:
            performSaveDataToGallery(imageData: imageData, completion: completion)
        @unknown default:
            completion(false, NSError(domain: "CameraPreview", code: 1, userInfo: [NSLocalizedDescriptionKey: "Unknown photo library authorization status"]))
        }
    }

    private func performSaveDataToGallery(imageData: Data, completion: @escaping (Bool, Error?) -> Void) {
        // Create a temporary file to write the JPEG data with EXIF
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")

        do {
            try imageData.write(to: tempURL)

            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: tempURL)
            }, completionHandler: { success, error in
                // Clean up temporary file
                try? FileManager.default.removeItem(at: tempURL)

                completion(success, error)
            })
        } catch {
            completion(false, error)
        }
    }

    private func isPortrait() -> Bool {
        let orientation = UIDevice.current.orientation
        if orientation.isValidInterfaceOrientation {
            return orientation.isPortrait
        } else {
            let interfaceOrientation: UIInterfaceOrientation? = {
                if Thread.isMainThread {
                    return (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.interfaceOrientation
                } else {
                    var value: UIInterfaceOrientation?
                    DispatchQueue.main.sync {
                        value = (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.interfaceOrientation
                    }
                    return value
                }
            }()
            return interfaceOrientation?.isPortrait ?? false
        }
    }

    private func calculateCameraFrame(x: CGFloat? = nil, y: CGFloat? = nil, width: CGFloat? = nil, height: CGFloat? = nil, aspectRatio: String? = nil) -> CGRect {
        // Use provided values or existing ones
        let currentWidth = width ?? self.width ?? UIScreen.main.bounds.size.width
        let currentHeight = height ?? self.height ?? UIScreen.main.bounds.size.height
        let currentX = x ?? self.posX ?? -1
        let currentY = y ?? self.posY ?? -1
        let currentAspectRatio = aspectRatio ?? self.aspectRatio

        let paddingBottom = self.paddingBottom ?? 0
        let adjustedHeight = currentHeight - CGFloat(paddingBottom)

        // Cache webView dimensions for performance
        let webViewWidth = self.webView?.frame.width ?? UIScreen.main.bounds.width
        let webViewHeight = self.webView?.frame.height ?? UIScreen.main.bounds.height

        let isPortrait = self.isPortrait()

        var finalX = currentX
        var finalY = currentY
        var finalWidth = currentWidth
        var finalHeight = adjustedHeight

        // Handle auto-centering when position is -1
        if currentX == -1 || currentY == -1 {
            // Only override dimensions if aspect ratio is provided and no explicit dimensions given
            if let ratio = currentAspectRatio,
               currentWidth == UIScreen.main.bounds.size.width &&
                currentHeight == UIScreen.main.bounds.size.height {
                finalWidth = webViewWidth

                // width: 428.0 height: 926.0 - portrait

                print("[CameraPreview] width: \(UIScreen.main.bounds.size.width) height: \(UIScreen.main.bounds.size.height)")

                // Calculate height based on aspect ratio
                let ratioParts = ratio.split(separator: ":").compactMap { Double($0) }
                if ratioParts.count == 2 {
                    // For camera, use portrait orientation: 4:3 becomes 3:4, 16:9 becomes 9:16
                    let ratioValue = ratioParts[1] / ratioParts[0]
                    if isPortrait {
                        finalHeight = finalWidth / CGFloat(ratioValue)
                    } else {
                        finalWidth = finalHeight / CGFloat(ratioValue)
                    }
                }
            }

            // Center horizontally if x is -1
            if currentX == -1 {
                finalX = (webViewWidth - finalWidth) / 2
            } else {
                finalX = currentX
            }

            // Position vertically if y is -1
            // TODO: fix top, bottom for landscape
            if currentY == -1 {
                // Use full screen height for positioning
                let screenHeight = UIScreen.main.bounds.size.height
                let screenWidth = UIScreen.main.bounds.size.width
                switch self.positioning {
                case "top":
                    finalY = 0
                    print("[CameraPreview] Positioning at top: finalY=0")
                case "bottom":
                    finalY = screenHeight - finalHeight
                    print("[CameraPreview] Positioning at bottom: screenHeight=\(screenHeight), finalHeight=\(finalHeight), finalY=\(finalY)")
                default: // "center"
                    if isPortrait {
                        finalY = (screenHeight - finalHeight) / 2
                        print("[CameraPreview] Centering vertically: screenHeight=\(screenHeight), finalHeight=\(finalHeight), finalY=\(finalY)")
                    } else {
                        // In landscape, center both horizontally and vertically
                        finalY = (screenHeight - finalHeight) / 2
                        finalX = (screenWidth - finalWidth) / 2
                    }
                }
            } else {
                finalY = currentY
            }
        }

        return CGRect(x: finalX, y: finalY, width: finalWidth, height: finalHeight)
    }

    private func updateCameraFrame() {
        guard let width = self.width, let height = self.height, let posX = self.posX, let posY = self.posY else {
            return
        }

        // Ensure UI operations happen on main thread
        guard Thread.isMainThread else {
            DispatchQueue.main.async {
                self.updateCameraFrame()
            }
            return
        }

        // Calculate the base frame using the factorized method
        var frame = calculateCameraFrame()

        // Apply aspect ratio adjustments only if not auto-centering
        if posX != -1 && posY != -1, let aspectRatio = self.aspectRatio {
            let ratioParts = aspectRatio.split(separator: ":").compactMap { Double($0) }
            if ratioParts.count == 2 {
                // For camera, use portrait orientation: 4:3 becomes 3:4, 16:9 becomes 9:16
                let ratio = ratioParts[1] / ratioParts[0]
                let currentRatio = Double(frame.width) / Double(frame.height)

                if currentRatio > ratio {
                    let newWidth = Double(frame.height) * ratio
                    frame.origin.x = frame.origin.x + (frame.width - CGFloat(newWidth)) / 2
                    frame.size.width = CGFloat(newWidth)
                } else {
                    let newHeight = Double(frame.width) / ratio
                    frame.origin.y = frame.origin.y + (frame.height - CGFloat(newHeight)) / 2
                    frame.size.height = CGFloat(newHeight)
                }
            }
        }

        // Disable ALL animations for frame updates - we want instant positioning
        CATransaction.begin()
        CATransaction.setDisableActions(true)

        // Batch UI updates for better performance
        if self.previewView == nil {
            self.previewView = UIView(frame: frame)
            self.previewView.backgroundColor = UIColor.clear
        } else {
            self.previewView.frame = frame
        }

        // Update preview layer frame efficiently
        if let previewLayer = self.cameraController.previewLayer {
            previewLayer.frame = self.previewView.bounds
        }

        // Update grid overlay frame if it exists
        if let gridOverlay = self.cameraController.gridOverlayView {
            gridOverlay.frame = self.previewView.bounds
        }

        CATransaction.commit()
    }

    @objc func getPreviewSize(_ call: CAPPluginCall) {
        guard self.isInitialized else {
            call.reject("camera not started")
            return
        }

        DispatchQueue.main.async {
            var result = JSObject()
            result["x"] = Double(self.previewView.frame.origin.x)
            result["y"] = Double(self.previewView.frame.origin.y)
            result["width"] = Double(self.previewView.frame.width)
            result["height"] = Double(self.previewView.frame.height)
            call.resolve(result)
        }
    }

    @objc func setPreviewSize(_ call: CAPPluginCall) {
        guard self.isInitialized else {
            call.reject("camera not started")
            return
        }

        // Always set to -1 for auto-centering if not explicitly provided
        if let x = call.getInt("x") {
            self.posX = CGFloat(x)
        } else {
            self.posX = -1 // Auto-center if X not provided
        }

        if let y = call.getInt("y") {
            self.posY = CGFloat(y)
        } else {
            self.posY = -1 // Auto-center if Y not provided
        }

        if let width = call.getInt("width") { self.width = CGFloat(width) }
        if let height = call.getInt("height") { self.height = CGFloat(height) }

        DispatchQueue.main.async {
            // Direct update without animation for better performance
            self.updateCameraFrame()
            self.makeWebViewTransparent()

            // Return the actual preview bounds
            var result = JSObject()
            result["x"] = Double(self.previewView.frame.origin.x)
            result["y"] = Double(self.previewView.frame.origin.y)
            result["width"] = Double(self.previewView.frame.width)
            result["height"] = Double(self.previewView.frame.height)
            call.resolve(result)
        }
    }

    @objc func setFocus(_ call: CAPPluginCall) {
        guard isInitialized else {
            call.reject("Camera not initialized")
            return
        }

        guard let x = call.getFloat("x"), let y = call.getFloat("y") else {
            call.reject("x and y parameters are required")
            return
        }

        // Reject if values are outside 0-1 range
        if x < 0 || x > 1 || y < 0 || y > 1 {
            call.reject("Focus coordinates must be between 0 and 1")
            return
        }

        DispatchQueue.main.async {
            do {
                // Convert normalized coordinates to view coordinates
                let viewX = CGFloat(x) * self.previewView.bounds.width
                let viewY = CGFloat(y) * self.previewView.bounds.height
                let focusPoint = CGPoint(x: viewX, y: viewY)

                // Convert view coordinates to device coordinates
                guard let previewLayer = self.cameraController.previewLayer else {
                    call.reject("Preview layer not available")
                    return
                }
                let devicePoint = previewLayer.captureDevicePointConverted(fromLayerPoint: focusPoint)

                try self.cameraController.setFocus(at: devicePoint, showIndicator: true, in: self.previewView)
                call.resolve()
            } catch {
                call.reject("Failed to set focus: \(error.localizedDescription)")
            }
        }
    }

    @objc private func handleOrientationChange() {
        DispatchQueue.main.async {
            let result = self.rawSetAspectRatio()
            self.notifyListeners("screenResize", data: result)
            self.notifyListeners("orientationChange", data: ["orientation": self.currentOrientationString()])
        }
    }

    @objc func deleteFile(_ call: CAPPluginCall) {
        guard let path = call.getString("path"), !path.isEmpty else {
            call.reject("path parameter is required")
            return
        }
        let url: URL?
        if path.hasPrefix("file://") {
            url = URL(string: path)
        } else {
            url = URL(fileURLWithPath: path)
        }
        guard let fileURL = url else {
            call.reject("Invalid path")
            return
        }
        do {
            if FileManager.default.fileExists(atPath: fileURL.path) {
                try FileManager.default.removeItem(at: fileURL)
                call.resolve(["success": true])
            } else {
                call.resolve(["success": false])
            }
        } catch {
            call.reject("Failed to delete file: \(error.localizedDescription)")
        }
    }

    // MARK: - Orientation
    private func currentOrientationString() -> String {
        // Prefer interface orientation for UI-consistent results
        let orientation: UIInterfaceOrientation? = {
            if Thread.isMainThread {
                return (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.interfaceOrientation
            } else {
                var value: UIInterfaceOrientation?
                DispatchQueue.main.sync {
                    value = (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.interfaceOrientation
                }
                return value
            }
        }()
        switch orientation {
        case .portrait: return "portrait"
        case .portraitUpsideDown: return "portrait-upside-down"
        case .landscapeLeft: return "landscape-left"
        case .landscapeRight: return "landscape-right"
        default: return "unknown"
        }
    }

    @objc func getOrientation(_ call: CAPPluginCall) {
        call.resolve(["orientation": self.currentOrientationString()])
    }
}
