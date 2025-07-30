//
//  CameraController.swift
//  Plugin
//
//  Created by Ariel Hernandez Musa on 7/14/19.
//  Copyright © 2019 Max Lynch. All rights reserved.
//

import AVFoundation
import UIKit
import CoreLocation

class CameraController: NSObject {
    var captureSession: AVCaptureSession?

    var currentCameraPosition: CameraPosition?

    var frontCamera: AVCaptureDevice?
    var frontCameraInput: AVCaptureDeviceInput?

    var dataOutput: AVCaptureVideoDataOutput?
    var photoOutput: AVCapturePhotoOutput?

    var rearCamera: AVCaptureDevice?
    var rearCameraInput: AVCaptureDeviceInput?

    var allDiscoveredDevices: [AVCaptureDevice] = []

    var fileVideoOutput: AVCaptureMovieFileOutput?

    var previewLayer: AVCaptureVideoPreviewLayer?
    var gridOverlayView: GridOverlayView?
    var focusIndicatorView: UIView?

    var flashMode = AVCaptureDevice.FlashMode.off
    var photoCaptureCompletionBlock: ((UIImage?, Error?) -> Void)?

    var sampleBufferCaptureCompletionBlock: ((UIImage?, Error?) -> Void)?

    var audioDevice: AVCaptureDevice?
    var audioInput: AVCaptureDeviceInput?

    var zoomFactor: CGFloat = 1.0
    private var lastZoomUpdateTime: TimeInterval = 0
    private let zoomUpdateThrottle: TimeInterval = 1.0 / 60.0 // 60 FPS max

    var videoFileURL: URL?
    private let saneMaxZoomFactor: CGFloat = 25.5

    var isUsingMultiLensVirtualCamera: Bool {
        guard let device = (currentCameraPosition == .rear) ? rearCamera : frontCamera else { return false }
        // A rear multi-lens virtual camera will have a min zoom of 1.0 but support wider angles
        return device.position == .back && device.isVirtualDevice && device.constituentDevices.count > 1
    }
}

extension CameraController {
    func prepareFullSession() {
        // Only prepare if we don't already have a session
        guard self.captureSession == nil else { return }

        print("[CameraPreview] Preparing full camera session in background")

        // 1. Create and configure session
        self.captureSession = AVCaptureSession()

        // 2. Pre-configure session preset (can be changed later)
        if captureSession!.canSetSessionPreset(.high) {
            captureSession!.sessionPreset = .high
        }

        // 3. Discover and configure all cameras
        discoverAndConfigureCameras()

        // 4. Pre-create outputs (don't add to session yet)
        prepareOutputs()

        print("[CameraPreview] Full session preparation complete")
    }

    private func discoverAndConfigureCameras() {
        let deviceTypes: [AVCaptureDevice.DeviceType] = [
            .builtInWideAngleCamera,
            .builtInUltraWideCamera,
            .builtInTelephotoCamera,
            .builtInDualCamera,
            .builtInDualWideCamera,
            .builtInTripleCamera,
            .builtInTrueDepthCamera
        ]

        let session = AVCaptureDevice.DiscoverySession(deviceTypes: deviceTypes, mediaType: AVMediaType.video, position: .unspecified)
        let cameras = session.devices.compactMap { $0 }

        // Store all discovered devices for fast lookup later
        self.allDiscoveredDevices = cameras

        // Log all found devices for debugging
        print("[CameraPreview] Found \(cameras.count) devices:")
        for camera in cameras {
            let constituentCount = camera.isVirtualDevice ? camera.constituentDevices.count : 1
            print("[CameraPreview] - \(camera.localizedName) (Position: \(camera.position.rawValue), Virtual: \(camera.isVirtualDevice), Lenses: \(constituentCount))")
        }

        // Find best cameras
        let rearVirtualDevices = cameras.filter { $0.position == .back && $0.isVirtualDevice }
        let bestRearVirtualDevice = rearVirtualDevices.max { $0.constituentDevices.count < $1.constituentDevices.count }

        self.frontCamera = cameras.first(where: { $0.position == .front })

        if let bestCamera = bestRearVirtualDevice {
            self.rearCamera = bestCamera
            print("[CameraPreview] Selected best virtual rear camera: \(bestCamera.localizedName) with \(bestCamera.constituentDevices.count) physical cameras.")
        } else if let firstRearCamera = cameras.first(where: { $0.position == .back }) {
            self.rearCamera = firstRearCamera
            print("[CameraPreview] WARN: No virtual rear camera found. Selected first available: \(firstRearCamera.localizedName)")
        }

        // Pre-configure focus modes
        configureCameraFocus(camera: self.rearCamera)
        configureCameraFocus(camera: self.frontCamera)
    }

    private func configureCameraFocus(camera: AVCaptureDevice?) {
        guard let camera = camera else { return }

        do {
            try camera.lockForConfiguration()
            if camera.isFocusModeSupported(.continuousAutoFocus) {
                camera.focusMode = .continuousAutoFocus
            }
            camera.unlockForConfiguration()
        } catch {
            print("[CameraPreview] Could not configure focus for \(camera.localizedName): \(error)")
        }
    }

    private func prepareOutputs() {
        // Pre-create photo output
        self.photoOutput = AVCapturePhotoOutput()
        self.photoOutput?.isHighResolutionCaptureEnabled = false // Default, can be changed

        // Pre-create video output
        self.fileVideoOutput = AVCaptureMovieFileOutput()

        // Pre-create data output
        self.dataOutput = AVCaptureVideoDataOutput()
        self.dataOutput?.videoSettings = [
            (kCVPixelBufferPixelFormatTypeKey as String): NSNumber(value: kCVPixelFormatType_32BGRA as UInt32)
        ]
        self.dataOutput?.alwaysDiscardsLateVideoFrames = true

        print("[CameraPreview] Outputs pre-created")
    }

