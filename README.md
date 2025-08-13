# Capacitor Camera Preview Plugin

<a href="https://capgo.app/"><img src='https://raw.githubusercontent.com/Cap-go/capgo/main/assets/capgo_banner.png' alt='Capgo - Instant updates for capacitor'/></a>

<div align="center">
  <h2><a href="https://capgo.app/?ref=plugin"> ➡️ Get Instant updates for your App with Capgo 🚀</a></h2>
  <h2><a href="https://capgo.app/consulting/?ref=plugin"> Fix your annoying bug now, Hire a Capacitor expert 💪</a></h2>
</div>

<p>
  Capacitor plugin that allows camera interaction from Javascript and HTML<br>(based on cordova-plugin-camera-preview).
</p>
<br>

This plugin is compatible Capacitor 7 and above.

Use v6 for Capacitor 6 and below.

**PR's are greatly appreciated.**

-- [@riderx](https://github.com/riderx), current maintainers

Remember to add the style below on your app's HTML or body element:

```css
:root {
  --ion-background-color: transparent !important;
}
```

Take into account that this will make transparent all ion-content on application, if you want to show camera preview only in one page, just add a custom class to your ion-content and make it transparent:

```css
.my-custom-camera-preview-content {
  --background: transparent;
}
```

If the camera preview is not displaying after applying the above styles, apply transparent background color to the root div element of the parent component
Ex: VueJS >> App.vue component
```html
<template>
  <ion-app id="app">
    <ion-router-outlet />
  </ion-app>
</template>

<style>
#app {
  background-color: transparent !important;
}
<style>
```

If it don't work in dark mode here is issue who explain how to fix it: https://github.com/capacitor-community/camera-preview/issues/199

<!-- # Features

<ul>
  <li>Start a camera preview from HTML code.</li>
  <li>Maintain HTML interactivity.</li>
  <li>Drag the preview box.</li>
  <li>Set camera color effect.</li>
  <li>Send the preview box to back of the HTML content.</li>
  <li>Set a custom position for the camera preview box.</li>
  <li>Set a custom size for the preview box.</li>
  <li>Set a custom alpha for the preview box.</li>
  <li>Set the focus mode, zoom, color effects, exposure mode, white balance mode and exposure compensation</li>
  <li>Tap to focus</li>
</ul> -->

## Good to know

Video and photo taken with the plugin are never removed, so do not forget to remove them after used to not bloat the user phone.

use https://capacitorjs.com/docs/apis/filesystem#deletefile for that


## Fast base64 from file path (no bridge)

When using `storeToFile: true`, you can avoid sending large base64 strings over the Capacitor bridge:

```ts
import { CameraPreview, getBase64FromFilePath } from '@capgo/camera-preview'

// Take a picture and get a file path
const { value: filePath } = await CameraPreview.capture({ quality: 85, storeToFile: true })

// Convert the file to base64 entirely on the JS side (fast, no bridge)
const base64 = await getBase64FromFilePath(filePath)

// Optionally cleanup the temp file natively
await CameraPreview.deleteFile({ path: filePath })
```


# Installation

```
yarn add @capgo/camera-preview

or

npm install @capgo/camera-preview
```

Then run

```
npx cap sync
```

## Optional Configuration

To use certain features of this plugin, you will need to add the following permissions/keys to your native project configurations.

### Android

In your `android/app/src/main/AndroidManifest.xml`:

- **Audio Recording** (`disableAudio: false`):
  ```xml
  <uses-permission android:name="android.permission.RECORD_AUDIO" />
  ```

- **Saving to Gallery** (`saveToGallery: true`):
  ```xml
  <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
  ```

- **Location in EXIF Data** (`withExifLocation: true`):
  ```xml
  <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
  <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
  ```

### iOS

In your `ios/App/App/Info.plist`, you must provide descriptions for the permissions your app requires. The keys are added automatically, but you need to provide the `string` values.

- **Audio Recording** (`disableAudio: false`):
  ```xml
  <key>NSMicrophoneUsageDescription</key>
  <string>To record audio with videos</string>
  ```

- **Saving to Gallery** (`saveToGallery: true`):
  ```xml
  <key>NSPhotoLibraryUsageDescription</key>
  <string>To save photos to your gallery</string>
  ```

- **Location in EXIF Data** (`withExifLocation: true`):
  ```xml
  <key>NSLocationWhenInUseUsageDescription</key>
  <string>To add location data to your photos</string>
  ```

## Extra Android installation steps

**Important** `camera-preview` 3+ requires Gradle 7.
Open `android/app/src/main/AndroidManifest.xml` and above the closing `</manifest>` tag add this line to request the CAMERA permission:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

```

For more help consult the [Capacitor docs](https://capacitorjs.com/docs/android/configuration#configuring-androidmanifestxml).

## Extra iOS installation steps

You will need to add two permissions to `Info.plist`. Follow the [Capacitor docs](https://capacitorjs.com/docs/ios/configuration#configuring-infoplist) and add permissions with the raw keys `NSCameraUsageDescription` and `NSMicrophoneUsageDescription`. `NSMicrophoneUsageDescription` is only required, if audio will be used. Otherwise set the `disableAudio` option to `true`, which also disables the microphone permission request.

## Extra Web installation steps

Add `import '@capgo/camera-preview'` to you entry script in ionic on `app.module.ts`, so capacitor can register the web platform from the plugin

### Exemple with Capacitor uploader:

Documentation for the [uploader](https://github.com/Cap-go/capacitor-uploader)

```typescript
  import { CameraPreview } from '@capgo/camera-preview'
  import { Uploader } from '@capgo/capacitor-uploader';


  async function record() {
    await CameraPreview.startRecordVideo({ storeToFile: true })
    await new Promise(resolve => setTimeout(resolve, 5000))
    const fileUrl = await CameraPreview.stopRecordVideo()
    console.log(fileUrl.videoFilePath)
    await uploadVideo(fileUrl.videoFilePath)
  }

  async function uploadVideo(filePath: string) {
    Uploader.addListener('events', (event) => {
      switch (event.name) {
        case 'uploading':
          console.log(`Upload progress: ${event.payload.percent}%`);
          break;
        case 'completed':
          console.log('Upload completed successfully');
          console.log('Server response status code:', event.payload.statusCode);
          break;
        case 'failed':
          console.error('Upload failed:', event.payload.error);
          break;
      }
    });
    try {
      const result = await Uploader.startUpload({
        filePath,
        serverUrl: 'S#_PRESIGNED_URL',
        method: 'PUT',
        headers: {
          'Content-Type': 'video/mp4',
        },
        mimeType: 'video/mp4',
      });
      console.log('Video uploaded successfully:', result.id);
    } catch (error) {
      console.error('Error uploading video:', error);
      throw error;
    }
  }
```

### API

<docgen-index>

* [`start(...)`](#start)
* [`stop()`](#stop)
* [`capture(...)`](#capture)
* [`captureSample(...)`](#capturesample)
* [`getSupportedFlashModes()`](#getsupportedflashmodes)
* [`setAspectRatio(...)`](#setaspectratio)
* [`getAspectRatio()`](#getaspectratio)
* [`setGridMode(...)`](#setgridmode)
* [`getGridMode()`](#getgridmode)
* [`getHorizontalFov()`](#gethorizontalfov)
* [`getSupportedPictureSizes()`](#getsupportedpicturesizes)
* [`setFlashMode(...)`](#setflashmode)
* [`flip()`](#flip)
* [`setOpacity(...)`](#setopacity)
* [`stopRecordVideo()`](#stoprecordvideo)
* [`startRecordVideo(...)`](#startrecordvideo)
* [`isRunning()`](#isrunning)
* [`getAvailableDevices()`](#getavailabledevices)
* [`getZoom()`](#getzoom)
* [`getZoomButtonValues()`](#getzoombuttonvalues)
* [`setZoom(...)`](#setzoom)
* [`getFlashMode()`](#getflashmode)
* [`removeAllListeners()`](#removealllisteners)
* [`setDeviceId(...)`](#setdeviceid)
* [`getDeviceId()`](#getdeviceid)
* [`getPreviewSize()`](#getpreviewsize)
* [`setPreviewSize(...)`](#setpreviewsize)
* [`setFocus(...)`](#setfocus)
* [`addListener('screenResize', ...)`](#addlistenerscreenresize-)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)
* [Enums](#enums)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

The main interface for the CameraPreview plugin.

### start(...)

```typescript
start(options: CameraPreviewOptions) => Promise<{ width: number; height: number; x: number; y: number; }>
```

Starts the camera preview.

| Param         | Type                                                                  | Description                                 |
| ------------- | --------------------------------------------------------------------- | ------------------------------------------- |
| **`options`** | <code><a href="#camerapreviewoptions">CameraPreviewOptions</a></code> | - The configuration for the camera preview. |

**Returns:** <code>Promise&lt;{ width: number; height: number; x: number; y: number; }&gt;</code>

**Since:** 0.0.1

--------------------


### stop()

```typescript
stop() => Promise<void>
```

Stops the camera preview.

**Since:** 0.0.1

--------------------


### capture(...)

```typescript
capture(options: CameraPreviewPictureOptions) => Promise<{ value: string; exif: ExifData; }>
```

Captures a picture from the camera.

| Param         | Type                                                                                | Description                              |
| ------------- | ----------------------------------------------------------------------------------- | ---------------------------------------- |
| **`options`** | <code><a href="#camerapreviewpictureoptions">CameraPreviewPictureOptions</a></code> | - The options for capturing the picture. |

**Returns:** <code>Promise&lt;{ value: string; exif: <a href="#exifdata">ExifData</a>; }&gt;</code>

**Since:** 0.0.1

--------------------


### captureSample(...)

```typescript
captureSample(options: CameraSampleOptions) => Promise<{ value: string; }>
```

Captures a single frame from the camera preview stream.

| Param         | Type                                                                | Description                             |
| ------------- | ------------------------------------------------------------------- | --------------------------------------- |
| **`options`** | <code><a href="#camerasampleoptions">CameraSampleOptions</a></code> | - The options for capturing the sample. |

**Returns:** <code>Promise&lt;{ value: string; }&gt;</code>

**Since:** 0.0.1

--------------------


### getSupportedFlashModes()

```typescript
getSupportedFlashModes() => Promise<{ result: CameraPreviewFlashMode[]; }>
```

Gets the flash modes supported by the active camera.

**Returns:** <code>Promise&lt;{ result: CameraPreviewFlashMode[]; }&gt;</code>

**Since:** 0.0.1

--------------------


### setAspectRatio(...)

```typescript
setAspectRatio(options: { aspectRatio: "4:3" | "16:9"; x?: number; y?: number; }) => Promise<{ width: number; height: number; x: number; y: number; }>
```

Set the aspect ratio of the camera preview.

| Param         | Type                                                                   | Description                                                                                                                                                                                                                                                                                                           |
| ------------- | ---------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`options`** | <code>{ aspectRatio: '4:3' \| '16:9'; x?: number; y?: number; }</code> | - The desired aspect ratio and optional position. - aspectRatio: The desired aspect ratio ('4:3' or '16:9') - x: Optional x coordinate for positioning. If not provided, view will be auto-centered horizontally. - y: Optional y coordinate for positioning. If not provided, view will be auto-centered vertically. |

**Returns:** <code>Promise&lt;{ width: number; height: number; x: number; y: number; }&gt;</code>

**Since:** 7.4.0

--------------------


### getAspectRatio()

```typescript
getAspectRatio() => Promise<{ aspectRatio: "4:3" | "16:9"; }>
```

Gets the current aspect ratio of the camera preview.

**Returns:** <code>Promise&lt;{ aspectRatio: '4:3' | '16:9'; }&gt;</code>

**Since:** 7.4.0

--------------------


### setGridMode(...)

```typescript
setGridMode(options: { gridMode: GridMode; }) => Promise<void>
```

Sets the grid mode of the camera preview overlay.

| Param         | Type                                                         | Description                                        |
| ------------- | ------------------------------------------------------------ | -------------------------------------------------- |
| **`options`** | <code>{ gridMode: <a href="#gridmode">GridMode</a>; }</code> | - The desired grid mode ('none', '3x3', or '4x4'). |

**Since:** 8.0.0

--------------------


### getGridMode()

```typescript
getGridMode() => Promise<{ gridMode: GridMode; }>
```

Gets the current grid mode of the camera preview overlay.

**Returns:** <code>Promise&lt;{ gridMode: <a href="#gridmode">GridMode</a>; }&gt;</code>

**Since:** 8.0.0

--------------------


### getHorizontalFov()

```typescript
getHorizontalFov() => Promise<{ result: number; }>
```

Gets the horizontal field of view (FoV) for the active camera.
Note: This can be an estimate on some devices.

**Returns:** <code>Promise&lt;{ result: number; }&gt;</code>

**Since:** 0.0.1

--------------------


### getSupportedPictureSizes()

```typescript
getSupportedPictureSizes() => Promise<{ supportedPictureSizes: SupportedPictureSizes[]; }>
```

Gets the supported picture sizes for all cameras.

**Returns:** <code>Promise&lt;{ supportedPictureSizes: SupportedPictureSizes[]; }&gt;</code>

**Since:** 7.4.0

--------------------


### setFlashMode(...)

```typescript
setFlashMode(options: { flashMode: CameraPreviewFlashMode | string; }) => Promise<void>
```

Sets the flash mode for the active camera.

| Param         | Type                                | Description               |
| ------------- | ----------------------------------- | ------------------------- |
| **`options`** | <code>{ flashMode: string; }</code> | - The desired flash mode. |

**Since:** 0.0.1

--------------------


### flip()

```typescript
flip() => Promise<void>
```

Toggles between the front and rear cameras.

**Since:** 0.0.1

--------------------


### setOpacity(...)

```typescript
setOpacity(options: CameraOpacityOptions) => Promise<void>
```

Sets the opacity of the camera preview.

| Param         | Type                                                                  | Description            |
| ------------- | --------------------------------------------------------------------- | ---------------------- |
| **`options`** | <code><a href="#cameraopacityoptions">CameraOpacityOptions</a></code> | - The opacity options. |

**Since:** 0.0.1

--------------------


### stopRecordVideo()

```typescript
stopRecordVideo() => Promise<{ videoFilePath: string; }>
```

Stops an ongoing video recording.

**Returns:** <code>Promise&lt;{ videoFilePath: string; }&gt;</code>

**Since:** 0.0.1

--------------------


### startRecordVideo(...)

```typescript
startRecordVideo(options: CameraPreviewOptions) => Promise<void>
```

Starts recording a video.

| Param         | Type                                                                  | Description                        |
| ------------- | --------------------------------------------------------------------- | ---------------------------------- |
| **`options`** | <code><a href="#camerapreviewoptions">CameraPreviewOptions</a></code> | - The options for video recording. |

**Since:** 0.0.1

--------------------


### isRunning()

```typescript
isRunning() => Promise<{ isRunning: boolean; }>
```

Checks if the camera preview is currently running.

**Returns:** <code>Promise&lt;{ isRunning: boolean; }&gt;</code>

**Since:** 7.4.0

--------------------


### getAvailableDevices()

```typescript
getAvailableDevices() => Promise<{ devices: CameraDevice[]; }>
```

Gets all available camera devices.

**Returns:** <code>Promise&lt;{ devices: CameraDevice[]; }&gt;</code>

**Since:** 7.4.0

--------------------


### getZoom()

```typescript
getZoom() => Promise<{ min: number; max: number; current: number; lens: LensInfo; }>
```

Gets the current zoom state, including min/max and current lens info.

**Returns:** <code>Promise&lt;{ min: number; max: number; current: number; lens: <a href="#lensinfo">LensInfo</a>; }&gt;</code>

**Since:** 7.4.0

--------------------


### getZoomButtonValues()

```typescript
getZoomButtonValues() => Promise<{ values: number[]; }>
```

Returns zoom button values for quick switching.
- iOS: includes 0.5 if ultra-wide available; 1 and 2 if wide available; 3 if telephoto available
- Android: always includes 1 and 2; includes min zoom (often 0.5) if ultra-wide detected
- Web: unsupported

**Returns:** <code>Promise&lt;{ values: number[]; }&gt;</code>

--------------------


### setZoom(...)

```typescript
setZoom(options: { level: number; ramp?: boolean; autoFocus?: boolean; }) => Promise<void>
```

Sets the zoom level of the camera.

| Param         | Type                                                                 | Description                                                                         |
| ------------- | -------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| **`options`** | <code>{ level: number; ramp?: boolean; autoFocus?: boolean; }</code> | - The desired zoom level. `ramp` is currently unused. `autoFocus` defaults to true. |

**Since:** 7.4.0

--------------------


### getFlashMode()

```typescript
getFlashMode() => Promise<{ flashMode: FlashMode; }>
```

Gets the current flash mode.

**Returns:** <code>Promise&lt;{ flashMode: <a href="#camerapreviewflashmode">CameraPreviewFlashMode</a>; }&gt;</code>

**Since:** 7.4.0

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

Removes all registered listeners.

**Since:** 7.4.0

--------------------


### setDeviceId(...)

```typescript
setDeviceId(options: { deviceId: string; }) => Promise<void>
```

Switches the active camera to the one with the specified `deviceId`.

| Param         | Type                               | Description                          |
| ------------- | ---------------------------------- | ------------------------------------ |
| **`options`** | <code>{ deviceId: string; }</code> | - The ID of the device to switch to. |

**Since:** 7.4.0

--------------------


### getDeviceId()

```typescript
getDeviceId() => Promise<{ deviceId: string; }>
```

Gets the ID of the currently active camera device.

**Returns:** <code>Promise&lt;{ deviceId: string; }&gt;</code>

**Since:** 7.4.0

--------------------


### getPreviewSize()

```typescript
getPreviewSize() => Promise<{ x: number; y: number; width: number; height: number; }>
```

Gets the current preview size and position.

**Returns:** <code>Promise&lt;{ x: number; y: number; width: number; height: number; }&gt;</code>

--------------------


### setPreviewSize(...)

```typescript
setPreviewSize(options: { x?: number; y?: number; width: number; height: number; }) => Promise<{ width: number; height: number; x: number; y: number; }>
```

Sets the preview size and position.

| Param         | Type                                                                    | Description                      |
| ------------- | ----------------------------------------------------------------------- | -------------------------------- |
| **`options`** | <code>{ x?: number; y?: number; width: number; height: number; }</code> | The new position and dimensions. |

**Returns:** <code>Promise&lt;{ width: number; height: number; x: number; y: number; }&gt;</code>

--------------------


### setFocus(...)

```typescript
setFocus(options: { x: number; y: number; }) => Promise<void>
```

Sets the camera focus to a specific point in the preview.

| Param         | Type                                   | Description          |
| ------------- | -------------------------------------- | -------------------- |
| **`options`** | <code>{ x: number; y: number; }</code> | - The focus options. |

**Since:** 8.1.0

--------------------


### addListener('screenResize', ...)

```typescript
addListener(eventName: "screenResize", listenerFunc: (data: { width: number; height: number; x: number; y: number; }) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                     |
| ------------------ | ---------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'screenResize'</code>                                                              |
| **`listenerFunc`** | <code>(data: { width: number; height: number; x: number; y: number; }) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### Interfaces


#### CameraPreviewOptions

Defines the configuration options for starting the camera preview.

| Prop                               | Type                                                            | Description                                                                                                                                                                                             | Default               | Since |
| ---------------------------------- | --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------- | ----- |
| **`parent`**                       | <code>string</code>                                             | The parent element to attach the video preview to.                                                                                                                                                      |                       |       |
| **`className`**                    | <code>string</code>                                             | A CSS class name to add to the preview element.                                                                                                                                                         |                       |       |
| **`width`**                        | <code>number</code>                                             | The width of the preview in pixels. Defaults to the screen width.                                                                                                                                       |                       |       |
| **`height`**                       | <code>number</code>                                             | The height of the preview in pixels. Defaults to the screen height.                                                                                                                                     |                       |       |
| **`x`**                            | <code>number</code>                                             | The horizontal origin of the preview, in pixels.                                                                                                                                                        |                       |       |
| **`y`**                            | <code>number</code>                                             | The vertical origin of the preview, in pixels.                                                                                                                                                          |                       |       |
| **`aspectRatio`**                  | <code>'4:3' \| '16:9'</code>                                    | The aspect ratio of the camera preview, '4:3' or '16:9' or 'fill'. Cannot be set if width or height is provided, otherwise the call will be rejected. Use setPreviewSize to adjust size after starting. |                       | 2.0.0 |
| **`gridMode`**                     | <code><a href="#gridmode">GridMode</a></code>                   | The grid overlay to display on the camera preview.                                                                                                                                                      | <code>"none"</code>   | 2.1.0 |
| **`includeSafeAreaInsets`**        | <code>boolean</code>                                            | Adjusts the y-position to account for safe areas (e.g., notches).                                                                                                                                       | <code>false</code>    |       |
| **`toBack`**                       | <code>boolean</code>                                            | If true, places the preview behind the webview.                                                                                                                                                         | <code>true</code>     |       |
| **`paddingBottom`**                | <code>number</code>                                             | Bottom padding for the preview, in pixels.                                                                                                                                                              |                       |       |
| **`rotateWhenOrientationChanged`** | <code>boolean</code>                                            | Whether to rotate the preview when the device orientation changes.                                                                                                                                      | <code>true</code>     |       |
| **`position`**                     | <code>string</code>                                             | The camera to use.                                                                                                                                                                                      | <code>"rear"</code>   |       |
| **`storeToFile`**                  | <code>boolean</code>                                            | If true, saves the captured image to a file and returns the file path. If false, returns a base64 encoded string.                                                                                       | <code>false</code>    |       |
| **`disableExifHeaderStripping`**   | <code>boolean</code>                                            | If true, prevents the plugin from rotating the image based on EXIF data.                                                                                                                                | <code>false</code>    |       |
| **`disableAudio`**                 | <code>boolean</code>                                            | If true, disables the audio stream, preventing audio permission requests.                                                                                                                               | <code>true</code>     |       |
| **`lockAndroidOrientation`**       | <code>boolean</code>                                            | If true, locks the device orientation while the camera is active.                                                                                                                                       | <code>false</code>    |       |
| **`enableOpacity`**                | <code>boolean</code>                                            | If true, allows the camera preview's opacity to be changed.                                                                                                                                             | <code>false</code>    |       |
| **`enableZoom`**                   | <code>boolean</code>                                            | If true, enables pinch-to-zoom functionality on the preview.                                                                                                                                            | <code>false</code>    |       |
| **`enableVideoMode`**              | <code>boolean</code>                                            | If true, uses the video-optimized preset for the camera session.                                                                                                                                        | <code>false</code>    |       |
| **`deviceId`**                     | <code>string</code>                                             | The `deviceId` of the camera to use. If provided, `position` is ignored.                                                                                                                                |                       |       |
| **`initialZoomLevel`**             | <code>number</code>                                             | The initial zoom level when starting the camera preview. If the requested zoom level is not available, the native plugin will reject.                                                                   | <code>1.0</code>      | 2.2.0 |
| **`positioning`**                  | <code><a href="#camerapositioning">CameraPositioning</a></code> | The vertical positioning of the camera preview.                                                                                                                                                         | <code>"center"</code> | 2.3.0 |


#### ExifData

Represents EXIF data extracted from an image.


#### CameraPreviewPictureOptions

Defines the options for capturing a picture.

| Prop                   | Type                                                    | Description                                                                                                                                | Default             | Since |
| ---------------------- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | ------------------- | ----- |
| **`height`**           | <code>number</code>                                     |                                                                                                                                            |                     |       |
| **`width`**            | <code>number</code>                                     |                                                                                                                                            |                     |       |
| **`aspectRatio`**      | <code>string</code>                                     |                                                                                                                                            |                     | 7.7.0 |
| **`quality`**          | <code>number</code>                                     | The quality of the captured image, from 0 to 100. Does not apply to `png` format.                                                          | <code>85</code>     |       |
| **`format`**           | <code><a href="#pictureformat">PictureFormat</a></code> | The format of the captured image.                                                                                                          | <code>"jpeg"</code> |       |
| **`saveToGallery`**    | <code>boolean</code>                                    | If true, the captured image will be saved to the user's gallery.                                                                           | <code>false</code>  | 7.5.0 |
| **`withExifLocation`** | <code>boolean</code>                                    | If true, the plugin will attempt to add GPS location data to the image's EXIF metadata. This may prompt the user for location permissions. | <code>false</code>  | 7.6.0 |


#### CameraSampleOptions

Defines the options for capturing a sample frame from the camera preview.

| Prop          | Type                | Description                                        | Default         |
| ------------- | ------------------- | -------------------------------------------------- | --------------- |
| **`quality`** | <code>number</code> | The quality of the captured sample, from 0 to 100. | <code>85</code> |


#### SupportedPictureSizes

Represents the supported picture sizes for a camera facing a certain direction.

| Prop                        | Type                       | Description                                        |
| --------------------------- | -------------------------- | -------------------------------------------------- |
| **`facing`**                | <code>string</code>        | The camera direction ("front" or "rear").          |
| **`supportedPictureSizes`** | <code>PictureSize[]</code> | A list of supported picture sizes for this camera. |


#### PictureSize

Defines a standard picture size with width and height.

| Prop         | Type                | Description                          |
| ------------ | ------------------- | ------------------------------------ |
| **`width`**  | <code>number</code> | The width of the picture in pixels.  |
| **`height`** | <code>number</code> | The height of the picture in pixels. |


#### CameraOpacityOptions

Defines the options for setting the camera preview's opacity.

| Prop          | Type                | Description                                                                 | Default          |
| ------------- | ------------------- | --------------------------------------------------------------------------- | ---------------- |
| **`opacity`** | <code>number</code> | The opacity percentage, from 0.0 (fully transparent) to 1.0 (fully opaque). | <code>1.0</code> |


#### CameraDevice

Represents a physical camera on the device (e.g., the front-facing camera).

| Prop            | Type                                                      | Description                                                                               |
| --------------- | --------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| **`deviceId`**  | <code>string</code>                                       | A unique identifier for the camera device.                                                |
| **`label`**     | <code>string</code>                                       | A human-readable name for the camera device.                                              |
| **`position`**  | <code><a href="#cameraposition">CameraPosition</a></code> | The physical position of the camera on the device.                                        |
| **`lenses`**    | <code>CameraLens[]</code>                                 | A list of all available lenses for this camera device.                                    |
| **`minZoom`**   | <code>number</code>                                       | The overall minimum zoom factor available across all lenses on this device.               |
| **`maxZoom`**   | <code>number</code>                                       | The overall maximum zoom factor available across all lenses on this device.               |
| **`isLogical`** | <code>boolean</code>                                      | Identifies whether the device is a logical camera (composed of multiple physical lenses). |


#### CameraLens

Represents a single camera lens on a device. A {@link <a href="#cameradevice">CameraDevice</a>} can have multiple lenses.

| Prop                | Type                                              | Description                                                                  |
| ------------------- | ------------------------------------------------- | ---------------------------------------------------------------------------- |
| **`label`**         | <code>string</code>                               | A human-readable name for the lens, e.g., "Ultra-Wide".                      |
| **`deviceType`**    | <code><a href="#devicetype">DeviceType</a></code> | The type of the camera lens.                                                 |
| **`focalLength`**   | <code>number</code>                               | The focal length of the lens in millimeters.                                 |
| **`baseZoomRatio`** | <code>number</code>                               | The base zoom factor for this lens (e.g., 0.5 for ultra-wide, 1.0 for wide). |
| **`minZoom`**       | <code>number</code>                               | The minimum zoom factor supported by this specific lens.                     |
| **`maxZoom`**       | <code>number</code>                               | The maximum zoom factor supported by this specific lens.                     |


#### LensInfo

Represents the detailed information of the currently active lens.

| Prop                | Type                                              | Description                                                      |
| ------------------- | ------------------------------------------------- | ---------------------------------------------------------------- |
| **`focalLength`**   | <code>number</code>                               | The focal length of the active lens in millimeters.              |
| **`deviceType`**    | <code><a href="#devicetype">DeviceType</a></code> | The device type of the active lens.                              |
| **`baseZoomRatio`** | <code>number</code>                               | The base zoom ratio of the active lens (e.g., 0.5x, 1.0x).       |
| **`digitalZoom`**   | <code>number</code>                               | The current digital zoom factor applied on top of the base zoom. |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


### Type Aliases


#### GridMode

<code>"none" | "3x3" | "4x4"</code>


#### CameraPosition

<code>"rear" | "front"</code>


#### CameraPositioning

<code>"center" | "top" | "bottom"</code>


#### PictureFormat

<code>"jpeg" | "png"</code>


#### CameraPreviewFlashMode

The available flash modes for the camera.
'torch' is a continuous light mode.

<code>"off" | "on" | "auto" | "torch"</code>


#### FlashMode

<code><a href="#camerapreviewflashmode">CameraPreviewFlashMode</a></code>


### Enums


#### DeviceType

| Members          | Value                    |
| ---------------- | ------------------------ |
| **`ULTRA_WIDE`** | <code>"ultraWide"</code> |
| **`WIDE_ANGLE`** | <code>"wideAngle"</code> |
| **`TELEPHOTO`**  | <code>"telephoto"</code> |
| **`TRUE_DEPTH`** | <code>"trueDepth"</code> |
| **`DUAL`**       | <code>"dual"</code>      |
| **`DUAL_WIDE`**  | <code>"dualWide"</code>  |
| **`TRIPLE`**     | <code>"triple"</code>    |

</docgen-api>
