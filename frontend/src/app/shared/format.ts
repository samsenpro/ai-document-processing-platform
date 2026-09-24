import { Pipe, PipeTransform } from '@angular/core';

import { DOCUMENT_TYPE_LABELS, DocumentType, RequestedDocumentType } from '../core/api.models';

@Pipe({ name: 'fileSize' })
export class FileSizePipe implements PipeTransform {
  transform(bytes: number | null | undefined): string {
    if (bytes == null) {
      return '—';
    }
    if (bytes < 1024) {
      return `${bytes} B`;
    }
    const kb = bytes / 1024;
    return kb < 1024 ? `${kb.toFixed(1)} KB` : `${(kb / 1024).toFixed(1)} MB`;
  }
}

@Pipe({ name: 'duration' })
export class DurationPipe implements PipeTransform {
  transform(ms: number | null | undefined): string {
    if (ms == null) {
      return '—';
    }
    return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
  }
}

@Pipe({ name: 'docType' })
export class DocumentTypePipe implements PipeTransform {
  transform(type: DocumentType | RequestedDocumentType | null | undefined): string {
    if (!type) {
      return '—';
    }
    return type === 'AUTO' ? 'Automático' : DOCUMENT_TYPE_LABELS[type];
  }
}
