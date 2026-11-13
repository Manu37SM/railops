import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl, FormArray, FormBuilder, FormGroup, ReactiveFormsModule, ValidationErrors, Validators,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize } from 'rxjs';
import { toUserFacingError, UserFacingError } from '../../core/api/api-error';
import { IdempotentAction } from '../../core/api/idempotency';
import { BerthType, CreateBookingRequest, Quota, TravelClass } from '../../core/api/models';
import { ReservationApi } from '../../core/api/reservation-api';
import { ProblemAlert } from '../../shared/problem-alert';

export const MAX_PASSENGERS = 6;
const SEATED: TravelClass[] = ['CC', 'EC', '2S'];

/**
 * Group-level rules mirrored from the server (QuotaEligibilityPolicy, PassengerSpec, AllocationPlanner) so
 * people learn about problems before submitting. The server re-checks all of them.
 */
export function passengersValidator() {
  return (arr: AbstractControl): ValidationErrors | null => {
    const pax = (arr as FormArray).getRawValue() as { age: number; gender: string; berthRequired: boolean }[];
    const errors: ValidationErrors = {};
    if (pax.length > 0 && !pax.some(p => p.berthRequired)) errors['noBerthPassenger'] = true;
    if (pax.some(p => !p.berthRequired && p.age > 4)) errors['noBerthTooOld'] = true;
    // Reads the sibling control instead of closing over the form: the form doesn't exist yet when the
    // validator is created, and referencing it there is a circular initialisation.
    const q = (arr.parent?.get('quota')?.value ?? 'GN') as Quota;
    if (q === 'LD' && pax.some(p => p.gender !== 'F' && p.age > 11)) errors['ladiesQuota'] = true;
    if ((q === 'TQ' || q === 'PT') && pax.some(p => !p.berthRequired)) errors['tatkalChild'] = true;
    return Object.keys(errors).length ? errors : null;
  };
}

