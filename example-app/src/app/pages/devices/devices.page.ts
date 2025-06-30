import { Component, OnInit, signal } from '@angular/core';
import { 
  IonButton, 
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
  IonTitle, 
  IonToolbar 
} from '@ionic/angular/standalone';
import { type CameraDevice } from '@capgo/camera-preview';
import { CapacitorCameraViewService } from '../../core/capacitor-camera-preview.service';

@Component({
  selector: 'app-devices',
  templateUrl: './devices.page.html',
  styleUrls: ['./devices.page.scss'],
  imports: [
    IonButton,
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
    IonTitle,
    IonToolbar,
  ],
})
export class DevicesPage implements OnInit {
  protected readonly devices = signal<CameraDevice[]>([]);
  protected readonly currentDeviceId = signal('');
  protected readonly loading = signal(false);

  constructor(private cameraService: CapacitorCameraViewService) {}

  async ngOnInit() {
    await this.loadDevices();
  }

  async loadDevices() {
    this.loading.set(true);
    try {
      const devices = await this.cameraService.getAvailableDevices();
      this.devices.set(devices);
      
      try {
        const currentId = await this.cameraService.getDeviceId();
        this.currentDeviceId.set(currentId);
      } catch (error) {
        console.warn('Could not get current device ID:', error);
      }
    } catch (error) {
      console.error('Failed to load devices:', error);
    } finally {
      this.loading.set(false);
    }
  }

  async switchToDevice(deviceId: string) {
    try {
      await this.cameraService.setDeviceId(deviceId);
      this.currentDeviceId.set(deviceId);
    } catch (error) {
      console.error('Failed to switch device:', error);
    }
  }

  getDeviceTypeColor(deviceType?: string): string {
    switch (deviceType) {
      case 'wideAngle': return 'primary';
      case 'ultraWide': return 'secondary';
      case 'telephoto': return 'tertiary';
      case 'trueDepth': return 'success';
      case 'dual': return 'warning';
      case 'dualWide': return 'warning';
      case 'triple': return 'danger';
      default: return 'medium';
    }
  }

  getPositionColor(position: string): string {
    return position === 'front' ? 'success' : 'primary';
  }
} 
