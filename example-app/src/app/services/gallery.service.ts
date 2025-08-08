import { Injectable, Signal, signal } from '@angular/core';
import { Capacitor } from '@capacitor/core';

@Injectable({
  providedIn: 'root',
})
export class GalleryService {
  readonly #photos = signal<Array<string>>([]);
  public photos = this.#photos.asReadonly();

  public addPhoto(photo: string) {
    console.log('addPhoto =>', photo);
    let src = photo;
    if (photo.startsWith('data:')) {
      // already a data URL
      src = photo;
    } else if (
      photo.startsWith('/') ||
      photo.startsWith('file://') ||
      photo.startsWith('content://')
    ) {
      // file path or uri -> convert for <img> usage
      const fileUrl = photo.startsWith('/') ? `file://${photo}` : photo;
      src = Capacitor.convertFileSrc(fileUrl);
    } else {
      // assume base64 payload
      src = `data:image/jpeg;base64,${photo}`;
    }

    this.#photos.update((curr) => [...curr, src]);
  }
}
