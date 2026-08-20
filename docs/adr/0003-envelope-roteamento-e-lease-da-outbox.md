# ADR 0003 — Envelope, roteamento e lease da Outbox

- **Status:** aceita
- **Data:** 20 de agosto de 2026
- **Escopo:** contratos Kafka novos e publicação da Transactional Outbox do `order`

## Contexto

O `order` já gravava e publicava `OrderCreated` v1, mas a implementação possuía duas limitações antes do início da saga:

- o tópico e os headers eram escolhidos pelo publisher, portanto qualquer outro tipo salvo na tabela seria enviado incorretamente para o tópico de criação;
- o lote permanecia em uma transação PostgreSQL enquanto o processo aguardava o acknowledgement remoto do Kafka.

Também era necessário consolidar o envelope dos novos comandos e eventos sem quebrar o contrato `OrderCreated` v1 já publicado. O incremento não deve ainda criar `ReserveInventory`, iniciar a saga nem provisionar os tópicos futuros.

## Decisão

### Contrato comum para mensagens novas

Todo contrato novo terá um `MessageContract(category, messageType, schemaVersion)`, no qual `category` é `COMMAND` ou `EVENT`. O valor Kafka usará o seguinte envelope:

```json
{
  "messageId": "11111111-1111-1111-1111-111111111111",
  "messageType": "ReserveInventory",
  "schemaVersion": 1,
  "occurredAt": "2026-08-20T20:15:30.123456Z",
  "source": "order",
  "correlationId": "22222222-2222-2222-2222-222222222222",
  "causationId": "33333333-3333-3333-3333-333333333333",
  "orderId": "44444444-4444-4444-4444-444444444444",
  "payload": {}
}
```

`causationId` poderá ser nulo quando não houver uma mensagem anterior. A identificação idempotente do efeito de negócio, como `operationId`, continuará no payload específico. O envelope não substitui o contrato do payload nem elimina a necessidade de schema e validação por tipo e versão.

### Compatibilidade de `OrderCreated` v1

`OrderCreated` v1 permanecerá deliberadamente fora do novo envelope. Serão preservados:

- payload flat com `eventId` e `eventType`;
- tópico `market.order.events.created.v1`;
- chave Kafka igual ao `orderId`;
- os cinco headers `eventId`, `eventType`, `schemaVersion`, `correlationId` e `occurredAt`.

Envelopar esse evento exigiria `OrderCreated` v2. A factory do contrato v1 possui teste que impede a inclusão acidental de campos do envelope.

### Rota resolvida antes da publicação

A rota será resolvida no momento em que a intenção de publicação for criada. O registro da Outbox persistirá o tópico, a chave Kafka, os headers e o texto JSON finais. O publisher apenas transportará esses valores; ele não consultará o tipo para escolher um tópico, não reconstruirá headers e não reserializará o payload.

O registro de rotas aceitará somente a tripla exata de categoria, tipo e versão definida por `MessageContract`. Não haverá tópico default nem fallback. Inicialmente, a única rota de negócio registrada continuará sendo `OrderCreated` v1; novos contratos e tópicos serão adicionados junto com seus respectivos incrementos.

`OrderCreatedOutboxWriter.append` usa propagação transacional `MANDATORY`. Assim, a intenção de publicação não pode ser inserida sem uma transação externa já ativa; no fluxo atual, ela participa da mesma transação que grava claim idempotente, pedido e itens.

### Schema V6

A migration `V6__generalize_outbox_messages.sql` renomeia `outbox_events` para `outbox_messages` e passa a persistir:

- `message_id`, agregado e categoria;
- tipo e versão do contrato;
- origem, destino e chave de partição;
- correlação e causa;
- headers, payload e instante de ocorrência;
- status, tentativas, reagendamento, erro, lease e timestamps de entrega.

