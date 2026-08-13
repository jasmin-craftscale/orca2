import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { runtime } from 'api-client';
import { ToastService } from 'ui-registry';
import { WorkItemDetailPage } from './work-item-detail.page';

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

describe('WorkItemDetailPage', () => {
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

  it('shows the item and prefills the completion textarea from eventData', async () => {
    const fixture = TestBed.createComponent(WorkItemDetailPage);
    fixture.componentRef.setInput('id', 'wi-1');
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/work-items/wi-1').flush({
      status: 'SUCCESS',
      code: 'OK',
      data: {
        externalId: 'wi-1',
        visitExternalId: 'visit-1',
        laneExternalId: 'lane-1',
        status: 'IN_PROGRESS',
        eventData: '{"plate":"T-1"}',
      },
    });
    httpMock.expectOne('/api/v1/work-items/wi-1/audit').flush({ status: 'SUCCESS', code: 'OK', data: [] });
    await fixture.whenStable();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('visit-1');
    const textarea = el.querySelector('textarea') as HTMLTextAreaElement;
    expect(textarea.value).toBe('{"plate":"T-1"}');
  });

  it('renders the audit trail', async () => {
    const fixture = TestBed.createComponent(WorkItemDetailPage);
    fixture.componentRef.setInput('id', 'wi-1');
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/work-items/wi-1').flush({
      status: 'SUCCESS',
      code: 'OK',
      data: { externalId: 'wi-1', status: 'IN_PROGRESS' },
    });
    httpMock.expectOne('/api/v1/work-items/wi-1/audit').flush({
      status: 'SUCCESS',
      code: 'OK',
      data: [{ action: 'TAKE', actor: 'usr-demo-clerk', occurredAt: '2026-08-13T10:00:00Z', elapsedSec: 12 }],
    });
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('TAKE');
  });

  it('surfaces a toast once when the item fails to load', async () => {
    const fixture = TestBed.createComponent(WorkItemDetailPage);
    const toast = TestBed.inject(ToastService);
    const showSpy = vi.spyOn(toast, 'show');
    fixture.componentRef.setInput('id', 'wi-1');
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/work-items/wi-1').flush('boom', { status: 500, statusText: 'Server Error' });
    httpMock.expectOne('/api/v1/work-items/wi-1/audit').flush({ status: 'SUCCESS', code: 'OK', data: [] });
    await fixture.whenStable();

    expect(showSpy).toHaveBeenCalledWith('Could not load that work item.', 'error');
  });
});
