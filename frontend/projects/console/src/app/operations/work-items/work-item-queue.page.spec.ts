import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { runtime } from 'api-client';
import { ToastService } from 'ui-registry';
import { WorkItemQueuePage } from './work-item-queue.page';

/** jsdom does not implement <dialog>; see confirm-dialog.spec.ts for the same polyfill. */
beforeAll(() => {
  if (typeof HTMLDialogElement.prototype.showModal !== 'function') {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  }
  if (typeof HTMLDialogElement.prototype.close !== 'function') {
    HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
      this.removeAttribute('open');
      this.dispatchEvent(new Event('close'));
    };
  }
});

describe('WorkItemQueuePage', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        runtime.provideApi(''),
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.match(() => true).forEach((req) => req.flush({ status: 'SUCCESS', code: 'OK', data: [] }));
  });

  it('renders both grids empty when the API returns no rows', async () => {
    const fixture = TestBed.createComponent(WorkItemQueuePage);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/work-items?status=QUEUED').flush({ status: 'SUCCESS', code: 'OK', data: [] });
    httpMock.expectOne('/api/v1/work-items?status=IN_PROGRESS').flush({ status: 'SUCCESS', code: 'OK', data: [] });
    httpMock.expectOne('/api/v1/operators/idle').flush({ status: 'SUCCESS', code: 'OK', data: [] });
    await fixture.whenStable();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('No queued work items.');
    expect(el.textContent).toContain('No work items in progress.');
  });

  it('renders a queued row and shows the SLA-breach highlight', async () => {
    const fixture = TestBed.createComponent(WorkItemQueuePage);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/work-items?status=QUEUED').flush({
      status: 'SUCCESS',
      code: 'OK',
      data: [
        {
          externalId: 'wi-1',
          visitExternalId: 'visit-1',
          laneExternalId: 'lane-1',
          status: 'QUEUED',
          queuedAt: new Date().toISOString(),
          slaBreachedAt: new Date().toISOString(),
        },
      ],
    });
    httpMock.expectOne('/api/v1/work-items?status=IN_PROGRESS').flush({ status: 'SUCCESS', code: 'OK', data: [] });
    httpMock.expectOne('/api/v1/operators/idle').flush({ status: 'SUCCESS', code: 'OK', data: [] });
    await fixture.whenStable();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('wi-1');
    expect(el.textContent).toContain('visit-1');
    const row = el.querySelector('tbody tr')!;
    expect(row.className).toContain('bg-negative-row');
  });

  it('enables Assign promptly after picking an operator, without a manual CD trigger', async () => {
    // Regression test for a zoneless staleness bug: [disabled] on the Assign button
    // reads the <select>'s DOM value directly. Without an Angular event binding on
    // the select, choosing an option doesn't itself schedule a CD pass, so the
    // disabled state goes stale until something unrelated ticks (up to 5s, via the
    // poll). autoDetectChanges mimics the real app's automatic zoneless scheduler —
    // a naive test calling fixture.detectChanges() manually after the interaction
    // would pass regardless of whether the (change) binding exists, which is exactly
    // why this test avoids that.
    const fixture = TestBed.createComponent(WorkItemQueuePage);
    fixture.autoDetectChanges(true);

    httpMock.expectOne('/api/v1/work-items?status=QUEUED').flush({
      status: 'SUCCESS',
      code: 'OK',
      data: [{ externalId: 'wi-1', visitExternalId: 'visit-1', laneExternalId: 'lane-1', status: 'QUEUED' }],
    });
    httpMock.expectOne('/api/v1/work-items?status=IN_PROGRESS').flush({ status: 'SUCCESS', code: 'OK', data: [] });
    httpMock.expectOne('/api/v1/operators/idle').flush({
      status: 'SUCCESS',
      code: 'OK',
      data: [{ userExternalId: 'usr-demo-idle', status: 'IDLE' }],
    });
    await fixture.whenStable();

    const el = fixture.nativeElement as HTMLElement;
    const select = el.querySelector('select') as HTMLSelectElement;
    const assignButton = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Assign')!;
    expect(assignButton.disabled).toBe(true);

    select.value = 'usr-demo-idle';
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await fixture.whenStable();

    expect(assignButton.disabled).toBe(false);
  });

  it('surfaces a toast once when the queued list fails to load', async () => {
    const fixture = TestBed.createComponent(WorkItemQueuePage);
    const toast = TestBed.inject(ToastService);
    const showSpy = vi.spyOn(toast, 'show');
    fixture.detectChanges();

    httpMock
      .expectOne('/api/v1/work-items?status=QUEUED')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    httpMock.expectOne('/api/v1/work-items?status=IN_PROGRESS').flush({ status: 'SUCCESS', code: 'OK', data: [] });
    httpMock.expectOne('/api/v1/operators/idle').flush({ status: 'SUCCESS', code: 'OK', data: [] });
    await fixture.whenStable();

    expect(showSpy).toHaveBeenCalledWith('Could not load the queued work items.', 'error');
  });
});
