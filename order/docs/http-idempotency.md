# Idempotência HTTP na criação de pedidos

## Objetivo

`POST /api/v1/orders` exige `Idempotency-Key` para que retries do cliente não criem outro pedido nem outra mensagem `OrderCreated` quando a resposta original for perdida.

Esta é a referência operacional da implementação. A decisão arquitetural está no [ADR 0002](../../docs/adr/0002-decisoes-checkout-mvp.md).

## Contrato HTTP

### Chave da requisição

| Regra | Valor |
|---|---|
| Header | `Idempotency-Key` |
| Obrigatório | sim |
| Semântica | valor opaco e case-sensitive |
| Comprimento | 1 a 100 caracteres |
| Formato | `[A-Za-z0-9._:-]{1,100}` |
| Escopo | `(customerId, idempotencyKey)` |
| Expiração no piloto | nenhuma |

Espaços, controles e caracteres fora do formato são rejeitados. A chave não deve conter nome, e-mail, credencial, token ou outro dado sensível.

### Resposta inicial e replay

| Situação | HTTP | `Idempotency-Replayed` | Efeito |
|---|---:|---|---|
| Chave nova e request válido | `201 Created` | `false` | Cria pedido, itens, claim e Outbox. |
| Mesma chave e mesmo request canônico | `201 Created` | `true` | Reproduz `Location` e corpo sem nova gravação de negócio. |
| Mesma chave e request canônico diferente | `409 Conflict` | ausente | Retorna `IDEMPOTENCY_KEY_REUSED`. |
| Chave presente, mas inválida | `400 Bad Request` | ausente | Retorna `INVALID_IDEMPOTENCY_KEY`. |
| Produto repetido | `400 Bad Request` | ausente | Retorna `DUPLICATE_PRODUCT` sem consumir a chave. |
| Header ausente | `400 Bad Request` | ausente | Retorna `IDEMPOTENCY_KEY_REQUIRED`; a requisição não chega ao caso de uso. |
| Bean Validation falha | `400 Bad Request` | ausente | Retorna `INVALID_REQUEST` e `violations`. |
| JSON malformado ou propriedade desconhecida | `400 Bad Request` | ausente | Retorna `INVALID_REQUEST_BODY`. |

Tanto a primeira resposta quanto o replay retornam:

```http
HTTP/1.1 201 Created
Location: /api/v1/orders/{orderId}
Idempotency-Replayed: false|true
Content-Type: application/json
```

O corpo possui apenas:

```json
{
  "id": "e309bd65-d3e7-486f-b115-42e5d8ec5f08",
  "orderNumber": "ORD-20260820-E309BD65",
  "status": "PENDING",
  "createdAt": "2026-08-20T18:00:00.123456Z"
}
```

`Location` é sempre derivado como `/api/v1/orders/{orderId}`. O status HTTP é sempre `201` no sucesso. Esses valores não são armazenados como colunas duplicadas.

Na criação, o `Instant` usado por pedido, resposta e evento é truncado para microssegundos antes da persistência. Essa precisão coincide com `TIMESTAMPTZ` no PostgreSQL: a primeira resposta em memória e o snapshot lido em um replay serializam exatamente o mesmo `createdAt`, sem diferença causada pela perda de nanossegundos no banco.

### Formato dos erros de entrada

No OpenAPI, as respostas `400` e `409` do POST referenciam `#/components/schemas/ApiProblemResponse` com `application/problem+json`. Trata-se do modelo documental do contrato; o handler continua retornando `ProblemDetail` em runtime.

Bean Validation retorna `ProblemDetail` com `code=INVALID_REQUEST` e uma violação por campo rejeitado:

```json
{
  "title": "Invalid request",
  "status": 400,
  "detail": "Request validation failed",
  "code": "INVALID_REQUEST",
  "violations": [
    {
      "field": "items",
      "message": "must not be empty"
    }
  ]
}
```

JSON malformado e propriedades desconhecidas retornam o mesmo erro de contrato:

```json
{
  "title": "Invalid request body",
  "status": 400,
  "detail": "Request body is malformed or contains unsupported fields",
  "code": "INVALID_REQUEST_BODY"
}
```

## Canonicalização e fingerprint

O `OrderCreationRequestHasher` transforma o comando em texto determinístico e retorna `OrderCreationRequestFingerprint`, um único value object com `version` e `hash`. A porta de persistência recebe, grava e compara esse par; a versão não viaja separada do digest nem fica implícita.

O formato canônico atual é `order-creation-v1`:

```text
order-creation-v1
<customerId>
<productId-ordenado-1>:<quantity>
<productId-ordenado-2>:<quantity>
```

Regras aplicadas:

- UUIDs usam sua representação textual canônica;
- itens são ordenados lexicograficamente pelo texto de `productId`;
- cada parte termina com `LF` (`\n`), inclusive o último item;
- o texto é codificado em UTF-8;
- o digest usa SHA-256;
- o resultado possui 64 caracteres hexadecimais minúsculos;
- a ordem original do array `items` não altera o hash;
- espaços e ordem das propriedades do JSON não alteram o hash porque o hash não usa o JSON bruto;
- mudança em `customerId`, `productId` ou `quantity` altera o hash;
- produtos repetidos são rejeitados antes da canonicalização.

