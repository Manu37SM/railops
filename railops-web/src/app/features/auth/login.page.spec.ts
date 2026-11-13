import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { LoginPage } from './login.page';

const URI = 'otpauth://totp/RailOps:staff%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=RailOps&digits=6&period=30';

describe('LoginPage', () => {
  let ctl: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([])), provideHttpClientTesting()],
    });
    ctl = TestBed.inject(HttpTestingController);
  });

  afterEach(() => ctl.verify());

  it('shows and hides the password, and says so to screen readers', () => {
    const f = TestBed.createComponent(LoginPage);
    f.detectChanges();
    const input: HTMLInputElement = f.nativeElement.querySelector('#password');
    const toggle: HTMLButtonElement = f.nativeElement.querySelector('button.reveal');
    expect(input.type).toBe('password');
    expect(toggle.type).toBe('button');   // never submits the form
    expect(toggle.getAttribute('aria-pressed')).toBe('false');
    expect(toggle.getAttribute('aria-label')).toBe('Show password');

    toggle.click();
    f.detectChanges();
    expect(input.type).toBe('text');
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
    expect(toggle.getAttribute('aria-label')).toBe('Hide password');

    toggle.click();
    f.detectChanges();
    expect(input.type).toBe('password');
  });

  function startEnrolment(otpauthUri: string) {
    const f = TestBed.createComponent(LoginPage);
    f.detectChanges();
    for (const [id, value] of Object.entries({ email: 'staff@example.com', password: 'a-long-password-1' })) {
      const input: HTMLInputElement = f.nativeElement.querySelector('#' + id);
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    f.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    ctl.expectOne('/api/v1/auth/login').flush({ status: 'MFA_ENROLMENT_REQUIRED', enrolmentToken: 'enrol-token' });
    ctl.expectOne('/api/v1/auth/mfa/enrol').flush({ secretBase32: 'JBSWY3DPEHPK3PXP', otpauthUri });
    f.detectChanges();
    return f;
  }

  it('shows a QR code for the authenticator app, with the key as a fallback', () => {
    const f = startEnrolment(URI);
    const svg: SVGElement = f.nativeElement.querySelector('[data-testid="mfa-qr"] svg');
    expect(svg).toBeTruthy();
    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toContain('authenticator');
    expect(svg.querySelector('path')!.getAttribute('d')!.length).toBeGreaterThan(100);
    expect(f.nativeElement.querySelector('[data-testid="mfa-secret"]').textContent).toContain('JBSWY3DPEHPK3PXP');
  });

  it('draws no QR code for anything that is not a TOTP link', () => {
    const f = startEnrolment('https://evil.example/?secret=x');
    expect(f.nativeElement.querySelector('[data-testid="mfa-qr"]')).toBeNull();
    expect(f.nativeElement.querySelector('[data-testid="mfa-secret"]').textContent).toContain('JBSWY3DPEHPK3PXP');
  });
});
