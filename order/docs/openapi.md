# OpenAPI do microsserviço Order

## Estado atual

O microsserviço `order` possui documentação OpenAPI instalada, configurada e testada com `springdoc-openapi 3.0.3`, compatível com Spring Boot 4.

A documentação cobre atualmente:

- `POST /api/v1/orders`;
- `GET /api/v1/orders/{orderId}`;
- contratos de entrada e saída representados por Java Records;
- validações, exemplos e descrições dos campos;
- respostas HTTP `200`, `201`, `400` e `404`;
- header `Location` retornado na criação.

## Endereços locais

Com o `order` em execução na porta `8080`:

| Recurso | URL |
|---|---|
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |
| OpenAPI YAML | `http://localhost:8080/v3/api-docs.yaml` |

## Como usar a Swagger UI

1. iniciar o PostgreSQL e o Redpanda pelo Docker Compose;
2. iniciar o microsserviço `order`;
3. abrir `http://localhost:8080/swagger-ui.html`;
4. expandir a tag `Pedidos`;
5. selecionar a operação desejada;
6. usar `Try it out`;
7. preencher os parâmetros ou o corpo JSON;
8. selecionar `Execute` e analisar a resposta.

Exemplo para criação:

```json
{
  "customerId": "0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a",
  "items": [
    {
      "productId": "6c20b55a-2e09-4473-98a6-411f48a8bb23",
      "quantity": 2
    }
  ]
}
```

Nome, preço, subtotal, total e moeda não são aceitos no contrato de criação.

## Exportar o contrato

O JSON ou YAML pode ser salvo para integração com clientes, ferramentas de teste e geração de SDKs:

```bash
curl http://localhost:8080/v3/api-docs
curl http://localhost:8080/v3/api-docs.yaml
```

O contrato gerado deve permanecer alinhado ao comportamento real dos controllers e Records. Alterações em endpoints ou modelos devem atualizar também as anotações e os testes OpenAPI.

## Configuração

A configuração fica em `src/main/resources/application.yaml`. Os caminhos adotados são os padrões consolidados do springdoc:

```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
```

Para desabilitar a documentação em um ambiente específico:

```bash
SPRINGDOC_API_DOCS_ENABLED=false
SPRINGDOC_SWAGGER_UI_ENABLED=false
```

## Segurança operacional

Ainda não existe segurança funcional no projeto. Por isso, a Swagger UI e o documento OpenAPI não devem ser expostos publicamente em produção. Quando a segurança for implementada, a política de acesso à documentação deverá ser definida junto com autenticação e autorização.

## Testes

Os testes integrados verificam:

- resposta `200` do documento JSON;
- resposta `200` do documento YAML;
- título e versão da API;
- presença dos paths GET e POST;
- presença dos schemas dos Records;
- redirecionamento correto para a Swagger UI.
