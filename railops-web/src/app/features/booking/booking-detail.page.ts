import { CurrencyPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { finalize } from 'rxjs';
import { toUserFacingError, UserFacingError } from '../../core/api/api-error';
import { IdempotentAction } from '../../core/api/idempotency';
import { BookingView, CancellationView } from '../../core/api/models';
import { ReservationApi } from '../../core/api/reservation-api';
import { PAYMENT_REDIRECT } from '../../core/payment-redirect';
import { HoldCountdown } from '../../shared/hold-countdown';
import { ProblemAlert } from '../../shared/problem-alert';
import { StatusBadge } from '../../shared/status-badge';
import { WlChance } from '../../shared/wl-chance';

const ACTIVE = ['CNF', 'RAC', 'WL', 'NOSB'];

@Component({
  selector: 'ro-booking-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CurrencyPipe, HoldCountdown, ProblemAlert, StatusBadge, WlChance],
  template: `
    <ro-problem-alert [error]="error()" />
    @if (booking(); as b) {
      <section class="card">
        <header class="split">
          <div>
            <h1>PNR <span data-testid="pnr">{{ b.pnr }}</span></h1>
            <p>Run {{ b.trainRunId }} on {{ b.runDate }} · stops {{ b.fromSeq }} → {{ b.toSeq }} · {{ b.classCode }} / {{ b.quotaCode }}</p>
          </div>
          <ro-status [value]="b.status" />
        </header>

        <table>
          <caption class="sr-only">Passengers</caption>
          <thead><tr><th scope="col">#</th><th scope="col">Name</th><th scope="col">Age</th><th scope="col">Booked as</th><th scope="col">Now</th>
            @if (cancellable()) { <th scope="col">Cancel</th> }</tr></thead>
          <tbody>
            @for (p of b.passengers; track p.seqNo) {
              <tr [attr.data-testid]="'pax-' + p.seqNo">
                <td>{{ p.seqNo }}</td><td>{{ p.name }}</td><td>{{ p.age }}</td>
                <td><ro-status [value]="p.bookingStatus" /></td>
                <td><ro-status [value]="p.currentStatus" /></td>
                @if (cancellable()) {
                  <td>
                    @if (isActive(p.currentStatus)) {
                      <input type="checkbox" [attr.aria-label]="'Cancel passenger ' + p.seqNo + ' ' + p.name"
                             [checked]="selected().has(p.seqNo)" (change)="toggle(p.seqNo)" />
                    }
                  </td>
                }
              </tr>
            }
          </tbody>
        </table>
        @if (waitlisted()) {
          <ro-wl-chance [pnr]="b.pnr" />
        }
        <p>Total fare: <strong>{{ b.totalFare | currency: 'INR' : 'symbol' : '1.2-2' : 'en-IN' }}</strong></p>

        @if (b.status === 'PENDING_PAYMENT' && b.holdExpiresAt) {
          <div class="pay">
            <ro-hold-countdown [expiresAt]="b.holdExpiresAt" (expired)="reload()" />
            <button type="button" (click)="pay()" [disabled]="busy()">{{ busy() ? 'Opening payment…' : 'Pay now' }}</button>
          </div>
        }
      </section>

      @if (cancellable()) {
        <section class="card">
          <h2>Cancel</h2>
          <!-- Quote errors live here, not in the page banner: they describe the current selection and must
               disappear as soon as the selection becomes valid. -->
          <ro-problem-alert [error]="quoteError()" />
          @if (selected().size === 0) {
            <p>Select passengers above to see the refund.</p>
          } @else if (quote(); as q) {
            <dl class="quote" data-testid="quote">
              <dt>Paid</dt><dd>{{ q.totalPaid | currency: 'INR' : 'symbol' : '1.2-2' : 'en-IN' }}</dd>
              <dt>Cancellation charge</dt><dd>{{ q.totalCharge | currency: 'INR' : 'symbol' : '1.2-2' : 'en-IN' }}</dd>
              <dt>Refund</dt><dd><strong>{{ q.refundAmount | currency: 'INR' : 'symbol' : '1.2-2' : 'en-IN' }}</strong></dd>
            </dl>
            <p class="hint">The refund depends on the time to departure, so it may change if you wait.</p>
            @if (confirming()) {
              <!-- A confirmation question isn't an alert: role="alert" would interrupt screen readers and compete
                   with real errors. Focus moves to the confirm button instead (see cdkTrapFocus-free approach below). -->
              <p id="confirm-q">Cancel {{ selected().size }} passenger(s)? This can't be undone.</p>
              <button type="button" class="danger" (click)="cancel()" [disabled]="busy()" aria-describedby="confirm-q"
                      autofocus>Yes, cancel</button>
              <button type="button" class="secondary" (click)="confirming.set(false)">Keep booking</button>
            } @else {
              <button type="button" class="danger" (click)="confirming.set(true)">Cancel selected</button>
            }
          }
        </section>
      }
      <!-- Outside the cancel section: a full cancellation hides that section, and this confirmation must survive it. -->
      @if (lastCancellation(); as c) {
        <p class="alert alert-info" role="status">Cancelled. Refund of
          {{ c.refundAmount | currency: 'INR' : 'symbol' : '1.2-2' : 'en-IN' }} requested to your original payment method.</p>
      }
    } @else if (!error()) {
      <p aria-busy="true">Loading booking…</p>
    }
  `,
})
export class BookingDetailPage {
  /** Bound from the route parameter via withComponentInputBinding(). */
  readonly pnr = input.required<string>();

  private readonly api = inject(ReservationApi);
  private readonly redirect = inject(PAYMENT_REDIRECT);
  private readonly cancelAction = new IdempotentAction();

  protected readonly booking = signal<BookingView | null>(null);
  protected readonly error = signal<UserFacingError | null>(null);
  protected readonly busy = signal(false);
  protected readonly selected = signal<Set<number>>(new Set());
  protected readonly quote = signal<CancellationView | null>(null);
  protected readonly quoteError = signal<UserFacingError | null>(null);
  protected readonly confirming = signal(false);
  protected readonly lastCancellation = signal<CancellationView | null>(null);

  protected readonly cancellable = computed(() => {
    const s = this.booking()?.status;
    return s === 'CONFIRMED' || s === 'PARTIALLY_CANCELLED';
  });

  /** Only paid bookings with a passenger still on the waitlist get an estimate request. */
  protected readonly waitlisted = computed(() => {
    const b = this.booking();
    return !!b && b.status !== 'PENDING_PAYMENT' && b.passengers.some(p => p.currentStatus.startsWith('WL'));
  });

  constructor() {
    effect(() => {
      const pnr = this.pnr();
      untracked(() => this.load(pnr));
    });
    // Re-quote whenever the selection changes. Stale responses are dropped by comparing the selection.
    effect(() => {
      const sel = [...this.selected()].sort();
      const pnr = this.pnr();
      untracked(() => {
        this.quote.set(null);
        this.quoteError.set(null);
        this.confirming.set(false);
        if (!sel.length) return;
        const current = () => [...this.selected()].sort().join() === sel.join();
        this.api.cancellationQuote(pnr, sel).subscribe({
          next: q => { if (current()) this.quote.set(q); },
          error: e => { if (current()) this.quoteError.set(toUserFacingError(e)); },
        });
      });
    });
  }

  isActive(status: string): boolean {
    return ACTIVE.includes(status.split('/')[0]);
  }

  toggle(seq: number): void {
    const next = new Set(this.selected());
    if (next.has(seq)) next.delete(seq); else next.add(seq);
    this.selected.set(next);
  }

  reload(): void {
    this.load(this.pnr());
  }

  pay(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.paymentIntent(this.pnr()).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: intent => {
        if (!this.redirect.go(intent.checkoutUrl)) {
          this.error.set({ code: 'PAYMENT_URL_REJECTED', message: 'The payment page address looked wrong, so we didn’t open it. Please contact support.', retryable: false, fieldErrors: [] });
        }
      },
      error: e => this.error.set(toUserFacingError(e)),
    });
  }

  cancel(): void {
    const seqs = [...this.selected()].sort();
    const key = this.cancelAction.keyFor({ pnr: this.pnr(), seqs });
    this.busy.set(true);
    this.error.set(null);
    this.api.cancel(this.pnr(), seqs, key).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: c => {
        this.cancelAction.reset();
        this.lastCancellation.set(c);
        this.selected.set(new Set());
        this.reload();
      },
      error: e => {
        const fe = toUserFacingError(e);
        if (!fe.retryable) this.cancelAction.reset();
        this.error.set(fe);
      },
    });
  }

  private load(pnr: string): void {
    this.api.booking(pnr).subscribe({
      next: b => { this.booking.set(b); this.error.set(null); },
      error: e => this.error.set(toUserFacingError(e)),
    });
  }
}
