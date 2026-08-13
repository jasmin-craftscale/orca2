import { AuthConfig } from 'angular-oauth2-oidc';

/**
 * The realm authenticates PEOPLE. Services validate the resulting token locally by
 * signature and never call the issuer on the gate path, so nothing here is on a
 * truck's critical path.
 *
 * `requireHttps` is deliberately left at its default of 'remoteOnly': it permits
 * http for localhost and refuses it for every other host, so the dev issuer works
 * and a plaintext production issuer still cannot be configured by accident.
 */
export const authConfig: AuthConfig = {
  issuer: 'http://localhost:18080/realms/orca',
  clientId: 'orca-console',
  redirectUri: window.location.origin + '/',
  postLogoutRedirectUri: window.location.origin + '/',
  responseType: 'code',
  scope: 'openid profile email',
  showDebugInformation: false,
};
