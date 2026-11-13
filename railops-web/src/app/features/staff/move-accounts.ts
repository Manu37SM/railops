import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { toUserFacingError, UserFacingError } from '../../core/api/api-error';
import { ProblemAlert } from '../../shared/problem-alert';

/** Summary from POST /api/v1/admin/raillens/account-move (identity service, Phase 4C). */
export interface AccountMoveSummary {
  examined: number;
  created: number;
  alreadyMoved: number;
  linkedInRailLens: number;
  emailTaken: number;
  emailTakenSample: string[];
  invalid: number[];
  raillensAdmins: number[];
  linkConflicts: number[];
}

/**
 * Moves RailLens accounts into the RailOps login (single login, Phase 4C). GLOBAL admins only (the API checks the
 * same, plus MFA). Safe to press again: already-moved accounts are skipped.
 */
@Component({
  selector: 'ro-move-accounts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ProblemAlert],
  template: `
    <section class="card">
      <h2>Move RailLens accounts</h2>
      <p class="hint">Copies RailLens accounts into this login with the same email and password, so people can use one
        account for both. Accounts already moved are skipped, and an email that already has an account here is listed,
        not merged.</p>
      @if (!confirming()) {
        <button type="button" class="secondary" (click)="confirming.set(true)" [disabled]="busy()">Move accounts…</button>
      } @else {
        <p class="alert alert-info">This creates accounts here for every RailLens account not moved yet. Continue?</p>
        <div class="actions">
          <button type="button" (click)="run()" [disabled]="busy()">{{ busy() ? 'Moving… (RailLens may take a minute to wake)' : 'Yes, move them' }}</button>
          <button type="button" class="secondary" (click)="confirming.set(false)" [disabled]="busy()">Cancel</button>
        </div>
      }
      <ro-problem-alert [error]="error()" />
      @if (summary(); as s) {
        <div aria-live="polite" data-testid="move-summary">
          <dl class="quote">
            <dt>Looked at</dt><dd>{{ s.examined }}</dd>
            <dt>Moved now</dt><dd>{{ s.created }}</dd>
            <dt>Moved before</dt><dd>{{ s.alreadyMoved }}</dd>
            <dt>Email already here</dt><dd>{{ s.emailTaken }}</dd>
            <dt>Couldn't move</dt><dd>{{ s.invalid.length }}</dd>
          </dl>
          @if (s.raillensAdmins.length) {
            <p class="alert alert-info">RailLens admins (RailLens ids {{ s.raillensAdmins.join(', ') }}) were moved as
              passengers. Give them staff roles here yourself if they need them.</p>
          }
          @if (s.emailTakenSample.length) {
            <p class="hint">Email already registered here (RailLens id, masked email): {{ s.emailTakenSample.join(' · ') }}.
              These people keep separate accounts until someone checks it's really the same person.</p>
          }
          @if (s.linkConflicts.length) {
            <p class="field-error">RailLens refused links for ids {{ s.linkConflicts.join(', ') }}. Check them before retrying.</p>
          }
        </div>
      }
    </section>
  `,
})
export class MoveAccounts {
  private readonly http = inject(HttpClient);
  protected readonly confirming = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<UserFacingError | null>(null);
  protected readonly summary = signal<AccountMoveSummary | null>(null);

  run(): void {
    this.busy.set(true);
    this.error.set(null);
    this.http.post<AccountMoveSummary>('/api/v1/admin/raillens/account-move', {})
      .pipe(finalize(() => { this.busy.set(false); this.confirming.set(false); }))
      .subscribe({
        next: s => this.summary.set(s),
        error: e => this.error.set(toUserFacingError(e)),
      });
  }
}
