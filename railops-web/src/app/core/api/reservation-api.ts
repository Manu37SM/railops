import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  BookingPage, BookingView, CancellationView, ChartResult, CreateBookingRequest, PaymentIntent, PoolOccupancy,
  RunAvailability, TravelClass, WlPrediction, ImportResult, ImportRunsRequest } from './models';

const BASE = '/api/v1';

/** Thin typed client over the reservation API. No business logic: the server owns the rules. */
@Injectable({ providedIn: 'root' })
export class ReservationApi {
  private readonly http = inject(HttpClient);

  availability(runId: number, from: number, to: number, cls?: TravelClass | null): Observable<RunAvailability> {
    let params = new HttpParams().set('from', from).set('to', to);
    if (cls) {
      params = params.set('class', cls);
    }
    return this.http.get<RunAvailability>(`${BASE}/runs/${runId}/availability`, { params });
  }

  createBooking(req: CreateBookingRequest, idempotencyKey: string): Observable<BookingView> {
    return this.http.post<BookingView>(`${BASE}/bookings`, req, { headers: { 'Idempotency-Key': idempotencyKey } });
  }

  booking(pnr: string): Observable<BookingView> {
    return this.http.get<BookingView>(`${BASE}/bookings/${encodeURIComponent(pnr)}`);
  }

  /** 404 also when the feature is switched off server-side: callers treat any error as "no estimate". */
  wlPrediction(pnr: string): Observable<WlPrediction> {
    return this.http.get<WlPrediction>(`${BASE}/bookings/${encodeURIComponent(pnr)}/wl-prediction`);
  }

  myBookings(cursor: string | null, limit = 10): Observable<BookingPage> {
    let params = new HttpParams().set('limit', limit);
    if (cursor) {
      params = params.set('cursor', cursor);
    }
    return this.http.get<BookingPage>(`${BASE}/bookings`, { params });
  }

  paymentIntent(pnr: string): Observable<PaymentIntent> {
    return this.http.post<PaymentIntent>(`${BASE}/bookings/${encodeURIComponent(pnr)}/payment-intent`, null);
  }

  cancellationQuote(pnr: string, seqs: number[]): Observable<CancellationView> {
    let params = new HttpParams();
    seqs.forEach(s => (params = params.append('passengers', s)));
    return this.http.get<CancellationView>(`${BASE}/bookings/${encodeURIComponent(pnr)}/cancellation-quote`, { params });
  }

  cancel(pnr: string, seqs: number[], idempotencyKey: string): Observable<CancellationView> {
    return this.http.post<CancellationView>(`${BASE}/bookings/${encodeURIComponent(pnr)}/cancellations`,
      { passengerSeqs: seqs }, { headers: { 'Idempotency-Key': idempotencyKey } });
  }

  occupancy(runId: number): Observable<PoolOccupancy[]> {
    return this.http.get<PoolOccupancy[]>(`${BASE}/staff/runs/${runId}/occupancy`);
  }

  /** Phase 3B: create dated runs of a train from its RailLens timetable (global admins). */
  importRuns(req: ImportRunsRequest): Observable<ImportResult> {
    return this.http.post<ImportResult>(`${BASE}/staff/runs/import`, req);
  }

  prepareChart(runId: number, chartNo: 1 | 2): Observable<ChartResult> {
    return this.http.post<ChartResult>(`${BASE}/staff/runs/${runId}/charts`, { chartNo });
  }
}
