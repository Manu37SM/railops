import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toUserFacingError, UserFacingError } from '../../core/api/api-error';
import { ChartResult, PoolOccupancy } from '../../core/api/models';
import { ReservationApi } from '../../core/api/reservation-api';
import { AuthService } from '../../core/auth/auth.service';
import { ProblemAlert } from '../../shared/problem-alert';
import { MoveAccounts } from './move-accounts';
import { OpenTrain } from './open-train';

/**
 * Staff run view. The chart button is shown only to roles that may prepare charts; a station master who
 * crafts the request anyway still gets a 403 from the API, which is what actually enforces it.
 */
@Component({
  selector: 'ro-run-operations-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, ProblemAlert, OpenTrain, MoveAccounts],
  template: `
    <section class="card">
      <h1>Run operations</h1>
      <p class="hint">Your scope: {{ grants() || 'none' }}</p>
      <form class="row" [formGroup]="form" (ngSubmit)="load()" novalidate>
        <div><label for="run">Train run</label><input id="run" type="number" min="1" formControlName="runId" /></div>
        <div class="align-end"><button type="submit" [disabled]="busy()">Show occupancy</button></div>
      </form>
      <ro-problem-alert [error]="error()" />
    </section>

    @if (pools(); as rows) {
      <section class="card" aria-live="polite">
        <h2>Run {{ loadedRun() }} occupancy</h2>
        <table data-testid="occupancy">
          <caption class="sr-only">Occupancy per class and quota (peak segment)</caption>
          <thead><tr><th scope="col">Class</th><th scope="col">Quota</th><th scope="col">Confirmed</th>
            <th scope="col">RAC slots</th><th scope="col">WL issued</th></tr></thead>
          <tbody>
            @for (p of rows; track p.classCode + p.quotaCode) {
              <tr><td>{{ p.classCode }}</td><td>{{ p.quotaCode }}</td>
                <td>{{ p.cnfPeakUsed }} / {{ p.cnfCapacity }}</td><td>{{ p.racPeakUsed }} / {{ p.racCapacity }}</td>
                <td>{{ p.wlIssued }}</td></tr>
            }
          </tbody>
        </table>
        @if (canChart()) {
          <div class="actions">
            <button type="button" (click)="chart(1)" [disabled]="busy()">Prepare chart 1</button>
            <button type="button" class="secondary" (click)="chart(2)" [disabled]="busy()">Prepare chart 2</button>
          </div>
        }
        @if (chartResult(); as c) {
          <p class="alert alert-info" role="status" data-testid="chart-result">
            Chart {{ c.chartNo }}: {{ c.status }} · promoted {{ c.promoted }} · WL dropped {{ c.droppedWlPassengers }}
            · bookings chart-cancelled {{ c.chartCancelledBookings }} · unpaid holds released {{ c.expiredHolds }}</p>
        }
      </section>
    }

    @if (canOpenTrains()) {
      <ro-open-train />
      <ro-move-accounts />
    }
  `,
})
export class RunOperationsPage {
  private readonly api = inject(ReservationApi);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly form = this.fb.group({ runId: [1, [Validators.required, Validators.min(1)]] });
  protected readonly pools = signal<PoolOccupancy[] | null>(null);
  protected readonly loadedRun = signal<number | null>(null);
  protected readonly chartResult = signal<ChartResult | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<UserFacingError | null>(null);
  protected readonly canChart = computed(() => this.auth.hasAnyRole('CONTROLLER', 'ADMIN'));
  /** Only a GLOBAL admin may create runs (the API checks the same thing). */
  protected readonly canOpenTrains = computed(() => (this.auth.claims()?.grants ?? []).includes('ADMIN:GLOBAL:*'));
  protected readonly grants = computed(() => (this.auth.claims()?.grants ?? []).join(', '));

  load(): void {
    const runId = this.form.getRawValue().runId;
    this.busy.set(true);
    this.error.set(null);
    this.chartResult.set(null);
    this.api.occupancy(runId).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: rows => { this.pools.set(rows); this.loadedRun.set(runId); },
      error: e => { this.pools.set(null); this.error.set(toUserFacingError(e)); },
    });
  }

  chart(no: 1 | 2): void {
    const runId = this.loadedRun();
    if (!runId) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.prepareChart(runId, no).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: r => { this.chartResult.set(r); this.load(); },
      error: e => this.error.set(toUserFacingError(e)),
    });
  }
}
