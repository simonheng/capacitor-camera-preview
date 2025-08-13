import { Injectable, Signal, signal, inject } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import { CapacitorCameraViewService } from '../core/capacitor-camera-preview.service';

@Injectable({
  providedIn: 'root',
})
export class GalleryService {
  readonly #photos = signal<Array<string>>([]);
  readonly #cameraViewService = inject(CapacitorCameraViewService);
  public photos = this.#photos.asReadonly();

  public async addPhoto(photo: string) {
    let src = photo;
    if (photo.startsWith('data:')) {
      // already a data URL
      src = photo;
    } else if (
      photo.startsWith('/data/') ||
      photo.startsWith('file://') ||
      photo.startsWith('content://')
    ) {
      // file path or uri -> convert for <img> usage
      const base64 = await this.#cameraViewService.getBase64FromFilePath(photo);
      src = `data:image/jpeg;base64,${base64}`;
      await this.#cameraViewService.deleteFile(photo);
    } else {
      // assume base64 payload
      src = `data:image/jpeg;base64,${photo}`;
    }

    this.#photos.update((curr) => [...curr, src]);
  }
}
