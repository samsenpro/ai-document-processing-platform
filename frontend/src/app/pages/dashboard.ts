import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, EMPTY, forkJoin, switchMap, timer } from 'rxjs';

import { DOCUMENT_TYPES, DocumentStats, DocumentSummary } from '../core/api.models';
import { DocumentsService } from '../core/documents.service';
import { errorMessage } from '../core/errors';
import { DocumentTypePipe, DurationPipe } from '../shared/format';
import { DocumentsTable } from './documents-table';

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, DocumentsTable, DocumentTypePipe, DurationPipe],
  template: `
    <div class="page-header">
      <h1>Dashboard</h1>
      <a class="btn btn-primary" routerLink="/upload">Subir documento</a>
    </div>

    @if (error()) {
      <p class="alert alert-error">{{ error() }}</p>
    }

    @if (stats(); as s) {
      <section class="stats">
        <div class="card stat"><span>Total de documentos</span><strong>{{ s.total }}</strong></div>
        <div class="card stat"><span>En proceso</span><strong>{{ s.processing }}</strong></div>
        <div class="card stat"><span>Completados</span><strong class="ok">{{ s.completed }}</strong></div>
        <div class="card stat"><span>Fallidos</span><strong class="ko">{{ s.failed }}</strong></div>
        <div class="card stat">
          <span>Tiempo medio de procesamiento</span><strong>{{ s.averageProcessingTimeMs | duration }}</strong>
        </div>
      </section>

      <section class="card">
        <h2>Documentos por tipo</h2>
        @for (row of byType(); track row.type) {
          <div class="bar-row">
            <span>{{ row.type | docType }}</span>
            <div class="bar"><div [style.width.%]="row.percent"></div></div>
            <strong>{{ row.count }}</strong>
          </div>
        } @empty {
          <p class="muted">Aún no hay documentos procesados.</p>
        }
      </section>
    }

    <section class="card">
      <h2>Documentos recientes</h2>
      <app-documents-table [documents]="recent()" (remove)="remove($event)" />
    </section>
  `,
})
export class DashboardPage implements OnInit {
  private readonly documents = inject(DocumentsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly stats = signal<DocumentStats | null>(null);
  protected readonly recent = signal<DocumentSummary[]>([]);
  protected readonly error = signal<string | null>(null);

  protected readonly byType = computed(() => {
    const byType = this.stats()?.byType ?? {};
    const max = Math.max(1, ...Object.values(byType).map((count) => count ?? 0));
    return DOCUMENT_TYPES.filter((type) => (byType[type] ?? 0) > 0)
      .map((type) => ({ type, count: byType[type] ?? 0, percent: ((byType[type] ?? 0) / max) * 100 }));
  });

  ngOnInit(): void {
    // Se refresca periódicamente: el procesamiento es asíncrono y los estados cambian solos
    timer(0, 5000)
      .pipe(
        switchMap(() =>
          forkJoin({ stats: this.documents.stats(), page: this.documents.list({ size: 10 }) }).pipe(
            // Un fallo puntual se muestra, pero no detiene el refresco periódico
            catchError((err: unknown) => {
              this.error.set(errorMessage(err));
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(({ stats, page }) => {
        this.stats.set(stats);
        this.recent.set(page.content);
        this.error.set(null);
      });
  }

  protected remove(doc: DocumentSummary): void {
    if (!confirm(`¿Eliminar "${doc.filename}"?`)) {
      return;
    }
    this.documents.delete(doc.id).subscribe({
      next: () => this.recent.update((docs) => docs.filter((d) => d.id !== doc.id)),
      error: (err: unknown) => this.error.set(errorMessage(err)),
    });
  }
}
