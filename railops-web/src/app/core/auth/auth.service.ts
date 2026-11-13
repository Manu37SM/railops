import { HttpBackend, HttpClient, HttpHeaders } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, finalize, map, Observable, of, shareReplay, tap, throwError } from 'rxjs';
import { MfaEnrolment, MfaEnrolmentRequired, SessionClaims, TokenResponse } from '../api/models';

export const AUTH_BASE = '/api/v1/auth';
/** Required on cookie-authenticated endpoints; see identity-service AuthController (CSRF defence). */
const CSRF = new HttpHeaders({ 'X-Requested-With': 'railops-web' });

export type LoginOutcome = { kind: 'session' } | { kind: 'mfa-enrolment'; enrolmentToken: string };

/**
 * Session state. The access token lives ONLY in this signal: not localStorage or sessionStorage, where any
 * injected script could read it. A reload loses it on purpose; {@link restoreSession} gets a new one through
 * the HttpOnly refresh cookie, which JavaScript can't read at all.
 *
 * Auth calls go through a raw HttpClient built on HttpBackend, bypassing interceptors: the auth interceptor
 * must never try to refresh while calling refresh itself.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly raw = new HttpClient(inject(HttpBackend));
  private readonly router = inject(Router);

  private readonly token = signal<string | null>(null);
  readonly claims = computed(() => decodeClaims(this.token()));
  readonly isLoggedIn = computed(() => this.claims() !== null);
  readonly isStaff = computed(() => this.claims()?.utyp === 'STAFF');
  readonly roles = computed(() => new Set(this.claims()?.roles ?? []));

  private refreshInFlight: Observable<string> | null = null;

  accessToken(): string | null {
    return this.token();
  }

  hasAnyRole(...roles: string[]): boolean {
    const held = this.roles();
    return roles.some(r => held.has(r));
  }

  login(email: string, password: string, otp?: string): Observable<LoginOutcome> {
    const body = otp ? { email, password, otp } : { email, password };
    return this.raw.post<TokenResponse | MfaEnrolmentRequired>(`${AUTH_BASE}/login`, body, { withCredentials: true }).pipe(
      map(res => {
        if ('status' in res && res.status === 'MFA_ENROLMENT_REQUIRED') {
          return { kind: 'mfa-enrolment' as const, enrolmentToken: res.enrolmentToken };
        }
        this.token.set((res as TokenResponse).accessToken);
        return { kind: 'session' as const };
      }),
    );
  }

  register(email: string, password: string, phone: string): Observable<void> {
    return this.raw.post<unknown>(`${AUTH_BASE}/register`, { email, password, phone }).pipe(map(() => undefined));
  }

  beginMfaEnrolment(enrolmentToken: string): Observable<MfaEnrolment> {
    return this.raw.post<MfaEnrolment>(`${AUTH_BASE}/mfa/enrol`, null, {
      headers: { Authorization: `Bearer ${enrolmentToken}` },
    });
  }

  confirmMfaEnrolment(enrolmentToken: string, code: string): Observable<void> {
    return this.raw.post<void>(`${AUTH_BASE}/mfa/confirm`, { code }, {
      headers: { Authorization: `Bearer ${enrolmentToken}` },
    });
  }

  /**
   * Single-flight refresh. Ten requests failing with 401 at once must trigger ONE refresh: the server
   * rotates the cookie on every refresh, and parallel refreshes would race the rotation (the server's 5 s
   * grace window tolerates that, but it shouldn't be relied on as the normal path).
   */
  refresh(): Observable<string> {
    if (!this.refreshInFlight) {
      this.refreshInFlight = this.raw.post<TokenResponse>(`${AUTH_BASE}/refresh`, null, {
        headers: CSRF, withCredentials: true,
      }).pipe(
        map(r => r.accessToken),
        tap(t => this.token.set(t)),
        catchError(err => {
          this.token.set(null);
          return throwError(() => err);
        }),
        finalize(() => (this.refreshInFlight = null)),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    }
    return this.refreshInFlight;
  }

  /** App start: silently resume a session if the refresh cookie is still valid. Never fails startup. */
  restoreSession(): Observable<boolean> {
    return this.refresh().pipe(map(() => true), catchError(() => of(false)));
  }

  logout(): void {
    this.raw.post(`${AUTH_BASE}/logout`, null, { headers: CSRF, withCredentials: true })
      .pipe(catchError(() => of(null)))
      .subscribe();
    this.token.set(null);
    void this.router.navigateByUrl('/login');
  }

  /** The server already ended every session (password changed): forget the token, no logout call needed. */
  clearSession(): void {
    this.token.set(null);
  }

  /** Called by the interceptor when refresh fails: the session is gone; send the user to log in. */
  sessionExpired(returnUrl: string): void {
    this.token.set(null);
    void this.router.navigate(['/login'], { queryParams: { returnUrl, reason: 'expired' } });
  }
}

/** Decodes the JWT payload for display/routing only. Signature verification is the API's job, not ours. */
export function decodeClaims(token: string | null): SessionClaims | null {
  if (!token) {
    return null;
  }
  try {
    const part = token.split('.')[1];
    const json = atob(part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '='));
    const c = JSON.parse(json) as Partial<SessionClaims>;
    return {
      sub: String(c.sub ?? ''),
      utyp: (c.utyp ?? 'PASSENGER') as SessionClaims['utyp'],
      roles: Array.isArray(c.roles) ? c.roles : [],
      grants: Array.isArray(c.grants) ? c.grants : [],
      amr: Array.isArray(c.amr) ? c.amr : [],
      exp: Number(c.exp ?? 0),
    };
  } catch {
    return null;
  }
}
