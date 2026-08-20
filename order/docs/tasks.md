# Tarefas do microsserviço Order

Legenda:

- `[x]`: concluída;
- `[ ]`: pendente.

## 1. Contrato REST e demonstração

- [x] Substituir o starter básico pelo `spring-boot-starter-webmvc`.
- [x] Adicionar `spring-boot-starter-validation`.
- [x] Criar o Record `OrderResponse`.
- [x] Criar o Record interno `OrderResponse.Item`.
- [x] Adicionar validações Jakarta aos componentes dos Records.
- [x] Criar os status `PENDING`, `CONFIRMED` e `REJECTED`.
- [x] Tornar imutável a lista de itens recebida pelo Record.
- [x] Criar `OrderController` em `/api/v1/orders`.
- [x] Expor `GET /api/v1/orders/{orderId}`.
- [x] Validar o UUID recebido pelo controller.
- [x] Criar um pedido fictício com cinco itens eletrônicos.
- [x] Retornar `200 OK` para o UUID de demonstração.
- [x] Retornar `404 Not Found` para outro UUID válido.
- [x] Validar manualmente o endpoint no Postman.
- [x] Executar os testes Maven com sucesso.

## 2. Domínio

- [x] Definir as invariantes iniciais de `Order` e `OrderItem`.
- [x] Criar a entidade de domínio `Order` sem dependências do Spring ou JPA.
- [x] Criar o modelo de domínio `OrderItem`.
- [x] Criar a representação de status no domínio.
- [x] Definir a representação de valores monetários e moeda.
- [x] Garantir que um pedido possua pelo menos um item.
- [x] Garantir quantidade maior que zero.
- [x] Garantir preços e subtotais válidos.
- [x] Calcular ou validar o total do pedido no domínio.
- [x] Criar testes unitários para as invariantes do domínio.

## 3. Service de aplicação

- [x] Definir a porta de consulta de pedidos por UUID.
- [x] Criar o caso de uso/service de consulta por identificador.
- [x] Representar pedido não encontrado sem acoplar o service ao HTTP.
- [x] Criar o mapeamento do resultado da aplicação para `OrderResponse`.
- [x] Injetar o service no `OrderController`.
- [x] Remover a construção do pedido fictício do controller.
- [x] Manter `200 OK` para pedido existente.
- [x] Manter `404 Not Found` para pedido inexistente.
- [x] Criar testes unitários do service.
- [x] Criar teste do controller para sucesso, ausência e UUID inválido.

## 4. Persistência PostgreSQL

- [x] Adicionar Spring Data JPA.
- [x] Adicionar o driver PostgreSQL.
- [x] Configurar o datasource de `order_db`.
- [x] Externalizar usuário, senha e URL de conexão.
- [x] Criar os modelos JPA de pedido e itens.
- [x] Configurar o relacionamento entre pedido e itens.
- [x] Adicionar optimistic locking quando o modelo persistente for definido.
- [x] Criar o repository Spring Data.
- [x] Implementar o adaptador da porta de consulta.
- [x] Criar o mapeamento entre persistência e domínio.
- [x] Configurar o Hibernate apenas para validar o schema.

## 5. Flyway

- [x] Adicionar a dependência do Flyway compatível com PostgreSQL.
- [x] Criar `V1__create_orders_and_order_items.sql`.
- [x] Criar a tabela de pedidos.
- [x] Criar a tabela de itens do pedido.
- [x] Criar restrições de integridade e relacionamento.
- [x] Criar índices necessários para consulta.
- [x] Validar a migration no PostgreSQL local.
- [x] Criar teste de integração das migrations com Testcontainers.

## 6. Integração e aceite

- [x] Adicionar Testcontainers para PostgreSQL.
- [x] Criar teste de integração do repository/adaptador.
- [x] Preparar dados de teste de maneira reproduzível.
- [x] Executar toda a suíte Maven.
- [x] Subir a aplicação conectada ao PostgreSQL local.
- [x] Consultar no Postman um pedido realmente persistido.
- [x] Confirmar automaticamente que pedido inexistente retorna `404 Not Found`.
- [x] Substituir o pedido construído no controller por um pedido persistido pelo Flyway.
- [x] Atualizar `spec.md`, `plan.md` e este arquivo após a conclusão.