    func prepare(cameraPosition: String, deviceId: String? = nil, disableAudio: Bool, cameraMode: Bool, aspectRatio: String? = nil, initialZoomLevel: Float = 1.0, completionHandler: @escaping (Error?) -> Void) {
        do {
            // Session and outputs already created in load(), just configure user-specific settings
            if self.captureSession == nil {
                // Fallback if prepareFullSession() wasn't called
                self.prepareFullSession()
            }

            guard let captureSession = self.captureSession else {
                throw CameraControllerError.captureSessionIsMissing
            }

            print("[CameraPreview] Fast prepare - using pre-initialized session")

            // Configure device inputs for the requested camera
            try self.configureDeviceInputs(cameraPosition: cameraPosition, deviceId: deviceId, disableAudio: disableAudio)

            // Add outputs to session and apply user settings
            try self.addOutputsToSession(cameraMode: cameraMode, aspectRatio: aspectRatio)

            // Start the session
            captureSession.startRunning()
            print("[CameraPreview] Session started")

            // Validate and set initial zoom level
            if initialZoomLevel != 1.0 {
                let device = (currentCameraPosition == .rear) ? rearCamera : frontCamera
                if let device = device {
                    let minZoom = device.minAvailableVideoZoomFactor
                    let maxZoom = min(device.maxAvailableVideoZoomFactor, saneMaxZoomFactor)

                    if CGFloat(initialZoomLevel) < minZoom || CGFloat(initialZoomLevel) > maxZoom {
                        let error = CameraControllerError.invalidZoomLevel(min: minZoom, max: maxZoom, requested: CGFloat(initialZoomLevel))
                        completionHandler(error)
                        return
                    }

                    do {
                        try device.lockForConfiguration()
                        device.videoZoomFactor = CGFloat(initialZoomLevel)
                        device.unlockForConfiguration()
                        self.zoomFactor = CGFloat(initialZoomLevel)
                        print("[CameraPreview] Set initial zoom to \(initialZoomLevel)")
                    } catch {
                        print("[CameraPreview] Failed to set initial zoom: \(error)")
                        completionHandler(error)
                        return
                    }
                }
            }

            completionHandler(nil)
        } catch {
            completionHandler(error)
        }
    }

    private func configureDeviceInputs(cameraPosition: String, deviceId: String?, disableAudio: Bool) throws {
        guard let captureSession = self.captureSession else { throw CameraControllerError.captureSessionIsMissing }

        var selectedDevice: AVCaptureDevice?

        // If deviceId is specified, find that specific device from pre-discovered devices
        if let deviceId = deviceId {
            selectedDevice = self.allDiscoveredDevices.first(where: { $0.uniqueID == deviceId })
            guard selectedDevice != nil else {
                print("[CameraPreview] ERROR: Device with ID \(deviceId) not found in pre-discovered devices")
                throw CameraControllerError.noCamerasAvailable
            }
        } else {
            // Use position-based selection from pre-discovered cameras
            if cameraPosition == "rear" {
                selectedDevice = self.rearCamera
            } else if cameraPosition == "front" {
                selectedDevice = self.frontCamera
            }
        }

        guard let finalDevice = selectedDevice else {
            print("[CameraPreview] ERROR: No camera device selected for position: \(cameraPosition)")
            throw CameraControllerError.noCamerasAvailable
        }

        print("[CameraPreview] Configuring device: \(finalDevice.localizedName)")
        let deviceInput = try AVCaptureDeviceInput(device: finalDevice)

        if captureSession.canAddInput(deviceInput) {
            captureSession.addInput(deviceInput)

            if finalDevice.position == .front {
                self.frontCameraInput = deviceInput
                self.currentCameraPosition = .front
            } else {
                self.rearCameraInput = deviceInput
                self.currentCameraPosition = .rear

                // Configure zoom for multi-camera systems
                try finalDevice.lockForConfiguration()
                let defaultWideAngleZoom: CGFloat = 2.0
                if finalDevice.isVirtualDevice && finalDevice.constituentDevices.count > 1 && finalDevice.videoZoomFactor != defaultWideAngleZoom {
                    if defaultWideAngleZoom >= finalDevice.minAvailableVideoZoomFactor && defaultWideAngleZoom <= finalDevice.maxAvailableVideoZoomFactor {
                        print("[CameraPreview] Setting initial zoom to \(defaultWideAngleZoom)")
                        finalDevice.videoZoomFactor = defaultWideAngleZoom
                    }
                }
                finalDevice.unlockForConfiguration()
            }
        } else {
            throw CameraControllerError.inputsAreInvalid
        }

        // Add audio input if needed
        if !disableAudio {
            if self.audioDevice == nil {
                self.audioDevice = AVCaptureDevice.default(for: AVMediaType.audio)
            }
            if let audioDevice = self.audioDevice {
                self.audioInput = try AVCaptureDeviceInput(device: audioDevice)
                if captureSession.canAddInput(self.audioInput!) {
                    captureSession.addInput(self.audioInput!)
                } else {
                    throw CameraControllerError.inputsAreInvalid
                }
            }
        }
    }

    private func addOutputsToSession(cameraMode: Bool, aspectRatio: String?) throws {
        guard let captureSession = self.captureSession else { throw CameraControllerError.captureSessionIsMissing }

        // Update session preset based on aspect ratio if needed
        var targetPreset: AVCaptureSession.Preset = .high // Default to high quality

        if let aspectRatio = aspectRatio {
            switch aspectRatio {
            case "16:9":
                targetPreset = captureSession.canSetSessionPreset(.hd1920x1080) ? .hd1920x1080 : .high
            case "4:3":
                targetPreset = captureSession.canSetSessionPreset(.photo) ? .photo : .high
            default:
                targetPreset = .high
            }
        }

        // Always try to set the best preset available
        if captureSession.canSetSessionPreset(targetPreset) {
            captureSession.sessionPreset = targetPreset
            print("[CameraPreview] Updated preset to \(targetPreset) for aspect ratio: \(aspectRatio ?? "default")")
        } else if captureSession.canSetSessionPreset(.high) {
            // Fallback to high if target preset not available
            captureSession.sessionPreset = .high
            print("[CameraPreview] Fallback to high preset")
        }

        // Add photo output (already created in prepareOutputs)
        if let photoOutput = self.photoOutput, captureSession.canAddOutput(photoOutput) {
            photoOutput.isHighResolutionCaptureEnabled = true
            captureSession.addOutput(photoOutput)
        }

        // Add video output only if camera mode is enabled
        if cameraMode, let videoOutput = self.fileVideoOutput, captureSession.canAddOutput(videoOutput) {
            captureSession.addOutput(videoOutput)
        }

        // Add data output
        if let dataOutput = self.dataOutput, captureSession.canAddOutput(dataOutput) {
            captureSession.addOutput(dataOutput)
            captureSession.commitConfiguration()

            dataOutput.setSampleBufferDelegate(self, queue: DispatchQueue.main)
        }
    }

