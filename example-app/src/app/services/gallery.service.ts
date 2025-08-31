import { Injectable, computed, signal, inject } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import { CapacitorCameraViewService } from '../core/capacitor-camera-preview.service';

export interface MediaItem {
  src: string;
  type: 'photo' | 'video';
  timestamp: number;
}

@Injectable({
  providedIn: 'root',
})
export class GalleryService {
  readonly #mediaItems = signal<Array<MediaItem>>([]);
  readonly #cameraViewService = inject(CapacitorCameraViewService);
  public mediaItems = this.#mediaItems.asReadonly();

  public readonly photos = computed(() =>
    this.#mediaItems().filter(item => item.type === 'photo')
  );

  public readonly videos = computed(() =>
    this.#mediaItems().filter(item => item.type === 'video')
  );

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

    this.#mediaItems.update((curr) => [...curr, {
      src,
      type: 'photo',
      timestamp: Date.now()
    }]);
  }

  public async addVideo(videoPath: string, videoData?: string) {
    let src = videoData;
    if (!src) {
      if (
        videoPath.startsWith('/data/') ||
        videoPath.startsWith('file://') ||
        videoPath.startsWith('content://')
      ) {
        try {
          // Prefer streaming via Capacitor file URL to avoid base64 blow-ups
          src = Capacitor.convertFileSrc(videoPath);
          if (!src) {
            console.error('Failed to convert video path to a file URL');
            return;
          }
          // Intentionally keep the file; do not delete here.
        } catch (error) {
          console.error('Error processing video path:', error);
          return;
        }
      } else if (videoPath.startsWith('data:video/mp4;base64,')) {
        // Already a data URL
        src = videoPath;
      } else {
        // assume base64 payload
        src = `data:video/mp4;base64,${videoPath}`;
      }
    } else if (videoData && !videoData.startsWith('data:video/mp4;base64,')) {
      // Add data URL prefix if not present
      src = `data:video/mp4;base64,${videoData}`;
    }

    if (!src) {
      console.error('No valid video source available');
      return;
    }

    // Validate only if base64 data URL; otherwise accept file/https URLs
    if (src.startsWith('data:video/')) {
      try {
        const base64Data = src.split('base64,')[1];
        if (!base64Data || base64Data.length === 0) {
          console.error('Invalid base64 data');
          return;
        }
      } catch (error) {
        console.error('Error validating video data:', error);
        return;
      }
    }

    this.#mediaItems.update((curr) => [...curr, {
      src,
      type: 'video',
      timestamp: Date.now()
    }]);
  }
}
