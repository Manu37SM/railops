import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { WlChance } from './wl-chance';

describe('WlChance', () => {
  let ctl: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    ctl = TestBed.inject(HttpTestingController);
  });

  afterEach(() => ctl.verify());

  function render() {
    const f = TestBed.createComponent(WlChance);
    f.componentRef.setInput('pnr', '1234567890');
    f.detectChanges();
    return f;
  }

  it('shows a rounded estimate with its band and the disclaimer', () => {
    const f = render();
    ctl.expectOne('/api/v1/bookings/1234567890/wl-prediction').flush({
      probability: 0.63, band: 'MEDIUM', modelVersion: 'm', currentWlPosition: 7, reason: null,
      disclaimer: 'Estimate only. Not a guarantee.',
    });
    f.detectChanges();
    const text = (f.nativeElement as HTMLElement).textContent!;
    expect(text).toContain('about 65%');
    expect(text).toContain('uncertain');
    expect(text).toContain('WL 7');
    expect(text).toContain('Not a guarantee');
  });

  it('never claims certainty at either end', () => {
    const c = render().componentInstance;
    ctl.expectOne(() => true).flush(null);
    expect(c.percent(0.999)).toBe(95);
    expect(c.percent(0.001)).toBe(5);
  });

  it('treats an omitted probability (server non_null serialisation) as no estimate, not NaN%', () => {
    const f = render();
    ctl.expectOne(() => true).flush({ modelVersion: 'm', reason: 'CHART_PREPARED', disclaimer: 'x' });
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="wl-chance"]')).toBeNull();
    expect(el.textContent).not.toContain('NaN');
  });

  it('renders nothing when there is no estimate or the feature is off', () => {
    const f = render();
    ctl.expectOne(() => true).flush({ probability: null, band: null, modelVersion: 'm', currentWlPosition: 3,
      reason: 'NOT_COVERED', disclaimer: 'x' });
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).querySelector('[data-testid="wl-chance"]')).toBeNull();

    const g = render();
    ctl.expectOne(() => true).flush({ code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    g.detectChanges();
    expect((g.nativeElement as HTMLElement).textContent!.trim()).toBe('');
  });
});
