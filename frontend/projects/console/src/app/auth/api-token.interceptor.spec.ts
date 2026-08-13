import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { apiTokenInterceptor } from './api-token.interceptor';
import { AuthService } from './auth.service';

describe('apiTokenInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let login: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    login = vi.fn();

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiTokenInterceptor])),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            accessToken: () => 'tkn',
            login,
          },
        },
      ],
    });

    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('carries the bearer token on a request to /api/**', () => {
    http.get('/api/v1/work-items').subscribe();

    const req = httpMock.expectOne('/api/v1/work-items');
    expect(req.request.headers.get('Authorization')).toBe('Bearer tkn');
    req.flush({});
  });

  it('does not carry the bearer token to the realm', () => {
    const realmUrl = 'http://localhost:18080/realms/orca/protocol/openid-connect/token';
    http.get(realmUrl).subscribe();

    const req = httpMock.expectOne(realmUrl);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('calls AuthService.login() once on a 401 from /api/**', async () => {
    const result = firstValueFrom(http.get('/api/v1/work-items')).catch(() => 'errored');

    const req = httpMock.expectOne('/api/v1/work-items');
    req.flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });

    await result;

    expect(login).toHaveBeenCalledTimes(1);
  });
});
