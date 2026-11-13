import { TestBed } from '@angular/core/testing';
import { THEME_STORAGE_KEY, ThemeService } from './theme.service';
import { ThemeToggle } from './theme-toggle';

describe('ThemeService', () => {
  const html = document.documentElement;
  let systemDark = false;
  let listeners: ((e: MediaQueryListEvent) => void)[] = [];

  beforeEach(() => {
    localStorage.clear();
    html.classList.remove('dark');
    systemDark = false;
    listeners = [];
    window.matchMedia = ((query: string) => ({
      get matches() { return systemDark; },
      media: query,
      addEventListener: (_: string, l: (e: MediaQueryListEvent) => void) => listeners.push(l),
      removeEventListener: () => {},
    })) as unknown as typeof window.matchMedia;
    TestBed.resetTestingModule();
  });

  it('defaults to following the system setting', () => {
    systemDark = true;
    const theme = TestBed.inject(ThemeService);
    expect(theme.preference()).toBe('system');
    expect(html.classList.contains('dark')).toBe(true);
  });

  it('follows the system when it changes, while on "system"', () => {
    TestBed.inject(ThemeService);
    expect(html.classList.contains('dark')).toBe(false);
    systemDark = true;
    listeners.forEach((l) => l({ matches: true } as MediaQueryListEvent));
    expect(html.classList.contains('dark')).toBe(true);
  });

  it('an explicit choice wins over the system and is saved under the RailLens key', () => {
    systemDark = true;
    const theme = TestBed.inject(ThemeService);
    theme.set('light');
    expect(html.classList.contains('dark')).toBe(false);
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light');
  });

  it('reads a saved choice and ignores junk values', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    expect(TestBed.inject(ThemeService).resolved()).toBe('dark');
    TestBed.resetTestingModule();
    localStorage.setItem(THEME_STORAGE_KEY, 'purple');
    expect(TestBed.inject(ThemeService).preference()).toBe('system');
  });

  it('the toggle cycles System → Light → Dark → System and says which one is on', () => {
    const f = TestBed.createComponent(ThemeToggle);
    f.detectChanges();
    const button: HTMLButtonElement = f.nativeElement.querySelector('button');
    expect(button.getAttribute('aria-label')).toBe('Theme: System. Change theme');
    for (const expected of ['Light', 'Dark', 'System']) {
      button.click();
      f.detectChanges();
      expect(button.textContent?.trim()).toBe(expected);
    }
  });
});
