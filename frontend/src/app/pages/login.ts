import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';

import { AuthService } from '../core/auth.service';
import { errorMessage } from '../core/errors';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule],
  template: `
    <main class="auth-page">
      <section class="card auth-card">
        <h1 class="brand-title">DocuMind <span>AI</span></h1>
        <p class="muted">Procesamiento inteligente de documentos</p>

        <div class="tabs">
          <button type="button" [class.active]="mode() === 'login'" (click)="mode.set('login')">Iniciar sesión</button>
          <button type="button" [class.active]="mode() === 'register'" (click)="mode.set('register')">Crear cuenta</button>
        </div>

        <form [formGroup]="form" (ngSubmit)="submit()" class="form">
          @if (mode() === 'register') {
            <label>Nombre completo <input formControlName="fullName" autocomplete="name" /></label>
            <label>Organización <input formControlName="organizationName" autocomplete="organization" /></label>
          }
          <label>Email <input type="email" formControlName="email" autocomplete="email" /></label>
          <label>
            Contraseña
            <input type="password" formControlName="password"
                   [attr.autocomplete]="mode() === 'login' ? 'current-password' : 'new-password'" />
          </label>
          @if (mode() === 'register') {
            <small class="muted">Mínimo 12 caracteres, con letras y números.</small>
          }
          @if (error()) {
            <p class="alert alert-error">{{ error() }}</p>
          }
          <button class="btn btn-primary" type="submit" [disabled]="loading()">
            {{ loading() ? 'Procesando…' : mode() === 'login' ? 'Entrar' : 'Crear cuenta' }}
          </button>
        </form>
      </section>
    </main>
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly mode = signal<'login' | 'register'>('login');
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    fullName: [''],
    organizationName: [''],
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.error.set('Completa el email y la contraseña.');
      return;
    }
    const { email, password, fullName, organizationName } = this.form.getRawValue();
    const request = this.mode() === 'login'
      ? this.auth.login(email, password)
      : this.auth.register({ email, password, fullName, organizationName });

    this.loading.set(true);
    this.error.set(null);
    request.subscribe({
      next: () => void this.router.navigate(['/']),
      error: (err: unknown) => {
        this.error.set(errorMessage(err));
        this.loading.set(false);
      },
    });
  }
}