    func displayPreview(on view: UIView) throws {
        guard let captureSession = self.captureSession, captureSession.isRunning else { throw CameraControllerError.captureSessionIsMissing }

        print("[CameraPreview] displayPreview called with view frame: \(view.frame)")

        self.previewLayer = AVCaptureVideoPreviewLayer(session: captureSession)
        self.previewLayer?.videoGravity = AVLayerVideoGravity.resizeAspectFill

        // Optimize preview layer for better quality
        self.previewLayer?.connection?.videoOrientation = .portrait
        self.previewLayer?.isOpaque = true

        // Set contentsScale for retina display quality
        self.previewLayer?.contentsScale = UIScreen.main.scale

        // Enable high-quality rendering
        if #available(iOS 13.0, *) {
            self.previewLayer?.videoGravity = .resizeAspectFill
        }

        view.layer.insertSublayer(self.previewLayer!, at: 0)

        // Disable animation for frame update
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        self.previewLayer?.frame = view.bounds
        CATransaction.commit()

        print("[CameraPreview] Set preview layer frame to view bounds: \(view.bounds)")
        print("[CameraPreview] Session preset: \(captureSession.sessionPreset.rawValue)")

        updateVideoOrientation()
    }

    func addGridOverlay(to view: UIView, gridMode: String) {
        removeGridOverlay()

        // Disable animation for grid overlay creation and positioning
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        gridOverlayView = GridOverlayView(frame: view.bounds)
        gridOverlayView?.gridMode = gridMode
        view.addSubview(gridOverlayView!)
        CATransaction.commit()
    }

    func removeGridOverlay() {
        gridOverlayView?.removeFromSuperview()
        gridOverlayView = nil
    }

    func setupGestures(target: UIView, enableZoom: Bool) {
        setupTapGesture(target: target, selector: #selector(handleTap(_:)), delegate: self)
        if enableZoom {
            setupPinchGesture(target: target, selector: #selector(handlePinch(_:)), delegate: self)
        }
    }

    func setupTapGesture(target: UIView, selector: Selector, delegate: UIGestureRecognizerDelegate?) {
        let tapGesture = UITapGestureRecognizer(target: self, action: selector)
        tapGesture.delegate = delegate
        target.addGestureRecognizer(tapGesture)
    }

    func setupPinchGesture(target: UIView, selector: Selector, delegate: UIGestureRecognizerDelegate?) {
        let pinchGesture = UIPinchGestureRecognizer(target: self, action: selector)
        pinchGesture.delegate = delegate
        // Optimize gesture recognition for better performance
        pinchGesture.delaysTouchesBegan = false
        pinchGesture.delaysTouchesEnded = false
        pinchGesture.cancelsTouchesInView = false
        target.addGestureRecognizer(pinchGesture)
    }

    func updateVideoOrientation() {
        if Thread.isMainThread {
            updateVideoOrientationOnMainThread()
        } else {
            DispatchQueue.main.sync {
                self.updateVideoOrientationOnMainThread()
            }
        }
    }

    private func updateVideoOrientationOnMainThread() {
        let videoOrientation: AVCaptureVideoOrientation

        // Use window scene interface orientation
        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            switch windowScene.interfaceOrientation {
            case .portrait:
                videoOrientation = .portrait
            case .landscapeLeft:
                videoOrientation = .landscapeLeft
            case .landscapeRight:
                videoOrientation = .landscapeRight
            case .portraitUpsideDown:
                videoOrientation = .portraitUpsideDown
            case .unknown:
                fallthrough
            @unknown default:
                videoOrientation = .portrait
            }
        } else {
            videoOrientation = .portrait
        }

        previewLayer?.connection?.videoOrientation = videoOrientation
        dataOutput?.connections.forEach { $0.videoOrientation = videoOrientation }
        photoOutput?.connections.forEach { $0.videoOrientation = videoOrientation }
    }

    func switchCameras() throws {
        guard let currentCameraPosition = currentCameraPosition,
              let captureSession = self.captureSession else {
            throw CameraControllerError.captureSessionIsMissing
        }

        // Ensure we have the necessary cameras
        guard (currentCameraPosition == .front && rearCamera != nil) ||
                (currentCameraPosition == .rear && frontCamera != nil) else {
            throw CameraControllerError.noCamerasAvailable
        }

        // Store the current running state
        let wasRunning = captureSession.isRunning
        if wasRunning {
            captureSession.stopRunning()
        }

        // Begin configuration
        captureSession.beginConfiguration()
        defer {
            captureSession.commitConfiguration()
            // Restart the session if it was running before
            if wasRunning {
                captureSession.startRunning()
            }
        }

        // Store audio input if it exists
        let audioInput = captureSession.inputs.first { ($0 as? AVCaptureDeviceInput)?.device.hasMediaType(.audio) ?? false }

        // Remove only video inputs
        captureSession.inputs.forEach { input in
            if (input as? AVCaptureDeviceInput)?.device.hasMediaType(.video) ?? false {
                captureSession.removeInput(input)
            }
        }

        // Configure new camera
        switch currentCameraPosition {
        case .front:
            guard let rearCamera = rearCamera else {
                throw CameraControllerError.invalidOperation
            }

            // Configure rear camera
            try rearCamera.lockForConfiguration()
            if rearCamera.isFocusModeSupported(.continuousAutoFocus) {
                rearCamera.focusMode = .continuousAutoFocus
            }
            rearCamera.unlockForConfiguration()

            if let newInput = try? AVCaptureDeviceInput(device: rearCamera),
               captureSession.canAddInput(newInput) {
                captureSession.addInput(newInput)
                rearCameraInput = newInput
                self.currentCameraPosition = .rear
            } else {
                throw CameraControllerError.invalidOperation
            }
        case .rear:
            guard let frontCamera = frontCamera else {
                throw CameraControllerError.invalidOperation
            }

            // Configure front camera
            try frontCamera.lockForConfiguration()
            if frontCamera.isFocusModeSupported(.continuousAutoFocus) {
                frontCamera.focusMode = .continuousAutoFocus
            }
            frontCamera.unlockForConfiguration()

            if let newInput = try? AVCaptureDeviceInput(device: frontCamera),
               captureSession.canAddInput(newInput) {
                captureSession.addInput(newInput)
                frontCameraInput = newInput
                self.currentCameraPosition = .front
            } else {
                throw CameraControllerError.invalidOperation
            }
        }

        // Re-add audio input if it existed
        if let audioInput = audioInput, captureSession.canAddInput(audioInput) {
            captureSession.addInput(audioInput)
        }

        // Update video orientation
        self.updateVideoOrientation()
    }

    func captureImage(width: Int?, height: Int?, quality: Float, gpsLocation: CLLocation?, completion: @escaping (UIImage?, Error?) -> Void) {
        guard let photoOutput = self.photoOutput else {
            completion(nil, NSError(domain: "Camera", code: 0, userInfo: [NSLocalizedDescriptionKey: "Photo output is not available"]))
            return
        }

        let settings = AVCapturePhotoSettings()

        // Apply the current flash mode to the photo settings
        // Check if the current device supports flash
        var currentCamera: AVCaptureDevice?
        switch currentCameraPosition {
        case .front:
            currentCamera = self.frontCamera
        case .rear:
            currentCamera = self.rearCamera
        default:
            break
        }

        // Only apply flash if the device has flash and the flash mode is supported
        if let device = currentCamera, device.hasFlash {
            let supportedFlashModes = photoOutput.supportedFlashModes
            if supportedFlashModes.contains(self.flashMode) {
                settings.flashMode = self.flashMode
            }
        }

        self.photoCaptureCompletionBlock = { (image, error) in
            if let error = error {
                completion(nil, error)
                return
            }

            guard let image = image else {
                completion(nil, NSError(domain: "Camera", code: 0, userInfo: [NSLocalizedDescriptionKey: "Failed to capture image"]))
                return
            }

            if let location = gpsLocation {
                self.addGPSMetadata(to: image, location: location)
            }

            if let width = width, let height = height {
                let resizedImage = self.resizeImage(image: image, to: CGSize(width: width, height: height))
                completion(resizedImage, nil)
            } else {
                completion(image, nil)
            }
        }

        photoOutput.capturePhoto(with: settings, delegate: self)
    }

    func addGPSMetadata(to image: UIImage, location: CLLocation) {
        guard let jpegData = image.jpegData(compressionQuality: 1.0),
              let source = CGImageSourceCreateWithData(jpegData as CFData, nil),
              let uti = CGImageSourceGetType(source) else { return }

        var metadata = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any] ?? [:]

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

        metadata[kCGImagePropertyGPSDictionary as String] = gpsDict

        let destData = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(destData, uti, 1, nil) else { return }
        CGImageDestinationAddImageFromSource(destination, source, 0, metadata as CFDictionary)
        CGImageDestinationFinalize(destination)
    }

    func resizeImage(image: UIImage, to size: CGSize) -> UIImage? {
        let renderer = UIGraphicsImageRenderer(size: size)
        let resizedImage = renderer.image { (_) in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        return resizedImage
    }

    func captureSample(completion: @escaping (UIImage?, Error?) -> Void) {
        guard let captureSession = captureSession,
              captureSession.isRunning else {
            completion(nil, CameraControllerError.captureSessionIsMissing)
            return
        }

        self.sampleBufferCaptureCompletionBlock = completion
    }

    func getSupportedFlashModes() throws -> [String] {
        var currentCamera: AVCaptureDevice?
        switch currentCameraPosition {
        case .front:
            currentCamera = self.frontCamera!
        case .rear:
            currentCamera = self.rearCamera!
        default: break
        }

        guard
            let device = currentCamera
        else {
            throw CameraControllerError.noCamerasAvailable
        }

        var supportedFlashModesAsStrings: [String] = []
        if device.hasFlash {
            guard let supportedFlashModes: [AVCaptureDevice.FlashMode] = self.photoOutput?.supportedFlashModes else {
                throw CameraControllerError.noCamerasAvailable
            }

            for flashMode in supportedFlashModes {
                var flashModeValue: String?
                switch flashMode {
                case AVCaptureDevice.FlashMode.off:
                    flashModeValue = "off"
                case AVCaptureDevice.FlashMode.on:
                    flashModeValue = "on"
                case AVCaptureDevice.FlashMode.auto:
                    flashModeValue = "auto"
                default: break
                }
                if flashModeValue != nil {
                    supportedFlashModesAsStrings.append(flashModeValue!)
                }
            }
        }
        if device.hasTorch {
            supportedFlashModesAsStrings.append("torch")
        }
        return supportedFlashModesAsStrings

    }
    func getHorizontalFov() throws -> Float {
        var currentCamera: AVCaptureDevice?
        switch currentCameraPosition {
        case .front:
            currentCamera = self.frontCamera!
        case .rear:
            currentCamera = self.rearCamera!
        default: break
        }

        guard
            let device = currentCamera
        else {
            throw CameraControllerError.noCamerasAvailable
        }

        // Get the active format and field of view
        let activeFormat = device.activeFormat
        let fov = activeFormat.videoFieldOfView

        // Adjust for current zoom level
        let zoomFactor = device.videoZoomFactor
        let adjustedFov = fov / Float(zoomFactor)

        return adjustedFov
    }
    func setFlashMode(flashMode: AVCaptureDevice.FlashMode) throws {
        var currentCamera: AVCaptureDevice?
        switch currentCameraPosition {
        case .front:
            currentCamera = self.frontCamera!
        case .rear:
            currentCamera = self.rearCamera!
        default: break
        }

        guard let device = currentCamera else {
            throw CameraControllerError.noCamerasAvailable
        }

        guard let supportedFlashModes: [AVCaptureDevice.FlashMode] = self.photoOutput?.supportedFlashModes else {
            throw CameraControllerError.invalidOperation
        }
        if supportedFlashModes.contains(flashMode) {
            do {
                try device.lockForConfiguration()

                if device.hasTorch && device.isTorchAvailable && device.torchMode == AVCaptureDevice.TorchMode.on {
                    device.torchMode = AVCaptureDevice.TorchMode.off
                }
                self.flashMode = flashMode
                let photoSettings = AVCapturePhotoSettings()
                photoSettings.flashMode = flashMode
                self.photoOutput?.photoSettingsForSceneMonitoring = photoSettings

                device.unlockForConfiguration()
            } catch {
                throw CameraControllerError.invalidOperation
            }
        } else {
            throw CameraControllerError.invalidOperation
        }
    }

    func setTorchMode() throws {
        var currentCamera: AVCaptureDevice?
        switch currentCameraPosition {
        case .front:
            currentCamera = self.frontCamera!
        case .rear:
            currentCamera = self.rearCamera!
        default: break
        }

        guard
            let device = currentCamera,
            device.hasTorch,
            device.isTorchAvailable
        else {
            throw CameraControllerError.invalidOperation
        }

        do {
            try device.lockForConfiguration()
            if device.isTorchModeSupported(AVCaptureDevice.TorchMode.on) {
                device.torchMode = AVCaptureDevice.TorchMode.on
            } else if device.isTorchModeSupported(AVCaptureDevice.TorchMode.auto) {
                device.torchMode = AVCaptureDevice.TorchMode.auto
            } else {
                device.torchMode = AVCaptureDevice.TorchMode.off
            }
            device.unlockForConfiguration()
        } catch {
            throw CameraControllerError.invalidOperation
        }
    }

    func getZoom() throws -> (min: Float, max: Float, current: Float) {
        var currentCamera: AVCaptureDevice?
        switch currentCameraPosition {
        case .front:
            currentCamera = self.frontCamera
        case .rear:
            currentCamera = self.rearCamera
        default: break
        }

        guard let device = currentCamera else {
            throw CameraControllerError.noCamerasAvailable
        }

        let effectiveMaxZoom = min(device.maxAvailableVideoZoomFactor, self.saneMaxZoomFactor)

        return (
            min: Float(device.minAvailableVideoZoomFactor),
            max: Float(effectiveMaxZoom),
            current: Float(device.videoZoomFactor)
        )
    }

    func setZoom(level: CGFloat, ramp: Bool, autoFocus: Bool = true) throws {
        var currentCamera: AVCaptureDevice?
        switch currentCameraPosition {
        case .front:
            currentCamera = self.frontCamera
        case .rear:
            currentCamera = self.rearCamera
        default: break
        }

        guard let device = currentCamera else {
            throw CameraControllerError.noCamerasAvailable
        }

        let effectiveMaxZoom = min(device.maxAvailableVideoZoomFactor, self.saneMaxZoomFactor)
        let zoomLevel = max(device.minAvailableVideoZoomFactor, min(level, effectiveMaxZoom))

        do {
            try device.lockForConfiguration()

            if ramp {
                // Use a very fast ramp rate for immediate response
                device.ramp(toVideoZoomFactor: zoomLevel, withRate: 8.0)
            } else {
                device.videoZoomFactor = zoomLevel
            }

            device.unlockForConfiguration()

            // Update our internal zoom factor tracking
            self.zoomFactor = zoomLevel

            // Trigger autofocus after zoom if requested
            if autoFocus {
                self.triggerAutoFocus()
            }
        } catch {
            throw CameraControllerError.invalidOperation
        }
    }

    private func triggerAutoFocus() {
        var currentCamera: AVCaptureDevice?
        switch currentCameraPosition {
        case .front:
            currentCamera = self.frontCamera
        case .rear:
            currentCamera = self.rearCamera
        default: break
        }

        guard let device = currentCamera else {
            return
        }

        // Focus on the center of the preview (0.5, 0.5)
        let centerPoint = CGPoint(x: 0.5, y: 0.5)

        do {
            try device.lockForConfiguration()

            // Set focus mode to auto if supported
            if device.isFocusModeSupported(.autoFocus) {
                device.focusMode = .autoFocus
                if device.isFocusPointOfInterestSupported {
                    device.focusPointOfInterest = centerPoint
                }
            } else if device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusMode = .continuousAutoFocus
                if device.isFocusPointOfInterestSupported {
                    device.focusPointOfInterest = centerPoint
                }
            }

            // Also set exposure point if supported
            if device.isExposurePointOfInterestSupported && device.isExposureModeSupported(.autoExpose) {
                device.exposureMode = .autoExpose
                device.exposurePointOfInterest = centerPoint
            } else if device.isExposureModeSupported(.continuousAutoExposure) {
                device.exposureMode = .continuousAutoExposure
                if device.isExposurePointOfInterestSupported {
                    device.exposurePointOfInterest = centerPoint
                }
            }

            device.unlockForConfiguration()
        } catch {
            // Silently ignore errors during autofocus
        }
    }

    func setFocus(at point: CGPoint, showIndicator: Bool = false, in view: UIView? = nil) throws {
        var currentCamera: AVCaptureDevice?
        switch currentCameraPosition {
        case .front:
            currentCamera = self.frontCamera
        case .rear:
            currentCamera = self.rearCamera
        default: break
        }

        guard let device = currentCamera else {
            throw CameraControllerError.noCamerasAvailable
        }

        guard device.isFocusPointOfInterestSupported else {
            // Device doesn't support focus point of interest
            return
        }

        // Show focus indicator if requested and view is provided
        if showIndicator, let view = view, let previewLayer = self.previewLayer {
            // Convert the device point to layer point for indicator display
            let layerPoint = previewLayer.layerPointConverted(fromCaptureDevicePoint: point)
            showFocusIndicator(at: layerPoint, in: view)
        }

        do {
            try device.lockForConfiguration()

            // Set focus mode to auto if supported
            if device.isFocusModeSupported(.autoFocus) {
                device.focusMode = .autoFocus
            } else if device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusMode = .continuousAutoFocus
            }

            // Set the focus point
            device.focusPointOfInterest = point

            // Also set exposure point if supported
            if device.isExposurePointOfInterestSupported && device.isExposureModeSupported(.autoExpose) {
                device.exposureMode = .autoExpose
                device.exposurePointOfInterest = point
            }

            device.unlockForConfiguration()
        } catch {
            throw CameraControllerError.unknown
        }
    }

    func getFlashMode() throws -> String {
        switch self.flashMode {
        case .off:
            return "off"
        case .on:
            return "on"
        case .auto:
            return "auto"
        @unknown default:
            return "off"
        }
    }

    func getCurrentDeviceId() throws -> String {
        var currentCamera: AVCaptureDevice?
        switch currentCameraPosition {
        case .front:
            currentCamera = self.frontCamera
        case .rear:
            currentCamera = self.rearCamera
        default:
            break
        }

        guard let device = currentCamera else {
            throw CameraControllerError.noCamerasAvailable
        }

        return device.uniqueID
    }

    func getCurrentLensInfo() throws -> (focalLength: Float, deviceType: String, baseZoomRatio: Float) {
        var currentCamera: AVCaptureDevice?
        switch currentCameraPosition {
        case .front:
            currentCamera = self.frontCamera
        case .rear:
            currentCamera = self.rearCamera
        default:
            break
        }

        guard let device = currentCamera else {
            throw CameraControllerError.noCamerasAvailable
        }

        var deviceType = "wideAngle"
        var baseZoomRatio: Float = 1.0

        switch device.deviceType {
        case .builtInWideAngleCamera:
            deviceType = "wideAngle"
            baseZoomRatio = 1.0
        case .builtInUltraWideCamera:
            deviceType = "ultraWide"
            baseZoomRatio = 0.5
        case .builtInTelephotoCamera:
            deviceType = "telephoto"
            baseZoomRatio = 2.0
        case .builtInDualCamera:
            deviceType = "dual"
            baseZoomRatio = 1.0
        case .builtInDualWideCamera:
            deviceType = "dualWide"
            baseZoomRatio = 1.0
        case .builtInTripleCamera:
            deviceType = "triple"
            baseZoomRatio = 1.0
        case .builtInTrueDepthCamera:
            deviceType = "trueDepth"
            baseZoomRatio = 1.0
        default:
            deviceType = "wideAngle"
            baseZoomRatio = 1.0
        }

        // Approximate focal length for mobile devices
        let focalLength: Float = 4.25

        return (focalLength: focalLength, deviceType: deviceType, baseZoomRatio: baseZoomRatio)
    }

    func swapToDevice(deviceId: String) throws {
        guard let captureSession = self.captureSession else {
            throw CameraControllerError.captureSessionIsMissing
        }

        // Find the device with the specified deviceId
        let allDevices = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .builtInUltraWideCamera, .builtInTelephotoCamera, .builtInDualCamera, .builtInDualWideCamera, .builtInTripleCamera, .builtInTrueDepthCamera],
            mediaType: .video,
            position: .unspecified
        ).devices

        guard let targetDevice = allDevices.first(where: { $0.uniqueID == deviceId }) else {
            throw CameraControllerError.noCamerasAvailable
        }

        // Store the current running state
        let wasRunning = captureSession.isRunning
        if wasRunning {
            captureSession.stopRunning()
        }

        // Begin configuration
        captureSession.beginConfiguration()
        defer {
            captureSession.commitConfiguration()
            // Restart the session if it was running before
            if wasRunning {
                captureSession.startRunning()
            }
        }

        // Store audio input if it exists
        let audioInput = captureSession.inputs.first { ($0 as? AVCaptureDeviceInput)?.device.hasMediaType(.audio) ?? false }

        // Remove only video inputs
        captureSession.inputs.forEach { input in
            if (input as? AVCaptureDeviceInput)?.device.hasMediaType(.video) ?? false {
                captureSession.removeInput(input)
            }
        }

        // Configure the new device
        let newInput = try AVCaptureDeviceInput(device: targetDevice)

        if captureSession.canAddInput(newInput) {
            captureSession.addInput(newInput)

            // Update camera references based on device position
            if targetDevice.position == .front {
                self.frontCameraInput = newInput
                self.frontCamera = targetDevice
                self.currentCameraPosition = .front
            } else {
                self.rearCameraInput = newInput
                self.rearCamera = targetDevice
                self.currentCameraPosition = .rear

                // Configure rear camera
                try targetDevice.lockForConfiguration()
                if targetDevice.isFocusModeSupported(.continuousAutoFocus) {
                    targetDevice.focusMode = .continuousAutoFocus
                }
                targetDevice.unlockForConfiguration()
            }
        } else {
            throw CameraControllerError.invalidOperation
        }

        // Re-add audio input if it existed
        if let audioInput = audioInput, captureSession.canAddInput(audioInput) {
            captureSession.addInput(audioInput)
        }

        // Update video orientation
        self.updateVideoOrientation()
    }

    func cleanup() {
        if let captureSession = self.captureSession {
            captureSession.stopRunning()
            captureSession.inputs.forEach { captureSession.removeInput($0) }
            captureSession.outputs.forEach { captureSession.removeOutput($0) }
        }

        self.previewLayer?.removeFromSuperlayer()
        self.previewLayer = nil

        self.focusIndicatorView?.removeFromSuperview()
        self.focusIndicatorView = nil

        self.frontCameraInput = nil
        self.rearCameraInput = nil
        self.audioInput = nil

        self.frontCamera = nil
        self.rearCamera = nil
        self.audioDevice = nil

        self.dataOutput = nil
        self.photoOutput = nil
        self.fileVideoOutput = nil

        self.captureSession = nil
        self.currentCameraPosition = nil
    }

    func captureVideo() throws {
        guard let captureSession = self.captureSession, captureSession.isRunning else {
            throw CameraControllerError.captureSessionIsMissing
        }
        guard let documentsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            throw CameraControllerError.cannotFindDocumentsDirectory
        }

        guard let fileVideoOutput = self.fileVideoOutput else {
            throw CameraControllerError.fileVideoOutputNotFound
        }

        // cpcp_video_A6C01203 - portrait
        //
        if let connection = fileVideoOutput.connection(with: .video) {
            switch UIDevice.current.orientation {
            case .landscapeRight:
                connection.videoOrientation = .landscapeLeft
            case .landscapeLeft:
                connection.videoOrientation = .landscapeRight
            case .portrait:
                connection.videoOrientation = .portrait
            case .portraitUpsideDown:
                connection.videoOrientation = .portraitUpsideDown
            default:
                connection.videoOrientation = .portrait
            }
        }

        let identifier = UUID()
        let randomIdentifier = identifier.uuidString.replacingOccurrences(of: "-", with: "")
        let finalIdentifier = String(randomIdentifier.prefix(8))
        let fileName="cpcp_video_"+finalIdentifier+".mp4"

        let fileUrl = documentsDirectory.appendingPathComponent(fileName)
        try? FileManager.default.removeItem(at: fileUrl)

        // Start recording video
        fileVideoOutput.startRecording(to: fileUrl, recordingDelegate: self)

        // Save the file URL for later use
        self.videoFileURL = fileUrl
    }

    func stopRecording(completion: @escaping (URL?, Error?) -> Void) {
        guard let captureSession = self.captureSession, captureSession.isRunning else {
            completion(nil, CameraControllerError.captureSessionIsMissing)
            return
        }
        guard let fileVideoOutput = self.fileVideoOutput else {
            completion(nil, CameraControllerError.fileVideoOutputNotFound)
            return
        }

        // Stop recording video
        fileVideoOutput.stopRecording()

        // Return the video file URL in the completion handler
        completion(self.videoFileURL, nil)
    }
}

