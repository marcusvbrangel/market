# OpenAPI do microsserviço Order

## Estado atual

O microsserviço `order` possui documentação OpenAPI instalada, configurada e testada com `springdoc-openapi 3.0.3`, compatível com Spring Boot 4.

A documentação cobre atualmente:

- `POST /api/v1/orders`;
- `GET /api/v1/orders/{orderId}`;
- contratos de entrada e saída representados por Java Records;
- validações, exemplos e descrições dos campos;
- respostas HTTP `200`, `201`, `400`, `404` e `409`;
- request header obrigatório `Idempotency-Key`;
- response headers `Location` e `Idempotency-Replayed` na criação e no replay.

O comportamento operacional completo da chave está em [`http-idempotency.md`](http-idempotency.md).

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
7. preencher `Idempotency-Key` ao criar um pedido;
8. preencher os demais parâmetros ou o corpo JSON;
9. selecionar `Execute` e analisar a resposta.

Exemplo para criação:

```http
Idempotency-Key: checkout-swagger-001
```

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

A chave aceita de 1 a 100 caracteres de `[A-Za-z0-9._:-]`. A primeira criação e o replay idêntico retornam `201`; `Idempotency-Replayed` vale `false` na primeira resposta e `true` no replay. O replay preserva `Location` e corpo. Reutilizar a chave com outro conteúdo retorna `409`.

`createdAt` é produzido com precisão de microssegundos. O valor é truncado antes da persistência para que a resposta inicial e o replay reconstruído do PostgreSQL tenham representação temporal idêntica.

## Erros de entrada

Falhas de entrada são retornadas em runtime como `ProblemDetail`, com as propriedades adicionais `code` e, quando aplicável, `violations`. Para tornar esse formato visível aos clientes, o OpenAPI usa o modelo documental `ApiProblemResponse`: as respostas `400` e `409` de `POST /api/v1/orders` referenciam `#/components/schemas/ApiProblemResponse` com mídia `application/problem+json`. O handler não instancia esse record; ele continua emitindo `ProblemDetail` compatível com o schema.

| Situação | HTTP | `code` | Detalhe específico |
|---|---:|---|---|
| Bean Validation | `400` | `INVALID_REQUEST` | Array `violations` com `field` e `message` |
| JSON malformado | `400` | `INVALID_REQUEST_BODY` | Corpo não pôde ser interpretado |
| Campo JSON desconhecido | `400` | `INVALID_REQUEST_BODY` | Campo não pertence ao contrato |
| Header obrigatório ausente | `400` | `IDEMPOTENCY_KEY_REQUIRED` | `Idempotency-Key` não foi enviado |
| Chave presente inválida | `400` | `INVALID_IDEMPOTENCY_KEY` | Formato ou tamanho inválido |
| Produto repetido | `400` | `DUPLICATE_PRODUCT` | Mesmo `productId` em mais de um item |
| Chave reutilizada com outro conteúdo | `409` | `IDEMPOTENCY_KEY_REUSED` | Versão ou hash canônico divergente |

Exemplo de Bean Validation:

```json
{
  "title": "Invalid request",
  "status": 400,
  "detail": "Request validation failed",
  "code": "INVALID_REQUEST",
  "violations": [
    {
      "field": "items[0].quantity",
      "message": "must be greater than 0"
    }
  ]
}
```

Exemplo para JSON malformado ou campo desconhecido:

```json
{
  "title": "Invalid request body",
  "status": 400,
  "detail": "Request body is malformed or contains unsupported fields",
  "code": "INVALID_REQUEST_BODY"
}
```

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

Os testes do documento e do controller verificam:

- resposta `200` do documento JSON;
- resposta `200` do documento YAML;
- título e versão da API;
- presença dos paths GET e POST;
- presença dos schemas dos Records;
- presença e obrigatoriedade de `Idempotency-Key` no POST;
- presença da resposta `409` no POST;
- `$ref` para `ApiProblemResponse` nas respostas `400` e `409` do POST;
- presença de `ApiProblemResponse` em `components.schemas`;
- headers `Location` e `Idempotency-Replayed` na resposta `201`;
- `INVALID_REQUEST` e seu array `violations` para Bean Validation;
- `INVALID_REQUEST_BODY` para JSON malformado ou propriedade desconhecida;
- redirecionamento correto para a Swagger UI.
