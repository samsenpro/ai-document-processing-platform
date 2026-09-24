import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from '../core/auth.service';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="topbar">
      <a routerLink="/" class="brand">DocuMind <span>AI</span></a>
      <nav>
        <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Dashboard</a>
        <a routerLink="/documents" routerLinkActive="active">Documentos</a>
        <a routerLink="/upload" routerLinkActive="active">Subir documento</a>
      </nav>
      <div class="user">
        <span>{{ auth.user()?.fullName }}</span>
        <span class="role">{{ auth.user()?.role }}</span>
        <button class="btn btn-link" type="button" (click)="auth.logout()">Salir</button>
      </div>
    </header>
    <main class="container">
      <router-outlet />
    </main>
  `,
})
export class Shell {
  protected readonly auth = inject(AuthService);
}
