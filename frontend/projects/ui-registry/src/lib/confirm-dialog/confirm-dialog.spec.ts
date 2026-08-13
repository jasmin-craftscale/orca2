import { TestBed } from '@angular/core/testing';
import { ConfirmDialog } from './confirm-dialog';

/**
 * jsdom (this workspace's test DOM, verified 13 Aug 2026) does not implement
 * `HTMLDialogElement.showModal()`/`close()` — real browsers do. This polyfill is
 * scoped to this spec file only and exists purely so the component's own open/close
 * bookkeeping can be exercised in a unit test; it is not a stand-in for real-browser
 * <dialog> behavior, which was verified separately in the browser.
 */
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

describe('ConfirmDialog', () => {
  function setup() {
    const fixture = TestBed.createComponent(ConfirmDialog);
    fixture.componentRef.setInput('title', 'Take this item?');
    fixture.componentRef.setInput('message', 'You will be assigned to it.');
    fixture.componentRef.setInput('confirmLabel', 'Take');
    fixture.detectChanges();
    return fixture;
  }

  it('renders the title, message and confirm label', () => {
    const fixture = setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h2')?.textContent).toBe('Take this item?');
    expect(el.textContent).toContain('You will be assigned to it.');
    expect(el.textContent).toContain('Take');
  });

  it('emits confirmed and closes the dialog when the confirm button is clicked', () => {
    const fixture = setup();
    const confirmed = vi.fn();
    fixture.componentInstance.confirmed.subscribe(confirmed);

    fixture.componentInstance.open();
    const dialog = (fixture.nativeElement as HTMLElement).querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);

    const confirmBtn = (fixture.nativeElement as HTMLElement).querySelectorAll('button')[1] as HTMLButtonElement;
    confirmBtn.click();

    expect(confirmed).toHaveBeenCalledTimes(1);
    expect(dialog.hasAttribute('open')).toBe(false);
  });

  it('emits cancelled when the cancel button is clicked', () => {
    const fixture = setup();
    const cancelled = vi.fn();
    fixture.componentInstance.cancelled.subscribe(cancelled);

    fixture.componentInstance.open();
    const cancelBtn = (fixture.nativeElement as HTMLElement).querySelectorAll('button')[0] as HTMLButtonElement;
    cancelBtn.click();

    expect(cancelled).toHaveBeenCalledTimes(1);
  });

  it('emits cancelled when the dialog is closed natively (Escape/backdrop)', () => {
    const fixture = setup();
    const cancelled = vi.fn();
    const confirmed = vi.fn();
    fixture.componentInstance.cancelled.subscribe(cancelled);
    fixture.componentInstance.confirmed.subscribe(confirmed);

    fixture.componentInstance.open();
    const dialog = (fixture.nativeElement as HTMLElement).querySelector('dialog')!;
    dialog.dispatchEvent(new Event('close'));

    expect(cancelled).toHaveBeenCalledTimes(1);
    expect(confirmed).not.toHaveBeenCalled();
  });
});
