# Plano de implementação do `payment`

## 1. Ponto de partida

O `payment` é atualmente um esqueleto Spring Boot 4.0.7 com Java 21 e um teste de contexto. `payment_db` e `payment_user` já foram provisionados no PostgreSQL local, mas o serviço ainda não possui datasource, driver, JPA, Flyway, migrations ou integração funcional com essa infraestrutura.

O desenvolvimento deve começar pelo contrato funcional, e não pelo cliente de um provedor. O `OrderCreated` v1 atual não possui valor, moeda ou token de pagamento e, portanto, não pode disparar uma cobrança.

## 2. Sequência recomendada

### Fase 0 — Decisões funcionais e contrato

- aplicar o [ADR 0002](../../docs/adr/0002-decisoes-checkout-mvp.md): `ChargePayment` com autorização e captura imediata, `PaymentCaptured` como sucesso, moeda única `BRL`, reconciliação para resultado ambíguo e `RefundPayment` como compensação;
- manter `AuthorizePayment`, `PaymentAuthorized` e `CancelPayment` fora do MVP;
- escolher o provedor e validar recursos de idempotência, consulta e webhook;
- definir o formato e a origem segura do token; valor e `BRL` chegam do pedido já precificado;
- fechar schemas e chaves dos contratos e adotar os tópicos-alvo já definidos no catálogo da arquitetura;
- detalhar a implementação da participação e das compensações já definidas para a saga;
- usar o ADR 0002 como decisão normativa e criar novos ADRs somente para deltas reais.

### Fase 1 — Núcleo de domínio

- modelar `Payment`, dinheiro, estados e transições;
- implementar o caso de uso de cobrança com captura imediata;
- criar a porta do provedor e um adaptador fake;
- cobrir regras, duplicidades e estados terminais com testes unitários.

### Fase 2 — Persistência isolada — infraestrutura provisionada, integração pendente

- usar o `payment_db` e o `payment_user` já provisionados;
- configurar o datasource exclusivo do serviço;
- adicionar JPA, PostgreSQL e Flyway;
- criar migrations para pagamento, inbox, outbox e tentativas;
- garantir unicidade das chaves idempotentes e controle de concorrência;
- validar as migrations com Testcontainers.

### Fase 3 — Entrada Kafka idempotente

- adicionar o consumidor idempotente de `ChargePayment`;
- validar envelope e schema;
- persistir inbox e mudança de estado na fronteira transacional adequada;
- configurar retries e DLT por categoria de falha;
- testar redelivery, concorrência e mensagens inválidas.

### Fase 4 — Provedor externo

- implementar o adaptador sandbox do provedor escolhido;
- externalizar credenciais e configurações;
- configurar timeouts e circuit breaker;
- propagar idempotency key;
- implementar consulta ou reconciliação de resultados ambíguos;
- implementar webhook autenticado e idempotente somente se necessário.

### Fase 5 — Saída transacional

- persistir `PaymentCaptured`, `PaymentDeclined`, `PaymentFailed` sem efeito financeiro ou `PaymentReconciliationRequired` na Outbox junto da transição de pagamento;
- publicar com garantia at-least-once;
- adicionar retry limitado, DLT ou tratamento operacional aplicável;
- integrar o consumo dos resultados no `order`;
- testar perda de acknowledgement, republicação e deduplicação.

### Fase 6 — Compensações

- liberar estoque quando o pagamento impedir a conclusão da compra;
- implementar `RefundPayment` idempotente quando uma captura não puder permanecer;
- publicar `PaymentRefunded` no sucesso; falha ou resultado ambíguo não pode ser presumido como reembolso concluído;
- manter `CancelPayment` fora do MVP;
- registrar e observar compensações pendentes ou esgotadas;
- testar eventos fora de ordem e retornos tardios do provedor.

### Fase 7 — Operação e segurança

- adicionar Actuator, métricas e tracing;
- garantir sanitização de logs e mensagens;
- criar imagem Docker e configuração no Compose;
- adicionar manifests Kubernetes, probes e recursos;
- documentar runbook de reconciliação, DLT e incidentes;
- revisar o escopo de PCI DSS e privacidade.

## 3. Dependências externas ao serviço

O primeiro fluxo depende de entregas fora do `payment`:

- implementação do total final do pedido a partir do snapshot de preço, com moeda `BRL`;
- captura segura do meio de pagamento e geração de token;
- implementação da etapa de estoque que precede o pagamento;
- evolução do `order` para enviar o comando e consumir o resultado;
- provisionamento declarativo dos novos tópicos;
- inclusão do serviço `payment` na infraestrutura local; o banco e o usuário já estão provisionados.

## 4. Ordem de entrega sugerida

O menor corte vertical deve usar um provedor fake e implementar `ChargePayment` como autorização com captura imediata em `BRL`, produzindo captura, recusa, falha conhecida ou reconciliação. `RefundPayment` completa a compensação financeira exigida pelo checkout MVP. Autorização sem captura, `PaymentAuthorized`, `CancelPayment`, webhooks e API administrativa ficam para evoluções posteriores.

Cada fase deve manter o build verde, atualizar a especificação e marcar as tarefas concluídas em [`tasks.md`](tasks.md).
