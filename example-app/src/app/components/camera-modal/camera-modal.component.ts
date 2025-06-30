import {
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  input,
  OnDestroy,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Capacitor } from '@capacitor/core';
import {
  IonCard,
  IonCardContent,
  IonCardHeader,
  IonCardTitle,
  IonFab,
  IonFabButton,
  IonIcon,
  IonSelect,
  IonSelectOption,
  IonSpinner,
  ModalController,
} from '@ionic/angular/standalone';
import { type CameraDevice, type CameraPosition, type FlashMode, type PictureFormat } from '@capgo/camera-preview';
import { CapacitorCameraViewService } from '../../core/capacitor-camera-preview.service';

function getDistance(touch1: Touch, touch2: Touch): number {
  const dx = touch1.clientX - touch2.clientX;
  const dy = touch1.clientY - touch2.clientY;
  return Math.sqrt(dx * dx + dy * dy);
}

@Component({
  selector: 'app-camera-modal',
  templateUrl: './camera-modal.component.html',
  styleUrls: ['./camera-modal.component.scss'],
  imports: [
    IonCard,
    IonCardContent,
    IonCardHeader,
    IonCardTitle,
    IonFab,
    IonFabButton,
    IonIcon,
    IonSelect,
    IonSelectOption,
    IonSpinner,
  ],
  host: {
    class: 'camera-modal',
  },
})
export class CameraModalComponent implements OnInit, OnDestroy {
  readonly #cameraViewService = inject(CapacitorCameraViewService);
  readonly #elementRef = inject(ElementRef);
  readonly #modalController = inject(ModalController);

  // Basic camera inputs
  public readonly deviceId = input<string>();
  public readonly position = input<CameraPosition>('rear');
  public readonly quality = input<number>(85);
  public readonly useTripleCameraIfAvailable = input<boolean>(false);
  public readonly initialZoomFactor = input<number>(1.0);

  // Picture settings inputs
  public readonly pictureFormat = input<PictureFormat>('jpeg');
  public readonly pictureQuality = input<number>(85);
  public readonly useCustomSize = input<boolean>(false);
  public readonly pictureWidth = input<number>(1920);
  public readonly pictureHeight = input<number>(1080);

  // Camera behavior inputs
  public readonly opacity = input<number>(100);
  public readonly enableZoom = input<boolean>(false);
  public readonly disableAudio = input<boolean>(false);
  public readonly enableHighResolution = input<boolean>(false);
  public readonly lockAndroidOrientation = input<boolean>(false);

  protected readonly cameraStarted = toSignal(
    this.#cameraViewService.cameraStarted,
    {
      requireSync: true,
    },
  );

  protected readonly flashMode = signal<FlashMode>('auto');
  protected readonly isCapturingPhoto = signal(false);
  protected readonly currentZoomFactor = signal(1.0);
  protected readonly minZoom = signal(1.0);
  protected readonly maxZoom = signal(10.0);
  protected readonly availableDevices = signal<CameraDevice[]>([]);
  protected readonly currentDeviceId = signal<string>('');
  protected readonly isRunning = signal(false);

  // Video recording and testing state
  protected readonly isRecording = signal(false);
  protected readonly currentOpacity = signal(100);
  protected readonly testResults = signal<string>('');

  protected readonly canZoomIn = computed(() => {
    return this.currentZoomFactor() + 0.5 <= this.maxZoom();
  });

  protected readonly canZoomOut = computed(() => {
    return this.currentZoomFactor() - 0.5 >= this.minZoom();
  });

  protected readonly isWeb = Capacitor.getPlatform() === 'web';

  #supportedFlashModes = signal<Array<FlashMode>>(['off']);
  #touchStartDistance = 0;
  #initialZoomFactorOnPinch = 1.0;

  constructor() {
    effect(() => {
      const flashModes = this.#supportedFlashModes();
      if (!flashModes.includes(this.flashMode())) {
        this.flashMode.set((flashModes[0] as FlashMode) ?? 'off');
      }
    });
  }

  public ngOnInit() {
    this.startCamera().catch((error) => {
      console.error('Failed to start camera', error);
      this.#modalController.dismiss();
    });

    this.#initializeEventListeners();
  }

  public ngOnDestroy(): void {
    this.#destroyEventListeners();
  }

