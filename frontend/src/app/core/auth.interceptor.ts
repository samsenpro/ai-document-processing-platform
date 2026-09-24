import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, of, switchMap, throwError } from 'rxjs';

import { AuthService } from './auth.service';

/**
 * Añade el access token a las llamadas a la API. Si no hay token (página recién cargada) o el
 * servidor responde 401 (token caducado), lo renueva con el refresh token y repite la petición una vez.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/') || request.url.startsWith('/api/v1/auth/')) {
    return next(request);
  }
  const auth = inject(AuthService);
  const send = (token: string | null) => next(withToken(request, token));
  const currentToken = auth.token();
  const token$ = currentToken ? of(currentToken) : auth.refresh().pipe(catchError(() => of(null)));

  return token$.pipe(
    switchMap((token) =>
      send(token).pipe(
        catchError((error: unknown) => {
          if (error instanceof HttpErrorResponse && error.status === 401 && token) {
            return auth.refresh().pipe(switchMap((renewed) => send(renewed)));
          }
          if (error instanceof HttpErrorResponse && error.status === 401) {
            auth.logout();
          }
          return throwError(() => error);
        }),
      ),
    ),
  );
};

function withToken(request: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;
}