extension CameraController: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        return true
    }

    @objc
    func handleTap(_ tap: UITapGestureRecognizer) {
        guard let device = self.currentCameraPosition == .rear ? rearCamera : frontCamera else { return }

        let point = tap.location(in: tap.view)
        let devicePoint = self.previewLayer?.captureDevicePointConverted(fromLayerPoint: point)

        // Show focus indicator at the tap point
        if let view = tap.view {
            showFocusIndicator(at: point, in: view)
        }

        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }

            let focusMode = AVCaptureDevice.FocusMode.autoFocus
            if device.isFocusPointOfInterestSupported && device.isFocusModeSupported(focusMode) {
                device.focusPointOfInterest = CGPoint(x: CGFloat(devicePoint?.x ?? 0), y: CGFloat(devicePoint?.y ?? 0))
                device.focusMode = focusMode
            }

            let exposureMode = AVCaptureDevice.ExposureMode.autoExpose
            if device.isExposurePointOfInterestSupported && device.isExposureModeSupported(exposureMode) {
                device.exposurePointOfInterest = CGPoint(x: CGFloat(devicePoint?.x ?? 0), y: CGFloat(devicePoint?.y ?? 0))
                device.exposureMode = exposureMode
            }
        } catch {
            debugPrint(error)
        }
    }

    private func showFocusIndicator(at point: CGPoint, in view: UIView) {
        // Remove any existing focus indicator
        focusIndicatorView?.removeFromSuperview()

        // Create a new focus indicator
        let indicator = UIView(frame: CGRect(x: 0, y: 0, width: 80, height: 80))
        indicator.center = point
        indicator.layer.borderColor = UIColor.yellow.cgColor
        indicator.layer.borderWidth = 2.0
        indicator.layer.cornerRadius = 40
        indicator.backgroundColor = UIColor.clear
        indicator.alpha = 0
        indicator.transform = CGAffineTransform(scaleX: 1.5, y: 1.5)

        // Add inner circle for better visibility
        let innerCircle = UIView(frame: CGRect(x: 20, y: 20, width: 40, height: 40))
        innerCircle.layer.borderColor = UIColor.yellow.cgColor
        innerCircle.layer.borderWidth = 1.0
        innerCircle.layer.cornerRadius = 20
        innerCircle.backgroundColor = UIColor.clear
        indicator.addSubview(innerCircle)

        view.addSubview(indicator)
        focusIndicatorView = indicator

        // Animate the focus indicator
        UIView.animate(withDuration: 0.15, animations: {
            indicator.alpha = 1.0
            indicator.transform = CGAffineTransform.identity
        }) { _ in
            // Keep the indicator visible for a moment
            UIView.animate(withDuration: 0.2, delay: 0.5, options: [], animations: {
                indicator.alpha = 0.3
            }) { _ in
                // Fade out and remove
                UIView.animate(withDuration: 0.3, delay: 0.2, options: [], animations: {
                    indicator.alpha = 0
                    indicator.transform = CGAffineTransform(scaleX: 0.8, y: 0.8)
                }) { _ in
                    indicator.removeFromSuperview()
                    if self.focusIndicatorView == indicator {
                        self.focusIndicatorView = nil
                    }
                }
            }
        }
    }

    @objc
    private func handlePinch(_ pinch: UIPinchGestureRecognizer) {
        guard let device = self.currentCameraPosition == .rear ? rearCamera : frontCamera else { return }

        let effectiveMaxZoom = min(device.maxAvailableVideoZoomFactor, self.saneMaxZoomFactor)
        func minMaxZoom(_ factor: CGFloat) -> CGFloat { return max(device.minAvailableVideoZoomFactor, min(factor, effectiveMaxZoom)) }

        switch pinch.state {
        case .began:
            // Store the initial zoom factor when pinch begins
            zoomFactor = device.videoZoomFactor

        case .changed:
            // Throttle zoom updates to prevent excessive CPU usage
            let currentTime = CACurrentMediaTime()
            guard currentTime - lastZoomUpdateTime >= zoomUpdateThrottle else { return }
            lastZoomUpdateTime = currentTime

            // Calculate new zoom factor based on pinch scale
            let newScaleFactor = minMaxZoom(pinch.scale * zoomFactor)

            // Use ramping for smooth zoom transitions during pinch
            // This provides much smoother performance than direct setting
            do {
                try device.lockForConfiguration()
                // Use a very fast ramp rate for immediate response
                device.ramp(toVideoZoomFactor: newScaleFactor, withRate: 5.0)
                device.unlockForConfiguration()
            } catch {
                debugPrint("Failed to set zoom: \(error)")
            }

        case .ended:
            // Update our internal zoom factor tracking
            zoomFactor = device.videoZoomFactor

        default: break
        }
    }
}

