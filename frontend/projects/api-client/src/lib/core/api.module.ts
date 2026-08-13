import { NgModule, ModuleWithProviders, SkipSelf, Optional } from '@angular/core';
import { CoreConfiguration } from './configuration';
import { HttpClient } from '@angular/common/http';


@NgModule({
  imports:      [],
  declarations: [],
  exports:      [],
  providers: []
})
export class CoreApiModule {
    public static forRoot(configurationFactory: () => CoreConfiguration): ModuleWithProviders<CoreApiModule> {
        return {
            ngModule: CoreApiModule,
            providers: [ { provide: CoreConfiguration, useFactory: configurationFactory } ]
        };
    }

    constructor( @Optional() @SkipSelf() parentModule: CoreApiModule,
                 @Optional() http: HttpClient) {
        if (parentModule) {
            throw new Error('CoreApiModule is already loaded. Import in your base AppModule only.');
        }
        if (!http) {
            throw new Error('You need to import the HttpClientModule in your AppModule! \n' +
            'See also https://github.com/angular/angular/issues/20575');
        }
    }
}
