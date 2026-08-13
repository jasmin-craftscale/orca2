import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'operations/work-items' },
  {
    path: 'operations/work-items',
    loadComponent: () =>
      import('./operations/work-items/work-item-queue.page').then((m) => m.WorkItemQueuePage),
  },
  {
    path: 'operations/work-items/:id',
    loadComponent: () =>
      import('./operations/work-items/work-item-detail.page').then((m) => m.WorkItemDetailPage),
  },
  {
    path: 'administration/builder',
    loadComponent: () =>
      import('./builder/builder-placeholder.page').then((m) => m.BuilderPlaceholderPage),
  },
  {
    path: '**',
    loadComponent: () => import('./not-found.page').then((m) => m.NotFoundPage),
  },
];