## 7. Criação de pedido e Transactional Outbox

- [x] Criar `POST /api/v1/orders`.
- [x] Criar `CreateOrderRequest` como Java Record.
- [x] Aceitar somente `customerId`, `productId` e `quantity` na criação.
- [x] Remover nome, preço, subtotal, total e moeda do contrato de criação.
- [x] Validar cliente, itens, produtos e quantidades.
- [x] Rejeitar propriedades JSON desconhecidas.
- [x] Criar resposta com `id`, `orderNumber`, `status` e `createdAt`.
- [x] Retornar `201 Created` e header `Location`.
- [x] Criar `CreateOrderService` e `OrderCreationPort`.
- [x] Gerar UUID e número legível para o pedido.
- [x] Criar pedidos inicialmente com status `PENDING`.
- [x] Permitir pedido `PENDING` ainda não precificado no domínio.
- [x] Refatorar persistência para dados de precificação ainda ausentes.
- [x] Criar migration `V3__support_order_creation_and_outbox.sql`.
- [x] Criar tabela `outbox_events` e seus índices.
- [x] Criar evento `OrderCreated` sem nome ou preço de produto.
- [x] Persistir pedido, itens e outbox na mesma transação.
- [x] Manter a outbox em `PENDING`, sem publisher Kafka, durante a entrega de persistência transacional.
- [x] Usar Jackson 3 para serialização do payload JSONB.
- [x] Testar domínio, service, controller, Flyway, persistência e outbox.
- [x] Executar os 18 testes existentes naquela entrega sem falhas.
- [x] Validar manualmente o `POST /api/v1/orders` com retorno `201 Created`.
- [x] Confirmar manualmente no PostgreSQL a persistência do pedido e de seus itens.
- [x] Confirmar manualmente o evento `OrderCreated` em `outbox_events` com status `PENDING`.

## 8. Publicador da Outbox e Kafka

- [x] Adicionar Spring Kafka ao microsserviço `order`.
- [x] Configurar bootstrap servers por variável de ambiente.
- [x] Configurar producer idempotente com `acks=all`.
- [x] Definir `market.order.events.created.v1` em `infrastructure/kafka/topics.yaml`.
- [x] Criar script idempotente de provisionamento do tópico.
- [x] Integrar o provisionamento ao Docker Compose com `kafka-init`.
- [x] Validar duas execuções consecutivas do provisionador no Redpanda local.
- [x] Criar polling configurável da Outbox.
- [x] Selecionar eventos com `FOR UPDATE SKIP LOCKED`.
- [x] Publicar `OrderCreated` com `orderId` como chave Kafka.
- [x] Publicar headers `eventId`, `eventType`, `schemaVersion`, `correlationId` e `occurredAt`.
- [x] Marcar o evento como `PUBLISHED` somente após acknowledgement do Kafka.
- [x] Criar retry com `attempts`, `next_attempt_at` e `last_error`.
- [x] Encerrar tentativas excedidas com status `FAILED`.
- [x] Criar migration Flyway V4.
- [x] Testar sucesso e falha do publicador com JUnit, Mockito e AssertJ.
- [x] Testar PostgreSQL e Kafka reais com Testcontainers.
- [x] Consumir e validar `OrderCreated` no teste integrado.
- [x] Documentar a garantia at-least-once e a idempotência obrigatória dos consumidores.
- [x] Executar a suíte completa com 21 testes e nenhuma falha.

## 9. OpenAPI e Swagger UI

- [x] Adicionar springdoc-openapi compatível com Spring Boot 4.
- [x] Configurar os metadados da API REST do `order`.
- [x] Documentar `POST /api/v1/orders`.
- [x] Documentar `GET /api/v1/orders/{orderId}`.
- [x] Documentar parâmetros, request body, respostas e header `Location`.
- [x] Adicionar descrições e exemplos aos schemas dos Java Records.
- [x] Expor a especificação em JSON e YAML.
- [x] Expor a Swagger UI em `/swagger-ui.html`.
- [x] Testar paths, schemas e respostas do documento OpenAPI.
- [x] Testar o acesso à Swagger UI.
- [x] Criar o guia `order/docs/openapi.md`.
- [x] Executar a suíte completa com 23 testes e nenhuma falha.

