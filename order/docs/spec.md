# Especificação do microsserviço Order

## 1. Objetivo

O microsserviço `order` é responsável pelo ciclo de vida dos pedidos de compra. A consulta por identificador usa domínio, service de aplicação, porta e adaptador PostgreSQL. O pedido de demonstração é persistido por migration Flyway. A criação exige idempotência HTTP e grava claim, agregado, itens e um evento `OrderCreated` na Transactional Outbox dentro da mesma transação PostgreSQL; um publicador posterior envia o evento ao Kafka.

Esta especificação descreve o que já foi aprovado e implementado. As regras completas do ciclo de vida, os consumidores e a saga serão detalhados conforme as próximas tarefas forem aprovadas.

## 2. Tecnologias atuais

- Java 21;
- Spring Framework 7.0.8;
- Spring Boot 4.0.7;
- Spring MVC por meio de `spring-boot-starter-webmvc`;
- Jakarta Bean Validation por meio de `spring-boot-starter-validation`;
- Spring Data JPA;
- Spring Kafka;
- PostgreSQL;
- Flyway;
- Testcontainers 2.0.5;
- JUnit, Mockito e AssertJ;
- Maven Wrapper;
- springdoc-openapi 3.0.3 e Swagger UI.

## 3. Endpoint implementado

A especificação OpenAPI gerada está disponível em `/v3/api-docs` e `/v3/api-docs.yaml`. A interface Swagger UI está disponível em `/swagger-ui.html`. O guia de uso está em [`openapi.md`](openapi.md).

### Criar pedido

```http
POST /api/v1/orders
Content-Type: application/json
Idempotency-Key: checkout-0f52f7d1-001
```

Contrato de entrada:

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

O contrato de criação não aceita `productName`, `unitPrice`, `subtotal`, `totalAmount` ou `currency`. Esses dados não são informados pelo cliente e serão obtidos ou calculados em uma etapa futura da saga.

Validações:

- `customerId` obrigatório e no formato UUID;
- ao menos um item;
- `productId` obrigatório e no formato UUID;
- o mesmo `productId` não pode aparecer mais de uma vez;
- `quantity` maior que zero;
- `Idempotency-Key` obrigatório, opaco, case-sensitive e compatível com `[A-Za-z0-9._:-]{1,100}`;
- propriedades JSON desconhecidas são rejeitadas.

Erros de entrada usam `ProblemDetail`:

| Situação | HTTP | `code` | Campo adicional |
|---|---:|---|---|
| Bean Validation em campos do request | `400` | `INVALID_REQUEST` | `violations`, com pares `field` e `message` |
| JSON malformado ou propriedade desconhecida | `400` | `INVALID_REQUEST_BODY` | nenhum |
| `Idempotency-Key` ausente | `400` | `IDEMPOTENCY_KEY_REQUIRED` | nenhum |
| `Idempotency-Key` presente, mas inválido | `400` | `INVALID_IDEMPOTENCY_KEY` | nenhum |
| Produto repetido | `400` | `DUPLICATE_PRODUCT` | nenhum |
| Mesma chave com outro request canônico | `409` | `IDEMPOTENCY_KEY_REUSED` | nenhum |

No documento OpenAPI, as respostas `400` e `409` do POST usam `application/problem+json` e referenciam `#/components/schemas/ApiProblemResponse`. Esse record descreve o schema; o handler em runtime continua produzindo `ProblemDetail` compatível.

Exemplo de Bean Validation:

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

JSON sintaticamente inválido e campos não suportados compartilham `INVALID_REQUEST_BODY`, com detail `Request body is malformed or contains unsupported fields`.

Resposta de sucesso:

```http
201 Created
Location: /api/v1/orders/{orderId}
Idempotency-Replayed: false
```

```json
{
  "id": "uuid-gerado",
  "orderNumber": "ORD-20260819-XXXXXXXX",
  "status": "PENDING",
  "createdAt": "2026-08-19T20:00:00.123456Z"
}
```

Uma repetição com a mesma chave e o mesmo request canônico também retorna `201 Created`, o mesmo `Location`, o mesmo corpo e `Idempotency-Replayed: true`. A mesma chave com outro conteúdo retorna `409 Conflict` e código `IDEMPOTENCY_KEY_REUSED`.

O instante de criação é truncado para microssegundos antes de compor pedido, resposta e evento. Essa precisão é preservada pelo PostgreSQL e permite que `createdAt` seja serializado de forma idêntica na resposta inicial e no replay.

Bean Validation e as validações do caso de uso acontecem antes da persistência. A fronteira `@Transactional` está em `PostgresOrderCreationAdapter.createOrReplay`; dentro dela, o claim em `api_idempotency`, o pedido, seus itens e seu evento de Outbox são gravados ou revertidos juntos. O contrato completo, incluindo canonicalização e exemplos, está em [`http-idempotency.md`](http-idempotency.md).

