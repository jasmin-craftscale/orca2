import { Component, ElementRef, input, output, viewChild } from '@angular/core';

let nextInstanceId = 0;

/**
 * Separate from Toast, deliberately. 1.x conflates notification and confirmation
 * into one `Toaster` imported by 85 files — the single worst component in the old
 * console. This one only ever confirms or cancels a single pending action.
 */
@Component({
  selector: 'orca-confirm-dialog',
  template: `
    <dialog
      #dialogEl
      class="m-auto rounded-modal border-0 p-0 shadow-card backdrop:bg-black/40"
      [attr.aria-labelledby]="titleId"
      [attr.aria-describedby]="messageId"
      (close)="onNativeClose()"
    >
      <div class="w-[380px] p-6">
        <h2 [id]="titleId" class="text-[16px] font-bold text-ink-heading">{{ title() }}</h2>
        <p [id]="messageId" class="mt-2 text-[13px] text-ink-muted">{{ message() }}</p>
        <div class="mt-6 flex justify-end gap-3">
          <button
            type="button"
            class="rounded-base px-4 py-2 text-[13px] font-semibold text-ink-label"
            (click)="cancel()"
          >
            Cancel
          </button>
          <button
            #confirmBtn
            type="button"
            class="rounded-base bg-primary px-4 py-2 text-[13px] font-semibold text-white"
            (click)="confirm()"
          >
            {{ confirmLabel() }}
          </button>
        </div>
      </div>
    </dialog>
  `,
})
export class ConfirmDialog {
  readonly title = input.required<string>();
  readonly message = input.required<string>();
  readonly confirmLabel = input('Confirm');

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  private readonly instanceId = nextInstanceId++;
  protected readonly titleId = `orca-confirm-dialog-title-${this.instanceId}`;
  protected readonly messageId = `orca-confirm-dialog-message-${this.instanceId}`;

  private readonly dialogEl = viewChild.required<ElementRef<HTMLDialogElement>>('dialogEl');
  private readonly confirmBtn = viewChild<ElementRef<HTMLButtonElement>>('confirmBtn');

  private closedByAction = false;

  open(): void {
    this.dialogEl().nativeElement.showModal();
    queueMicrotask(() => this.confirmBtn()?.nativeElement.focus());
  }

  confirm(): void {
    this.closedByAction = true;
    this.dialogEl().nativeElement.close();
    this.confirmed.emit();
  }

  cancel(): void {
    this.closedByAction = true;
    this.dialogEl().nativeElement.close();
    this.cancelled.emit();
  }

  /** Escape and the backdrop close the native dialog without calling confirm()/cancel() — treat that as a cancel. */
  protected onNativeClose(): void {
    if (!this.closedByAction) {
      this.cancelled.emit();
    }
    this.closedByAction = false;
  }
}