O payload passa de `JSONB` para `TEXT` com constraint que exige um objeto JSON válido. Assim, mensagens novas conservam exatamente o texto produzido entre a inserção e o Kafka. Os headers passam a ser um objeto `JSONB` cujos nomes não podem ser vazios e cujos valores devem ser strings. Nomes e valores são contrato, mas a ordem física não é significativa e nomes repetidos não são suportados.

Antes do backfill, a V6 reconhece exatamente dois formatos históricos de `OrderCreated` v1:

1. o formato mínimo antigo, sem `eventType`, `schemaVersion` e `correlationId` no payload;
2. o formato flat completo atual, com os três campos presentes e coerentes.

Nos dois casos, `eventId`, `orderId` e `occurredAt` precisam corresponder às colunas legadas. A presença parcial dos três metadados, um valor divergente ou outro contrato aborta a migration em vez de receber uma rota presumida.

Linhas válidas recebem a rota canônica `market.order.events.created.v1`, a chave `orderId` e os cinco headers que o publisher antigo efetivamente enviava. O backfill deriva esses metadados das colunas e do agregado; ele não modifica o payload histórico. Status, tentativas, reagendamento, erro e timestamps de entrega também são preservados. Uma linha legada em `PROCESSING` recebe uma lease já expirada para que possa ser recuperada com segurança pelo protocolo novo.

O backfill usa o tópico canônico do projeto porque uma migration SQL não lê a variável de ambiente do processo. Uma instalação que tenha usado outro `ORDER_CREATED_EVENTS_TOPIC` deverá reconciliar todas as linhas legadas antes de aplicar V6, sobretudo `PENDING`, `PROCESSING` e qualquer `FAILED` que possa ser reaberta; sem isso, a V6 registrará o tópico canônico inclusive no histórico já publicado. O ambiente local deste piloto usa o nome canônico.

A V6 não é compatível com a execução simultânea das versões da aplicação: o código V5 acessa `outbox_events`, enquanto o código V6 acessa `outbox_messages`. O rollout deve parar e drenar todas as instâncias V5 antes de aplicar a migration e iniciar a versão nova. Essa parada coordenada é aceitável no piloto; uma evolução com disponibilidade contínua exigirá uma migration em etapas, sem rename imediato.

### Claim curto e lease recuperável

Cada execução agendada processará sequencialmente no máximo `batch-size` mensagens. Para cada mensagem:

1. uma transação curta seleciona uma linha elegível com `FOR UPDATE SKIP LOCKED`;
2. a linha passa para `PROCESSING`, incrementa `attempts` e recebe `lease_id` e `lease_until`;
3. a transação é confirmada antes da chamada ao Kafka;
4. o publisher envia fora da transação PostgreSQL e aguarda no máximo `send-timeout`;
5. sucesso ou falha atualiza a linha somente se o mesmo `lease_id` ainda for proprietário do claim;
6. uma lease expirada pode ser recuperada por outra execução;
7. uma tentativa não terminal volta para `PENDING` com `next_attempt_at`; o limite produz `FAILED`.

`attempts` representa claims, e não apenas falhas confirmadas pelo Kafka. Se a última lease permitida expirar, a linha passa para `FAILED` sem novo claim. A elegibilidade, a expiração da lease, o reagendamento e `published_at` usam `CURRENT_TIMESTAMP` do PostgreSQL para que instâncias com relógios locais diferentes compartilhem a mesma referência temporal.

O insert também usa `CURRENT_TIMESTAMP` para `created_at`. `occurred_at` permanece o instante de negócio transportado pela mensagem; timestamps operacionais e decisões de lease pertencem ao relógio do banco.

O default de `lease-duration` é `30s`. Ele deve exceder o orçamento completo de envio: `send-timeout`, `max.block.ms` do producer Kafka e uma margem fixa de segurança de `5s`. Com os defaults, são `10s`, `5000 ms` e `5s`, respectivamente. A configuração é rejeitada quando não respeita essa desigualdade estrita. O scheduler possui um único worker por processo. Não são usados `ExecutorService`, `@Async`, streams paralelos, programação reativa ou virtual threads.

