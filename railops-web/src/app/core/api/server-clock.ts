import { Injectable, signal } from '@angular/core';

/**
 * Tracks the offset between the browser clock and the server clock (from HTTP Date headers).
 *
 * Why: hold expiry is decided by the database clock. A phone whose clock is 3 minutes fast would show
 * "hold expired" while the seats are still held, and the passenger would abandon a valid booking.
 * Date headers have 1-second resolution, which is plenty for a 10-minute countdown.
 */
@Injectable({ providedIn: 'root' })
export class ServerClock {
  /** serverTime - browserTime, in ms. */
  readonly offsetMs = signal(0);

  observe(dateHeader: string | null, receivedAtMs: number): void {
    if (!dateHeader) {
      return;
    }
    const server = Date.parse(dateHeader);
    if (Number.isNaN(server)) {
      return;
    }
    // Date is truncated to the second; add half a second to centre the estimate.
    const offset = server + 500 - receivedAtMs;
    // Ignore sub-2s differences: they're network latency and truncation, not real skew.
    this.offsetMs.set(Math.abs(offset) < 2000 ? 0 : offset);
  }

  now(): number {
    return Date.now() + this.offsetMs();
  }
}
