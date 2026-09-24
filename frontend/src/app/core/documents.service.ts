import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import {
  DocumentDetail,
  DocumentResult,
  DocumentStats,
  DocumentStatus,
  DocumentSummary,
  DocumentType,
  Page,
  ProcessingStatusResponse,
  RequestedDocumentType,
} from './api.models';

@Injectable({ providedIn: 'root' })
export class DocumentsService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/v1/documents';

  list(filters: { status?: DocumentStatus | ''; type?: DocumentType | ''; page?: number; size?: number }):
    Observable<Page<DocumentSummary>> {
    let params = new HttpParams().set('page', filters.page ?? 0).set('size', filters.size ?? 20);
    if (filters.status) {
      params = params.set('status', filters.status);
    }
    if (filters.type) {
      params = params.set('type', filters.type);
    }
    return this.http.get<Page<DocumentSummary>>(this.base, { params });
  }

  stats(): Observable<DocumentStats> {
    return this.http.get<DocumentStats>(`${this.base}/stats`);
  }

  get(id: string): Observable<DocumentDetail> {
    return this.http.get<DocumentDetail>(`${this.base}/${id}`);
  }

  /** La clave de idempotencia hace seguro repetir la subida (doble clic, reintento de red). */
  upload(file: File, documentType: RequestedDocumentType, autoProcess: boolean, idempotencyKey: string):
    Observable<DocumentDetail> {
    const form = new FormData();
    form.append('file', file);
    const params = new HttpParams().set('documentType', documentType).set('autoProcess', autoProcess);
    return this.http.post<DocumentDetail>(this.base, form, {
      params,
      headers: { 'X-Idempotency-Key': idempotencyKey },
    });
  }

  /**
   * La clave se deriva del intento actual: dos envíos para el mismo intento (doble clic) encolan el
   * documento una sola vez, pero un reintento posterior, tras otro fallo, usa una clave nueva.
   */
  process(id: string, currentAttempt: number): Observable<ProcessingStatusResponse> {
    return this.http.post<ProcessingStatusResponse>(`${this.base}/${id}/process`, null, {
      headers: { 'X-Idempotency-Key': `process-${id}-after-${currentAttempt}` },
    });
  }

  processing(id: string): Observable<ProcessingStatusResponse> {
    return this.http.get<ProcessingStatusResponse>(`${this.base}/${id}/processing`);
  }

  result(id: string): Observable<DocumentResult> {
    return this.http.get<DocumentResult>(`${this.base}/${id}/result`);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}