O identificador textual `order-creation-v1` versiona o formato canônico e o fingerprint atual usa `version=1`. Uma alteração incompatível exige uma nova versão e estratégia de convivência com fingerprints já persistidos. No replay, versão e hash precisam coincidir; divergência em qualquer um deles produz `409`.

### Vetor de compatibilidade da versão 1

Para o cliente `0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a` e os itens abaixo, já ordenados durante a canonicalização:

```text
order-creation-v1
0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a
11111111-1111-1111-1111-111111111111:1
22222222-2222-2222-2222-222222222222:2
```

O texto termina com `LF`. O resultado conhecido é:

```text
version: 1
hash: 5798d1b87558114d39b20bd51a3ff74cbb3be8e32cca09b2467dc487f2147e96
```

`OrderCreationRequestHasherTest` fixa esse vetor para detectar mudanças acidentais em versão, delimitadores, ordenação, codificação ou digest.

## Tabela `api_idempotency`

A migration `V5__add_order_creation_idempotency.sql` cria a tabela dedicada:

| Coluna | Tipo | Uso |
|---|---|---|
| `customer_id` | UUID | Parte do escopo da chave. |
| `idempotency_key` | `VARCHAR(100)` | Chave opaca enviada pelo cliente. |
| `request_hash_version` | `SMALLINT` | Versão do fingerprint; V5 aceita somente `1`. |
| `request_hash` | `VARCHAR(64)` | SHA-256 do comando canônico. |
| `order_id` | UUID | Reconstrói `id` e determina `Location`. |
| `response_order_number` | `VARCHAR(50)` | Reconstrói `orderNumber`. |
| `response_order_status` | `VARCHAR(20)` | Reconstrói `status`; no fluxo atual deve ser `PENDING`. |
| `response_created_at` | `TIMESTAMPTZ` | Reconstrói `createdAt`. |
| `created_at` | `TIMESTAMPTZ` | Auditoria do claim. |

Proteções do schema:

- chave primária `(customer_id, idempotency_key)`;
- unicidade de `order_id`;
- unicidade auxiliar `(id, customer_id)` em `orders`;
- foreign key composta `(order_id, customer_id)` para `orders (id, customer_id)`, com `ON DELETE RESTRICT`, impedindo que um claim seja associado a pedido de outro cliente;
- foreign key `DEFERRABLE INITIALLY DEFERRED` para permitir o claim antes do insert do pedido na mesma transação;
- check do formato da chave;
- check de `request_hash_version = 1`;
- check do hash hexadecimal SHA-256;
- check de status de resposta `PENDING`;
- check de igualdade entre `created_at` e `response_created_at`.

A tabela não armazena status HTTP, `Location` ou o JSON completo. `CreateOrderResult` é reconstruído com `order_id`, `response_order_number`, `response_order_status` e `response_created_at`; o controller deriva o restante do contrato.

Os registros não expiram nem são removidos automaticamente no piloto.

O PostgreSQL protege a presença conjunta de total e moeda no pedido, exige ambos para status `CONFIRMED`, aceita somente `BRL` quando houver moeda e protege a tríade de preço dentro de cada item. A regra que compara o pedido com todos os seus filhos — todos precificados ou todos não precificados e total igual à soma dos subtotais — permanece no domínio `Order`; uma `CHECK` local não atravessa `orders` e `order_items`.

## Claim e atomicidade

O fluxo de criação é sequencial:

1. a camada REST executa Bean Validation;
2. `CreateOrderService` valida `Idempotency-Key`, rejeita `productId` repetido e deixa o domínio validar o agregado;
3. ainda fora da transação, o service calcula `OrderCreationRequestFingerprint`, cria pedido e evento e trunca o instante comum para microssegundos;
4. `PostgresOrderCreationAdapter.createOrReplay` abre a transação;
5. o adaptador tenta o claim com `INSERT ... ON CONFLICT (customer_id, idempotency_key) DO NOTHING RETURNING order_id`;
6. se o claim for novo, persiste pedido e itens e insere `OrderCreated` em `outbox_messages`; o writer exige essa transação externa por propagação `MANDATORY`;
7. se já existir claim, carrega o registro e compara versão e hash do fingerprint;
8. fingerprint igual reconstrói a resposta marcada como replay; versão ou hash diferente lança o conflito;
9. o adaptador confirma ou reverte a única transação PostgreSQL.

O limite `@Transactional` está em `PostgresOrderCreationAdapter.createOrReplay`, não em `CreateOrderService.create`. Assim, erros de validação e canonicalização ocorrem antes da transação e não reservam a chave; depois que a porta é chamada, claim, pedido, itens e Outbox confirmam juntos ou fazem rollback juntos.

