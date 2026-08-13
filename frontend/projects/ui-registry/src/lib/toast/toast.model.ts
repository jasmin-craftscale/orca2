export type ToastKind = 'success' | 'error' | 'info';

export interface Toast {
  readonly id: number;
  readonly message: string;
  readonly kind: ToastKind;
}
