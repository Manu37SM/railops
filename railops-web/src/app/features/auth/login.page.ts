import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toUserFacingError, UserFacingError } from '../../core/api/api-error';
import { MfaEnrolment } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { ProblemAlert } from '../../shared/problem-alert';
import { QrCode } from '../../shared/qr-code';

@Component({
  selector: 'ro-login-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, ProblemAlert, QrCode],
  template: `
    <section class="card narrow">
      @if (!enrolment()) {
        <h1>Log in</h1>
        @if (passwordChanged) {
          <p class="alert alert-info" role="status">Password changed. Sign in again with the new one.</p>
        }
        @if (expired) {
          <p class="alert alert-info" role="status">Your session ended. Please log in again.</p>
        }
        <ro-problem-alert [error]="error()" />
        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <label for="email">Email</label>
          <input id="email" type="email" formControlName="email" autocomplete="username" required />
          <label for="password">Password</label>
          <div class="password-field">
            <input id="password" [type]="showPassword() ? 'text' : 'password'" formControlName="password"
                   autocomplete="current-password" required />
            <button type="button" class="secondary reveal" (click)="showPassword.set(!showPassword())"
                    [attr.aria-pressed]="showPassword()" aria-controls="password"
                    [attr.aria-label]="showPassword() ? 'Hide password' : 'Show password'">
              {{ showPassword() ? 'Hide' : 'Show' }}
            </button>
          </div>
          <label for="otp">Authenticator code <span class="hint">(staff only)</span></label>
          <input id="otp" inputmode="numeric" autocomplete="one-time-code" maxlength="6" formControlName="otp" />
          <button type="submit" [disabled]="busy() || form.invalid">{{ busy() ? 'Logging in…' : 'Log in' }}</button>
        </form>
        <p>New passenger? <a routerLink="/register">Create an account</a></p>
      } @else {
        <h1>Set up two-factor authentication</h1>
        <p>Staff accounts need an authenticator app (Google Authenticator, Microsoft Authenticator, Aegis…).
          Scan this code with it, then enter the 6-digit code it shows.</p>
        @if (enrolment()?.setup; as setup) {
          @if (isTotpUri(setup.otpauthUri)) {
            <ro-qr-code class="mfa-qr" data-testid="mfa-qr" [data]="setup.otpauthUri" [size]="220"
                        label="QR code to add RailOps to your authenticator app" />
          }
          <p class="hint">Can't scan it? Type this key into the app instead:</p>
          <p class="secret" data-testid="mfa-secret"><code>{{ setup.secretBase32 }}</code></p>
          <!-- No <a href="otpauth:…">: Angular's URL sanitizer would (rightly) rewrite an unknown scheme to
               "unsafe:", and bypassing the sanitizer for a URL built from server data isn't worth it. -->
          <p class="hint">Key type: time-based, 6 digits, 30 seconds.</p>
        }
        <ro-problem-alert [error]="error()" />
        <form [formGroup]="codeForm" (ngSubmit)="confirmEnrolment()" novalidate>
          <label for="code">6-digit code</label>
          <input id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" formControlName="code" />
          <button type="submit" [disabled]="busy() || codeForm.invalid">Confirm</button>
        </form>
      }
    </section>
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly expired = this.route.snapshot.queryParamMap.get('reason') === 'expired';
  protected readonly passwordChanged = this.route.snapshot.queryParamMap.get('reason') === 'password-changed';
  protected readonly busy = signal(false);
  protected readonly showPassword = signal(false);
  protected readonly error = signal<UserFacingError | null>(null);
  protected readonly enrolment = signal<{ token: string; setup: MfaEnrolment | null } | null>(null);

  protected readonly form = this.fb.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    password: ['', [Validators.required, Validators.maxLength(128)]],
    otp: ['', [Validators.pattern(/^\d{6}$/)]],
  });
  protected readonly codeForm = this.fb.group({ code: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]] });

  submit(): void {
    if (this.form.invalid) return;
    const { email, password, otp } = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    this.auth.login(email, password, otp || undefined).subscribe({
      next: outcome => {
        this.busy.set(false);
        if (outcome.kind === 'mfa-enrolment') {
          this.enrolment.set({ token: outcome.enrolmentToken, setup: null });
          this.auth.beginMfaEnrolment(outcome.enrolmentToken).subscribe({
            next: setup => this.enrolment.set({ token: outcome.enrolmentToken, setup }),
            error: e => this.error.set(toUserFacingError(e)),
          });
          return;
        }
        this.navigateAfterLogin();
      },
      error: e => {
        this.busy.set(false);
        this.form.controls.password.reset(); // never leave a failed password sitting in the DOM
        this.showPassword.set(false);
        this.error.set(toUserFacingError(e));
      },
    });
  }

  confirmEnrolment(): void {
    const e = this.enrolment();
    if (!e || this.codeForm.invalid) return;
    this.busy.set(true);
    this.error.set(null);
    this.auth.confirmMfaEnrolment(e.token, this.codeForm.getRawValue().code).subscribe({
      next: () => {
        this.busy.set(false);
        this.enrolment.set(null);
        this.form.controls.password.reset();
        this.error.set({ code: 'MFA_DONE', message: 'Two-factor set up. Log in with your password and a fresh code.',
          retryable: false, fieldErrors: [] });
      },
      error: err => {
        this.busy.set(false);
        this.error.set(toUserFacingError(err));
      },
    });
  }

  /** Only a TOTP provisioning link becomes a QR code; anything else from the server is ignored. */
  protected isTotpUri(uri: string | null | undefined): uri is string {
    return typeof uri === 'string' && uri.startsWith('otpauth://totp/') && uri.length < 1024;
  }

  private navigateAfterLogin(): void {
    const requested = this.route.snapshot.queryParamMap.get('returnUrl');
    // Only same-app paths: "//evil.example" or "https://…" would be an open redirect.
    const safe = requested && requested.startsWith('/') && !requested.startsWith('//') ? requested : null;
    void this.router.navigateByUrl(safe ?? (this.auth.isStaff() ? '/staff' : '/search'));
  }
}