## 10. Refactor e documentação do tópico `OrderCreated`

- [x] Adotar `market.order.events.created.v1` como nome físico do tópico.
- [x] Renomear a propriedade para `market.kafka.topics.order-created-events`.
- [x] Renomear a variável para `ORDER_CREATED_EVENTS_TOPIC`.
- [x] Renomear o binding Java para `orderCreatedEvents`.
- [x] Atualizar o catálogo declarativo e o provisionador local.
- [x] Atualizar testes unitários e de integração.
- [x] Regenerar artefatos rastreados e remover referências obsoletas.
- [x] Provisionar e validar o tópico atual no Redpanda local.
- [x] Remover o tópico genérico anterior do ambiente piloto.
- [x] Executar os 23 testes sem falhas após o refactor.
- [x] Registrar a decisão de nomenclatura no ADR 0001.
- [x] Documentar payload, headers, configuração, garantias, retry e limitações.
- [x] Confirmar por busca integral que não restaram identificadores obsoletos.

## 11. Idempotência HTTP e constraints do pedido

- [x] Registrar as decisões do checkout no ADR 0002.
- [x] Exigir `Idempotency-Key` em `POST /api/v1/orders`.
- [x] Validar chave opaca com 1 a 100 caracteres e formato `[A-Za-z0-9._:-]{1,100}`.
- [x] Definir o escopo da chave por `(customerId, idempotencyKey)`.
- [x] Criar `OrderCreationRequestHasher` com formato canônico versionado.
- [x] Encapsular versão e hash em `OrderCreationRequestFingerprint` e comparar os dois componentes no replay.
- [x] Ordenar itens por `productId` antes de calcular o hash.
- [x] Calcular SHA-256 em hexadecimal minúsculo.
- [x] Rejeitar produto repetido antes de acessar a persistência.
- [x] Proteger produto único também no domínio.
- [x] Criar `CreateOrderResult` para representar criação e replay sem acoplar a aplicação ao HTTP.
- [x] Criar migration `V5__add_order_creation_idempotency.sql`.
- [x] Criar tabela dedicada `api_idempotency`.
- [x] Garantir chave primária por `(customer_id, idempotency_key)`.
- [x] Persistir `request_hash_version=1` e protegê-lo com constraint.
- [x] Adicionar `UNIQUE (id, customer_id)` em `orders` e foreign key composta do claim para o pedido do mesmo cliente.
- [x] Persistir somente os campos necessários para reconstruir `CreateOrderResponse`.
- [x] Criar claim atômico com `INSERT ... ON CONFLICT DO NOTHING`.
- [x] Usar foreign key diferida entre claim e pedido na mesma transação.
- [x] Manter claim, pedido, itens e Outbox atomicamente consistentes.
- [x] Delimitar a transação em `PostgresOrderCreationAdapter.createOrReplay` e manter validação e canonicalização fora dela.
- [x] Truncar o `Instant` da criação para microssegundos e preservar `createdAt` exatamente no replay.
- [x] Retornar `201`, `Location` e `Idempotency-Replayed: false` na primeira criação.
- [x] Retornar `201`, o mesmo `Location`, o mesmo corpo e `Idempotency-Replayed: true` no replay.
- [x] Retornar `409` e `IDEMPOTENCY_KEY_REUSED` para a mesma chave com versão ou hash diferente.
- [x] Retornar `400` e `INVALID_IDEMPOTENCY_KEY` para chave presente inválida.
- [x] Retornar `400` e `DUPLICATE_PRODUCT` para produto repetido.
- [x] Não consumir a chave quando a validação de produto falhar.
- [x] Adicionar constraints de presença conjunta de preço e moeda no pedido.
- [x] Exigir no PostgreSQL que pedido `CONFIRMED` possua `total_amount` e `currency`; manter no domínio a coerência entre esse cabeçalho e os preços de todos os itens.
- [x] Restringir a moeda persistida a `BRL` no PostgreSQL.
- [x] Adicionar constraint de presença conjunta dos campos de preço do item.
- [x] Adicionar unicidade por `(order_id, product_id)`.
- [x] Atualizar o contrato OpenAPI com os headers, a resposta `409` e `$ref` para `ApiProblemResponse` em `400`/`409`.
- [x] Criar testes do fingerprint v1, vetor SHA-256 conhecido e independência da ordem dos itens.
- [x] Criar testes de service para chave inválida e produto repetido antes da porta.
- [x] Criar testes de controller para header obrigatório, replay e conflito.
- [x] Criar testes integrados de V5, replay sem duplicação, conflito e constraints.
- [x] Testar rollback de claim, pedido, itens e Outbox com `TransactionTemplate` e retry posterior da mesma chave.
- [x] Atualizar a integração Kafka para fornecer a chave obrigatória na criação.
- [x] Testar criação seguida de replay e exatamente uma publicação Kafka.
- [x] Criar o guia `order/docs/http-idempotency.md`.
- [x] Registrar a execução final deste incremento: 50 testes, nenhuma falha, nenhum erro e nenhum teste ignorado em 20/08/2026.
- [ ] Avaliar em ADR futuro um harness concorrente apenas de teste; a implementação de produção permanece sequencial e protegida pelo claim atômico do PostgreSQL.

