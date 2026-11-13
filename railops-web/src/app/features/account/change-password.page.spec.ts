import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { ChangePasswordPage } from './change-password.page';
import { AuthService } from '../../core/auth/auth.service';

describe('ChangePasswordPage (Phase 6)', () => {
  let ctl: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([])), provideHttpClientTesting()],
    });
    ctl = TestBed.inject(HttpTestingController);
  });

  afterEach(() => ctl.verify());

  function fill(values: Record<string, string>) {
    const f = TestBed.createComponent(ChangePasswordPage);
    f.detectChanges();
    for (const [id, value] of Object.entries(values)) {
      const input: HTMLInputElement = f.nativeElement.querySelector('#' + id);
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    f.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    f.detectChanges();
    return f;
  }

  it('sends current and new password, then signs out and goes to login', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const clear = vi.spyOn(TestBed.inject(AuthService), 'clearSession');
    fill({ 'cp-current': 'old-password-1', 'cp-new': 'brand-new-pass-9', 'cp-again': 'brand-new-pass-9' });
    const req = ctl.expectOne('/api/v1/auth/password');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ currentPassword: 'old-password-1', newPassword: 'brand-new-pass-9' });
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(clear).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { reason: 'password-changed' } });
  });

  it('catches mismatched, short or unchanged passwords before calling the server', () => {
    let f = fill({ 'cp-current': 'old-password-1', 'cp-new': 'brand-new-pass-9', 'cp-again': 'brand-new-pass-8' });
    expect(f.nativeElement.textContent).toContain('different');
    f = fill({ 'cp-current': 'old-password-1', 'cp-new': 'short', 'cp-again': 'short' });
    expect(f.nativeElement.textContent).toContain('at least 10');
    f = fill({ 'cp-current': 'same-password-1', 'cp-new': 'same-password-1', 'cp-again': 'same-password-1' });
    expect(f.nativeElement.textContent).toContain('different from the current');
    ctl.expectNone('/api/v1/auth/password');
  });

  it('a wrong current password shows the server error and keeps the session', () => {
    const clear = vi.spyOn(TestBed.inject(AuthService), 'clearSession');
    const f = fill({ 'cp-current': 'wrong-password-1', 'cp-new': 'brand-new-pass-9', 'cp-again': 'brand-new-pass-9' });
    ctl.expectOne('/api/v1/auth/password').flush({ code: 'AUTH_FAILED', detail: 'x' }, { status: 401, statusText: 'Unauthorized' });
    f.detectChanges();
    expect(clear).not.toHaveBeenCalled();
  });
});
