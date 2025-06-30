import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { 
  IonButton, 
  IonButtons, 
  IonCard, 
  IonCardContent, 
  IonCardHeader, 
  IonCardTitle, 
  IonChip,
  IonContent, 
  IonHeader, 
  IonItem, 
  IonLabel, 
  IonList, 
  IonSelect, 
  IonSelectOption, 
  IonTitle, 
  IonToolbar 
} from '@ionic/angular/standalone';
import { type CameraDevice, type FlashMode } from '@capgo/camera-preview';
import { CapacitorCameraViewService } from '../../core/capacitor-camera-preview.service';

@Component({
  selector: 'app-features-test',
  templateUrl: './features-test.page.html',
  styleUrls: ['./features-test.page.scss'],
  imports: [
    IonButton,
    IonButtons,
    IonCard,
    IonCardContent,
    IonCardHeader,
    IonCardTitle,
    IonChip,
    IonContent,
    IonHeader,
    IonItem,
    IonLabel,
    IonList,
    IonSelect,
    IonSelectOption,
    IonTitle,
    IonToolbar,
  ],
})
export class FeaturesTestPage implements OnInit, OnDestroy {
  protected readonly cameraStarted = signal(false);
  protected readonly isRunning = signal(false);
  protected readonly availableDevices = signal<CameraDevice[]>([]);
  protected readonly currentDeviceId = signal('');
  protected readonly currentFlashMode = signal<FlashMode>('off');
  protected readonly supportedFlashModes = signal<FlashMode[]>([]);
  protected readonly zoomCapabilities = signal<{ min: number; max: number; current: number }>({ min: 1, max: 1, current: 1 });
  protected readonly testResults = signal<string[]>([]);

  constructor(private cameraService: CapacitorCameraViewService) {}

  async ngOnInit() {
    await this.refreshAllData();
  }

  async ngOnDestroy() {
    if (this.cameraStarted()) {
      await this.stopCamera();
    }
  }

  async startCamera() {
    try {
      await this.cameraService.start({
        position: 'rear',
        width: 300,
        height: 400,
      });
      this.cameraStarted.set(true);
      await this.refreshAllData();
      this.addTestResult('✅ Camera started successfully');
    } catch (error) {
      this.addTestResult(`❌ Failed to start camera: ${error}`);
    }
  }

  async stopCamera() {
    try {
      await this.cameraService.stop();
      this.cameraStarted.set(false);
      this.addTestResult('✅ Camera stopped successfully');
    } catch (error) {
      this.addTestResult(`❌ Failed to stop camera: ${error}`);
    }
  }

  async testIsRunning() {
    try {
      const running = await this.cameraService.isRunning();
      this.isRunning.set(running);
      this.addTestResult(`✅ Camera running status: ${running}`);
    } catch (error) {
      this.addTestResult(`❌ Failed to get running status: ${error}`);
    }
  }

  async testGetDevices() {
    try {
      const devices = await this.cameraService.getAvailableDevices();
      this.availableDevices.set(devices);
      this.addTestResult(`✅ Found ${devices.length} devices`);
      devices.forEach(device => {
        this.addTestResult(`  - ${device.label} (${device.position}, ${device.deviceType})`);
      });
    } catch (error) {
      this.addTestResult(`❌ Failed to get devices: ${error}`);
    }
  }

  async testGetCurrentDevice() {
    try {
      const deviceId = await this.cameraService.getDeviceId();
      this.currentDeviceId.set(deviceId);
      this.addTestResult(`✅ Current device ID: ${deviceId}`);
    } catch (error) {
      this.addTestResult(`❌ Failed to get current device: ${error}`);
    }
  }

  async testSwitchDevice(deviceId: string) {
    try {
      await this.cameraService.setDeviceId(deviceId);
      await this.testGetCurrentDevice();
      this.addTestResult(`✅ Switched to device: ${deviceId}`);
    } catch (error) {
      this.addTestResult(`❌ Failed to switch device: ${error}`);
    }
  }

  async testZoom() {
    try {
      const zoom = await this.cameraService.getZoom();
      this.zoomCapabilities.set(zoom);
      this.addTestResult(`✅ Zoom - Min: ${zoom.min}, Max: ${zoom.max}, Current: ${zoom.current}`);
      
      // Test setting zoom
      const testZoom = Math.min(zoom.max, zoom.min + 1);
      await this.cameraService.setZoom(testZoom, false);
      this.addTestResult(`✅ Set zoom to ${testZoom}x`);
      
      const newZoom = await this.cameraService.getZoom();
      this.addTestResult(`✅ New zoom level: ${newZoom.current}x`);
    } catch (error) {
      this.addTestResult(`❌ Failed to test zoom: ${error}`);
    }
  }

  async testFlash() {
    try {
      const supportedModes = await this.cameraService.getSupportedFlashModes();
      this.supportedFlashModes.set(supportedModes);
      this.addTestResult(`✅ Supported flash modes: ${supportedModes.join(', ')}`);
      
      const currentMode = await this.cameraService.getFlashMode();
      this.currentFlashMode.set(currentMode);
      this.addTestResult(`✅ Current flash mode: ${currentMode}`);
      
      // Test setting flash mode
      if (supportedModes.length > 1) {
        const newMode = supportedModes.find(mode => mode !== currentMode) || supportedModes[0];
        await this.cameraService.setFlashMode(newMode);
        this.addTestResult(`✅ Set flash mode to: ${newMode}`);
        
        const updatedMode = await this.cameraService.getFlashMode();
        this.addTestResult(`✅ Updated flash mode: ${updatedMode}`);
      }
    } catch (error) {
      this.addTestResult(`❌ Failed to test flash: ${error}`);
    }
  }

  async testAllFeatures() {
    this.clearTestResults();
    this.addTestResult('🧪 Starting comprehensive feature test...');
    
    await this.testIsRunning();
    await this.testGetDevices();
    await this.testGetCurrentDevice();
    
    if (this.cameraStarted()) {
      await this.testZoom();
      await this.testFlash();
    } else {
      this.addTestResult('ℹ️ Start camera to test zoom and flash features');
    }
    
    this.addTestResult('✅ All tests completed!');
  }

  async refreshAllData() {
    await Promise.all([
      this.testIsRunning(),
      this.testGetDevices(),
      this.testGetCurrentDevice(),
    ]);
  }

  private addTestResult(result: string) {
    this.testResults.update(results => [...results, `${new Date().toLocaleTimeString()}: ${result}`]);
  }

  private clearTestResults() {
    this.testResults.set([]);
  }
} 