### Consultar pedido por identificador

```http
GET /api/v1/orders/{orderId}
```

O parâmetro `orderId` deve ser um UUID válido e não nulo.

#### Respostas atuais

| Situação | Status HTTP |
|---|---:|
| UUID fictício conhecido | `200 OK` |
| UUID válido diferente do conhecido | `404 Not Found` |
| Valor que não pode ser convertido para UUID | `400 Bad Request` |

O pedido de demonstração persistido pode ser consultado em:

```http
GET http://localhost:8080/api/v1/orders/550e8400-e29b-41d4-a716-446655440000
```

O controller delega a consulta ao `OrderQueryService`, que usa `OrderQueryPort`. O adaptador PostgreSQL implementa essa porta e converte os modelos JPA para o domínio antes da criação da resposta HTTP.

## 4. Contrato de resposta

O contrato HTTP é representado pelo Java Record `OrderResponse`.

### Pedido

| Campo | Tipo | Validação |
|---|---|---|
| `id` | UUID | obrigatório |
| `orderNumber` | String | obrigatório, máximo de 50 caracteres |
| `customerId` | UUID | obrigatório |
| `status` | Enum | obrigatório |
| `items` | Lista de itens | obrigatória e não vazia |
| `totalAmount` | BigDecimal | opcional enquanto `PENDING`; quando presente, maior ou igual a zero |
| `currency` | String | opcional enquanto `PENDING`; quando presente, exatamente 3 caracteres |
| `rejectionReason` | String | opcional, máximo de 500 caracteres |
| `createdAt` | Instant | obrigatório |
| `updatedAt` | Instant | obrigatório |

Os status inicialmente representados são:

- `PENDING`;
- `CONFIRMED`;
- `REJECTED`.

### Item do pedido

| Campo | Tipo | Validação |
|---|---|---|
| `id` | UUID | obrigatório |
| `productId` | UUID | obrigatório |
| `productName` | String | opcional enquanto não enriquecido, máximo de 200 caracteres |
| `quantity` | int | maior que zero |
| `unitPrice` | BigDecimal | opcional enquanto não precificado; quando presente, maior ou igual a zero |
| `subtotal` | BigDecimal | opcional enquanto não precificado; quando presente, maior ou igual a zero |

A lista recebida pelo construtor do Record é copiada para uma lista imutável.

Um `productId` pode aparecer somente uma vez no agregado e possui proteção adicional de unicidade por pedido no PostgreSQL.

## 5. Pedido de demonstração

O pedido de referência inserido e persistido por `V2__insert_sample_order.sql` possui número `ORD-2026-000001`, status `CONFIRMED`, moeda `BRL` e valor total de `6549.40`. O controller não contém mais dados estáticos: toda resposta de sucesso percorre service, porta, repository, adaptador e PostgreSQL.

Seus cinco itens são:

1. Smart TV 55 polegadas 4K;
2. Soundbar com subwoofer Bluetooth;
3. Kit alarme residencial inteligente;
4. Smartphone 5G 256 GB;
5. Câmera de segurança Wi-Fi Full HD, com duas unidades.

## 6. Critérios de aceitação atendidos