extension CameraController: AVCapturePhotoCaptureDelegate {
    public func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error = error {
            self.photoCaptureCompletionBlock?(nil, error)
            return
        }

        // Get the photo data using the modern API
        guard let imageData = photo.fileDataRepresentation() else {
            self.photoCaptureCompletionBlock?(nil, CameraControllerError.unknown)
            return
        }

        guard let image = UIImage(data: imageData) else {
            self.photoCaptureCompletionBlock?(nil, CameraControllerError.unknown)
            return
        }

        self.photoCaptureCompletionBlock?(image.fixedOrientation(), nil)
    }
}

extension CameraController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let completion = sampleBufferCaptureCompletionBlock else { return }

        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            completion(nil, CameraControllerError.unknown)
            return
        }

        CVPixelBufferLockBaseAddress(imageBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(imageBuffer, .readOnly) }

        let baseAddress = CVPixelBufferGetBaseAddress(imageBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(imageBuffer)
        let width = CVPixelBufferGetWidth(imageBuffer)
        let height = CVPixelBufferGetHeight(imageBuffer)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo: UInt32 = CGBitmapInfo.byteOrder32Little.rawValue |
            CGImageAlphaInfo.premultipliedFirst.rawValue

        let context = CGContext(
            data: baseAddress,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        )

        guard let cgImage = context?.makeImage() else {
            completion(nil, CameraControllerError.unknown)
            return
        }

        let image = UIImage(cgImage: cgImage)
        completion(image.fixedOrientation(), nil)

        sampleBufferCaptureCompletionBlock = nil
    }
}

