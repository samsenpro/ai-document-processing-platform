import { DatePipe } from '@angular/common';
import { Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';

import { DocumentSummary } from '../core/api.models';
import { DocumentTypePipe, DurationPipe } from '../shared/format';
import { StatusBadge } from '../shared/status-badge';

/** Tabla de documentos: Documento, Tipo, Estado, Creado, Tiempo de procesamiento y Acciones. */
@Component({
  selector: 'app-documents-table',
  imports: [RouterLink, DatePipe, StatusBadge, DocumentTypePipe, DurationPipe],
  template: `
    <div class="table-wrapper">
      <table>
        <thead>
          <tr>
            <th>Documento</th>
            <th>Tipo</th>
            <th>Estado</th>
            <th>Creado</th>
            <th>Tiempo de procesamiento</th>
            <th class="actions">Acciones</th>
          </tr>
        </thead>
        <tbody>
          @for (doc of documents(); track doc.id) {
            <tr>
              <td><a [routerLink]="['/documents', doc.id]">{{ doc.filename }}</a></td>
              <td>{{ (doc.detectedType ?? doc.requestedType) | docType }}</td>
              <td><app-status-badge [status]="doc.status" /></td>
              <td>{{ doc.createdAt | date: 'short' }}</td>
              <td>{{ doc.processingTimeMs | duration }}</td>
              <td class="actions">
                <a class="btn btn-small" [routerLink]="['/documents', doc.id]">Ver</a>
                @if (doc.status !== 'PROCESSING') {
                  <button class="btn btn-small btn-danger" type="button" (click)="remove.emit(doc)">Eliminar</button>
                }
              </td>
            </tr>
          } @empty {
            <tr><td colspan="6" class="empty">No hay documentos todavía.</td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class DocumentsTable {
  readonly documents = input.required<DocumentSummary[]>();
  readonly remove = output<DocumentSummary>();
}
