import { HttpErrorResponse, HttpEvent, HttpInterceptorFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, tap, throwError } from 'rxjs';
import { ServerClock } from '../api/server-clock';
import { AuthService } from './auth.service';

/** Only our own API gets the token. A bearer token sent to a third-party URL is a token handed away. */
function isOwnApi(req: HttpRequest<unknown>): boolean {
  return req.url.startsWith('/api/');
}

function withToken(req: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
}

/**
 * Attaches the access token; on 401 refreshes once (shared across concurrent requests) and retries the
 * original request once. A second 401 after a fresh token means the session really is gone.
 * Also feeds response Date headers to the ServerClock.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const clock = inject(ServerClock);
  const router = inject(Router);

  if (!isOwnApi(req)) {
    return next(req);
  }
  const observeClock = tap<HttpEvent<unknown>>({
    next: e => {
      if (e instanceof HttpResponse) {
        clock.observe(e.headers.get('Date'), Date.now());
      }
    },
  });

  return next(withToken(req, auth.accessToken())).pipe(
    observeClock,
    catchError((err: unknown) => {
      // Only retry when we had (or could have had) a session; anonymous 401s just surface.
      if (!(err instanceof HttpErrorResponse) || err.status !== 401 || !auth.isLoggedIn()) {
        return throwError(() => err);
      }
      return auth.refresh().pipe(
        catchError(refreshErr => {
          auth.sessionExpired(router.url);
          return throwError(() => refreshErr);
        }),
        switchMap(fresh => next(withToken(req, fresh)).pipe(observeClock)),
      );
    }),
  );
};
