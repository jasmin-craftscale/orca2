import { Component, inject } from '@angular/core';
import { ToastService } from './toast.service';
import { ToastKind } from './toast.model';

const KIND_CLASSES: Record<ToastKind, string> = {
  success: 'bg-positive-bg text-positive-text',
  error: 'bg-negative-bg text-negative',
  info: 'bg-info-bg text-info',
};

@Component({
  selector: 'orca-toast-host',
  template: `
    <div class="fixed top-4 right-4 z-50 flex flex-col gap-2">
      @for (toast of toastService.all(); track toast.id) {
        <div
          class="rounded-base px-4 py-3 shadow-pill text-[13px] font-semibold"
          [class]="kindClasses[toast.kind]"
          role="status"
        >
          {{ toast.message }}
        </div>
      }
    </div>
  `,
})
export class ToastHost {
  protected readonly toastService = inject(ToastService);
  protected readonly kindClasses = KIND_CLASSES;
}
