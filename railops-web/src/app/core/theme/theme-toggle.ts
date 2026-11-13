import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { ThemeService } from './theme.service';

const LABELS = { system: 'System', light: 'Light', dark: 'Dark' } as const;

@Component({
  selector: 'app-theme-toggle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button type="button" class="theme-toggle" (click)="theme.cycle()"
            [attr.aria-label]="'Theme: ' + label() + '. Change theme'" [title]="'Theme: ' + label()">
      <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
           stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        @switch (theme.preference()) {
          @case ('light') { <circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/> }
          @case ('dark') { <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/> }
          @default { <rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/> }
        }
      </svg>
      <span>{{ label() }}</span>
    </button>
  `,
})
export class ThemeToggle {
  protected readonly theme = inject(ThemeService);
  protected readonly label = computed(() => LABELS[this.theme.preference()]);
}
