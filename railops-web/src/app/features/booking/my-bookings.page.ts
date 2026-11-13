import { CurrencyPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { toUserFacingError, UserFacingError } from '../../core/api/api-error';
import { BookingSummary } from '../../core/api/models';
import { ReservationApi } from '../../core/api/reservation-api';
import { ProblemAlert } from '../../shared/problem-alert';
import { StatusBadge } from '../../shared/status-badge';

/** "Load more" over the keyset cursor: appends pages instead of numbered pages, which keyset can't offer. */
@Component({
  selector: 'ro-my-bookings-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CurrencyPipe, RouterLink, ProblemAlert, StatusBadge],
  template: `
    <section class="card">
      <h1>My bookings</h1>
      <ro-problem-alert [error]="error()" />
      @if (items().length === 0 && !busy()) {
        <p>No bookings yet. <a routerLink="/search">Check availability</a></p>
      }
      <ul class="bookings">
        @for (b of items(); track b.pnr) {
          <li>
            <a [routerLink]="['/bookings', b.pnr]">PNR {{ b.pnr }}</a>
            <span>Run {{ b.trainRunId }} · {{ b.runDate }} · {{ b.classCode }}</span>
            <span>{{ b.totalFare | currency: 'INR' : 'symbol' : '1.2-2' : 'en-IN' }}</span>
            <ro-status [value]="b.status" />
          </li>
        }
      </ul>
      @if (cursor()) {
        <button type="button" class="secondary" (click)="loadMore()" [disabled]="busy()">Load more</button>
      }
    </section>
  `,
})
export class MyBookingsPage {
  private readonly api = inject(ReservationApi);
  protected readonly items = signal<BookingSummary[]>([]);
  protected readonly cursor = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<UserFacingError | null>(null);

  constructor() {
    this.fetch(null);
  }

  loadMore(): void {
    this.fetch(this.cursor());
  }

  private fetch(cursor: string | null): void {
    this.busy.set(true);
    this.api.myBookings(cursor).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: page => {
        // A booking created between page loads could reappear at a page boundary; dedupe by PNR.
        const seen = new Set(this.items().map(i => i.pnr));
        this.items.update(list => [...list, ...page.items.filter(i => !seen.has(i.pnr))]);
        this.cursor.set(page.nextCursor ?? null);
      },
      error: e => this.error.set(toUserFacingError(e)),
    });
  }
}
