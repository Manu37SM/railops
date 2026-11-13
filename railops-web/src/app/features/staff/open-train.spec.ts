import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { OpenTrain } from './open-train';

describe('OpenTrain', () => {
  let ctl: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    ctl = TestBed.inject(HttpTestingController);
  });

  afterEach(() => ctl.verify());

  function render() {
    const f = TestBed.createComponent(OpenTrain);
    f.detectChanges();
    return f;
  }

  function fill(f: ReturnType<typeof render>, values: Record<string, string>) {
    for (const [id, value] of Object.entries(values)) {
      const input: HTMLInputElement = f.nativeElement.querySelector('#' + id);
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    f.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    f.detectChanges();
  }

  it('sends only the classes with coaches, and shows one result per date', () => {
    const f = render();
    fill(f, { 'ot-train': '12951', 'ot-from': '2026-12-01', 'ot-to': '2026-12-02', 'ot-3A': '2', 'ot-SL': '6' });
    const req = ctl.expectOne('/api/v1/staff/runs/import');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ trainNo: '12951', fromDate: '2026-12-01', toDate: '2026-12-02',
      coaches: [{ travelClass: '3A', count: 2 }, { travelClass: 'SL', count: 6 }], tatkalPercent: 10 });
    req.flush({ trainNo: '12951', trainName: 'Mumbai Rajdhani', stops: 5, dates: [
      { runDate: '2026-12-01', outcome: 'CREATED', trainRunId: 7, detail: null },
      { runDate: '2026-12-02', outcome: 'ALREADY_EXISTS', trainRunId: 3, detail: 'left unchanged' }] });
    f.detectChanges();
    const text = f.nativeElement.querySelector('[data-testid="import-result"]').textContent;
    expect(text).toContain('Mumbai Rajdhani');
    expect(text).toContain('Opened');
    expect(text).toContain('Already open');
  });

  it('checks the form before calling the API', () => {
    const f = render();
    fill(f, { 'ot-train': '1295', 'ot-from': '2026-12-01', 'ot-to': '2026-12-02', 'ot-SL': '1' });
    expect(f.nativeElement.textContent).toContain('5-digit train number');
    fill(f, { 'ot-train': '12951', 'ot-SL': '0' });
    expect(f.nativeElement.textContent).toContain('at least one coach');
    fill(f, { 'ot-SL': '1', 'ot-from': '2026-12-05', 'ot-to': '2026-12-01' });
    expect(f.nativeElement.textContent).toContain('on or after the first date');
    ctl.expectNone('/api/v1/staff/runs/import');
  });

  it('a standard coach set fills in the counts, which staff can still change (Phase 6)', () => {
    const f = render();
    const select: HTMLSelectElement = f.nativeElement.querySelector('#ot-set');
    select.value = 'SHATABDI';
    select.dispatchEvent(new Event('change'));
    f.detectChanges();
    fill(f, { 'ot-train': '12001', 'ot-from': '2026-12-01', 'ot-to': '2026-12-01', 'ot-CC': '10' });
    const req = ctl.expectOne('/api/v1/staff/runs/import');
    expect(req.request.body.coaches).toEqual([{ travelClass: 'CC', count: 10 }, { travelClass: 'EC', count: 2 }]);
    req.flush({ trainNo: '12001', trainName: 'Shatabdi', stops: 3, dates: [] });
  });
});