@Component({
  selector: 'ro-booking-form-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, ProblemAlert],
  template: `
    <section class="card">
      <h1>Passenger details</h1>
      <p>Run {{ form.controls.trainRunId.value }} · stops {{ form.controls.fromStopSeq.value }} →
        {{ form.controls.toStopSeq.value }} · {{ form.controls.travelClass.value }} · {{ form.controls.quota.value }} quota</p>

      <ro-problem-alert [error]="error()" />

      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <fieldset formArrayName="passengers">
          <legend>Passengers ({{ passengers.length }} of {{ max }})</legend>
          @for (p of passengers.controls; track p; let i = $index) {
            <div class="passenger" [formGroupName]="i" [attr.data-testid]="'passenger-' + (i + 1)">
              <h3>Passenger {{ i + 1 }}</h3>
              <div class="row">
                <div class="grow">
                  <label [for]="'name' + i">Full name</label>
                  <input [id]="'name' + i" formControlName="name" autocomplete="off" maxlength="60" />
                </div>
                <div>
                  <label [for]="'age' + i">Age</label>
                  <input [id]="'age' + i" type="number" min="0" max="125" formControlName="age" />
                </div>
                <div>
                  <label [for]="'gender' + i">Gender</label>
                  <select [id]="'gender' + i" formControlName="gender">
                    <option value="F">Female</option><option value="M">Male</option><option value="T">Transgender</option>
                  </select>
                </div>
                <div>
                  <label [for]="'pref' + i">Berth preference</label>
                  <select [id]="'pref' + i" formControlName="berthPreference">
                    <option [ngValue]="null">No preference</option>
                    @for (b of berthOptions(); track b.value) { <option [ngValue]="b.value">{{ b.label }}</option> }
                  </select>
                </div>
              </div>
              <!-- Also shown when unticked: if the age is raised past 4 afterwards, the box must stay reachable to fix it. -->
              @if (p.get('age')!.value <= 4 || !p.get('berthRequired')!.value) {
                <label class="inline">
                  <input type="checkbox" [checked]="!p.get('berthRequired')!.value"
                         (change)="p.get('berthRequired')!.setValue(!$any($event.target).checked)" />
                  Child under 5 travelling without a separate berth (no fare)
                </label>
              }
              @if (p.get('name')!.touched && p.get('name')!.invalid) {
                <p class="field-error">Enter the name as on the passenger's ID (letters, spaces, . ' -).</p>
              }
              @if (passengers.length > 1) {
                <button type="button" class="link" (click)="remove(i)">Remove passenger {{ i + 1 }}</button>
              }
            </div>
          }
          @if (passengers.length < max) {
            <button type="button" class="secondary" (click)="add()">Add passenger</button>
          }
          @if (passengers.errors; as e) {
            <div class="field-error" role="alert">
              @if (e['noBerthPassenger']) { <p>At least one passenger must have a berth.</p> }
              @if (e['noBerthTooOld']) { <p>Only children under 5 can travel without a berth.</p> }
              @if (e['ladiesQuota']) { <p>Ladies quota is for women, and boys aged 11 or under travelling with them.</p> }
              @if (e['tatkalChild']) { <p>Tatkal bookings can't include children without a berth.</p> }
            </div>
          }
        </fieldset>

        <fieldset>
          <legend>Contact</legend>
          <div class="row">
            <div class="grow"><label for="phone">Mobile</label><input id="phone" type="tel" formControlName="contactPhone" /></div>
            <div class="grow"><label for="cemail">Email (optional)</label><input id="cemail" type="email" formControlName="contactEmail" /></div>
          </div>
          <label class="inline"><input type="checkbox" formControlName="onlyIfConfirmed" />
            Book only if every passenger gets a confirmed berth</label>
        </fieldset>

        <button type="submit" [disabled]="busy() || form.invalid">{{ busy() ? 'Booking…' : 'Book and hold seats' }}</button>
        <p class="hint">Seats are held for 10 minutes while you pay. Submitting twice won't book twice.</p>
      </form>
    </section>
  `,
})
export class BookingFormPage {
  private readonly api = inject(ReservationApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly action = new IdempotentAction();

  protected readonly max = MAX_PASSENGERS;
  protected readonly busy = signal(false);
  protected readonly error = signal<UserFacingError | null>(null);

  private readonly q = this.route.snapshot.queryParamMap;
  protected readonly form = this.fb.group({
    trainRunId: [Number(this.q.get('run') ?? 0), [Validators.required, Validators.min(1)]],
    fromStopSeq: [Number(this.q.get('from') ?? 0), [Validators.required, Validators.min(0)]],
    toStopSeq: [Number(this.q.get('to') ?? 1), [Validators.required, Validators.min(1)]],
    travelClass: [(this.q.get('cls') ?? '3A') as TravelClass, Validators.required],
    quota: [(this.q.get('quota') ?? 'GN') as Quota, Validators.required],
    passengers: this.fb.array([this.passengerGroup()], { validators: passengersValidator() }),
    contactPhone: ['', [Validators.required, Validators.pattern(/^\+?[0-9 -]{10,16}$/)]],
    contactEmail: ['', [Validators.email, Validators.maxLength(254)]],
    onlyIfConfirmed: [false],
  });

  private readonly travelClass = toSignal(this.form.controls.travelClass.valueChanges,
    { initialValue: this.form.controls.travelClass.value });

  constructor() {
    // Quota rules live on the passenger array; re-run them when the quota changes.
    this.form.controls.quota.valueChanges.pipe(takeUntilDestroyed())
      .subscribe(() => this.form.controls.passengers.updateValueAndValidity());
    this.form.controls.passengers.updateValueAndValidity(); // first run, now that the parent exists
  }
  protected readonly berthOptions = computed(() => SEATED.includes(this.travelClass())
    ? [{ value: 'WS' as BerthType, label: 'Window' }, { value: 'MS' as BerthType, label: 'Middle' },
       { value: 'AS' as BerthType, label: 'Aisle' }]
    : [{ value: 'LB' as BerthType, label: 'Lower' }, { value: 'MB' as BerthType, label: 'Middle' },
       { value: 'UB' as BerthType, label: 'Upper' }, { value: 'SL' as BerthType, label: 'Side lower' },
       { value: 'SU' as BerthType, label: 'Side upper' }]);

  get passengers(): FormArray<FormGroup> {
    return this.form.controls.passengers as unknown as FormArray<FormGroup>;
  }

  add(): void {
    if (this.passengers.length < MAX_PASSENGERS) this.passengers.push(this.passengerGroup());
  }

  remove(i: number): void {
    this.passengers.removeAt(i);
  }

  submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    const req: CreateBookingRequest = {
      trainRunId: v.trainRunId, fromStopSeq: v.fromStopSeq, toStopSeq: v.toStopSeq,
      travelClass: v.travelClass, quota: v.quota, onlyIfConfirmed: v.onlyIfConfirmed,
      contactPhone: v.contactPhone, contactEmail: v.contactEmail || null,
      passengers: (v.passengers as { name: string; age: number; gender: 'M' | 'F' | 'T'; berthPreference: BerthType | null; berthRequired: boolean }[])
        .map(p => ({ ...p, name: p.name.trim() })),
    };
    // Same body as a failed attempt → same key → the server replays instead of booking twice.
    const key = this.action.keyFor(req);
    this.busy.set(true);
    this.error.set(null);
    this.api.createBooking(req, key).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: b => {
        this.action.reset();
        void this.router.navigate(['/bookings', b.pnr]);
      },
      error: e => {
        const fe = toUserFacingError(e);
        if (!fe.retryable) this.action.reset(); // a definite "no": the next attempt is a new request
        this.error.set(fe);
      },
    });
  }

  private passengerGroup(): FormGroup {
    return this.fb.group({
      name: ['', [Validators.required, Validators.maxLength(60), Validators.pattern(/^[\p{L} .'-]+$/u)]],
      age: [30, [Validators.required, Validators.min(0), Validators.max(125)]],
      gender: ['F' as 'M' | 'F' | 'T', Validators.required],
      berthPreference: [null as BerthType | null],
      berthRequired: [true],
    });
  }
}
