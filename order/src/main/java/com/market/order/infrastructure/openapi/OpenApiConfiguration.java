package com.market.order.infrastructure.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI orderOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Market Order API")
                        .description("API REST para criação e consulta de pedidos do projeto Market.")
                        .version("v1")
                        .license(new License()
                                .name("Uso interno")))
                .addTagsItem(new Tag()
                        .name("Pedidos")
                        .description("Operações do ciclo de vida dos pedidos."));
    }
}
