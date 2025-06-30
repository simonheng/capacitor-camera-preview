import { Component } from '@angular/core';
import { IonApp, IonRouterOutlet } from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import {
  add,
  aperture,
  bugOutline,
  cameraOutline,
  cameraReverse,
  cameraSharp,
  close,
  closeCircle,
  closeOutline,
  flash,
  flashOff,
  imagesSharp,
  qrCodeOutline,
  refreshOutline,
  remove,
  search,
  searchOutline,
  stop,
  sunny,
  sunnyOutline,
  videocam,
} from 'ionicons/icons';

@Component({
  selector: 'app-root',
  templateUrl: 'app.component.html',
  imports: [IonApp, IonRouterOutlet],
})
export class AppComponent {
  constructor() {
    addIcons({
      add,
      aperture,
      bugOutline,
      cameraOutline,
      cameraReverse,
      cameraSharp,
      close,
      closeCircle,
      closeOutline,
      flash,
      flashOff,
      imagesSharp,
      qrCodeOutline,
      refreshOutline,
      remove,
      search,
      searchOutline,
      stop,
      sunny,
      sunnyOutline,
      videocam,
    });
  }
}
