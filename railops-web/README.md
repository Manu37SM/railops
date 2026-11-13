# RailopsWeb

This project was generated using [Angular CLI](https://github.com/angular/angular-cli) version 22.2.6.

## Look and feel (matches RailLens)

Since Phase 3A this app uses RailLens' design: the same colour tokens (slate neutrals, blue `#2563eb` primary), the
Geist font (bundled from `@fontsource-variable/geist`, served from this site, so the `font-src 'self'` CSP still
holds) and a light / dark / system theme.

- All colours are CSS variables at the top of `src/styles.scss` (`:root` for light, `:root.dark` for dark). Components
  use the shared classes there and have no colours of their own.
- `core/theme/theme.service.ts` stores the choice under `raillens-theme`, like RailLens. `public/theme-init.js`
  applies it before Angular starts so dark mode doesn't flash white. It's a separate file because the CSP forbids
  inline scripts.
- Every text colour passes WCAG AA (4.5:1) in both themes. The one difference from RailLens is that filled buttons
  stay blue-600 in dark mode, because white on RailLens' dark blue is only 3.7:1.

## Development server

To start a local development server, run:

```bash
ng serve
```

Once the server is running, open your browser and navigate to `http://localhost:4200/`. The application will automatically reload whenever you modify any of the source files.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the project run:

```bash
ng build
```

This will compile your project and store the build artifacts in the `dist/` directory. By default, the production build optimizes your application for performance and speed.

## Running unit tests

To execute unit tests with the [Vitest](https://vitest.dev/) test runner, use the following command:

```bash
ng test
```

## Running end-to-end tests

For end-to-end (e2e) testing, run:

```bash
ng e2e
```

Angular CLI does not come with an end-to-end testing framework by default. You can choose one that suits your needs.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
