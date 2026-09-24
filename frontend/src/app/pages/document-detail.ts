import { DatePipe, DecimalPipe, KeyValuePipe } from '@angular/common';
import { Component, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { catchError, EMPTY, forkJoin, switchMap, takeWhile, timer } from 'rxjs';

import { DocumentDetail, DocumentResult, ProcessingStatusResponse } from '../core/api.models';
import { DocumentsService } from '../core/documents.service';
import { errorMessage } from '../core/errors';
import { DocumentTypePipe, DurationPipe, FileSizePipe } from '../shared/format';
import { StatusBadge } from '../shared/status-badge';

const STAGE_LABELS: Record<string, string> = {
  QUEUED: 'En cola',
  CALLING_AI_SERVICE: 'Analizando con el servicio de IA',
  SAVING_RESULT: 'Guardando el resultado',
  COMPLETED: 'Completado',
  FAILED: 'Fallido',
};

@Component({
  selector: 'app-document-detail',
  imports: [RouterLink, DatePipe, DecimalPipe, KeyValuePipe, StatusBadge, DocumentTypePipe, DurationPipe,
    FileSizePipe],
  template: `
    <a routerLink="/documents" class="back">← Documentos</a>
    @if (error()) {
      <p class="alert alert-error">{{ error() }}</p>
    }

    @if (document(); as doc) {
      <div class="page-header">
        <div>
          <h1>{{ doc.filename }}</h1>
          <p class="muted">{{ doc.sizeBytes | fileSize }} · {{ doc.contentType }} · subido {{ doc.createdAt | date: 'medium' }}</p>
        </div>
        <div class="header-actions">
          @if (doc.actions.process || doc.actions.retry) {
            <button class="btn btn-primary" type="button" [disabled]="busy()" (click)="process(doc)">
              {{ doc.actions.retry ? 'Reintentar procesamiento' : 'Procesar' }}
            </button>
          }
          @if (doc.actions.delete) {
            <button class="btn btn-danger" type="button" [disabled]="busy()" (click)="remove(doc)">Eliminar</button>
          }
        </div>
      </div>

      <section class="grid-2">
        <div class="card">
          <h2>Estado del procesamiento</h2>
          <dl class="facts">
            <dt>Estado</dt><dd><app-status-badge [status]="doc.status" /></dd>
            @if (processing()?.stage; as stage) {
              <dt>Etapa</dt><dd>{{ stageLabel(stage) }}</dd>
            }
            <dt>Tipo solicitado</dt><dd>{{ doc.requestedType | docType }}</dd>
            <dt>Tipo detectado</dt><dd>{{ doc.detectedType | docType }}</dd>
            <dt>Tiempo de procesamiento</dt><dd>{{ doc.processingTimeMs | duration }}</dd>
          </dl>
          @if (doc.status === 'PROCESSING') {
            <div class="progress"><div></div></div>
          }
          @if (doc.latestProcessing?.errorCode) {
            <p class="alert alert-error">
              <strong>{{ doc.latestProcessing?.errorCode }}</strong>: {{ doc.latestProcessing?.errorMessage }}
            </p>
          }
        </div>

        <div class="card">
          <h2>Intentos</h2>
          <ol class="attempts">
            @for (attempt of processing()?.attempts ?? []; track attempt.id) {
              <li>
                <strong>#{{ attempt.attempt }}</strong> {{ attempt.status }}
                <span class="muted">· {{ attempt.queuedAt | date: 'mediumTime' }} · {{ attempt.durationMs | duration }}</span>
                @if (attempt.errorCode) {
                  <span class="error-code">{{ attempt.errorCode }}</span>
                }
              </li>
            } @empty {
              <li class="muted">El documento aún no se ha procesado.</li>
            }
          </ol>
        </div>
      </section>

      @if (result(); as r) {
        <section class="card">
          <h2>Resumen</h2>
          <p class="summary">{{ r.summary || '—' }}</p>
          <p class="muted">
            {{ r.documentType | docType }} · confianza {{ r.confidence * 100 | number: '1.0-0' }} % ·
            idioma {{ r.language ?? 'desconocido' }}
          </p>
        </section>

        <section class="grid-2">
          <div class="card">
            <h2>Información extraída</h2>
            <dl class="facts">
              @for (entry of r.entities | keyvalue: keepOrder; track entry.key) {
                <dt>{{ humanize(entry.key) }}</dt><dd>{{ display(entry.value) }}</dd>
              }
            </dl>
          </div>
          <div class="card">
            <h2>Detalles del análisis</h2>
            <dl class="facts">
              @for (entry of r.metadata | keyvalue: keepOrder; track entry.key) {
                <dt>{{ humanize(entry.key) }}</dt><dd>{{ display(entry.value) }}</dd>
              }
            </dl>
          </div>
        </section>

        <section class="card">
          <details>
            <summary>Texto extraído ({{ r.text.length }} caracteres)</summary>
            <pre class="extracted-text">{{ r.text }}</pre>
          </details>
        </section>
      }
    }
  `,
})
export class DocumentDetailPage implements OnInit {
  /** Parámetro de ruta :id (withComponentInputBinding). */
  readonly id = input.required<string>();

  private readonly documents = inject(DocumentsService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly document = signal<DocumentDetail | null>(null);
  protected readonly processing = signal<ProcessingStatusResponse | null>(null);
  protected readonly result = signal<DocumentResult | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly keepOrder = () => 0;

  ngOnInit(): void {
    this.poll();
  }

  /** Consulta el estado cada 2 s mientras el documento se está procesando. */
  private poll(): void {
    timer(0, 2000)
      .pipe(
        switchMap(() =>
          forkJoin({ doc: this.documents.get(this.id()), processing: this.documents.processing(this.id()) }).pipe(
            catchError((err: unknown) => {
              this.error.set(errorMessage(err));
              return EMPTY;
            }),
          ),
        ),
        takeWhile(({ doc }) => doc.status === 'PROCESSING', true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(({ doc, processing }) => {
        this.document.set(doc);
        this.processing.set(processing);
        if (doc.status === 'COMPLETED' && !this.result()) {
          this.documents.result(doc.id).subscribe({
            next: (result) => this.result.set(result),
            error: (err: unknown) => this.error.set(errorMessage(err)),
          });
        }
      });
  }

  protected process(doc: DocumentDetail): void {
    this.busy.set(true);
    this.error.set(null);
    this.documents.process(doc.id, doc.latestProcessing?.attempt ?? 0).subscribe({
      next: () => {
        this.busy.set(false);
        this.result.set(null);
        this.poll();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }

  protected remove(doc: DocumentDetail): void {
    if (!confirm(`¿Eliminar "${doc.filename}"?`)) {
      return;
    }
    this.busy.set(true);
    this.documents.delete(doc.id).subscribe({
      next: () => void this.router.navigate(['/documents']),
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }

  protected stageLabel(stage: string): string {
    return STAGE_LABELS[stage] ?? stage;
  }

  protected humanize(key: string): string {
    const text = key.replace(/_/g, ' ');
    return text.charAt(0).toUpperCase() + text.slice(1);
  }

  protected display(value: unknown): string {
    if (value === null || value === undefined || value === '') {
      return '—';
    }
    if (Array.isArray(value)) {
      return value.length ? value.join(', ') : '—';
    }
    if (typeof value === 'boolean') {
      return value ? 'Sí' : 'No';
    }
    if (typeof value === 'number') {
      return value.toLocaleString('es-CO');
    }
    return String(value);
  }
}
