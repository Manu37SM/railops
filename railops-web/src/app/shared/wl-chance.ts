import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { WlPrediction } from '../core/api/models';
import { ReservationApi } from '../core/api/reservation-api';

/**
 * "Chance of confirmation" for a waitlisted booking. Deliberately quiet: no estimate (feature off, not covered,
 * error) renders nothing rather than an error banner, because the estimate is optional and the booking is not.
 * Shown as a rounded percentage with a band and the server's disclaimer, never as a promise.
 */
@Component({
  selector: 'ro-wl-chance',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (prediction(); as p) {
      @if (p.probability != null) {
        <aside class="card wl-chance" data-testid="wl-chance" [attr.data-band]="p.band">
          <p>
            Chance of confirmation by chart time:
            <strong>about {{ percent(p.probability) }}%</strong>
            <span class="band">({{ label(p.band) }})</span>
            @if (p.currentWlPosition != null) { · currently WL {{ p.currentWlPosition }} }
          </p>
          <p class="hint">{{ p.disclaimer }}</p>
        </aside>
      }
    }
  `,
  styles: [`.wl-chance .band { margin-left: .25rem; } .hint { font-size: .85em; opacity: .8; }`],
})
export class WlChance {
  readonly pnr = input.required<string>();
  private readonly api = inject(ReservationApi);
  protected readonly prediction = signal<WlPrediction | null>(null);

  constructor() {
    effect(() => {
      const pnr = this.pnr();
      untracked(() => {
        this.prediction.set(null);
        this.api.wlPrediction(pnr).subscribe({
          next: p => this.prediction.set(p),
          error: () => this.prediction.set(null),
        });
      });
    });
  }

  /** Nearest 5%: the model is not precise to the percent, and "63%" invites false precision. */
  percent(p: number): number {
    return Math.min(95, Math.max(5, Math.round(p * 20) * 5));
  }

  label(band: WlPrediction['band']): string {
    return band === 'HIGH' ? 'likely' : band === 'MEDIUM' ? 'uncertain' : 'unlikely';
  }
}