Depois de uma falha conhecida, o publisher registra o resultado e continua sequencialmente com a próxima mensagem elegível. Uma `InterruptedException` restaura a flag de interrupção e encerra o lote atual.

O protocolo continua **at-least-once**. Se o Kafka confirmar e o processo não conseguir persistir `PUBLISHED`, a lease expirará e a mensagem poderá ser entregue novamente. Consumidores stateful deverão deduplicar por `messageId`; para o legado, por `eventId`.

## Consequências

Benefícios:

- comandos e eventos podem compartilhar o mesmo publisher sem compartilhar tópico;
- o destino passa a fazer parte da intenção transacional durável;
- um contrato desconhecido falha cedo, em vez de vazar para outro tópico;
- chamadas Kafka não mantêm transações nem locks PostgreSQL abertos;
- crash durante a publicação possui recuperação automática por lease;
- o JSON publicado não sofre nova serialização;
- `OrderCreated` v1 permanece compatível.

Custos e restrições:

- a tabela e o repositório possuem mais metadados;
- o fluxo é at-least-once e aceita duplicatas;
- `FAILED` ainda requer intervenção operacional;
- não existe backoff exponencial, jitter, DLT ou limpeza automática da Outbox;
- múltiplas instâncias podem publicar simultaneamente e não oferecem ordem global entre tópicos ou produtores; a saga deverá validar estado, versão e causa;
- o catálogo futuro ainda não foi provisionado e nenhum comando de saga é emitido neste incremento.

## Alternativas rejeitadas

- escolher o tópico no publisher por `if`, `switch` ou fallback;
- continuar mantendo a transação PostgreSQL durante o envio Kafka;
- envolver silenciosamente `OrderCreated` v1 no envelope novo;
- editar migrations V3 ou V4 já aplicadas em vez de criar V6;
- introduzir paralelismo no publisher do piloto;
- adotar Debezium ou outro CDC antes de estabilizar o primeiro fluxo.

## Próximo passo

O próximo incremento é adicionar Inbox, estado durável e histórico da saga no `order`. Somente depois disso o contrato `ReserveInventory` deverá ser materializado, provisionado e gravado atomicamente com o início da saga.

## Evidências de implementação

- `MessageEnvelopeTest` fixa os campos do envelope e suas validações essenciais;
- `OrderCreatedOutboxMessageFactoryTest` protege o payload e os headers legados e rejeita contrato sem rota;
- `OutboxMigrationTests` cobre backfill, preservação de estados e aborto para contrato legado desconhecido;
- `OutboxMessageRepositoryTests` cobre claim, retry, falha terminal, recuperação de lease e constraints de headers;
- `OutboxPublisherPropertiesTest` protege o orçamento temporal completo da lease;
- `TransactionalOutboxPublisherTest` cobre rota persistida, continuidade sequencial, interrupção, limite do lote e perda de lease;
- `OutboxKafkaIntegrationTests` publica o contrato legado e uma sonda de roteamento em destinos persistidos distintos.

Em 20/08/2026, `./mvnw clean test` executou 73 testes, com zero falhas, zero erros e zero testes ignorados. O upgrade do banco local de V5 para V6 também foi validado: duas linhas `PUBLISHED` foram preservadas, ambas com o tópico canônico e os cinco headers completos.

## Referências

- [Arquitetura completa do fluxo de compra](../../full-architecture.md)
- [Arquitetura geral](../architecture.md)
- [Diretrizes de desenvolvimento](../development-guidelines.md)
- [Kafka e Transactional Outbox do `order`](../../order/docs/kafka-outbox.md)
- [ADR 0001 — Tópico por tipo de evento](0001-topico-por-tipo-de-evento.md)
- [ADR 0002 — Decisões do checkout MVP](0002-decisoes-checkout-mvp.md)
