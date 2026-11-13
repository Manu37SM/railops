import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toUserFacingError, UserFacingError } from '../../core/api/api-error';
import { ImportResult, ImportRunsRequest } from '../../core/api/models';
import { ReservationApi } from '../../core/api/reservation-api';
import { ProblemAlert } from '../../shared/problem-alert';

const CLASSES = ['1A', '2A', '3A', '3E', 'SL', 'CC', 'EC', '2S'] as const;
type Cls = (typeof CLASSES)[number];

/**
 * Standard coach sets (Phase 6). Same numbers as RunPlanner.STANDARD_CONSISTS on the server (which API clients can
 * name directly); here a set only fills in the counts, so staff can still adjust them before opening.
 */
export const COACH_SETS: Record<string, { label: string; coaches: Partial<Record<Cls, number>> }> = {
  RAJDHANI: { label: 'Rajdhani', coaches: { '1A': 1, '2A': 4, '3A': 11 } },
  DURONTO: { label: 'Duronto', coaches: { '1A': 1, '2A': 3, '3A': 8, SL: 4 } },
  MAIL_EXPRESS: { label: 'Mail / Express', coaches: { '2A': 2, '3A': 6, '3E': 1, SL: 8, '2S': 2 } },
  SHATABDI: { label: 'Shatabdi', coaches: { EC: 2, CC: 12 } },
  VANDE_BHARAT: { label: 'Vande Bharat', coaches: { EC: 2, CC: 14 } },
  JAN_SHATABDI: { label: 'Jan Shatabdi', coaches: { CC: 4, '2S': 10 } },
  GARIB_RATH: { label: 'Garib Rath', coaches: { '3E': 12 } },
};

/**
 * Opens a train for booking on a range of dates, from its RailLens timetable (Phase 3B). Shown only to admins with
 * a GLOBAL grant; the API enforces that too. Safe to submit twice: existing dates are left unchanged.
 */
@Component({
  selector: 'ro-open-train',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, ProblemAlert],
  template: `
    <section class="card">
      <h2>Open a train for booking</h2>
      <p class="hint">Reads the train's timetable from RailLens and creates one run per date with these coaches.
        Dates that already have a run are left as they are.</p>
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="row">
          <div class="grow"><label for="ot-train">Train number</label>
            <input id="ot-train" formControlName="trainNo" inputmode="numeric" maxlength="5" placeholder="12951" /></div>
          <div class="grow"><label for="ot-from">First date</label><input id="ot-from" type="date" formControlName="fromDate" /></div>
          <div class="grow"><label for="ot-to">Last date</label><input id="ot-to" type="date" formControlName="toDate" /></div>
        </div>
        <div><label for="ot-set">Standard coach set</label>
          <select id="ot-set" (change)="applySet($any($event.target).value)">
            <option value="">Choose to fill in the counts…</option>
            @for (s of setNames; track s) { <option [value]="s">{{ sets[s].label }}</option> }
          </select></div>
        <fieldset>
          <legend>Coaches per class</legend>
          <div class="row">
            @for (c of classes; track c) {
              <div><label [for]="'ot-' + c">{{ c }}</label>
                <input [id]="'ot-' + c" type="number" min="0" max="24" [formControlName]="c" /></div>
            }
            <div><label for="ot-tq">Tatkal %</label><input id="ot-tq" type="number" min="0" max="50" formControlName="tatkalPercent" /></div>
          </div>
        </fieldset>
        @if (formError(); as m) { <p class="field-error" role="alert">{{ m }}</p> }
        <button type="submit" [disabled]="busy()">{{ busy() ? 'Opening… (RailLens may take a minute to wake)' : 'Open for booking' }}</button>
      </form>
      <ro-problem-alert [error]="error()" />
      @if (result(); as r) {
        <div aria-live="polite" data-testid="import-result">
          <h3>{{ r.trainNo }} {{ r.trainName }} · {{ r.stops }} stops</h3>
          <table>
            <thead><tr><th scope="col">Date</th><th scope="col">Result</th><th scope="col">Run</th><th scope="col">Note</th></tr></thead>
            <tbody>
              @for (d of r.dates; track d.runDate) {
                <tr><td>{{ d.runDate }}</td>
                  <td><span class="badge" [class.badge-ok]="d.outcome === 'CREATED'" [class.badge-muted]="d.outcome !== 'CREATED'">{{ label(d.outcome) }}</span></td>
                  <td>{{ d.trainRunId ?? '–' }}</td><td>{{ d.detail ?? '' }}</td></tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
})
export class OpenTrain {
  private readonly api = inject(ReservationApi);
  protected readonly classes = CLASSES;
  protected readonly sets = COACH_SETS;
  protected readonly setNames = Object.keys(COACH_SETS);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    trainNo: ['', [Validators.required, Validators.pattern(/^\d{5}$/)]],
    fromDate: ['', Validators.required],
    toDate: ['', Validators.required],
    '1A': [0], '2A': [0], '3A': [0], '3E': [0], SL: [0], CC: [0], EC: [0], '2S': [0],
    tatkalPercent: [10],
  });
  protected readonly busy = signal(false);
  protected readonly error = signal<UserFacingError | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly result = signal<ImportResult | null>(null);

  protected label(outcome: string): string {
    return outcome === 'CREATED' ? 'Opened' : outcome === 'ALREADY_EXISTS' ? 'Already open' : 'Skipped';
  }

  applySet(name: string): void {
    const set = COACH_SETS[name];
    if (!set) return;
    this.form.patchValue(Object.fromEntries(CLASSES.map(c => [c, set.coaches[c] ?? 0])));
  }

  submit(): void {
    const v = this.form.getRawValue();
    const coaches = CLASSES.filter(c => Number(v[c]) > 0).map(c => ({ travelClass: c, count: Number(v[c]) }));
    if (this.form.invalid) {
      this.formError.set('Enter a 5-digit train number and both dates.');
      return;
    }
    if (coaches.length === 0) {
      this.formError.set('Add at least one coach.');
      return;
    }
    if (v.toDate < v.fromDate) {
      this.formError.set('The last date must be on or after the first date.');
      return;
    }
    this.formError.set(null);
    const req: ImportRunsRequest = { trainNo: v.trainNo, fromDate: v.fromDate, toDate: v.toDate, coaches,
      tatkalPercent: Number(v.tatkalPercent) };
    this.busy.set(true);
    this.error.set(null);
    this.result.set(null);
    this.api.importRuns(req).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: r => this.result.set(r),
      error: e => this.error.set(toUserFacingError(e)),
    });
  }
}
