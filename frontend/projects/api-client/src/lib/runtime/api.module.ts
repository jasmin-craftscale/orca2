import { NgModule, ModuleWithProviders, SkipSelf, Optional } from '@angular/core';
import { RuntimeConfiguration } from './configuration';
import { HttpClient } from '@angular/common/http';


@NgModule({
  imports:      [],
  declarations: [],
  exports:      [],
  providers: []
})
export class RuntimeApiModule {
    public static forRoot(configurationFactory: () => RuntimeConfiguration): ModuleWithProviders<RuntimeApiModule> {
        return {
            ngModule: RuntimeApiModule,
            providers: [ { provide: RuntimeConfiguration, useFactory: configurationFactory } ]
        };
    }

    constructor( @Optional() @SkipSelf() parentModule: RuntimeApiModule,
                 @Optional() http: HttpClient) {
        if (parentModule) {
            throw new Error('RuntimeApiModule is already loaded. Import in your base AppModule only.');
        }
        if (!http) {
            throw new Error('You need to import the HttpClientModule in your AppModule! \n' +
            'See also https://github.com/angular/angular/issues/20575');
        }
    }
}
