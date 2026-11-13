import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

/** Builds an unsigned JWT-shaped token; the client only decodes claims, it never verifies. */
function fakeJwt(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${b64({ alg: 'none' })}.${b64({ sub: 'u1', utyp: 'PASSENGER', roles: ['PASSENGER'], amr: ['pwd'], exp: 9e9, ...claims })}.sig`;
}

describe('authInterceptor', () => {
  let http: HttpClient;
  let ctl: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    ctl = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => ctl.verify());

  function login(token: string) {
    auth.login('a@b.in', 'pw').subscribe();
    ctl.expectOne('/api/v1/auth/login').flush({ accessToken: token, tokenType: 'Bearer', expiresIn: 600 });
  }

  it('attaches the bearer token to our API only', () => {
    login(fakeJwt({}));
    http.get('/api/v1/bookings').subscribe();
    http.get('https://cdn.example.com/x.json').subscribe();
    expect(ctl.expectOne('/api/v1/bookings').request.headers.get('Authorization')).toMatch(/^Bearer /);
    expect(ctl.expectOne('https://cdn.example.com/x.json').request.headers.has('Authorization')).toBe(false);
  });

  it('runs ONE refresh for concurrent 401s, then retries each request once with the new token', () => {
    login(fakeJwt({ v: 1 }));
    const results: unknown[] = [];
    http.get('/api/v1/bookings').subscribe(r => results.push(r));
    http.get('/api/v1/bookings/1234567897').subscribe(r => results.push(r));
    ctl.expectOne('/api/v1/bookings').flush(null, { status: 401, statusText: 'Unauthorized' });
    ctl.expectOne('/api/v1/bookings/1234567897').flush(null, { status: 401, statusText: 'Unauthorized' });

    const refreshes = ctl.match('/api/v1/auth/refresh');
    expect(refreshes.length).toBe(1);
    expect(refreshes[0].request.headers.get('X-Requested-With')).toBe('railops-web');
    expect(refreshes[0].request.withCredentials).toBe(true);
    const fresh = fakeJwt({ v: 2 });
    refreshes[0].flush({ accessToken: fresh, tokenType: 'Bearer', expiresIn: 600 });

    const retried = [ctl.expectOne('/api/v1/bookings'), ctl.expectOne('/api/v1/bookings/1234567897')];
    retried.forEach(r => expect(r.request.headers.get('Authorization')).toBe(`Bearer ${fresh}`));
    retried.forEach(r => r.flush({ ok: true }));
    expect(results.length).toBe(2);
  });

  it('logs the user out and redirects when the refresh itself fails', () => {
    const router = TestBed.inject(Router);
    const nav = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    login(fakeJwt({}));
    let failed = false;
    http.get('/api/v1/bookings').subscribe({ error: () => (failed = true) });
    ctl.expectOne('/api/v1/bookings').flush(null, { status: 401, statusText: 'Unauthorized' });
    ctl.expectOne('/api/v1/auth/refresh').flush({ code: 'AUTH_FAILED' }, { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBe(true);
    expect(auth.isLoggedIn()).toBe(false);
    expect(nav).toHaveBeenCalledWith(['/login'], expect.objectContaining({ queryParams: expect.objectContaining({ reason: 'expired' }) }));
  });

  it('does not loop: an anonymous 401 is surfaced without a refresh attempt', () => {
    let status = 0;
    http.get('/api/v1/bookings').subscribe({ error: e => (status = e.status) });
    ctl.expectOne('/api/v1/bookings').flush(null, { status: 401, statusText: 'Unauthorized' });
    ctl.expectNone('/api/v1/auth/refresh');
    expect(status).toBe(401);
  });
});
