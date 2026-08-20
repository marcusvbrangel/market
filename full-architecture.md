# Arquitetura completa do fluxo de compra

## 1. Visão geral

Este documento descreve uma proposta funcional para o fluxo de compra do Market, separando o primeiro corte executável das evoluções esperadas para um e-commerce mais completo.

A saga recomendada é:

> `order` cria a saga → `inventory` reserva tudo ou nada → `payment` cobra de forma idempotente → `inventory` confirma a reserva e baixa o saldo → `order` confirma → `notification` reage fora da saga.

O `order` é o orquestrador. Ele decide qual passo executar, envia comandos aos participantes e interpreta os eventos de resultado. `inventory`, `payment` e `notification` executam somente operações sob sua responsabilidade.

Para o primeiro corte, não é necessário adicionar outro microsserviço. O `inventory` pode manter catálogo e preço fixo em BRL como uma concessão explícita do piloto. Em uma arquitetura mais madura, `catalog/pricing` será a primeira separação recomendada.

As decisões normativas desse recorte estão no [ADR 0002](docs/adr/0002-decisoes-checkout-mvp.md), e as regras para implementação estão nas [diretrizes de desenvolvimento](docs/development-guidelines.md).

## 2. Diagnóstico do repositório

Hoje o projeto possui criação de pedido, não um checkout completo:

- o request aceita somente `customerId`, `productId` e quantidade em [`CreateOrderRequest.java`](order/src/main/java/com/market/order/interfaces/rest/CreateOrderRequest.java);
- `OrderCreated` não possui preço, moeda ou referência de pagamento em [`OrderCreatedEvent.java`](order/src/main/java/com/market/order/application/OrderCreatedEvent.java);
- os únicos estados são `PENDING`, `CONFIRMED` e `REJECTED` em [`OrderStatus.java`](order/src/main/java/com/market/order/domain/OrderStatus.java);
- `inventory` ainda é um bootstrap Spring Boot sem comportamento funcional;
- `payment` ainda é um bootstrap sem domínio ou integração, embora sua infraestrutura de banco já esteja provisionada;
- `notification` já possui starter/configuração SMTP e MailHog local, mas ainda não implementa listener Kafka, template nem envio de e-mail de negócio;
- apenas `market.order.events.created.v1` está catalogado em [`topics.yaml`](infrastructure/kafka/topics.yaml);
- a Outbox V6 resolve a rota por `MessageContract`, persiste tópico, chave, headers e payload finais e os publica sem inferir o destino pelo tipo em [`TransactionalOutboxPublisher.java`](order/src/main/java/com/market/order/infrastructure/messaging/TransactionalOutboxPublisher.java);
- `payment_db` e `payment_user` já são criados por [`01-create-databases.sql`](docker/postgres/init/01-create-databases.sql) e foram provisionados no PostgreSQL local; o microsserviço `payment` ainda não possui datasource, JPA, Flyway nem migrations próprias;
- o total do pedido atualmente comporta somente a soma dos itens. Frete, desconto e imposto ainda não cabem no modelo de [`Order.java`](order/src/main/java/com/market/order/domain/Order.java).

## 3. Correções conceituais

### 3.1 Reservar em vez de consultar e depois reservar

Consultar disponibilidade e depois reservar cria uma condição de corrida:

1. pedido A consulta e encontra uma unidade;
2. pedido B consulta e encontra a mesma unidade;
3. os dois tentam reservá-la.

O comando deve ser único: `ReserveInventory`. Dentro de uma transação local, o `inventory` bloqueia os registros, valida todos os itens e reserva tudo ou nada. A consulta pública de disponibilidade pode existir, mas nunca garante estoque.

