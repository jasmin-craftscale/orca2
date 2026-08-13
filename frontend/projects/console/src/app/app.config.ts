import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideOAuthClient } from 'angular-oauth2-oidc';
import { core, runtime } from 'api-client';

import { apiTokenInterceptor } from './auth/api-token.interceptor';
import { AuthService } from './auth/auth.service';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([apiTokenInterceptor])),
    provideOAuthClient(),

    // Same origin: the dev server proxies, the reverse proxy does it in production.
    // Both contracts must be wired — each namespace has its own BASE_PATH token, and
    // an unwired one silently falls back to the OpenAPI document's localhost:808x
    // server URL, which bypasses the proxy and dies on CORS that does not exist.
    runtime.provideApi(''),
    core.provideApi(''),

    provideAppInitializer(() => inject(AuthService).bootstrap()),
  ],
};
