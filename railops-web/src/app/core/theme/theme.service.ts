import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';

export type ThemePreference = 'light' | 'dark' | 'system';

/** Same key and values as RailLens (lib/theme.ts), so both apps behave the same way. */
export const THEME_STORAGE_KEY = 'raillens-theme';
const ORDER: ThemePreference[] = ['system', 'light', 'dark'];

/**
 * Light / dark / follow-the-system theme, like RailLens. public/theme-init.js applies the saved choice before
 * Angular starts (an external file, because the CSP forbids inline scripts), so pages don't flash white.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  private readonly window = this.document.defaultView;
  private readonly media = this.window?.matchMedia?.('(prefers-color-scheme: dark)') ?? null;
  private readonly systemDark = signal(this.media?.matches ?? false);

  readonly preference = signal<ThemePreference>(this.readStored());
  readonly resolved = computed<'light' | 'dark'>(() => {
    const p = this.preference();
    return p === 'system' ? (this.systemDark() ? 'dark' : 'light') : p;
  });

  constructor() {
    const onSystem = (e: MediaQueryListEvent) => {
      this.systemDark.set(e.matches);
      this.apply();
    };
    const onStorage = (e: StorageEvent) => {
      if (e.key === THEME_STORAGE_KEY) {
        this.preference.set(this.readStored());
        this.apply();
      }
    };
    this.media?.addEventListener('change', onSystem);
    this.window?.addEventListener('storage', onStorage);
    inject(DestroyRef).onDestroy(() => {
      this.media?.removeEventListener('change', onSystem);
      this.window?.removeEventListener('storage', onStorage);
    });
    this.apply();
  }

  set(preference: ThemePreference): void {
    this.preference.set(preference);
    try {
      this.window?.localStorage.setItem(THEME_STORAGE_KEY, preference);
    } catch {
      // private mode or storage disabled: the choice lasts for this page only
    }
    this.apply();
  }

  /** System → Light → Dark → System. */
  cycle(): void {
    const next = ORDER[(ORDER.indexOf(this.preference()) + 1) % ORDER.length];
    this.set(next);
  }

  private apply(): void {
    this.document.documentElement.classList.toggle('dark', this.resolved() === 'dark');
  }

  private readStored(): ThemePreference {
    try {
      const stored = this.window?.localStorage.getItem(THEME_STORAGE_KEY);
      return stored === 'light' || stored === 'dark' || stored === 'system' ? stored : 'system';
    } catch {
      return 'system';
    }
  }
}