- [x] O projeto usa Java Record como contrato REST.
- [x] O Record possui validações declarativas.
- [x] O controller possui validação do parâmetro de entrada.
- [x] `GET /api/v1/orders/{orderId}` está disponível.
- [x] O UUID de demonstração retorna `200 OK` com cinco itens.
- [x] Outro UUID válido retorna `404 Not Found`.
- [x] O projeto compila e o teste de contexto passa com Java 21.
- [x] O controller delega a consulta ao service de aplicação.
- [x] A aplicação possui porta de consulta independente de HTTP e JPA.
- [x] O domínio protege suas invariantes.
- [x] A persistência usa Spring Data JPA e PostgreSQL.
- [x] O schema e o dado de demonstração são versionados pelo Flyway.
- [x] As migrations foram aplicadas e validadas no PostgreSQL 17.10 local.
- [x] A consulta usa um pedido realmente persistido, sem fallback em memória.
- [x] A suíte automatizada possui testes de domínio, service, controller e integração PostgreSQL.
- [x] O teste de integração cria um PostgreSQL descartável com Testcontainers e aplica o Flyway desde um schema vazio.
- [x] `POST /api/v1/orders` cria um pedido `PENDING` e retorna `201 Created`.
- [x] O contrato de criação não recebe nome nem valores de produto.
- [x] Claim idempotente, pedido, itens e Outbox são persistidos na mesma transação.
- [x] O evento `OrderCreated` é publicado no Kafka a partir da Outbox.
- [x] O evento é publicado em `market.order.events.created.v1` com `orderId` como chave.
- [x] A configuração usa `ORDER_CREATED_EVENTS_TOPIC` e binding `orderCreatedEvents`.
- [x] A Outbox somente é marcada como `PUBLISHED` após o acknowledgement do broker.
- [x] O fluxo de criação foi validado manualmente com a aplicação conectada ao PostgreSQL local.
- [x] O pedido, seus itens e o evento correspondente foram confirmados manualmente nas tabelas PostgreSQL.
- [x] Os endpoints REST e seus Records estão documentados em OpenAPI.
- [x] O contrato OpenAPI JSON, YAML e a Swagger UI possuem testes automatizados.
- [x] `POST /api/v1/orders` exige `Idempotency-Key` válido.
- [x] Criação inicial e replay retornam `201`, o mesmo `Location` e o mesmo corpo.
- [x] O header `Idempotency-Replayed` diferencia criação de replay.
- [x] Replay válido não duplica pedido nem `OrderCreated` na Outbox.
- [x] Replay válido seguido da publicação da Outbox produz uma única mensagem Kafka.
- [x] Reuso da chave com outro payload retorna `409` e `IDEMPOTENCY_KEY_REUSED`.
- [x] Produto repetido retorna `400` e `DUPLICATE_PRODUCT` sem consumir a chave.
- [x] A migration V5 cria `api_idempotency` e reforça as constraints de preço e produto.
- [x] A migration V5 persiste `request_hash_version=1`, restringe moeda a `BRL` e vincula claim e pedido pelo mesmo cliente.
- [x] Rollback da transação remove claim, pedido, itens e Outbox e permite nova tentativa da mesma chave.

## 7. Estratégia de testes implementada

| Teste | Escopo |
|---|---|
| `OrderTest` | Invariantes do pedido, itens, totais, rejeição, produto único e imutabilidade |
| `OrderQueryServiceTest` | Delegação à porta e resultado presente ou ausente |
| `CreateOrderServiceTest` | Criação, chave inválida e produto repetido antes da porta de persistência |
| `OrderCreationRequestHasherTest` | Fingerprint v1, vetor SHA-256 conhecido, ordem dos itens e mudança de quantidade |
| `OrderControllerTest` | Contrato JSON, headers de idempotência, `200`, `201`, `400`, `404` e `409` |
| `OrderApplicationTests` | PostgreSQL real, Flyway V5, replay exato, conflito, constraints, rollback via `TransactionTemplate`, JPA, adaptador e Outbox |
| `TransactionalOutboxPublisherTest` | Chave, tópico, headers, sucesso e retry do publicador |
| `OutboxKafkaIntegrationTests` | Criação seguida de replay, PostgreSQL, Flyway, uma única publicação no Kafka e status `PUBLISHED` |

Os testes integrados usam PostgreSQL `17.10-alpine` e Kafka em containers temporários. Eles confirmam a versão 5 do Flyway, consultam o pedido de referência, criam um pedido sem precificação, validam replay e conflito, exercitam constraints, publicam e consomem `OrderCreated` e confirmam a Outbox como `PUBLISHED`. No checkpoint aprovado em 20/08/2026, a suíte completa do `order` executou 50 testes, sem falhas, erros ou testes ignorados.

## 8. Idempotência HTTP e migration V5

A migration `V5__add_order_creation_idempotency.sql`:

- cria `api_idempotency` com chave primária `(customer_id, idempotency_key)`;
- persiste o fingerprint como `request_hash_version=1` e SHA-256 do request canônico, além do snapshot mínimo para reconstruir `CreateOrderResponse`;
- adiciona `UNIQUE (id, customer_id)` em `orders` e relaciona cada claim ao pedido do mesmo cliente pela foreign key composta `(order_id, customer_id)`, diferida;
- não persiste status HTTP, `Location` ou corpo JSON completo;
- adiciona checks de presença conjunta de preço e moeda no pedido;
- exige preço em pedido `CONFIRMED`;
- aceita somente `BRL` quando o pedido possui moeda;
- exige presença conjunta de nome, preço unitário e subtotal no item;
- garante unicidade de `(order_id, product_id)`.

`OrderCreationRequestHasher` devolve `OrderCreationRequestFingerprint(version, hash)`; a porta persiste e compara o value object como uma unidade. O vetor conhecido da versão 1 usa hash `5798d1b87558114d39b20bd51a3ff74cbb3be8e32cca09b2467dc487f2147e96` e protege a compatibilidade do formato canônico.

O claim usa `INSERT ... ON CONFLICT DO NOTHING`. Em conflito, o adaptador compara versão e hash persistidos: fingerprint igual produz replay; divergência em qualquer componente produz `409`. Não existe expiração automática dos registros no piloto.

