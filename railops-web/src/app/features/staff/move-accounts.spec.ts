import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MoveAccounts } from './move-accounts';

describe('MoveAccounts', () => {
  let ctl: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    ctl = TestBed.inject(HttpTestingController);
  });

  afterEach(() => ctl.verify());

  function click(f: { nativeElement: HTMLElement; detectChanges(): void }, text: string) {
    const b = Array.from(f.nativeElement.querySelectorAll('button')).find(x => x.textContent?.includes(text)) as HTMLButtonElement;
    b.click();
    f.detectChanges();
  }

  it('asks for confirmation, then shows what happened', () => {
    const f = TestBed.createComponent(MoveAccounts);
    f.detectChanges();
    click(f, 'Move accounts');
    ctl.expectNone('/api/v1/admin/raillens/account-move');
    click(f, 'Yes, move them');
    ctl.expectOne(r => r.method === 'POST' && r.url === '/api/v1/admin/raillens/account-move').flush({
      examined: 3, created: 2, alreadyMoved: 0, linkedInRailLens: 2, emailTaken: 1,
      emailTakenSample: ['41 a***@example.com'], invalid: [], raillensAdmins: [7], linkConflicts: [] });
    f.detectChanges();
    const text = f.nativeElement.querySelector('[data-testid="move-summary"]').textContent;
    expect(text).toContain('Moved now');
    expect(text).toContain('RailLens ids 7');
    expect(text).toContain('41 a***@example.com');
  });

  it('cancel sends nothing', () => {
    const f = TestBed.createComponent(MoveAccounts);
    f.detectChanges();
    click(f, 'Move accounts');
    click(f, 'Cancel');
    ctl.expectNone('/api/v1/admin/raillens/account-move');
  });
});
