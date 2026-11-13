import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, output, signal } from '@angular/core';
import { ServerClock } from '../core/api/server-clock';

/**
 * Seat-hold countdown against the SERVER clock (see ServerClock). The visible text updates every second,
 * but the screen-reader live region only announces at minute boundaries and the final 30 s, so it
 * doesn't talk over everything else on the page.
 */
@Component({
  selector: 'ro-hold-countdown',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="countdown" [class.urgent]="remainingMs() < 60_000">
      @if (remainingMs() > 0) {
        <span aria-hidden="true">Seats held for {{ text() }}</span>
      } @else {
        <span>Hold expired</span>
      }
      <span class="sr-only" aria-live="polite">{{ announcement() }}</span>
    </div>
  `,
})
export class HoldCountdown {
  readonly expiresAt = input.required<string>();
  readonly expired = output<void>();

  private readonly clock = inject(ServerClock);
  private readonly tick = signal(this.clock.now());

  protected readonly remainingMs = computed(() => Math.max(0, Date.parse(this.expiresAt()) - this.tick()));
  protected readonly text = computed(() => {
    const s = Math.ceil(this.remainingMs() / 1000);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  });
  protected readonly announcement = computed(() => {
    const s = Math.ceil(this.remainingMs() / 1000);
    if (s === 0) return 'Seat hold expired';
    if (s <= 30) return `${s} seconds left to pay`;
    return s % 60 === 0 ? `${s / 60} minutes left to pay` : '';
  });

  constructor() {
    let fired = false;
    const id = setInterval(() => {
      this.tick.set(this.clock.now());
      if (!fired && this.remainingMs() === 0) {
        fired = true;
        this.expired.emit();
      }
    }, 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(id));
  }
}
