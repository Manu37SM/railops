// Mirrors docs/openapi/reservation-v1.yaml and identity-v1.yaml. Keep in sync when the contract changes;
// Phase 11 adds a contract test that diffs these against the exported spec.

export type TravelClass = '1A' | '2A' | '3A' | '3E' | 'SL' | 'CC' | 'EC' | '2S';
export type Quota = 'GN' | 'LD' | 'SS' | 'TQ' | 'PT' | 'DF' | 'FT' | 'HP' | 'PQ' | 'RL';
export type BerthType = 'LB' | 'MB' | 'UB' | 'SL' | 'SU' | 'WS' | 'MS' | 'AS';
export type BookingStatus =
  | 'PENDING_PAYMENT' | 'CONFIRMED' | 'PARTIALLY_CANCELLED' | 'CANCELLED'
  | 'EXPIRED' | 'FAILED_REFUNDING' | 'CHART_CANCELLED' | 'RUN_CANCELLED';

/** RFC 9457 problem details as returned by every RailOps service. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  code?: string;
  errors?: { field: string; message: string }[];
}

export interface PoolAvailability {
  classCode: TravelClass;
  quotaCode: Quota;
  cnfFree: number;
  racSlotsFree: number;
  nextWlNumber: number;
  wlRemaining: number;
  display: string; // AVAILABLE-n | RAC-n | WL-n | REGRET | BOOKING_CLOSED
}

export interface RunAvailability {
  trainRunId: number;
  runStatus: string;
  fromSeq: number;
  toSeq: number;
  pools: PoolAvailability[];
}

export interface PassengerRequest {
  name: string;
  age: number;
  gender: 'M' | 'F' | 'T';
  berthPreference?: BerthType | null;
  berthRequired?: boolean;
}

export interface CreateBookingRequest {
  trainRunId: number;
  fromStopSeq: number;
  toStopSeq: number;
  travelClass: TravelClass;
  quota: Quota;
  passengers: PassengerRequest[];
  onlyIfConfirmed: boolean;
  contactPhone: string;
  contactEmail?: string | null;
}

export interface PassengerView {
  seqNo: number;
  name: string;
  age: number;
  gender: string;
  bookingStatus: string;
  currentStatus: string;
}

export interface BookingView {
  pnr: string;
  status: BookingStatus;
  trainRunId: number;
  runDate: string;
  classCode: TravelClass;
  quotaCode: Quota;
  fromSeq: number;
  toSeq: number;
  totalFare: number;
  holdExpiresAt?: string;
  passengers: PassengerView[];
}

export interface BookingSummary {
  pnr: string;
  status: BookingStatus;
  trainRunId: number;
  runDate: string;
  classCode: TravelClass;
  quotaCode: Quota;
  fromSeq: number;
  toSeq: number;
  totalFare: number;
  createdAt: string;
}

export interface BookingPage {
  items: BookingSummary[];
  nextCursor?: string | null;
}

export interface PaymentIntent {
  paymentId: string;
  checkoutUrl: string;
  amount: number;
  status: string;
}

export interface PassengerRefund {
  seqNo: number;
  paid: number;
  charge: number;
  refund: number;
  rule: string;
}

export interface CancellationView {
  cancellationId?: string | null;
  pnr: string;
  bookingStatus: string;
  cancelled: number[];
  totalPaid: number;
  totalCharge: number;
  refundAmount: number;
  breakdown: PassengerRefund[];
}

export interface PoolOccupancy {
  classCode: string;
  quotaCode: string;
  cnfCapacity: number;
  cnfPeakUsed: number;
  racCapacity: number;
  racPeakUsed: number;
  wlIssued: number;
}

export interface ChartResult {
  runId: number;
  chartNo: number;
  status: string;
  promoted: number;
  chartCancelledBookings: number;
  droppedWlPassengers: number;
  expiredHolds: number;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
}

export interface MfaEnrolmentRequired {
  status: 'MFA_ENROLMENT_REQUIRED';
  enrolmentToken: string;
}

export interface MfaEnrolment {
  secretBase32: string;
  otpauthUri: string;
}

/** Decoded access-token claims the UI uses. Never trusted for security: the API enforces everything. */
export interface SessionClaims {
  sub: string;
  utyp: 'PASSENGER' | 'AGENT' | 'STAFF';
  roles: string[];
  grants: string[];
  amr: string[];
  exp: number;
}

/**
 * Advisory waitlist estimate. The server omits null fields (non_null), so "no estimate" arrives as a MISSING
 * probability plus a reason: always test with `== null`, never `=== null`. Always show the disclaimer.
 */
export interface WlPrediction {
  probability?: number | null;
  band?: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  modelVersion: string;
  currentWlPosition?: number | null;
  reason?: 'NOT_WAITLISTED' | 'NOT_COVERED' | 'CHART_PREPARED' | 'UNAVAILABLE' | null;
  disclaimer: string;
}

/** POST /staff/runs/import (Phase 3B; all classes since Phase 6). Either coaches or a standard `consist`. */
export interface ImportRunsRequest {
  trainNo: string;
  fromDate: string;
  toDate: string;
  coaches?: { travelClass: TravelClass; count: number }[];
  consist?: string;
  tatkalPercent?: number;
  waitlistPercent?: number;
}

export interface ImportResult {
  trainNo: string;
  trainName: string;
  stops: number;
  dates: { runDate: string; outcome: 'CREATED' | 'ALREADY_EXISTS' | 'SKIPPED'; trainRunId: number | null; detail: string | null }[];
}
