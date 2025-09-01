import { Component, inject } from '@angular/core';
import {
  IonContent,
  IonHeader,
  IonTitle,
  IonToolbar,
  ModalController,
  IonIcon,
} from '@ionic/angular/standalone';
import { GalleryService, MediaItem } from '../../services/gallery.service';
import { MediaViewerComponent } from '../../components/media-viewer/media-viewer.component';

@Component({
  selector: 'app-gallery',
  templateUrl: './gallery.component.html',
  styleUrls: ['./gallery.component.scss'],
  imports: [IonHeader, IonToolbar, IonTitle, IonContent, IonIcon],
})
export class GalleryComponent {
  protected readonly galleryService = inject(GalleryService);
  protected readonly modalCtrl = inject(ModalController);

  protected async openMediaViewer(item: MediaItem) {
    const modal = await this.modalCtrl.create({
      component: MediaViewerComponent,
      componentProps: {
        item,
      },
      cssClass: 'fullscreen-modal'
    });

    await modal.present();
  }
}