## 12. Envelope comum, roteamento e lease da Outbox V6

- [x] Registrar as decisões no ADR 0003.
- [x] Criar `MessageCategory`, `MessageContract` e `MessageEnvelope`.
- [x] Validar campos obrigatórios, tipo, versão e origem do envelope.
- [x] Preservar o payload flat e os cinco headers de `OrderCreated` v1.
- [x] Definir o registry pela tripla categoria, tipo e versão.
- [x] Rejeitar contrato sem rota explícita, sem default ou fallback.
- [x] Criar factory e writer de `OrderCreated` para separar contrato, rota e persistência.
- [x] Exigir uma transação externa ativa no writer por propagação `MANDATORY`.
- [x] Criar `V6__generalize_outbox_messages.sql` sem alterar V3 ou V4.
- [x] Validar antes do backfill somente os formatos históricos reconhecidos de `OrderCreated`.
- [x] Abortar V6 para formato legado parcial, desconhecido ou inconsistente.
- [x] Renomear `outbox_events` para `outbox_messages`.
- [x] Renomear identidade e tipo para `message_id` e `message_type`.
- [x] Persistir categoria, versão, origem, destino, chave, correlação, causa e headers.
- [x] Converter o payload para `TEXT` validado como objeto JSON.
- [x] Restringir headers a nomes não vazios e valores textuais.
- [x] Preservar payload e estado de entrega das linhas legadas válidas.
- [x] Tornar uma linha legada `PROCESSING` recuperável por lease expirada.
- [x] Fazer o publisher usar somente rota, chave, headers e payload persistidos.
- [x] Reivindicar uma mensagem por transação curta com `FOR UPDATE SKIP LOCKED`.
- [x] Publicar sequencialmente e fora da transação PostgreSQL.
- [x] Proteger conclusão e falha pelo `lease_id` proprietário.
- [x] Recuperar lease expirada enquanto houver orçamento de claims.
- [x] Marcar como `FAILED` a última lease expirada sem novo claim.
- [x] Usar o relógio PostgreSQL para elegibilidade, lease, retry e publicação.
- [x] Usar `CURRENT_TIMESTAMP` do PostgreSQL também no `created_at` da Outbox.
- [x] Fazer `attempts` representar claims realizados.
- [x] Continuar o lote após falha conhecida e interrompê-lo com restauração da flag em `InterruptedException`.
- [x] Configurar `max.block.ms` e lease por variáveis de ambiente.
- [x] Exigir lease maior que timeout, bloqueio máximo Kafka e margem de segurança.
- [x] Testar envelope, factory, registry, migration, repository, propriedades e publisher.
- [x] Testar `OrderCreated` legado e uma rota persistida distinta com PostgreSQL e Kafka reais.
- [x] Validar o upgrade local V5→V6 preservando duas linhas `PUBLISHED`, seus payloads, o tópico canônico e headers completos.
- [x] Documentar que o upgrade exige parar e drenar instâncias V5 antes do rename da tabela.
- [x] Atualizar arquitetura, ADRs, especificação, plano, tarefas e guia operacional.
- [x] Executar `./mvnw clean test` em 20/08/2026: 73 testes, zero falhas, zero erros e zero testes ignorados.
