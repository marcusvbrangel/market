# Plano de evolução do microsserviço Order

## 1. Estado atual

O microsserviço expõe `GET /api/v1/orders/{orderId}` e `POST /api/v1/orders`. A consulta delega ao `OrderQueryService`, que usa `OrderQueryPort`; o adaptador PostgreSQL implementa a porta com Spring Data JPA. A criação delega ao `CreateOrderService`, que persiste o pedido, seus itens e um evento `OrderCreated` na Transactional Outbox dentro da mesma transação. Um publisher agendado envia o evento para `market.order.events.created.v1`, aguarda o acknowledgement e registra sucesso, reagendamento ou falha terminal. O schema, a Outbox e o pedido de demonstração são gerenciados pelo Flyway.

## 2. Direção arquitetural

A evolução seguirá DDD tático leve e separação pragmática de responsabilidades:

```text
interfaces/rest/   controller e contratos HTTP
application/       casos de uso e portas
domain/            modelo e regras de negócio
infrastructure/    JPA, PostgreSQL, Kafka e configurações técnicas
```

O controller não deverá conter regras de negócio nem conhecer detalhes de persistência. O Record REST não será usado como entidade de domínio ou entidade JPA.

## 3. Etapas planejadas

### Etapa 1 — Domínio — concluída

Criar o modelo de domínio do pedido e de seus itens, incluindo:

- identidade do pedido;
- número e cliente;
- itens;
- valores monetários;
- status inicial;
- datas relevantes;
- invariantes de quantidade, preços, total e lista de itens;
- comportamento de transição de estado apenas quando as regras forem especificadas.

As regras deverão ser testadas sem carregar o contexto Spring.

### Etapa 2 — Service de aplicação — concluída

Criar o caso de uso de consulta por identificador. O service deverá:

1. receber o UUID do pedido;
2. consultar uma porta de saída;
3. representar a ausência do pedido sem depender de HTTP;
4. devolver um resultado que possa ser convertido para `OrderResponse`.

O `OrderController` passará a delegar ao service. A tradução para `200 OK` ou `404 Not Found` permanecerá na camada REST.

### Etapa 3 — Persistência — concluída

Implementar o adaptador PostgreSQL:

- adicionar Spring Data JPA e o driver PostgreSQL;
- criar os modelos de persistência de pedido e itens;
- criar o repository Spring Data;
- implementar a porta definida pela aplicação/domínio;
- mapear explicitamente domínio e persistência;
- configurar a conexão com `order_db` usando `order_user`;
- remover o pedido fictício do controller.

O domínio não dependerá de JPA.

### Etapa 4 — Flyway — concluída

Adicionar Flyway e criar a primeira migration versionada para:

- tabela de pedidos;
- tabela de itens do pedido;
- chaves primárias e estrangeiras internas ao banco `order_db`;
- índices necessários para a consulta;
- restrições que também possam ser garantidas pelo banco.

O Hibernate deverá validar o schema criado pelo Flyway, sem criar ou atualizar tabelas automaticamente.

### Etapa 5 — Verificação — concluída

Validar o fluxo completo com:

- testes unitários do domínio;
- testes do service com doubles da porta;
- teste do controller;
- teste de integração da persistência com PostgreSQL real via Testcontainers;
- teste das migrations Flyway;
- consulta manual pelo Postman usando o mesmo endpoint já publicado.

Foram implementados testes unitários de domínio e service, testes isolados do controller e testes integrados com PostgreSQL real via Testcontainers. O pedido de referência é criado pelo Flyway e consultado pelo fluxo real da aplicação. A nova suíte não depende do PostgreSQL compartilhado do ambiente local.

## 4. Decisões que serão confirmadas durante a implementação

- estratégia para gerar `orderNumber`;
- representação de valores monetários no domínio;
- formato do erro HTTP;
- localização do mapeamento entre domínio e REST;
- detalhes das entidades de persistência e dos índices;
- dados iniciais para desenvolvimento e testes.

Na etapa descrita acima, a publicação Kafka e o avanço da saga ainda não faziam parte do plano imediato. A publicação foi concluída posteriormente na etapa 7; o avanço da saga continua pendente de especificação própria.

## 5. Criação de pedido e outbox — concluída

Foram implementados:

- `CreateOrderRequest` com somente cliente, produto e quantidade;
- `CreateOrderResponse` enxuto;
- `CreateOrderService` e `OrderCreationPort`;
- persistência de pedido e outbox na mesma transação;
- geração de UUID e número de pedido;
- suporte de domínio a pedidos `PENDING` ainda não precificados;
- migration incremental V3;
- serialização do evento com Jackson 3;
- testes unitários, de controller e de integração com Testcontainers.

O publicador da Outbox, suas políticas de retry e a integração Kafka foram implementados sem misturar a publicação com a transação de criação.

## 6. Aceite manual — concluído

O fluxo de criação foi executado com a aplicação conectada ao PostgreSQL local. Foram validados:

- retorno `201 Created` pelo endpoint `POST /api/v1/orders`;
- criação do pedido com status `PENDING`;
- persistência do pedido e de seus itens;
- persistência do evento `OrderCreated` em `outbox_events` com status `PENDING`;
- ausência de publicação Kafka, conforme o escopo aprovado para esta etapa.

## 7. Publicação da Outbox no Kafka — concluída

Foram implementados:

- tópico declarativo específico para `OrderCreated`, atualmente chamado `market.order.events.created.v1` após o refactor da etapa 9;
- serviço `kafka-init` idempotente no Docker Compose;
- Spring Kafka com producer idempotente e acknowledgement `all`;
- polling agendado da Outbox com lotes configuráveis;
- locking PostgreSQL com `FOR UPDATE SKIP LOCKED` para múltiplas instâncias;
- publicação de `OrderCreated` usando `orderId` como chave;
- headers de identificação, versão, correlação e ocorrência;
- transição para `PUBLISHED` somente após confirmação do Kafka;
- retry com reagendamento, limite de tentativas e estado terminal `FAILED`;
- migration Flyway V4 para metadados de retry;
- testes unitários e integração real com PostgreSQL e Kafka via Testcontainers.

A garantia é at-least-once e os futuros consumidores deverão deduplicar pelo `eventId`. O próximo plano funcional deverá especificar o consumo pelo `inventory` e o início da Saga orquestrada.

## 8. Documentação OpenAPI — concluída

Foram implementados:

- springdoc-openapi compatível com Spring Boot 4;
- metadados da `Market Order API` versão `v1`;
- documentação das operações GET e POST;
- schemas, validações, exemplos, respostas e header `Location`;
- especificações JSON e YAML;
- Swagger UI com execução interativa habilitada;
- testes automatizados do documento e da interface;
- guia operacional em `order/docs/openapi.md`.

## 9. Refactor do tópico `OrderCreated` — concluído

Foi adotada a convenção de tópico por tipo de evento. O refactor alinhou:

- tópico `market.order.events.created.v1`;
- propriedade `market.kafka.topics.order-created-events`;
- variável `ORDER_CREATED_EVENTS_TOPIC`;
- binding `KafkaTopicProperties.orderCreatedEvents()`;
- provisionador, catálogo, testes e documentação.

Por se tratar de um piloto sem consumidores, não houve alias nem publicação dupla. O tópico atual foi provisionado, o tópico genérico anterior foi removido do Redpanda local e os artefatos rastreados foram regenerados. A suíte completa permaneceu verde com 23 testes.

A decisão está registrada no [ADR 0001](../../docs/adr/0001-topico-por-tipo-de-evento.md), e o contrato operacional completo está em [`kafka-outbox.md`](kafka-outbox.md).
