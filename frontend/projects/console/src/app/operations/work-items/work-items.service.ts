import { Service, inject } from '@angular/core';
import { runtime } from 'api-client';
import { firstValueFrom } from 'rxjs';

/** Wraps the generated client and nothing else — no caching, no store, no mapping layer. */
@Service()
export class WorkItemsApi {
  private readonly api = inject(runtime.WorkItemsService);

  list(status: 'QUEUED' | 'IN_PROGRESS') {
    return this.api.listWorkItems(status);
  }

  get(id: string) {
    return this.api.getWorkItem(id);
  }

  audit(id: string) {
    return this.api.getWorkItemAudit(id);
  }

  take(id: string) {
    return firstValueFrom(this.api.takeWorkItem(id));
  }

  takeover(id: string) {
    return firstValueFrom(this.api.takeoverWorkItem(id));
  }

  park(id: string) {
    return firstValueFrom(this.api.parkWorkItem(id));
  }

  assign(id: string, assigneeExternalId: string) {
    return firstValueFrom(this.api.assignWorkItem(id, { assigneeExternalId }));
  }

  complete(id: string, correctedEventData: string) {
    return firstValueFrom(this.api.completeWorkItem(id, { correctedEventData }));
  }
}
