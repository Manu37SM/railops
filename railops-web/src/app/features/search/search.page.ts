import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { toUserFacingError, UserFacingError } from '../../core/api/api-error';
import { PoolAvailability, RunAvailability, TravelClass } from '../../core/api/models';
import { ReservationApi } from '../../core/api/reservation-api';
import { AuthService } from '../../core/auth/auth.service';
import { ProblemAlert } from '../../shared/problem-alert';

/**
 * Availability lookup by run and stop sequence. Station-name search ("Mumbai Central → New Delhi on 20 Nov")
 * belongs to catalog-service, which isn't built yet; until then the run and stops are entered directly.
 */
@Component({
  selector: 'ro-search-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, ProblemAlert],
  template: `
    <section class="card">
      <h1>Check availability</h1>
      <form class="row" [formGroup]="form" (ngSubmit)="search()" novalidate>
        <div><label for="run">Train run</label><input id="run" type="number" min="1" formControlName="runId" /></div>
        <div><label for="from">From stop</label><input id="from" type="number" min="0" formControlName="from" /></div>
        <div><label for="to">To stop</label><input id="to" type="number" min="1" formControlName="to" /></div>
        <div>
          <label for="cls">Class</label>
          <select id="cls" formControlName="cls">
            <option value="">All classes</option>
            @for (c of classes; track c) { <option [value]="c">{{ c }}</option> }
          </select>
        </div>
        <div class="align-end"><button type="submit" [disabled]="busy() || form.invalid">Search</button></div>
      </form>
      <ro-problem-alert [error]="error()" />
    </section>

    @if (result(); as r) {
      <section class="card" aria-live="polite">
        <h2>Run {{ r.trainRunId }}, stops {{ r.fromSeq }} → {{ r.toSeq }}</h2>
        @if (!r.pools.length) {
          <p>No classes are offered on this journey.</p>
        } @else {
          <table>
            <caption class="sr-only">Availability by class and quota</caption>
            <thead><tr><th scope="col">Class</th><th scope="col">Quota</th><th scope="col">Status</th><th scope="col"></th></tr></thead>
            <tbody>
              @for (p of r.pools; track p.classCode + p.quotaCode) {
                <tr>
                  <td>{{ p.classCode }}</td>
                  <td>{{ p.quotaCode }}</td>
                  <td><span class="avail" [class.ok]="p.display.startsWith('AVAILABLE')">{{ p.display }}</span></td>
                  <td>
                    @if (canBook(p)) {
                      <button type="button" class="secondary" (click)="book(r, p)">Book</button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
          <p class="hint">Availability changes quickly; seats are confirmed only when you book.</p>
        }
      </section>
    }
  `,
})
export class SearchPage {
  private readonly api = inject(ReservationApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly classes: TravelClass[] = ['1A', '2A', '3A', '3E', 'SL', 'CC', 'EC', '2S'];
  protected readonly busy = signal(false);
  protected readonly error = signal<UserFacingError | null>(null);
  protected readonly result = signal<RunAvailability | null>(null);
  protected readonly form = this.fb.group({
    runId: [1, [Validators.required, Validators.min(1)]],
    from: [0, [Validators.required, Validators.min(0)]],
    to: [4, [Validators.required, Validators.min(1)]],
    cls: ['' as TravelClass | ''],
  });

  search(): void {
    const v = this.form.getRawValue();
    if (v.to <= v.from) {
      this.error.set({ code: 'CLIENT', message: 'The destination stop must come after the boarding stop.', retryable: false, fieldErrors: [] });
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.availability(v.runId, v.from, v.to, v.cls || null)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({ next: r => this.result.set(r), error: e => this.error.set(toUserFacingError(e)) });
  }

  canBook(p: PoolAvailability): boolean {
    return p.display !== 'REGRET' && p.display !== 'BOOKING_CLOSED';
  }

  book(r: RunAvailability, p: PoolAvailability): void {
    const target = ['/book'];
    const queryParams = { run: r.trainRunId, from: r.fromSeq, to: r.toSeq, cls: p.classCode, quota: p.quotaCode };
    if (!this.auth.isLoggedIn()) {
      const returnUrl = this.router.serializeUrl(this.router.createUrlTree(target, { queryParams }));
      void this.router.navigate(['/login'], { queryParams: { returnUrl } });
      return;
    }
    void this.router.navigate(target, { queryParams });
  }
}
