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
  IonButton,
  IonButtons,
  // IonChip,
  IonFab,
  IonFabButton,
  IonHeader,
  IonIcon,
  IonItem,
  IonLabel,
  IonList,
  IonSelect,
  IonSelectOption,
  IonSpinner,
  IonTitle,
  IonToolbar,
  ModalController,
} from '@ionic/angular/standalone';
import { type CameraDevice, type CameraPosition, type FlashMode } from '@capgo/camera-preview';
// import { concat, map, of, switchMap, tap, timer } from 'rxjs';
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
    IonButton,
    IonButtons,
    IonFab,
    IonFabButton,
    IonHeader,
    IonIcon,
    IonItem,
    IonLabel,
    IonList,
    IonSelect,
    IonSelectOption,
    IonSpinner,
    IonTitle,
    IonToolbar,
    // IonChip,
  ],
  host: {
    class: 'camera-modal',
  },
})
export class CameraModalComponent implements OnInit, OnDestroy {
  readonly #cameraViewService = inject(CapacitorCameraViewService);
  readonly #elementRef = inject(ElementRef);
  readonly #modalController = inject(ModalController);

  protected barcodeRect =
    viewChild.required<ElementRef<HTMLDivElement>>('barcodeRect');

  public readonly deviceId = input<string>();
  public readonly enableBarcodeDetection = input<boolean>(false);
  public readonly position = input<CameraPosition>('rear');
  public readonly quality = input<number>(85);
  public readonly useTripleCameraIfAvailable = input<boolean>(false);
  public readonly initialZoomFactor = input<number>(1.0);

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

  protected readonly canZoomIn = computed(() => {
    return this.currentZoomFactor() + 0.5 <= this.maxZoom();
  });

  protected readonly canZoomOut = computed(() => {
    return this.currentZoomFactor() - 0.5 >= this.minZoom();
  });

  // protected readonly detectedBarcode = toSignal(
  //   this.#cameraViewService.barcodeData.pipe(
  //     tap((value) => console.log('Barcode detected:', value)),
  //     switchMap((value) =>
  //       concat(of(value), timer(1000).pipe(map(() => undefined))),
  //     ),
  //   ),
  //   { initialValue: undefined },
  // );

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

    // effect(() => {
      // const barcodeData = this.detectedBarcode();
      // const element = this.barcodeRect().nativeElement;

      // if (barcodeData) {
      //   const boundingRect = barcodeData.boundingRect;

      //   element.style.visibility = 'visible';
      //   element.style.opacity = '1';
      //   element.style.left = `${boundingRect.x - 5}px`;
      //   element.style.top = `${boundingRect.y - 5}px`;
      //   element.style.width = `${boundingRect.width + 10}px`;
      //   element.style.height = `${boundingRect.height + 10}px`;
      // } else {
        // element.style.opacity = '0';
        // element.style.width = `0`;
        // element.style.height = `0`;
        // element.style.visibility = 'hidden';
      // }
    // });
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
    await this.#cameraViewService.start({
      deviceId: this.deviceId(),
      position: this.position(),
    });

    await Promise.all([
      this.#initializeZoomLimits(),
      this.#initializeFlashModes(),
      this.#initializeDevices(),
      this.#updateRunningStatus(),
      this.#updateCurrentDeviceId(),
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
      const photo = await this.#cameraViewService.capture(this.quality());
      this.#modalController.dismiss({ photo });
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
      const photo = await this.#cameraViewService.captureSample();
      this.#modalController.dismiss({ photo });
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
    console.log('=== Testing All Camera Features ===');
    
    // Test running status
    const running = await this.#cameraViewService.isRunning();
    console.log('Camera running:', running);
    
    // Test available devices
    const devices = await this.#cameraViewService.getAvailableDevices();
    console.log('Available devices:', devices);
    
    // Test current device ID
    const currentId = await this.#cameraViewService.getDeviceId();
    console.log('Current device ID:', currentId);
    
    // Test zoom capabilities
    const zoom = await this.#cameraViewService.getZoom();
    console.log('Zoom capabilities:', zoom);
    
    // Test flash mode
    const flashMode = await this.#cameraViewService.getFlashMode();
    console.log('Current flash mode:', flashMode);
    
    // Test supported flash modes
    const supportedFlash = await this.#cameraViewService.getSupportedFlashModes();
    console.log('Supported flash modes:', supportedFlash);
  }

  // protected async readBarcode(): Promise<void> {
  //   await this.stopCamera();
  //   await this.#modalController.dismiss({ barcode: this.detectedBarcode() });
  // }

  async #setZoom(zoomFactor: number): Promise<void> {
    this.currentZoomFactor.set(zoomFactor);
    await this.#cameraViewService.setZoom(zoomFactor, false);
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
      this.#handleTouchStart,
    );
    this.#elementRef.nativeElement.addEventListener(
      'touchmove',
      this.#handleTouchMove,
    );
  }

  #destroyEventListeners(): void {
    this.#elementRef.nativeElement.removeEventListener(
      'touchstart',
      this.#handleTouchStart,
    );
    this.#elementRef.nativeElement.removeEventListener(
      'touchmove',
      this.#handleTouchMove,
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
