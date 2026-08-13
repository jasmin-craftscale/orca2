import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Attaches the bearer token to ORCA's own API and to NOTHING ELSE.
 *
 * The generated clients are configured with an empty basePath, so every call they
 * make is the relative path `/api/v1/...`. The realm's own endpoints are absolute
 * URLs on another origin and must never receive this header — sending a platform
 * token to the identity provider would leak it outside the API it was issued for.
 *
 * ⚠️ THE ORDER OF THE NEXT TWO STATEMENTS IS LOAD-BEARING. The URL check comes
 * FIRST and `inject(AuthService)` second, deliberately. At startup AuthService is
 * being constructed when it asks OAuthService to fetch the discovery document;
 * that request passes through this interceptor, and injecting AuthService before
 * the early return would ask for the very service that is still constructing —
 * a cyclic dependency (NG0200) that kills the app before it renders. Because the
 * realm's URLs are absolute, they return early and never reach the inject.
 * Do not "tidy" this by hoisting the inject to the top of the function.
 */
export const apiTokenInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) {
    return next(req);
  }

  const auth = inject(AuthService);
  const token = auth.accessToken();
  const authorized = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(authorized).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        auth.login();
      }
      return throwError(() => error);
    }),
  );
};
