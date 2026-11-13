import { InjectionToken } from '@angular/core';
import { environment } from '../../environments/environment';

export interface PaymentRedirect {
  /** Navigates to the gateway checkout. Returns false (and does nothing) if the URL isn't allowed. */
  go(url: string): boolean;
}

/**
 * Only https URLs on configured gateway hosts. The checkout URL comes from our own server, but if
 * payment-service or its gateway response were ever compromised, this stops the app from sending
 * passengers to a look-alike payment page. Injectable so tests can capture the navigation.
 */
export function isAllowedCheckoutUrl(raw: string, allowedHosts: readonly string[]): boolean {
  try {
    const u = new URL(raw);
    return u.protocol === 'https:' && allowedHosts.includes(u.hostname) && !u.username && !u.password;
  } catch {
    return false;
  }
}

export const PAYMENT_REDIRECT = new InjectionToken<PaymentRedirect>('PAYMENT_REDIRECT', {
  factory: () => ({
    go: (url: string) => {
      if (!isAllowedCheckoutUrl(url, environment.paymentHosts)) return false;
      window.location.assign(url);
      return true;
    },
  }),
});