No PostgreSQL, `SELECT ... FOR UPDATE` pode proteger as linhas contra alterações concorrentes até o fim da transação. Os produtos devem ser bloqueados em ordem determinística para reduzir deadlocks. Consulte a [documentação oficial do PostgreSQL](https://www.postgresql.org/docs/17/explicit-locking.html).

### 3.2 Comando não é evento

- **Comando**: pedido para um destinatário executar algo. Exemplo: `ReserveInventory`.
- **Evento**: fato ocorrido que pode interessar a vários consumidores. Exemplo: `InventoryReserved`.

`OrderCreated` não deve ser usado como comando implícito para o `inventory`. Isso transformaria o início da saga em coreografia. Na mesma transação que cria o pedido, o `order` deve gravar:

- o evento informativo `OrderCreated`;
- o comando explícito `ReserveInventory`.

Nem toda alteração interna precisa gerar um evento Kafka. Devem ser publicados fatos relevantes para outros contextos. Estados puramente técnicos permanecem no histórico da saga.

### 3.3 Autorização não é pagamento concluído

`PaymentAuthorized` normalmente significa que os fundos foram autorizados, não necessariamente capturados. Para o piloto, o ADR 0002 definiu uma operação única de autorização mais captura imediata:

- comando: `ChargePayment`;
- sucesso: `PaymentCaptured`;
- recusa: `PaymentDeclined`;
- resultado ambíguo: `PaymentReconciliationRequired`.

Autorização e captura separadas podem entrar posteriormente, principalmente quando houver fulfillment e captura no envio. APIs reais de pagamento possuem ciclos assíncronos, autenticação adicional e múltiplos estados. Consulte [Stripe Payment Intents](https://docs.stripe.com/payments/payment-intents) como exemplo de ciclo real.

### 3.4 Notificação não pertence ao caminho crítico

Depois de persistir cada marco aceito da saga, o `order` publica um comando `NotifyOrderMilestone`. O `notification` consome esses comandos, renderiza o template e envia somente e-mail. Ele é passivo em relação à saga: não decide transições, não publica resultados para o `order` e não possui banco, Inbox ou Outbox.

Se o e-mail falhar, a compra continua seu fluxo normal. Retries e DLT pertencem ao consumidor Kafka do `notification`; não existe compensação financeira ou de estoque por falha de notificação.

Sem persistência local existe uma janela de duplicação: se o SMTP aceitar o e-mail e o processo cair antes do commit do offset Kafka, o comando será entregue novamente. No piloto, prioriza-se a entrega e aceita-se essa possibilidade. O `notificationId` deverá ser propagado como chave idempotente quando o provedor de e-mail oferecer esse recurso.

## 4. Escopo concreto do primeiro MVP

O primeiro fluxo completo assumirá:

- um único centro de estoque;
- reserva tudo ou nada;
- preço fixo vindo do `inventory`;
- moeda única `BRL`;
- sem frete, imposto, cupom ou promoção;
- pagamento fake com captura imediata;
- uma tentativa financeira por pedido;
- reserva com TTL configurável, inicialmente de 10 minutos;
- e-mail de desenvolvimento capturado pelo MailHog;
- sem cancelamento depois da confirmação;
- sem carrinho persistente ou entrega.

Nesse MVP, o `inventory` mantém temporariamente produto, preço e estoque. O evento de reserva devolve o snapshot de nome, preço, moeda e versão. O `order` persiste esse snapshot, que não muda retroativamente.

## 5. Fluxo feliz

```mermaid
sequenceDiagram
    actor Client as Cliente
    participant Order as order
    participant Kafka
    participant Inventory as inventory
    participant Payment as payment
    participant Notification as notification
    participant MailHog as MailHog (SMTP local)

    Client->>Order: POST /orders + Idempotency-Key
    Order->>Order: Pedido PENDING + saga + outbox
    Order-->>Kafka: OrderCreated
    Order-->>Kafka: NotifyOrderMilestone(ORDER_RECEIVED)
    Kafka-->>Notification: NotifyOrderMilestone
    Notification->>MailHog: Envia e-mail por SMTP
    Order-->>Kafka: ReserveInventory
    Kafka-->>Inventory: ReserveInventory

    Inventory->>Inventory: Valida e reserva tudo ou nada
    Inventory-->>Kafka: InventoryReserved
    Kafka-->>Order: InventoryReserved

    Order->>Order: Persiste preços, total, reservationId e expiração
    Order-->>Kafka: NotifyOrderMilestone(INVENTORY_RESERVED)
    Kafka-->>Notification: NotifyOrderMilestone
    Notification->>MailHog: Envia e-mail por SMTP
    Order-->>Kafka: ChargePayment
    Kafka-->>Payment: ChargePayment

    Payment->>Payment: Persiste operação e chave idempotente
    Payment->>Payment: Autoriza + captura
    Payment-->>Kafka: PaymentCaptured
    Kafka-->>Order: PaymentCaptured

    Order-->>Kafka: NotifyOrderMilestone(PAYMENT_CAPTURED)
    Kafka-->>Notification: NotifyOrderMilestone
    Notification->>MailHog: Envia e-mail por SMTP
    Order-->>Kafka: CommitInventoryReservation
    Kafka-->>Inventory: CommitInventoryReservation
    Inventory->>Inventory: onHand -= quantidade; reserved -= quantidade
    Inventory-->>Kafka: InventoryReservationCommitted
    Kafka-->>Order: InventoryReservationCommitted

    Order->>Order: Pedido CONFIRMED + saga COMPLETED
    Order-->>Kafka: OrderConfirmed
    Order-->>Kafka: NotifyOrderMilestone(ORDER_CONFIRMED)
    Kafka-->>Notification: NotifyOrderMilestone
    Notification->>MailHog: Envia e-mail por SMTP
```

O `CommitInventoryReservation` é indispensável: reservar não é dar baixa. Sem esse comando, um pedido pago pode continuar somente reservado e expirar incorretamente.

Os caminhos alternativos geram os marcos `INVENTORY_REJECTED`, `PAYMENT_DECLINED`, `PAYMENT_PROCESSING`, `ORDER_REJECTED` e `PAYMENT_REFUNDED` somente depois que o `order` aceita e persiste o fato correspondente. A notificação final de falha ocorre somente depois das compensações necessárias.

## 6. Falhas e compensações

| Situação | Tratamento correto |
|---|---|
| Produto inexistente ou sem saldo | `InventoryReservationRejected`; pedido `REJECTED`; nenhuma compensação |
| Pagamento recusado | `ReleaseInventoryReservation`; pedido somente fica `REJECTED` depois de `InventoryReservationReleased` |
| Falha técnica comprovadamente anterior à cobrança | Liberar a reserva após esgotar os retries definidos |
| Timeout depois de enviar ao provedor | Estado `PAYMENT_RECONCILIATION`; não cobrar novamente nem liberar estoque |
| Pagamento confirmado tardiamente | Se a reserva ainda existir, continuar; caso contrário, reembolsar |
| Reserva expira depois da cobrança | `RefundPayment` |
| Commit do estoque falha temporariamente | Repetir o mesmo comando idempotente |
| Commit é definitivamente rejeitado depois da cobrança | Reembolsar e somente depois rejeitar o pedido |
| Refund fica com resultado desconhecido | `MANUAL_REVIEW`; nunca presumir sucesso |
| Notification falha | Retry/DLT do `notification`; pedido continua `CONFIRMED` |
| Mensagem duplicada nos participantes stateful | Inbox e chave de operação tornam o processamento um no-op |
| Comando de e-mail repetido | Pode gerar e-mail duplicado; usar `notificationId` no provedor quando houver suporte |
| Evento atrasado ou fora de ordem | Validar estado, `causationId`, operação e versão antes de transicionar |

Uma falha de rede do provedor não prova que a cobrança falhou. Repetições devem usar a mesma chave idempotente e consultar o estado canônico do pagamento. Provedores como Stripe oferecem idempotency keys para evitar efeitos duplicados em retries. Consulte a [documentação de idempotência](https://docs.stripe.com/api/idempotent_requests).

## 7. Estados do pedido e da saga

### 7.1 Estado comercial do pedido

| Estado | Significado |
|---|---|
| `PENDING` | A saga está ativa, compensando ou reconciliando |
| `CONFIRMED` | Pagamento capturado e estoque definitivamente baixado |
| `REJECTED` | Nenhum pagamento permanece e nenhum estoque está retido |
| `CANCELLED` | Futuro: cancelamento solicitado depois da criação |

`FAILED` não é um bom estado comercial. Uma recusa de pagamento pode produzir `REJECTED` com `rejectionReason=PAYMENT_DECLINED`. Falhas técnicas pertencem à saga.

### 7.2 Estado técnico da saga

- `WAITING_INVENTORY`;
- `WAITING_PAYMENT`;
- `PAYMENT_RECONCILIATION`;
- `COMMITTING_INVENTORY`;
- `RELEASING_INVENTORY`;
- `REFUNDING_PAYMENT`;
- `COMPLETED`;
- `COMPENSATED`;
- `REJECTED`;
- `MANUAL_REVIEW`.

A tabela da saga deve possuir `deadline_at`, versão otimista, IDs das operações, última falha e histórico das transições. Os timeouts precisam ser persistidos; timers somente em memória seriam perdidos ao reiniciar o `order`.

## 8. Responsabilidades e propriedade dos dados

| Serviço | Responsabilidade | Principais tabelas |
|---|---|---|
| `order` | Pedido, snapshots comprados, estado comercial, saga e compensações | `orders`, `order_items`, `order_status_history`, `purchase_sagas`, `saga_steps`, `inbox_messages`, `outbox_messages`, `api_idempotency` |
| `inventory` | Produto/preço temporariamente, saldo, reserva, expiração, commit e liberação | `products`, `inventory_balances`, `inventory_reservations`, `inventory_reservation_items`, `stock_movements`, inbox e outbox |
| `payment` | Intenção financeira, chamadas ao provedor, captura, reconciliação e refund | `payments`, `payment_operations`, `provider_call_attempts`, `provider_webhook_events`, inbox e outbox |
| `notification` | Consumir marcos e enviar e-mail por SMTP sem influenciar a saga | Nenhuma; serviço stateless |
| `catalog`, futuro | Produto, SKU, nome, preço atual e vigência | `products`, `skus`, `prices` ou `price_quotes` |
| `fulfillment`, futuro | Separação, endereço, envio, transportadora e entrega | `shipments`, `shipment_items`, `tracking_events` |

Nenhum serviço acessa diretamente o banco de outro. IDs como `reservationId` e `paymentId` são referências, não foreign keys distribuídas.

### 8.1 `order_db`

#### `orders`

Manter os campos atuais e adicionar:

- checks garantindo valor e moeda ambos nulos ou ambos presentes;
- pedido `CONFIRMED` obrigatoriamente com total e moeda no cabeçalho; a coerência completa com os itens permanece no domínio;
- moeda nula enquanto não precificado ou exatamente `BRL` quando presente;
- unicidade auxiliar `(id, customer_id)` para sustentar a integridade composta da idempotência;
- motivo obrigatório para `REJECTED`.

#### `order_items`

- manter identidade e posição próprias;
- persistir snapshots de produto e preço;
- garantir que nome, preço unitário e subtotal estejam todos nulos ou todos preenchidos;
- rejeitar produtos repetidos no request e garantir unicidade por pedido e produto.

#### `api_idempotency`

- claim atômico por `(customer_id, idempotency_key)` usando `INSERT ... ON CONFLICT DO NOTHING`;
- chave opaca de 1 a 100 caracteres conforme `[A-Za-z0-9._:-]{1,100}`;
- fingerprint indivisível `OrderCreationRequestFingerprint(version, hash)`, persistido e comparado como par;
- `request_hash_version=1` e hash SHA-256 do request canônico com itens ordenados por `productId`;
- foreign key composta `(order_id, customer_id) → orders (id, customer_id)`, garantindo que claim e pedido pertençam ao mesmo cliente;
- `order_id`, número, status e instante de criação necessários para reconstruir o corpo;
- `createdAt` truncado para microssegundos antes da persistência, preservando a representação exata no replay;
- status HTTP `201` definido pelo contrato e `Location` derivado de `order_id`, sem colunas duplicadas;
- replay com a mesma versão e hash devolvendo o mesmo `201`, `Location` e corpo;
- conflito `409` quando a mesma chave chegar com versão ou hash diferente;
- validação e canonicalização fora da transação; claim, pedido, itens e Outbox dentro de `PostgresOrderCreationAdapter.createOrReplay`;
- retenção sem expiração automática durante o piloto.

O contrato implementado está detalhado em [`order/docs/http-idempotency.md`](order/docs/http-idempotency.md). As respostas OpenAPI `400` e `409` referenciam o schema documental `ApiProblemResponse`; em runtime, o handler emite `ProblemDetail` compatível.

#### `purchase_sagas`

- `saga_id`;
- `order_id UNIQUE`;
- estado e passo atual;
- `deadline_at`;
- IDs de reserva, pagamento e operações;
- falha atual;
- timestamps e versão otimista.

#### `saga_steps`

- passo da saga;
- direção `FORWARD` ou `COMPENSATION`;
- `operation_id` estável;
- `command_message_id`;
- status, tentativa, deadline e resultado.

Compensações devem ser passos persistidos, não flags temporárias.

### 8.2 `inventory_db`

#### `inventory_balances`

Invariantes:

- `on_hand >= 0`;
- `reserved >= 0`;
- `reserved <= on_hand`;
- `available = on_hand - reserved`, calculado e não armazenado como terceira fonte.

Transições:

- reserva: `reserved += quantidade`;
- release ou expiração: `reserved -= quantidade`;
- commit: `on_hand -= quantidade` e `reserved -= quantidade`.

#### `inventory_reservations`

- `reservation_id` estável, preferencialmente gerado pelo orquestrador;
- `order_id` e `saga_id`;
- status `RESERVED`, `RELEASED`, `COMMITTED`, `EXPIRED` ou `REJECTED`;
- hash do request;
- `expires_at`;
- timestamps e versão.

#### `inventory_reservation_items`

- chave composta por reserva e produto/SKU;
- quantidade positiva;
- snapshots de nome, preço, moeda e versão;
- nenhuma alteração de saldo quando a reserva completa for rejeitada.

#### `stock_movements`

Ledger imutável com movimentos como `RECEIVE`, `ADJUST`, `RESERVE`, `RELEASE` e `COMMIT`. Uma constraint por operação, produto e tipo protege contra movimento duplicado.

### 8.3 `payment_db`

O banco `payment_db` e seu proprietário `payment_user` já estão provisionados no workload PostgreSQL local. Esse marco cobre somente a infraestrutura: o módulo `payment` ainda não configura datasource, não possui dependências JPA/Flyway e ainda não criou o schema descrito abaixo.

#### `payments`

- `payment_id` gerado pelo orquestrador;
- `order_id`;
- valor e moeda imutáveis;
- status financeiro;
- provedor;
- referência externa sanitizada;
- timestamps e versão.

Estados mínimos:

- `PENDING`;
- `PROCESSING`;
- `CAPTURED`;
- `DECLINED`;
- `RECONCILIATION_REQUIRED`;
- `REFUNDED`;
- `FAILED`.

#### `payment_operations`

- uma operação lógica de `CHARGE` ou `REFUND`;
- `operation_id UNIQUE`;
- `provider_idempotency_key UNIQUE`;
- valor, status, resultado e timestamps.

#### `provider_call_attempts`

Auditoria sanitizada das chamadas externas, sem PAN, CVV ou payload sensível integral.

#### `provider_webhook_events`

- unicidade por provedor e ID do evento;
- hash do payload;
- status e instantes de recebimento/processamento;
- retenção controlada do conteúdo bruto.

Não manter uma transação PostgreSQL aberta durante a chamada HTTP ao provedor:

1. persistir inbox, pagamento e operação `PROCESSING`;
2. concluir a transação;
3. chamar o provedor com idempotency key;
4. gravar resultado e Outbox em outra transação;
5. reconciliar antes de repetir se o resultado for ambíguo.

Webhooks devem validar assinatura, deduplicar eventos e não confiar na ordem de entrega. Consulte a [orientação oficial sobre webhooks](https://docs.stripe.com/webhooks).

PAN e CVV não devem passar pelo Kafka nem ser persistidos. O PCI SSC proíbe manter o código de verificação depois da autorização. Consulte a [FAQ do PCI SSC](https://www.pcisecuritystandards.org/faqs/1574/).

### 8.4 `notification` stateless

O `notification` não possui banco. O comando `NotifyOrderMilestone` carrega os dados mínimos necessários:

- `notificationId`;
- `orderId`;
- `recipientEmail`;
- `milestone`;
- `sequence`;
- `templateKey` e `templateData`;
- correlação, causa e instante.

O serviço não consulta `order_db`, não emite `NotificationSent` ou `NotificationFailed` e não oferece retorno para a saga. Ele envia o e-mail e confirma o offset Kafka. Falhas temporárias seguem para retry; falhas permanentes, para DLT e alerta operacional.

No ambiente local, o SMTP é o MailHog em `mailhog:1025` pela rede Docker ou `localhost:1025` a partir da máquina. A interface web fica em `http://localhost:8025`. O guia operacional está em [`notification/README.md`](notification/README.md).

## 9. Catálogo inicial de comandos, eventos e tópicos

Todos os tópicos da saga usarão `orderId` como chave Kafka. Isso mantém afinidade dentro de cada tópico, mas não garante ordem entre tópicos diferentes.

| Tipo | Contrato | Tópico |
|---|---|---|
| Evento existente | `OrderCreated` | `market.order.events.created.v1` |
| Comando | `ReserveInventory` | `market.inventory.commands.reserve.v1` |
| Evento | `InventoryReserved` | `market.inventory.events.reserved.v1` |
| Evento | `InventoryReservationRejected` | `market.inventory.events.reservation-rejected.v1` |
| Comando | `ChargePayment` | `market.payment.commands.charge.v1` |
| Evento | `PaymentCaptured` | `market.payment.events.captured.v1` |
| Evento | `PaymentDeclined` | `market.payment.events.declined.v1` |
| Evento | `PaymentFailed` | `market.payment.events.failed.v1` |
| Evento | `PaymentReconciliationRequired` | `market.payment.events.reconciliation-required.v1` |
| Comando | `CommitInventoryReservation` | `market.inventory.commands.commit-reservation.v1` |
| Evento | `InventoryReservationCommitted` | `market.inventory.events.reservation-committed.v1` |
| Evento | `InventoryReservationCommitRejected` | `market.inventory.events.reservation-commit-rejected.v1` |
| Evento | `InventoryReservationExpired` | `market.inventory.events.reservation-expired.v1` |
| Comando | `ReleaseInventoryReservation` | `market.inventory.commands.release-reservation.v1` |
| Evento | `InventoryReservationReleased` | `market.inventory.events.reservation-released.v1` |
| Comando | `RefundPayment` | `market.payment.commands.refund.v1` |
| Evento | `PaymentRefunded` | `market.payment.events.refunded.v1` |
| Evento | `PaymentRefundFailed` | `market.payment.events.refund-failed.v1` |
| Evento | `OrderConfirmed` | `market.order.events.confirmed.v1` |
| Evento | `OrderRejected` | `market.order.events.rejected.v1` |
| Comando | `NotifyOrderMilestone` | `market.notification.commands.notify-order-milestone.v1` |

`PaymentFailed` somente significa que o sistema sabe que nenhum efeito financeiro ocorreu. Resultado desconhecido deve produzir `PaymentReconciliationRequired`.

Retries e DLT são tópicos técnicos derivados, por exemplo:

```text
market.payment.commands.charge.v1.retry
market.payment.commands.charge.v1.dlt
```

Recusa de estoque ou pagamento é resultado de negócio e não deve ir para DLT.

## 10. Envelope dos novos contratos

A fundação deste envelope já está implementada no `order` por `MessageEnvelope` e `MessageContract`. Ela ainda não materializa nem publica um comando da saga: a única rota de negócio registrada continua sendo `OrderCreated` v1, preservado deliberadamente em seu formato legado.

```json
{
  "messageId": "uuid",
  "messageType": "ReserveInventory",
  "schemaVersion": 1,
  "occurredAt": "2026-08-20T18:00:00Z",
  "source": "order",
  "correlationId": "saga-id",
  "causationId": "message-id-anterior",
  "orderId": "uuid",
  "payload": {}
}
```

Regras:

- `messageId` identifica a entrega;
- `operationId` no payload identifica o efeito de negócio;
- retry ou redelivery da mesma intenção preserva `messageId`; uma nova mensagem para a mesma operação poderá usar outro `messageId`, mas deverá conservar `operationId`;
- redelivery conserva o mesmo `operationId`;
- `causationId` pode ser nulo em uma mensagem raiz;
- quando houver resposta, ela usa como `causationId` o comando processado; `NotifyOrderMilestone` não gera resposta;
- `orderId` é a chave Kafka;
- `traceparent` pode ficar em header;
- o `OrderCreated` v1 existente permanece inalterado; uma uniformização exige v2.

Campos essenciais por contrato:

- `InventoryReserved`: `reservationId`, `expiresAt`, itens precificados, `totalAmount` e `currency`;
- `ChargePayment`: `paymentRequestId`, `orderId`, valor, moeda, referência opaca do meio de pagamento e chave idempotente;
- eventos financeiros: `paymentId`, valor, moeda, resultado, referência externa sanitizada e reason code;
- comandos de compensação: IDs das operações originais e motivo estável.
- `NotifyOrderMilestone`: `notificationId`, `orderId`, destinatário, marco, sequência, template e dados mínimos.

Com tópicos separados por evento, não existe ordenação global entre `PaymentCaptured`, `InventoryReservationExpired` e outros eventos. A máquina de estados precisa validar a transição, e não confiar apenas na ordem de chegada.

## 11. Inbox e Outbox

### 11.1 Inbox comum aos consumidores

Os participantes stateful da saga (`order`, `inventory` e `payment`) terão `inbox_messages` com:

- chave primária por `consumer_name` e `message_id`;
- tipo, versão, origem, correlação e causa;
- tópico, partição e offset;
- hash do payload;
- status, instantes e erro sanitizado.

O hash detecta reuso incorreto do mesmo ID com payload diferente. Para operações puramente locais, inbox, alteração de domínio e próxima Outbox ficam na mesma transação. O offset Kafka somente é confirmado depois do commit.

A retenção da Inbox deve ser maior que a retenção Kafka somada às janelas de retry, DLT e replay manual.

O `notification` é a exceção deliberada: não possui Inbox. Ele confirma o offset somente depois do SMTP aceitar a mensagem e usa retry/DLT do Kafka. Essa decisão troca deduplicação durável por simplicidade e statelessness.

### 11.2 Outbox comum aos produtores

No `order`, a migration V6 renomeou a tabela para `outbox_messages` e implementou a estrutura comum. `inventory` e `payment` deverão adotar o mesmo contrato quando passarem a produzir mensagens. A Outbox persiste:

- `message_id`;
- aggregate ID e tipo;
- tipo e versão da mensagem;
- categoria `COMMAND` ou `EVENT`;
- tópico de destino e chave Kafka;
- correlação e causa;
- payload final como `TEXT` validado como objeto JSON e headers textuais como objeto `JSONB`;
- status, tentativas, disponibilidade, lease e timestamps.

O publisher processa sequencialmente até o limite do lote. Cada mensagem é reivindicada individualmente em uma transação curta, passa para `PROCESSING` com um `lease_id` e é enviada fora da transação PostgreSQL. Sucesso ou falha somente pode ser gravado pelo proprietário da lease. Elegibilidade, lease, reagendamento e publicação usam o relógio do PostgreSQL.

No fluxo atual de criação, o writer da Outbox exige propagação transacional `MANDATORY`, portanto a mensagem somente pode ser inserida dentro da transação que grava o pedido. `created_at`, elegibilidade, retry, lease e publicação usam `CURRENT_TIMESTAMP` do PostgreSQL; `occurred_at` continua representando o instante de negócio da mensagem.

`attempts` é incrementado no claim. Uma lease expirada pode ser recuperada enquanto houver orçamento; se a última lease permitida expirar, a mensagem passa para `FAILED` sem novo claim. Continuará existindo uma janela de duplicação se o Kafka confirmar e a atualização do banco falhar.

Transactional Outbox elimina o dual write, mas ainda pode produzir duplicatas. Consumidores idempotentes permanecem obrigatórios. Consulte [AWS Transactional Outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html).

O `notification` não produz mensagens de negócio e não possui Outbox. A publicação em retry ou DLT é responsabilidade técnica da infraestrutura do consumidor, não uma resposta para a saga.

## 12. Timeouts e operação

Valores iniciais sugeridos para o piloto, sempre externalizados:

| Etapa | Sugestão inicial |
|---|---:|
| Resposta do estoque | 30 segundos |
| TTL da reserva | 10 minutos |
| Margem mínima de reserva antes de cobrar | 2 minutos |
| Conexão/resposta do provedor | 2 segundos / 5 segundos |
| Resolução normal do pagamento | 2 minutos |
| Reconciliação antes de alerta manual | 15 minutos |
| Commit ou release | 30 segundos com retries limitados |

O `order` processa deadlines persistidos com um scheduler e locking. A compensação financeira deve continuar sendo reconciliada mesmo depois de gerar alerta operacional.

Métricas essenciais:

- sagas por estado e tempo de permanência;
- reservas ativas, expiradas, liberadas e comprometidas;
- pagamentos capturados, recusados e em reconciliação;
- compensações pendentes e falhas;
- duplicatas detectadas;
- lag, retries, DLT e falhas de Outbox;
- pedidos em `MANUAL_REVIEW`.
- e-mails aceitos pelo SMTP, falhas, retries, DLT e latência de envio.

## 13. Microsserviços adicionais

### 13.1 Não adicionar no primeiro corte

Não é necessário criar agora:

- `checkout-service`: o `order` já é o orquestrador;
- `cart`: desnecessário sem carrinho persistente;
- `customer`: `customerId` pode permanecer opaco;
- `fraud`: pode começar como resposta fake ou capacidade do provedor;
- `tax`, `promotion` e `coupon`: fora do primeiro total;
- um serviço separado para webhook: webhook é um adaptador HTTP do `payment`.

### 13.2 Próximos candidatos

1. **`catalog/pricing`**: primeira extração recomendada quando surgirem promoções, vigência de preço ou múltiplos canais. Entraria antes da reserva com `PriceOrder → OrderPriced`.
2. **`fulfillment/shipping`**: necessário para produtos físicos. Após pagamento e estoque, o pedido está `CONFIRMED`, não `COMPLETED`. `COMPLETED` deveria significar entregue ou finalizado.
3. **`customer`**: quando endereço e contatos forem reais.
4. **`fraud/risk`**: quando possuir sinais, regras e decisões independentes.
5. **`returns`**: outra saga para cancelamento pós-confirmação, devolução e reembolso.

Responsabilidade única não significa um microsserviço para cada substantivo. Separar cedo demais aumentaria contratos, tópicos, falhas e compensações sem adicionar valor ao piloto.

## 14. Ordem de implementação

1. **Decisões consolidadas; implementação funcional da saga ainda pendente:** aplicar o [ADR 0002](docs/adr/0002-decisoes-checkout-mvp.md): captura imediata, BRL, estoque único, preço temporariamente no `inventory`, TTL, estados, produtos repetidos e idempotência HTTP.
2. **Concluído no `order`:** corrigir constraints, rejeitar produtos repetidos e implementar a idempotência HTTP definida no ADR. O aceite inclui vetor conhecido do fingerprint v1, rollback completo via `TransactionTemplate` e criação seguida de replay com uma única publicação Kafka.
3. **Concluído no `order`:** criar o envelope comum, preservar `OrderCreated` v1 e evoluir a Outbox V6 para rotear comandos e eventos com claim curto e lease recuperável. Em 20/08/2026, `./mvnw clean test` executou 73 testes sem falhas, erros ou testes ignorados; o upgrade local V5→V6 preservou duas linhas `PUBLISHED` com rota canônica e headers completos.
4. Adicionar Inbox, saga persistente e histórico no `order`.
5. Implementar `inventory` com produto/preço, reserva tudo ou nada, expiração, release e commit.
6. Fazer o `order` consumir resultados e avançar a saga transacionalmente.
7. Implementar `payment` com provedor fake, captura imediata e reconciliação simulada.
8. Implementar as compensações de release e refund.
9. Criar testes ponta a ponta para o caminho feliz e as falhas.
10. Implementar o `notification` stateless consumindo `NotifyOrderMilestone` e enviando e-mail ao MailHog.
11. Integrar um sandbox real, webhook e controles de segurança.
12. Extrair `catalog/pricing` e adicionar fulfillment quando o primeiro fluxo estiver estável.

## 15. Testes indispensáveis

- duas compras concorrendo pela última unidade;
- repetição de `POST /orders` com a mesma idempotency key;
- mesma chave HTTP com payload diferente;
- redelivery Kafka;
- mesma operação com outro `messageId`;
- mesma mensagem com payload diferente;
- corrida entre commit, release e expiração;
- pagamento recusado;
- timeout ambíguo do provedor;
- pagamento tardio depois da expiração;
- falha de commit depois da cobrança e posterior refund;
- refund com resultado ambíguo;
- notificação falhando sem cancelar o pedido;
- captura dos marcos de e-mail pela API do MailHog;
- redelivery depois do SMTP aceitar a mensagem e antes do commit do offset;
- mensagem fora de ordem;
- crash depois do commit local e antes do acknowledgement Kafka;
- replay de uma mensagem da DLT.

## 16. Decisões consolidadas

As decisões centrais do checkout estão formalizadas no [ADR 0002](docs/adr/0002-decisoes-checkout-mvp.md). Para o primeiro corte vertical, serão adotadas:

- `order` como orquestrador;
- reserva atômica antes do pagamento;
- `inventory` como autoridade temporária de produto e preço;
- BRL e um único centro de estoque;
- captura imediata por `ChargePayment`;
- `PaymentCaptured`, e não `PaymentAuthorized`, como sucesso financeiro do MVP;
- confirmação explícita da reserva antes de confirmar o pedido;
- release somente depois de recusa conhecida;
- reconciliação para resultado financeiro ambíguo;
- refund se a cobrança ocorreu e o estoque não puder ser confirmado;
- notificação stateless, somente de e-mail e fora do caminho crítico;
- MailHog como SMTP e interface de inspeção no ambiente local;
- Inbox e Outbox nos participantes stateful da saga, sem persistência no `notification`;
- um contrato por tópico, conforme o ADR vigente;
- `orderId` como chave Kafka;
- nenhuma informação bruta de cartão no sistema.

Essa arquitetura segue a característica essencial da saga orquestrada: o `order` decide o próximo passo, enquanto os participantes executam transações locais idempotentes e devolvem fatos. Consulte [AWS Saga Orchestration](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/saga-orchestration.html).

## 17. Referências do projeto

- [ADR 0001 — Tópico Kafka por tipo de evento](docs/adr/0001-topico-por-tipo-de-evento.md)
- [ADR 0002 — Decisões do checkout MVP](docs/adr/0002-decisoes-checkout-mvp.md)
- [ADR 0003 — Envelope, roteamento e lease da Outbox](docs/adr/0003-envelope-roteamento-e-lease-da-outbox.md)
- [Diretrizes de desenvolvimento](docs/development-guidelines.md)
- [Arquitetura geral](docs/architecture.md)
