import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Route guards are UX, not security: they keep people out of screens that would only show errors.
 * Every rule is enforced again by the API, which is the only place it actually matters.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  return auth.isLoggedIn() ? true : inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

export const passengerGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  if (!auth.isLoggedIn()) {
    return authGuard(route, state);
  }
  return auth.hasAnyRole('PASSENGER', 'AGENT') ? true : inject(Router).createUrlTree(['/staff']);
};

export const staffGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  if (!auth.isLoggedIn()) {
    return authGuard(route, state);
  }
  const mfa = auth.claims()?.amr.includes('otp') ?? false;
  return auth.isStaff() && mfa ? true : inject(Router).createUrlTree(['/']);
};
