import { Component, DestroyRef, computed, inject, input, signal } from '@angular/core';

function formatElapsed(totalSeconds: number): string {
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const mm = String(minutes).padStart(2, '0');
  const ss = String(seconds).padStart(2, '0');
  return hours > 0 ? `${hours}:${mm}:${ss}` : `${mm}:${ss}`;
}

@Component({
  selector: 'orca-elapsed-timer',
  template: `<span>{{ display() }}</span>`,
})
export class ElapsedTimer {
  readonly since = input.required<string>();

  // The app is zoneless: a plain field mutated by setInterval repaints nothing,
  // so the tick itself has to be a signal.
  private readonly now = signal(Date.now());

  protected readonly display = computed(() => {
    const startMs = new Date(this.since()).getTime();
    const elapsedSec = Math.max(0, Math.floor((this.now() - startMs) / 1000));
    return formatElapsed(elapsedSec);
  });

  constructor() {
    const intervalId = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(intervalId));
  }
}
