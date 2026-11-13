import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { toUserFacingError, UserFacingError } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { ProblemAlert } from '../../shared/problem-alert';

/** Mirrors identity-service PasswordPolicy for early feedback; the server remains the authority. */
export function passwordPolicy(control: AbstractControl): ValidationErrors | null {
  const v = String(control.value ?? '');
  if (v.length < 10) return { minlength: true };
  if (v.length > 128) return { maxlength: true };
  if (new Set(v).size < 4) return { repetitive: true };
  return null;
}

@Component({
  selector: 'ro-register-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, ProblemAlert],
  template: `
    <section class="card narrow">
      <h1>Create a passenger account</h1>
      <ro-problem-alert [error]="error()" />
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <label for="email">Email</label>
        <input id="email" type="email" formControlName="email" autocomplete="email" />
        <label for="phone">Mobile number</label>
        <input id="phone" type="tel" formControlName="phone" autocomplete="tel" placeholder="+91 98200 12345" />
        <label for="password">Password</label>
        <input id="password" type="password" formControlName="password" autocomplete="new-password"
               aria-describedby="pw-help" />
        <p id="pw-help" class="hint">At least 10 characters. A short phrase is easier to remember than symbols.</p>
        @if (form.controls.password.touched && form.controls.password.errors; as e) {
          <p class="field-error" role="alert">
            @if (e['minlength']) { Use at least 10 characters. }
            @if (e['repetitive']) { That password is too repetitive. }
          </p>
        }
        <button type="submit" [disabled]="busy() || form.invalid">Create account</button>
      </form>
      <p>Already registered? <a routerLink="/login">Log in</a></p>
    </section>
  `,
})
export class RegisterPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly busy = signal(false);
  protected readonly error = signal<UserFacingError | null>(null);
  protected readonly form = this.fb.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    phone: ['', [Validators.required, Validators.pattern(/^\+?[0-9 -]{10,16}$/)]],
    password: ['', [Validators.required, passwordPolicy]],
  });

  submit(): void {
    if (this.form.invalid) return;
    const { email, password, phone } = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    this.auth.register(email, password, phone).subscribe({
      next: () => this.auth.login(email, password).subscribe({
        next: () => void this.router.navigateByUrl('/search'),
        error: e => { this.busy.set(false); this.error.set(toUserFacingError(e)); },
      }),
      error: e => { this.busy.set(false); this.error.set(toUserFacingError(e)); },
    });
  }
}