O rollback foi exercitado em PostgreSQL real com `TransactionTemplate`: o teste marca a transação externa como rollback-only, confirma zero claims, pedidos, itens e registros de Outbox e então repete a mesma chave com sucesso. Isso comprova que uma transação revertida libera a chave para nova criação.

A idempotência HTTP impede duplicação da criação local e da intenção na Outbox. Ela não muda a garantia at-least-once do publisher Kafka; consumidores ainda precisam deduplicar `OrderCreated` por `eventId`.

## Exemplos com `curl`

### Primeira criação

```bash
curl --fail-with-body --silent --show-error --include \
  --request POST http://localhost:8080/api/v1/orders \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: checkout-example-001' \
  --data '{
    "customerId": "0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a",
    "items": [
      {
        "productId": "6c20b55a-2e09-4473-98a6-411f48a8bb23",
        "quantity": 2
      }
    ]
  }'
```

O resultado esperado é `201`, `Idempotency-Replayed: false` e um `Location` contendo o novo `orderId`.

### Replay idêntico

Repita exatamente o comando anterior com a mesma chave. O resultado esperado é `201`, `Idempotency-Replayed: true`, o mesmo `Location` e o mesmo corpo.

### Conflito de conteúdo

```bash
curl --fail-with-body --silent --show-error --include \
  --request POST http://localhost:8080/api/v1/orders \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: checkout-example-001' \
  --data '{
    "customerId": "0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a",
    "items": [
      {
        "productId": "6c20b55a-2e09-4473-98a6-411f48a8bb23",
        "quantity": 3
      }
    ]
  }'
```

Como a quantidade mudou, o resultado esperado é `409` com `code=IDEMPOTENCY_KEY_REUSED`.

### Produto repetido

```bash
curl --fail-with-body --silent --show-error --include \
  --request POST http://localhost:8080/api/v1/orders \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: checkout-duplicate-001' \
  --data '{
    "customerId": "0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a",
    "items": [
      {
        "productId": "6c20b55a-2e09-4473-98a6-411f48a8bb23",
        "quantity": 1
      },
      {
        "productId": "6c20b55a-2e09-4473-98a6-411f48a8bb23",
        "quantity": 2
      }
    ]
  }'
```

O resultado esperado é `400` com `code=DUPLICATE_PRODUCT`. Nenhuma linha é criada em `api_idempotency` para essa chave.

### Chave inválida

```bash
curl --fail-with-body --silent --show-error --include \
  --request POST http://localhost:8080/api/v1/orders \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: chave com espaços' \
  --data '{
    "customerId": "0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a",
    "items": [
      {
        "productId": "6c20b55a-2e09-4473-98a6-411f48a8bb23",
        "quantity": 1
      }
    ]
  }'
```

O resultado esperado é `400` com `code=INVALID_IDEMPOTENCY_KEY`.

## Verificação no PostgreSQL

```sql
SELECT
    customer_id,
    idempotency_key,
    request_hash_version,
    request_hash,
    order_id,
    response_order_number,
    response_order_status,
    response_created_at
FROM api_idempotency
ORDER BY created_at DESC;
```

Para um replay válido, devem continuar existindo exatamente um registro de idempotência, um pedido e uma mensagem `OrderCreated` na Outbox.

## Cobertura automatizada

- `OrderCreationRequestHasherTest` fixa `version=1` e o vetor SHA-256 conhecido, além de verificar independência da ordem dos itens e sensibilidade à quantidade.
- `CreateOrderServiceTest` verifica criação, produto repetido e chave inválida antes da persistência.
- `OrderTest` protege também a unicidade de produto no agregado.
- `OrderControllerTest` verifica header obrigatório, headers da resposta e conflito `409`.
- `OrderApplicationTests` aplica as migrations até V6 em PostgreSQL real e cobre as regras introduzidas por V5: replay exato, conflito, ausência de duplicação, rollback via `TransactionTemplate`, validação e constraints.
- `OutboxKafkaIntegrationTests` executa criação e replay antes do publisher e confirma exatamente uma publicação Kafka de `OrderCreated`.

No checkpoint aprovado em 20/08/2026, a suíte completa do `order` executou 50 testes, sem falhas, erros ou testes ignorados.

Esse total pertence ao checkpoint de idempotência anterior à Outbox V6. Após o incremento V6, `./mvnw clean test` executou 73 testes, também sem falhas, erros ou testes ignorados.

## Referências

- [ADR 0002 — Decisões do checkout MVP](../../docs/adr/0002-decisoes-checkout-mvp.md)
- [ADR 0003 — Envelope, roteamento e lease da Outbox](../../docs/adr/0003-envelope-roteamento-e-lease-da-outbox.md)
- [Especificação do `order`](spec.md)
- [Plano do `order`](plan.md)
- [Tarefas do `order`](tasks.md)
- [OpenAPI do `order`](openapi.md)
- [Kafka e Transactional Outbox](kafka-outbox.md)
