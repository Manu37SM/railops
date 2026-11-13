import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, Injectable, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize, Observable } from 'rxjs';
import { toUserFacingError, UserFacingError } from '../../core/api/api-error';
import { AUTH_BASE, AuthService } from '../../core/auth/auth.service';
import { ProblemAlert } from '../../shared/problem-alert';
import { passwordPolicy } from '../auth/register.page';

/**
 * Uses the normal HttpClient, so the auth interceptor adds the access token. (AuthService itself uses a raw client,
 * and can't inject the intercepted one without a circular dependency.)
 */
@Injectable({ providedIn: 'root' })
export class AccountApi {
  private readonly http = inject(HttpClient);

  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http.post<void>(`${AUTH_BASE}/password`, { currentPassword, newPassword });
  }
}

/**
 * Change my password (Phase 6), for staff and passengers. The server checks the current password (wrong guesses
 * count toward the lockout) and then ends every session, this one included, so the person signs in again.
 */
@Component({
  selector: 'ro-change-password-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, ProblemAlert],
  template: `
    <section class="card narrow">
      <h1>Change password</h1>
      <p class="hint">You'll be signed out everywhere afterwards and sign in again with the new password.</p>
      <ro-problem-alert [error]="error()" />
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <label for="cp-current">Current password</label>
        <input id="cp-current" type="password" formControlName="current" autocomplete="current-password" />
        <label for="cp-new">New password</label>
        <input id="cp-new" type="password" formControlName="next" autocomplete="new-password" aria-describedby="cp-help" />
        <p id="cp-help" class="hint">At least 10 characters, and different from the current one.</p>
        <label for="cp-again">New password again</label>
        <input id="cp-again" type="password" formControlName="again" autocomplete="new-password" />
        @if (formError(); as m) { <p class="field-error" role="alert">{{ m }}</p> }
        <button type="submit" [disabled]="busy()">{{ busy() ? 'Changing…' : 'Change password' }}</button>
      </form>
    </section>
  `,
})
export class ChangePasswordPage {
  private readonly api = inject(AccountApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly busy = signal(false);
  protected readonly error = signal<UserFacingError | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    current: ['', Validators.required],
    next: ['', [Validators.required, passwordPolicy]],
    again: ['', Validators.required],
  });

  submit(): void {
    const v = this.form.getRawValue();
    if (!v.current) return this.formError.set('Enter your current password.');
    if (this.form.controls.next.invalid) return this.formError.set('The new password needs at least 10 characters and some variety.');
    if (v.next !== v.again) return this.formError.set('The two new passwords are different.');
    if (v.next === v.current) return this.formError.set('Choose a password different from the current one.');
    this.formError.set(null);
    this.error.set(null);
    this.busy.set(true);
    this.api.changePassword(v.current, v.next).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: () => {
        this.auth.clearSession();
        void this.router.navigate(['/login'], { queryParams: { reason: 'password-changed' } });
      },
      error: e => this.error.set(toUserFacingError(e)),
    });
  }
}
