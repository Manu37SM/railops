import { HttpErrorResponse } from '@angular/common/http';
import { FormArray, FormControl, FormGroup } from '@angular/forms';
import { passengersValidator } from '../../features/booking/booking-form.page';
import { passwordPolicy } from '../../features/auth/register.page';
import { isAllowedCheckoutUrl } from '../payment-redirect';
import { toUserFacingError } from './api-error';
import { IdempotentAction, stableStringify } from './idempotency';
import { ServerClock } from './server-clock';
import { decodeClaims } from '../auth/auth.service';

describe('IdempotentAction', () => {
  it('reuses the key for the same body (retry) and issues a new one for a changed body', () => {
    const a = new IdempotentAction();
    const k1 = a.keyFor({ x: 1, y: [1, 2] });
    expect(a.keyFor({ y: [1, 2], x: 1 })).toBe(k1); // key order doesn't matter
    const k2 = a.keyFor({ x: 2, y: [1, 2] });
    expect(k2).not.toBe(k1);
    a.reset();
    expect(a.keyFor({ x: 2, y: [1, 2] })).not.toBe(k2);
  });

  it('fingerprints deterministically and ignores undefined fields', () => {
    expect(stableStringify({ b: 1, a: { d: undefined, c: 2 } })).toBe('{"a":{"c":2},"b":1}');
  });
});

describe('toUserFacingError', () => {
  const problem = (status: number, body: object) => new HttpErrorResponse({ status, error: body });

  it('maps stable codes to passenger messages and marks retryability', () => {
    const regret = toUserFacingError(problem(409, { status: 409, code: 'RES_REGRET', detail: 'internal wording' }));
    expect(regret.message).toContain('No seats');
    expect(regret.retryable).toBe(false);
    expect(toUserFacingError(problem(503, { status: 503, code: 'PAYMENT_UNAVAILABLE' })).retryable).toBe(true);
    expect(toUserFacingError(new HttpErrorResponse({ status: 0 })).code).toBe('NETWORK');
  });

  it('uses server detail only for codes where it is the useful part', () => {
    expect(toUserFacingError(problem(422, { status: 422, code: 'RES_QUOTA_INELIGIBLE', detail: 'senior citizen quota: passenger 1 is under 60' })).message)
      .toContain('under 60');
    expect(toUserFacingError(problem(500, { status: 500, code: 'INTERNAL', detail: 'NullPointerException at Foo' })).message)
      .not.toContain('NullPointer');
  });
});

describe('isAllowedCheckoutUrl', () => {
  const hosts = ['pay.example'];
  it.each([
    ['https://pay.example/checkout/1', true],
    ['http://pay.example/checkout/1', false],              // downgrade
    ['https://pay.example.evil.in/checkout', false],       // look-alike host
    ['https://user:pw@pay.example/x', false],              // credential smuggling
    ['javascript:alert(1)', false],
    ['/relative', false],
  ])('%s -> %s', (url, ok) => expect(isAllowedCheckoutUrl(url, hosts)).toBe(ok));
});

describe('passengersValidator', () => {
  function group(quota: string, pax: { age: number; gender: string; berthRequired: boolean }[]) {
    const arr = new FormArray(pax.map(p => new FormGroup({
      age: new FormControl(p.age), gender: new FormControl(p.gender), berthRequired: new FormControl(p.berthRequired),
    })), { validators: passengersValidator() });
    new FormGroup({ quota: new FormControl(quota), passengers: arr });
    arr.updateValueAndValidity();
    return arr.errors;
  }

  it('mirrors the server rules', () => {
    expect(group('GN', [{ age: 30, gender: 'F', berthRequired: true }, { age: 3, gender: 'M', berthRequired: false }])).toBeNull();
    expect(group('GN', [{ age: 3, gender: 'M', berthRequired: false }])).toEqual({ noBerthPassenger: true });
    expect(group('GN', [{ age: 30, gender: 'F', berthRequired: true }, { age: 7, gender: 'M', berthRequired: false }]))
      .toEqual({ noBerthTooOld: true });
    expect(group('LD', [{ age: 30, gender: 'F', berthRequired: true }, { age: 10, gender: 'M', berthRequired: true }])).toBeNull();
    expect(group('LD', [{ age: 30, gender: 'M', berthRequired: true }])).toEqual({ ladiesQuota: true });
    expect(group('TQ', [{ age: 30, gender: 'F', berthRequired: true }, { age: 2, gender: 'F', berthRequired: false }]))
      .toEqual({ tatkalChild: true });
  });
});

describe('small pure helpers', () => {
  it('passwordPolicy matches the server minimums', () => {
    expect(passwordPolicy(new FormControl('short'))).toEqual({ minlength: true });
    expect(passwordPolicy(new FormControl('aaaaaaaaaaaa'))).toEqual({ repetitive: true });
    expect(passwordPolicy(new FormControl('monsoon-express-1950'))).toBeNull();
  });

  it('decodeClaims tolerates garbage instead of throwing', () => {
    expect(decodeClaims('not.a.jwt')).toBeNull();
    expect(decodeClaims(null)).toBeNull();
  });

  it('ServerClock corrects real skew but ignores latency-sized noise', () => {
    const c = new ServerClock();
    const now = Date.now();
    c.observe(new Date(now + 180_000).toUTCString(), now);   // server 3 min ahead
    expect(Math.abs(c.offsetMs() - 180_000)).toBeLessThan(1500);
    c.observe(new Date(now).toUTCString(), now + 400);         // 0.4 s: just latency
    expect(c.offsetMs()).toBe(0);
  });
});
