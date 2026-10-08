package org.bsl.cartonloading.config;

import org.bsl.cartonloading.common.constants.AppConstants;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {


    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(AppConstants.OpenApi.TITLE)
                        .version(AppConstants.OpenApi.VERSION)
                        .description(AppConstants.OpenApi.DESCRIPTION))
                .addSecurityItem(new SecurityRequirement()
                        .addList(AppConstants.OpenApi.SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(
                                AppConstants.OpenApi.SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(AppConstants.OpenApi.SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme(AppConstants.OpenApi.BEARER_SCHEME)
                                        .bearerFormat(AppConstants.OpenApi.BEARER_FORMAT)
                        ));
    }
}