  protected async startCamera(): Promise<void> {
    const startOptions = {
      deviceId: this.deviceId(),
      position: this.position(),
      enableZoom: this.enableZoom(),
      disableAudio: this.disableAudio(),
      enableHighResolution: this.enableHighResolution(),
      lockAndroidOrientation: this.lockAndroidOrientation(),
    };

    await this.#cameraViewService.start(startOptions);

    await Promise.all([
      this.#initializeZoomLimits(),
      this.#initializeFlashModes(),
      this.#initializeDevices(),
      this.#updateRunningStatus(),
      this.#updateCurrentDeviceId(),
      this.#setInitialOpacity(),
    ]);

    this.currentZoomFactor.set(this.initialZoomFactor());
  }

  protected async stopCamera(): Promise<void> {
    try {
      await this.#cameraViewService.stop();
    } catch (error) {
      console.error('Failed to stop camera', error);
    }
  }

  protected async close(): Promise<void> {
    this.stopCamera().catch((error) =>
      console.error('Failed to stop camera', error),
    );
    await this.#modalController.dismiss();
  }

  protected async capturePhoto(): Promise<void> {
    this.isCapturingPhoto.set(true);
    try {
      const captureOptions = {
        quality: this.pictureQuality(),
        format: this.pictureFormat(),
        ...(this.useCustomSize() && {
          width: this.pictureWidth(),
          height: this.pictureHeight(),
        }),
      };

      const photo = await this.#cameraViewService.capture(this.quality(), captureOptions);
      this.#modalController.dismiss({ 
        photo, 
        options: captureOptions,
        type: 'standard'
      });
    } catch (error) {
      console.error('Failed to capture photo', error);
      this.#modalController.dismiss();
    }

    this.stopCamera().catch((error) => {
      console.error('Failed to stop camera', error);
    });

    this.isCapturingPhoto.set(false);
  }

  protected async captureSample(): Promise<void> {
    this.isCapturingPhoto.set(true);
    try {
      const photo = await this.#cameraViewService.captureSample(this.pictureQuality());
      this.#modalController.dismiss({ 
        photo,
        type: 'sample'
      });
    } catch (error) {
      console.error('Failed to capture sample', error);
      this.#modalController.dismiss();
    }

    this.stopCamera().catch((error) => {
      console.error('Failed to stop camera', error);
    });

    this.isCapturingPhoto.set(false);
  }

  protected async flipCamera(): Promise<void> {
    await this.#cameraViewService.flipCamera();
    await this.#initializeZoomLimits();
  }

  protected async nextFlashMode(): Promise<void> {
    const supportedModes = this.#supportedFlashModes();
    if (supportedModes.length <= 1) return;

    const currentMode = this.flashMode();
    const currentIndex = supportedModes.indexOf(currentMode);
    const nextIndex = (currentIndex + 1) % supportedModes.length;
    const nextFlashMode = supportedModes[nextIndex] as FlashMode;

    this.flashMode.set(nextFlashMode);
    await this.#cameraViewService.setFlashMode(nextFlashMode);
  }

  protected async zoomIn(): Promise<void> {
    if (this.canZoomIn()) {
      this.currentZoomFactor.update((curr) => curr + 0.5);
      await this.#cameraViewService.setZoom(this.currentZoomFactor(), true);
    }
  }

  protected async zoomOut(): Promise<void> {
    if (this.canZoomOut()) {
      this.currentZoomFactor.update((curr) => curr - 0.5);
      await this.#cameraViewService.setZoom(this.currentZoomFactor(), true);
    }
  }

  protected async switchToDevice(deviceId: string): Promise<void> {
    try {
      await this.#cameraViewService.setDeviceId(deviceId);
      await this.#updateCurrentDeviceId();
      await this.#initializeZoomLimits();
      await this.#initializeFlashModes();
    } catch (error) {
      console.error('Failed to switch device', error);
    }
  }

  protected async refreshDeviceInfo(): Promise<void> {
    await Promise.all([
      this.#initializeDevices(),
      this.#updateRunningStatus(),
      this.#updateCurrentDeviceId(),
    ]);
  }

  protected async testAllFeatures(): Promise<void> {
    let results = '=== Camera Modal Test Results ===\n';
    
    try {
      // Test running status
      const running = await this.#cameraViewService.isRunning();
      results += `\n✓ Camera running: ${running}`;
      
      // Test current device ID
      const currentId = await this.#cameraViewService.getDeviceId();
      results += `\n✓ Current device: ${currentId}`;
      
      // Test zoom capabilities
      const zoom = await this.#cameraViewService.getZoom();
      results += `\n✓ Zoom: ${zoom.min} - ${zoom.max} (current: ${zoom.current})`;
      
      // Test flash mode
      const flashMode = await this.#cameraViewService.getFlashMode();
      results += `\n✓ Flash mode: ${flashMode}`;

      this.testResults.set(results);
    } catch (error) {
      results += `\n✗ Error during testing: ${error}`;
      this.testResults.set(results);
    }
  }

  protected async startRecording(): Promise<void> {
    try {
      await this.#cameraViewService.startRecordVideo({
        position: this.position(),
        deviceId: this.currentDeviceId(),
        disableAudio: this.disableAudio(),
      });
      this.isRecording.set(true);
      const results = this.testResults() + `\n✓ Video recording started`;
      this.testResults.set(results);
    } catch (error) {
      const results = this.testResults() + `\n✗ Failed to start recording: ${error}`;
      this.testResults.set(results);
      console.error('Failed to start recording:', error);
    }
  }

  protected async stopRecording(): Promise<void> {
    try {
      const result = await this.#cameraViewService.stopRecordVideo();
      this.isRecording.set(false);
      this.#modalController.dismiss({ 
        video: result.videoFilePath,
        type: 'video'
      });
    } catch (error) {
      const results = this.testResults() + `\n✗ Failed to stop recording: ${error}`;
      this.testResults.set(results);
      console.error('Failed to stop recording:', error);
    }
  }

  async #setZoom(zoomFactor: number): Promise<void> {
    this.currentZoomFactor.set(zoomFactor);
    await this.#cameraViewService.setZoom(zoomFactor, false);
  }

  async #setInitialOpacity(): Promise<void> {
    try {
      this.currentOpacity.set(this.opacity());
      await this.#cameraViewService.setOpacity(this.opacity() / 100);
    } catch (error) {
      console.warn('Failed to set initial opacity', error);
    }
  }

  async #initializeZoomLimits(): Promise<void> {
    try {
      const zoomRange = await this.#cameraViewService.getZoom();
      if (zoomRange) {
        this.minZoom.set(zoomRange.min);
        this.maxZoom.set(zoomRange.max);
      }
    } catch (error) {
      console.warn('Failed to get zoom range, using default values.', error);
    }
  }

  async #initializeFlashModes(): Promise<void> {
    try {
      this.#supportedFlashModes.set(
        await this.#cameraViewService.getSupportedFlashModes(),
      );
    } catch (error) {
      console.warn('Failed to get supported flash modes', error);
    }
  }

  async #initializeDevices(): Promise<void> {
    try {
      const devices = await this.#cameraViewService.getAvailableDevices();
      this.availableDevices.set(devices);
    } catch (error) {
      console.warn('Failed to get available devices', error);
    }
  }

  async #updateRunningStatus(): Promise<void> {
    try {
      const running = await this.#cameraViewService.isRunning();
      this.isRunning.set(running);
    } catch (error) {
      console.warn('Failed to get running status', error);
    }
  }

  async #updateCurrentDeviceId(): Promise<void> {
    try {
      const deviceId = await this.#cameraViewService.getDeviceId();
      this.currentDeviceId.set(deviceId);
    } catch (error) {
      console.warn('Failed to get current device ID', error);
    }
  }

  #initializeEventListeners(): void {
    this.#elementRef.nativeElement.addEventListener(
      'touchstart',
      this.#handleTouchStart.bind(this),
    );
    this.#elementRef.nativeElement.addEventListener(
      'touchmove',
      this.#handleTouchMove.bind(this),
    );
  }

  #destroyEventListeners(): void {
    this.#elementRef.nativeElement.removeEventListener(
      'touchstart',
      this.#handleTouchStart.bind(this),
    );
    this.#elementRef.nativeElement.removeEventListener(
      'touchmove',
      this.#handleTouchMove.bind(this),
    );
  }

  #handleTouchStart(event: TouchEvent): void {
    if (event.touches.length < 2) return;

    this.#touchStartDistance = getDistance(event.touches[0], event.touches[1]);
    this.#initialZoomFactorOnPinch = this.currentZoomFactor();
  }

  #handleTouchMove(event: TouchEvent): void {
    if (event.touches.length < 2 || this.#touchStartDistance <= 0) return;

    const currentDistance = getDistance(event.touches[0], event.touches[1]);

    // Calculate new zoom factor
    const scale = currentDistance / this.#touchStartDistance;
    const newZoomFactor = Math.max(
      this.minZoom(),
      Math.min(this.maxZoom(), this.#initialZoomFactorOnPinch * scale),
    );

    this.#setZoom(newZoomFactor);
    event.preventDefault(); // Prevent scrolling
  }
}