enum CameraControllerError: Swift.Error {
    case captureSessionAlreadyRunning
    case captureSessionIsMissing
    case inputsAreInvalid
    case invalidOperation
    case noCamerasAvailable
    case cannotFindDocumentsDirectory
    case fileVideoOutputNotFound
    case unknown
    case invalidZoomLevel(min: CGFloat, max: CGFloat, requested: CGFloat)
}

public enum CameraPosition {
    case front
    case rear
}

extension CameraControllerError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .captureSessionAlreadyRunning:
            return NSLocalizedString("Capture Session is Already Running", comment: "Capture Session Already Running")
        case .captureSessionIsMissing:
            return NSLocalizedString("Capture Session is Missing", comment: "Capture Session Missing")
        case .inputsAreInvalid:
            return NSLocalizedString("Inputs Are Invalid", comment: "Inputs Are Invalid")
        case .invalidOperation:
            return NSLocalizedString("Invalid Operation", comment: "invalid Operation")
        case .noCamerasAvailable:
            return NSLocalizedString("Failed to access device camera(s)", comment: "No Cameras Available")
        case .unknown:
            return NSLocalizedString("Unknown", comment: "Unknown")
        case .cannotFindDocumentsDirectory:
            return NSLocalizedString("Cannot find documents directory", comment: "This should never happen")
        case .fileVideoOutputNotFound:
            return NSLocalizedString("Video recording is not available. Make sure the camera is properly initialized.", comment: "Video recording not available")
        case .invalidZoomLevel(let min, let max, let requested):
            return NSLocalizedString("Invalid zoom level. Must be between \(min) and \(max). Requested: \(requested)", comment: "Invalid Zoom Level")
        }
    }
}

