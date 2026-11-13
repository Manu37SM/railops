import { Routes } from '@angular/router';
import { authGuard, passengerGuard, staffGuard } from './core/auth/auth.guards';

/** Every feature is lazy-loaded: a passenger never downloads the staff screens, and vice versa. */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'search' },
  { path: 'login', title: 'Log in · RailOps', loadComponent: () => import('./features/auth/login.page').then(m => m.LoginPage) },
  { path: 'register', title: 'Register · RailOps', loadComponent: () => import('./features/auth/register.page').then(m => m.RegisterPage) },
  { path: 'search', title: 'Availability · RailOps', loadComponent: () => import('./features/search/search.page').then(m => m.SearchPage) },
  {
    path: 'book', title: 'Book · RailOps', canActivate: [passengerGuard],
    loadComponent: () => import('./features/booking/booking-form.page').then(m => m.BookingFormPage),
  },
  {
    path: 'bookings', title: 'My bookings · RailOps', canActivate: [passengerGuard],
    loadComponent: () => import('./features/booking/my-bookings.page').then(m => m.MyBookingsPage),
  },
  {
    path: 'bookings/:pnr', title: 'Booking · RailOps', canActivate: [passengerGuard],
    loadComponent: () => import('./features/booking/booking-detail.page').then(m => m.BookingDetailPage),
  },
  {
    path: 'staff', title: 'Run operations · RailOps', canActivate: [staffGuard],
    loadComponent: () => import('./features/staff/run-operations.page').then(m => m.RunOperationsPage),
  },
  {
    path: 'account/password', title: 'Change password · RailOps', canActivate: [authGuard],
    loadComponent: () => import('./features/account/change-password.page').then(m => m.ChangePasswordPage),
  },
  { path: '**', redirectTo: 'search' },
];
