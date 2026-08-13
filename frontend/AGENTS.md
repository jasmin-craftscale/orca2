
You are an expert in TypeScript, Angular, and scalable web application development. You write functional, maintainable, performant, and accessible code following Angular and TypeScript best practices.

## TypeScript Best Practices

- Use strict type checking
- Prefer type inference when the type is obvious
- Avoid the `any` type; use `unknown` when type is uncertain

## Angular Best Practices

- Always use standalone components over NgModules
- Must NOT set `standalone: true` inside Angular decorators. It's the default in Angular v20+.
- Do NOT set `changeDetection: ChangeDetectionStrategy.OnPush` explicitly. `OnPush` is the default in Angular v22+.
- Use signals for state management
- Implement lazy loading for feature routes
- Do NOT use the `@HostBinding` and `@HostListener` decorators. Put host bindings inside the `host` object of the `@Component` or `@Directive` decorator instead
- Use `NgOptimizedImage` for all static images.
  - `NgOptimizedImage` does not work for inline base64 images.

## Accessibility Requirements

- It MUST pass all AXE checks.
- It MUST follow all WCAG AA minimums, including focus management, color contrast, and ARIA attributes.

### Components

- Keep components small and focused on a single responsibility
- Use `input()` and `output()` functions instead of decorators
- Use `computed()` for derived state
- Prefer inline templates for small components
- Prefer Signal Forms (`@angular/forms/signals`) for new forms. They are stable in Angular v22+ and provide signal-based state, type-safe field access, and schema-based validation
- When not using Signal Forms, prefer Reactive forms instead of Template-driven ones
- Do NOT use `ngClass`, use `class` bindings instead
- Do NOT use `ngStyle`, use `style` bindings instead
- When using external templates/styles, use paths relative to the component TS file.

## State Management

- Use signals for local component state
- Use `computed()` for derived state
- Keep state transformations pure and predictable
- Do NOT use `mutate` on signals, use `update` or `set` instead

## Templates

- Keep templates simple and avoid complex logic
- Use native control flow (`@if`, `@for`, `@switch`) instead of `*ngIf`, `*ngFor`, `*ngSwitch`
- Do not assume globals like (`new Date()`) are available.

## Services

- Design services around a single responsibility
- Use the `providedIn: 'root'` option for singleton services
- Prefer the `@Service` decorator over `@Injectable({providedIn: 'root'})` for new singleton services (Angular v22+)
- Use the `inject()` function instead of constructor injection

## ORCA-specific

- **Auth is `angular-oauth2-oidc`.** Not `keycloak-angular`, not a hand-rolled token
  flow — the backend validates any OIDC issuer by signature and knows nothing about
  Keycloak, so the console shouldn't either.
- **Read primitive is `rxResource`, never `httpResource`.** The generated clients
  return `Observable<T>` and build their own URL from an injected `BASE_PATH`;
  `httpResource` takes a URL, which means hand-writing one.
- **Commands use `firstValueFrom`, then `.reload()` the affected resource(s).** A
  take/complete/park is one shot — there is nothing to subscribe to over time.
- **`tsconfig.json` paths point at library *source*** (`projects/*/src/public-api.ts`),
  not `dist/`, so the console compiles without either library having been built first
  and `ng serve` picks up a live `ui-registry` edit.
- **The proxy is wired into `angular.json`** (`serve.configurations.development.proxyConfig`).
  Forgetting it does not fail loudly — the dev server's SPA fallback answers `/api/v1/…`
  with 200 and `index.html`, so the typed client dies parsing HTML as JSON.
- **Layers are component → one thin feature service → generated client.** Three
  layers, not five. No facade, no repository, no state-management library.
- **Native `<dialog>` for modals and confirms.** Focus trap, `Escape` and the backdrop
  are free and accessible; no dependency.
- **Never hand-write an HTTP call, and never mock an endpoint that should exist.** The
  typed client generated from `projects/api-client` is the only way to call the API. A
  missing endpoint or field is a written finding, not a workaround.
- **`npx ng build` with no project name fails** (`Error: Cannot determine project for
  command.`). This is a multi-project workspace — name the project, e.g.
  `npx ng build console`.
