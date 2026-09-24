import { Routes } from '@angular/router';

import { authGuard, guestGuard } from './core/auth.guard';
import { DashboardPage } from './pages/dashboard';
import { DocumentDetailPage } from './pages/document-detail';
import { DocumentsPage } from './pages/documents';
import { LoginPage } from './pages/login';
import { Shell } from './pages/shell';
import { UploadPage } from './pages/upload';

export const routes: Routes = [
  { path: 'login', component: LoginPage, canActivate: [guestGuard], title: 'DocuMind AI · Acceso' },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      { path: '', component: DashboardPage, title: 'DocuMind AI · Dashboard' },
      { path: 'upload', component: UploadPage, title: 'DocuMind AI · Subir documento' },
      { path: 'documents', component: DocumentsPage, title: 'DocuMind AI · Documentos' },
      { path: 'documents/:id', component: DocumentDetailPage, title: 'DocuMind AI · Documento' },
    ],
  },
  { path: '**', redirectTo: '' },
];
