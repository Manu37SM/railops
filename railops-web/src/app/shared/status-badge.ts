import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Colour AND text: colour alone fails WCAG 1.4.1 (and colour-blind passengers can't tell CNF from WL).
 * Accepts passenger statuses ("CNF/B1/12/LB", "WL/3") or booking statuses ("PENDING_PAYMENT").
 */
@Component({
  selector: 'ro-status',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge" [class]="'badge badge-' + tone()">{{ label() }}</span>`,
})
export class StatusBadge {
  readonly value = input.required<string>();

  protected readonly kind = computed(() => this.value().split('/')[0]);
  protected readonly label = computed(() => {
    const v = this.value();
    const k = this.kind();
    switch (k) {
      case 'CNF': {
        const [, coach, berth, type] = v.split('/');
        return coach ? `Confirmed · ${coach} ${berth} ${type ?? ''}`.trim() : 'Confirmed';
      }
      case 'RAC': return `RAC · ${v.split('/').slice(1).join(' ')}`.trim();
      case 'WL': return `Waitlist ${v.split('/')[1] ?? ''}`.trim();
      case 'NOSB': return 'Child, no berth';
      case 'CAN': return 'Cancelled';
      case 'CHART_CAN': return 'Not confirmed at chart';
      default: return v.replaceAll('_', ' ').toLowerCase().replace(/^./, c => c.toUpperCase());
    }
  });
  protected readonly tone = computed(() => {
    const k = this.kind();
    if (['CNF', 'CONFIRMED', 'NOSB'].includes(k)) return 'ok';
    if (['RAC', 'WL', 'PENDING_PAYMENT', 'PARTIALLY_CANCELLED'].includes(k)) return 'warn';
    return 'muted';
  });
}
