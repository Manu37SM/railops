import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetail } from './models';

/**
 * Turns any HTTP failure into a message a passenger can act on. Switches on the stable `code`, never on
 * `detail` text, except where the server's detail is the useful part (validation, eligibility).
 */
export interface UserFacingError {
  code: string;
  message: string;
  retryable: boolean;
  fieldErrors: { field: string; message: string }[];
}

const MESSAGES: Record<string, string> = {
  AUTH_FAILED: 'Those details didn’t match, or the account is temporarily locked. Try again in a few minutes.',
  ACCOUNT_EXISTS: 'An account with this email or phone number already exists. Try logging in.',
  RES_REGRET: 'No seats or waitlist places are left in this class and quota.',
  RES_ONLY_IF_CNF_UNMET: 'Confirmed berths aren’t available for everyone, so nothing was booked (you chose "book only if confirmed").',
  RES_RUN_NOT_BOOKABLE: 'Booking isn’t open for this train right now.',
  RES_HOLD_EXPIRED: 'Your seat hold expired before payment. Please book again.',
  RES_BOOKING_NOT_FOUND: 'We couldn’t find that booking.',
  RES_CANCELLATION_CLOSED: 'Online cancellation is closed for this booking.',
  RES_BOOKING_NOT_PAYABLE: 'This booking can’t be paid for any more.',
  IDEM_KEY_REUSED: 'This request conflicts with an earlier one. Please reload and try again.',
  IDEM_IN_PROGRESS: 'Your earlier request is still being processed. Please wait a moment.',
  PAYMENT_UNAVAILABLE: 'Payments are temporarily unavailable. Your seats are still held; please try again shortly.',
  DEPENDENCY_UNAVAILABLE: 'A service is temporarily unavailable. Please try again shortly.',
  FORBIDDEN: 'You don’t have permission to do that.',
  UNAUTHENTICATED: 'Please log in again.',
  TOO_MANY_REQUESTS: 'Too many requests. Please wait a moment and try again.',
};

/** Codes where the server's own detail text is the most specific explanation. */
const USE_SERVER_DETAIL = new Set([
  'RES_QUOTA_INELIGIBLE', 'RES_INVALID_JOURNEY', 'RES_INVALID_CANCELLATION', 'WEAK_PASSWORD',
  'RES_CHART_NOT_ALLOWED', 'MFA_CODE_INVALID',
]);

export function toUserFacingError(err: unknown): UserFacingError {
  if (!(err instanceof HttpErrorResponse)) {
    return { code: 'CLIENT', message: 'Something went wrong in the app. Please reload the page.', retryable: false, fieldErrors: [] };
  }
  if (err.status === 0) {
    return { code: 'NETWORK', message: 'Can’t reach RailOps. Check your connection and try again.', retryable: true, fieldErrors: [] };
  }
  const p = (typeof err.error === 'object' && err.error ? err.error : { status: err.status }) as ProblemDetail;
  const code = p.code ?? (err.status === 429 ? 'TOO_MANY_REQUESTS' : `HTTP_${err.status}`);
  const retryable = err.status >= 500 || err.status === 429 || code === 'IDEM_IN_PROGRESS';
  let message = MESSAGES[code];
  if (!message && USE_SERVER_DETAIL.has(code) && p.detail) {
    // Server details are log-style fragments ("senior citizen quota: …"); present them as a sentence.
    message = p.detail.charAt(0).toUpperCase() + p.detail.slice(1) + (/[.!?]$/.test(p.detail) ? '' : '.');
  }
  if (!message) {
    message = err.status >= 500
      ? 'The service had a problem. Please try again.'
      : 'The request couldn’t be completed. Please check the details and try again.';
  }
  return { code, message, retryable, fieldErrors: p.errors ?? [] };
}
