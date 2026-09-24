import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { DOCUMENT_TYPES, DocumentStatus, DocumentSummary, DocumentType, Page, STATUS_LABELS } from '../core/api.models';
import { DocumentsService } from '../core/documents.service';
import { errorMessage } from '../core/errors';
import { DocumentTypePipe } from '../shared/format';
import { DocumentsTable } from './documents-table';

@Component({
  selector: 'app-documents',
  imports: [FormsModule, DocumentsTable, DocumentTypePipe],
  template: `
    <div class="page-header"><h1>Documentos</h1></div>

    <section class="card">
      <div class="filters">
        <label>
          Estado
          <select [(ngModel)]="status" (ngModelChange)="load(0)">
            <option value="">Todos</option>
            @for (s of statuses; track s) {
              <option [value]="s">{{ statusLabels[s] }}</option>
            }
          </select>
        </label>
        <label>
          Tipo
          <select [(ngModel)]="type" (ngModelChange)="load(0)">
            <option value="">Todos</option>
            @for (t of types; track t) {
              <option [value]="t">{{ t | docType }}</option>
            }
          </select>
        </label>
      </div>

      @if (error()) {
        <p class="alert alert-error">{{ error() }}</p>
      }
      <app-documents-table [documents]="page()?.content ?? []" (remove)="remove($event)" />

      @if (page(); as p) {
        <div class="pagination">
          <button class="btn btn-small" type="button" [disabled]="p.page === 0" (click)="load(p.page - 1)">Anterior</button>
          <span>Página {{ p.page + 1 }} de {{ p.totalPages || 1 }} · {{ p.totalElements }} documentos</span>
          <button class="btn btn-small" type="button" [disabled]="p.page + 1 >= p.totalPages" (click)="load(p.page + 1)">
            Siguiente
          </button>
        </div>
      }
    </section>
  `,
})
export class DocumentsPage implements OnInit {
  private readonly documents = inject(DocumentsService);

  protected readonly statuses: DocumentStatus[] = ['UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED'];
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly types = DOCUMENT_TYPES;
  protected readonly page = signal<Page<DocumentSummary> | null>(null);
  protected readonly error = signal<string | null>(null);
  protected status: DocumentStatus | '' = '';
  protected type: DocumentType | '' = '';

  ngOnInit(): void {
    this.load(0);
  }

  protected load(page: number): void {
    this.documents.list({ status: this.status, type: this.type, page, size: 20 }).subscribe({
      next: (result) => {
        this.page.set(result);
        this.error.set(null);
      },
      error: (err: unknown) => this.error.set(errorMessage(err)),
    });
  }

  protected remove(doc: DocumentSummary): void {
    if (!confirm(`¿Eliminar "${doc.filename}"?`)) {
      return;
    }
    this.documents.delete(doc.id).subscribe({
      next: () => this.load(this.page()?.page ?? 0),
      error: (err: unknown) => this.error.set(errorMessage(err)),
    });
  }
}