extension UIImage {

    func fixedOrientation() -> UIImage? {

        guard imageOrientation != UIImage.Orientation.up else {
            // This is default orientation, don't need to do anything
            return self.copy() as? UIImage
        }

        guard let cgImage = self.cgImage else {
            // CGImage is not available
            return nil
        }

        guard let colorSpace = cgImage.colorSpace, let ctx = CGContext(data: nil,
                                                                       width: Int(size.width), height: Int(size.height),
                                                                       bitsPerComponent: cgImage.bitsPerComponent, bytesPerRow: 0,
                                                                       space: colorSpace, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
            return nil // Not able to create CGContext
        }

        var transform: CGAffineTransform = CGAffineTransform.identity
        switch imageOrientation {
        case .down, .downMirrored:
            transform = transform.translatedBy(x: size.width, y: size.height)
            transform = transform.rotated(by: CGFloat.pi)
            print("down")
        case .left, .leftMirrored:
            transform = transform.translatedBy(x: size.width, y: 0)
            transform = transform.rotated(by: CGFloat.pi / 2.0)
            print("left")
        case .right, .rightMirrored:
            transform = transform.translatedBy(x: 0, y: size.height)
            transform = transform.rotated(by: CGFloat.pi / -2.0)
            print("right")
        case .up, .upMirrored:
            break
        @unknown default:
            break
        }

        // Flip image one more time if needed to, this is to prevent flipped image
        switch imageOrientation {
        case .upMirrored, .downMirrored:
            transform.translatedBy(x: size.width, y: 0)
            transform.scaledBy(x: -1, y: 1)
        case .leftMirrored, .rightMirrored:
            transform.translatedBy(x: size.height, y: 0)
            transform.scaledBy(x: -1, y: 1)
        case .up, .down, .left, .right:
            break
        @unknown default:
            break
        }

        ctx.concatenate(transform)

        switch imageOrientation {
        case .left, .leftMirrored, .right, .rightMirrored:
            if let cgImage = self.cgImage {
                ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: size.height, height: size.width))
            }
        default:
            if let cgImage = self.cgImage {
                ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: size.width, height: size.height))
            }
        }
        guard let newCGImage = ctx.makeImage() else { return nil }
        return UIImage.init(cgImage: newCGImage, scale: 1, orientation: .up)
    }
}

extension CameraController: AVCaptureFileOutputRecordingDelegate {
    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        if let error = error {
            print("Error recording movie: \(error.localizedDescription)")
        } else {
            print("Movie recorded successfully: \(outputFileURL)")
            // You can save the file to the library, upload it, etc.
        }
    }
}