O PostgreSQL garante que `total_amount` e `currency` apareçam juntos, que um pedido `CONFIRMED` possua ambos, que uma moeda preenchida seja `BRL` e que cada item possua todos ou nenhum dos campos `product_name`, `unit_price` e `subtotal`. A coerência entre o pedido e o conjunto completo de filhos — todos os itens no mesmo estado de precificação e `totalAmount` igual à soma dos subtotais — é protegida pelo domínio `Order`; ela não pode ser expressa pelas constraints `CHECK` locais de V5 entre tabelas.

A decisão está no [ADR 0002](../../docs/adr/0002-decisoes-checkout-mvp.md), e o contrato operacional está em [`http-idempotency.md`](http-idempotency.md).

## 9. Transactional Outbox

O contrato operacional completo é mantido em [`kafka-outbox.md`](kafka-outbox.md). A decisão de usar um tópico por tipo de evento está no [ADR 0001](../../docs/adr/0001-topico-por-tipo-de-evento.md).

A migration `V3__support_order_creation_and_outbox.sql` criou `outbox_events`. Ao criar um pedido, o sistema grava:

- agregado `Order` e seus itens;
- evento `OrderCreated` serializado como JSONB;
- status inicial `PENDING`;
- identificadores do evento e do agregado;
- tipo do agregado e do evento;
- instante de ocorrência;
- contador de tentativas iniciado em zero.

### 9.1 Contrato Kafka

O payload contém `eventId`, `eventType`, `schemaVersion`, `correlationId`, `orderId`, `customerId`, `items` e `occurredAt`. Cada item contém apenas `productId` e `quantity`. Nome e valores dos produtos não fazem parte do comando de criação nem do evento atual.

O publicador consulta registros `PENDING` elegíveis periodicamente usando `FOR UPDATE SKIP LOCKED`. Cada registro `OrderCreated` elegível é enviado para `market.order.events.created.v1` com `orderId` como chave e os headers `eventId`, `eventType`, `schemaVersion`, `correlationId` e `occurredAt`.

Após o acknowledgement do Kafka, o registro muda para `PUBLISHED` e recebe `published_at`. Uma falha incrementa `attempts`, registra `last_error` e agenda `next_attempt_at`; ao atingir o limite configurado, o registro muda para `FAILED`.

A entrega é at-least-once. Consumidores deverão deduplicar eventos por `eventId`.

Mapeamento oficial:

| Camada | Identificador |
|---|---|
| Tópico | `market.order.events.created.v1` |
| Propriedade Spring | `market.kafka.topics.order-created-events` |
| Variável de ambiente | `ORDER_CREATED_EVENTS_TOPIC` |
| Binding Java | `KafkaTopicProperties.orderCreatedEvents()` |

### 9.2 Defaults operacionais

| Configuração | Default |
|---|---|
| Bootstrap Kafka | `localhost:19092` |
| Publisher habilitado | `true` |
| Intervalo do polling | `1000 ms` |
| Tamanho do lote | `50` |
| Máximo de tentativas | `5` |
| Atraso entre tentativas | `5s` |
| Timeout de envio | `10s` |
| Acknowledgement do produtor | `all` |
| Idempotência do produtor | `true` |

### 9.3 Limitação atual de roteamento

O publicador atual envia todos os registros elegíveis para o tópico de criação e não seleciona o destino por `eventType`. Portanto, somente `OrderCreated` pode ser colocado na fila publicável com segurança até que exista roteamento explícito por contrato.

## 10. Aceite manual

O fluxo foi validado manualmente em 19 de agosto de 2026 com a aplicação conectada ao PostgreSQL local. Uma requisição para `POST /api/v1/orders` retornou `201 Created`, status `PENDING` e os identificadores gerados para o novo pedido. Esse aceite é histórico e antecede a obrigatoriedade de `Idempotency-Key`.

Após a requisição, foram confirmados diretamente no PostgreSQL:

- o pedido na tabela `orders`;
- seus itens na tabela `order_items`;
- o evento `OrderCreated` na tabela `outbox_events`, com status `PENDING`.

Essa validação confirmou a persistência do pedido e da Outbox antes da inclusão do publicador. A publicação Kafka foi validada posteriormente por teste automatizado integrado com broker real.

Em 20 de agosto de 2026, na etapa do refactor de nomenclatura, o tópico atual foi provisionado, o tópico genérico anterior foi removido e a suíte então existente passou. Essa execução é histórica, anterior à idempotência HTTP, e não fixa a contagem da suíte atual. A remoção do tópico anterior foi destrutiva e não permite recuperar suas mensagens pelo broker.

## 11. Fora do escopo desta entrega

- alteração de pedidos persistidos após a criação inicial;
- enriquecimento de produto e precificação;
- consumo de mensagens Kafka;
- saga de compra;
- segurança.
