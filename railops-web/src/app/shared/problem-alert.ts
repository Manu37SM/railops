import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { UserFacingError } from '../core/api/api-error';

/** Error banner. role="alert" makes screen readers announce it as soon as it appears. */
@Component({
  selector: 'ro-problem-alert',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (error(); as e) {
      <div class="alert alert-error" role="alert" [attr.data-code]="e.code">
        <p>{{ e.message }}</p>
        @if (e.fieldErrors.length) {
          <ul>
            @for (f of e.fieldErrors; track f.field) {
              <li><strong>{{ f.field }}</strong>: {{ f.message }}</li>
            }
          </ul>
        }
        @if (e.retryable) {
          <p class="hint">This is usually temporary; retrying is safe.</p>
        }
      </div>
    }
  `,
})
export class ProblemAlert {
  readonly error = input<UserFacingError | null>(null);
}
