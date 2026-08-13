import { EnvironmentProviders, makeEnvironmentProviders } from "@angular/core";
import { CoreConfiguration, CoreConfigurationParameters } from './configuration';
import { BASE_PATH } from './variables';

// Returns the service class providers, to be used in the [ApplicationConfig](https://angular.dev/api/core/ApplicationConfig).
export function provideApi(configOrBasePath: string | CoreConfigurationParameters): EnvironmentProviders {
    return makeEnvironmentProviders([
        typeof configOrBasePath === "string"
            ? { provide: BASE_PATH, useValue: configOrBasePath }
            : {
                provide: CoreConfiguration,
                useValue: new CoreConfiguration({ ...configOrBasePath }),
            },
    ]);
}