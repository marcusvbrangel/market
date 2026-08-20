package com.market.order.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.util.List;

@Schema(description = "Erro HTTP no formato Problem Details")
public record ApiProblemResponse(
        @Schema(description = "Identificador do tipo de problema")
        URI type,
        @Schema(description = "Título estável do problema", example = "Invalid request")
        String title,
        @Schema(description = "Status HTTP", example = "400")
        int status,
        @Schema(description = "Descrição segura do erro", example = "Request validation failed")
        String detail,
        @Schema(description = "URI da requisição que falhou")
        URI instance,
        @Schema(description = "Código estável da aplicação", example = "INVALID_REQUEST")
        String code,
        @Schema(description = "Violações de campos, quando existirem")
        List<FieldViolation> violations
) {

    public record FieldViolation(
            @Schema(description = "Campo inválido", example = "items")
            String field,
            @Schema(description = "Motivo da rejeição", example = "must not be empty")
            String message
    ) {
    }
}
