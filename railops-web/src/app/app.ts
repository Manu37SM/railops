import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth/auth.service';
import { ThemeToggle } from './core/theme/theme-toggle';

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ThemeToggle],
  template: `
    <a class="skip-link" href="#main">Skip to content</a>
    <header class="topbar">
      <a routerLink="/" class="brand" aria-label="RailOps home">
        <span class="brand-mark" aria-hidden="true">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round">
            <rect x="5" y="3" width="14" height="14" rx="3"/><path d="M5 11h14M9 21l-2-4M15 21l2-4"/>
            <circle cx="9" cy="14" r="0.5"/><circle cx="15" cy="14" r="0.5"/>
          </svg>
        </span>
        <span>RailOps<small>by RailLens</small></span>
      </a>
      <nav aria-label="Main">
        <a routerLink="/search" routerLinkActive="active">Availability</a>
        @if (auth.isLoggedIn() && !auth.isStaff()) {
          <a routerLink="/bookings" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">My bookings</a>
        }
        @if (auth.isStaff()) {
          <a routerLink="/staff" routerLinkActive="active">Run operations</a>
        }
      </nav>
      <div class="session">
        <app-theme-toggle />
        @if (auth.isLoggedIn()) {
          <a routerLink="/account/password" routerLinkActive="active">Password</a>
          <button type="button" class="link" (click)="auth.logout()">Log out</button>
        } @else {
          <a routerLink="/login">Log in</a>
        }
      </div>
    </header>
    <main id="main" tabindex="-1"><router-outlet /></main>
  `,
})
export class App {
  protected readonly auth = inject(AuthService);
}
