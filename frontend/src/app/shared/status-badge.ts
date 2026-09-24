import { Component, computed, input } from '@angular/core';

import { DocumentStatus, STATUS_LABELS } from '../core/api.models';

@Component({
  selector: 'app-status-badge',
  template: `<span class="badge" [class]="'badge badge-' + status().toLowerCase()">{{ label() }}</span>`,
})
export class StatusBadge {
  readonly status = input.required<DocumentStatus>();
  protected readonly label = computed(() => STATUS_LABELS[this.status()]);
}
