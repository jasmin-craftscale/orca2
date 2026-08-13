import { Directive, TemplateRef, inject, input } from '@angular/core';

@Directive({ selector: 'ng-template[orcaColumn]' })
export class OrcaColumn {
  readonly key = input.required<string>({ alias: 'orcaColumn' });
  readonly header = input('');
  readonly template = inject(TemplateRef);
}
