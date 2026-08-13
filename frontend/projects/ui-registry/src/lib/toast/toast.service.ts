import { Service, signal } from '@angular/core';
import { Toast, ToastKind } from './toast.model';

const AUTO_DISMISS_MS = 5000;

@Service()
export class ToastService {
  private readonly toasts = signal<Toast[]>([]);
  private nextId = 0;

  readonly all = this.toasts.asReadonly();

  /** One error path for the whole app: an API failure becomes a toast, and the page stays usable. */
  show(message: string, kind: ToastKind = 'info'): void {
    const id = this.nextId++;
    this.toasts.update((list) => [...list, { id, message, kind }]);
    setTimeout(() => this.dismiss(id), AUTO_DISMISS_MS);
  }

  dismiss(id: number): void {
    this.toasts.update((list) => list.filter((toast) => toast.id !== id));
  }
}
