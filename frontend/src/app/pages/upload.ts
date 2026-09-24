import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { DOCUMENT_TYPES, RequestedDocumentType } from '../core/api.models';
import { DocumentsService } from '../core/documents.service';
import { errorMessage } from '../core/errors';
import { DocumentTypePipe, FileSizePipe } from '../shared/format';

const ACCEPTED = '.pdf,.png,.jpg,.jpeg,.tif,.tiff,.webp,.docx,.txt';

@Component({
  selector: 'app-upload',
  imports: [FormsModule, DocumentTypePipe, FileSizePipe],
  template: `
    <div class="page-header"><h1>Subir documento</h1></div>

    <section class="card upload-card">
      <label class="dropzone" [class.dragging]="dragging()"
             (dragover)="$event.preventDefault(); dragging.set(true)"
             (dragleave)="dragging.set(false)"
             (drop)="onDrop($event)">
        <input type="file" [accept]="accepted" (change)="onSelect($event)" hidden />
        @if (file(); as f) {
          <strong>{{ f.name }}</strong>
          <span class="muted">{{ f.size | fileSize }} · haz clic para cambiarlo</span>
        } @else {
          <strong>Arrastra un archivo aquí o haz clic para elegirlo</strong>
          <span class="muted">PDF, imágenes (PNG, JPEG, TIFF, WEBP), DOCX o TXT · máximo 20 MB</span>
        }
      </label>

      <div class="form-row">
        <label>
          Tipo de documento
          <select [(ngModel)]="documentType">
            <option value="AUTO">Detectar automáticamente</option>
            @for (type of types; track type) {
              <option [value]="type">{{ type | docType }}</option>
            }
          </select>
        </label>
        <label class="checkbox">
          <input type="checkbox" [(ngModel)]="autoProcess" /> Procesar al subir
        </label>
      </div>

      @if (error()) {
        <p class="alert alert-error">{{ error() }}</p>
      }
      <button class="btn btn-primary" type="button" [disabled]="!file() || uploading()" (click)="upload()">
        {{ uploading() ? 'Subiendo…' : 'Subir documento' }}
      </button>
    </section>
  `,
})
export class UploadPage {
  private readonly documents = inject(DocumentsService);
  private readonly router = inject(Router);

  protected readonly accepted = ACCEPTED;
  protected readonly types = DOCUMENT_TYPES;
  protected readonly file = signal<File | null>(null);
  protected readonly dragging = signal(false);
  protected readonly uploading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected documentType: RequestedDocumentType = 'AUTO';
  protected autoProcess = true;

  /** Una clave por archivo elegido: reintentar la misma subida no crea documentos duplicados. */
  private idempotencyKey = crypto.randomUUID();

  protected onSelect(event: Event): void {
    this.setFile((event.target as HTMLInputElement).files?.item(0) ?? null);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    this.setFile(event.dataTransfer?.files.item(0) ?? null);
  }

  protected upload(): void {
    const file = this.file();
    if (!file) {
      return;
    }
    this.uploading.set(true);
    this.error.set(null);
    this.documents.upload(file, this.documentType, this.autoProcess, this.idempotencyKey).subscribe({
      next: (doc) => void this.router.navigate(['/documents', doc.id]),
      error: (err: unknown) => {
        this.error.set(errorMessage(err));
        this.uploading.set(false);
      },
    });
  }

  private setFile(file: File | null): void {
    this.file.set(file);
    this.error.set(null);
    this.idempotencyKey = crypto.randomUUID();
  }
}
