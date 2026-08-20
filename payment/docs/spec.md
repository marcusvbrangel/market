# Especificação inicial do microsserviço `payment`

## 1. Propósito

O `payment` será o proprietário do contexto de pagamentos do Market. Sua responsabilidade será transformar uma solicitação de pagamento associada a um pedido em um resultado confiável, auditável e idempotente, isolando o restante do sistema das particularidades do provedor externo.

Este documento separa deliberadamente o que existe hoje da arquitetura proposta. O [ADR 0002](../../docs/adr/0002-decisoes-checkout-mvp.md) já aprovou captura imediata em `BRL`, `ChargePayment`, `PaymentCaptured`, reconciliação de resultado ambíguo e `RefundPayment`. A [arquitetura completa](../../full-architecture.md) fixa também os nomes dos tópicos-alvo; seus schemas e detalhes do provedor ainda precisam ser fechados, e os tópicos de pagamento ainda não foram provisionados.

## 2. Estado implementado

Em 20/08/2026, o código do serviço possui apenas o esqueleto gerado para Spring Boot. O banco e o usuário já foram provisionados separadamente na infraestrutura local:

| Item | Estado |
|---|---|
| Java 21 e Spring Boot 4.0.7 | Implementado |
| Bootstrap `PaymentApplication` | Implementado |
| Nome da aplicação `payment` | Implementado |
| Teste `contextLoads` | Implementado |
| Domínio e casos de uso | Não implementado |
| API REST | Não implementada |
| Consumidor ou produtor Kafka | Não implementado |
| `payment_db` e `payment_user` no PostgreSQL local | Provisionados |
| Datasource, driver PostgreSQL, JPA e Flyway no serviço | Não implementados |
| Migrations e schema do `payment` | Não implementados |
| Integração com provedor de pagamento | Não implementada |
| Idempotência, inbox e outbox | Não implementadas |
| Actuator, métricas e tracing | Não implementados |
| Serviço `payment` no Docker Compose e Kubernetes | Não implementado |

O `pom.xml` usa somente `spring-boot-starter` e `spring-boot-starter-test`. Portanto, o serviço ainda não expõe comportamento funcional.

## 3. Fronteira de responsabilidade

O `payment` deverá:

- receber comandos de pagamento enviados pelo `order`;
- validar dados sob responsabilidade do contexto de pagamento;
- criar e persistir a tentativa de pagamento;
- integrar-se ao provedor por uma porta independente de fornecedor;
- registrar o resultado e a referência retornada pelo provedor;
- publicar eventos de resultado via Kafka;
- deduplicar comandos e operações externas;
- reconciliar respostas ambíguas sem cobrar novamente;
- preservar uma trilha de auditoria sem dados financeiros sensíveis;
- executar `RefundPayment` de forma idempotente quando uma captura não puder permanecer.

O `payment` não deverá:

- alterar diretamente o estado do pedido ou do estoque;
- decidir o próximo passo da saga;
- recalcular o preço da compra;
- enviar notificações ao cliente;
- acessar bancos de outros microsserviços;
- persistir ou transportar PAN, CVV ou credenciais do cliente;
- implementar `AuthorizePayment`, `PaymentAuthorized` ou `CancelPayment` no MVP;
- expor entidades de persistência em contratos de integração.

O `order` permanece como orquestrador e proprietário do ciclo de vida da compra.

## 4. Pré-condições para iniciar um pagamento

O evento `OrderCreated` v1 existente não é suficiente para iniciar um pagamento: ele identifica o pedido e seus itens, mas não define valor monetário, moeda nem uma referência segura do meio de pagamento.

Antes da integração, o fluxo deverá fornecer ao `payment`, por meio de um comando dedicado:

- `orderId` e um identificador único da solicitação;
- valor final imutável para aquela tentativa;
- moeda `BRL`;
- token ou referência segura do meio de pagamento;
- `correlationId` e `causationId`;
- versão do contrato;
- dados estritamente necessários ao provedor e à auditoria.

O valor virá do total consolidado pelo `order` a partir do snapshot de preço devolvido pelo `inventory`. A forma de tokenizar o meio de pagamento ainda precisa de especificação funcional. O `payment` não deve inferir preços a partir dos itens do pedido nem aceitar moeda diferente de `BRL` no MVP.

## 5. Participação proposta na saga

O fluxo aprovado para o MVP, ainda não implementado, é:

1. `order` recebe e persiste o pedido;
2. `inventory` confirma a reserva e devolve o snapshot de preço em `BRL`;
3. `order` persiste o total e envia `ChargePayment` ao `payment`;
4. `payment` persiste a tentativa antes de chamar o provedor;
5. o provedor fake ou real autoriza e captura imediatamente como uma única operação lógica;
6. `payment` publica `PaymentCaptured` no sucesso, `PaymentDeclined` na recusa conhecida, `PaymentFailed` somente quando sabe que não houve efeito financeiro ou `PaymentReconciliationRequired` quando o resultado é desconhecido;
7. após `PaymentCaptured`, `order` confirma a reserva de estoque; somente depois da baixa definitiva confirma o pedido;
8. em recusa ou falha conhecida sem efeito financeiro, `order` libera a reserva;
9. se a captura ocorreu e a compra não puder terminar, `order` envia `RefundPayment` e aguarda o resultado da compensação;
10. `notification` processa a comunicação adequada fora do caminho crítico.

Autorização separada da captura e `CancelPayment` ficam fora do MVP. Um timeout depois de enviar a cobrança não prova falha: o pagamento entra em reconciliação e não deve ser repetido com uma nova chave nem tratado automaticamente como recusado.

## 6. Modelo de domínio proposto

Um agregado `Payment` poderá conter:

- `paymentId`;
- `orderId`;
- valor e moeda como value objects;
- estado atual;
- provedor utilizado;
- referência externa não sensível;
- chave de idempotência;
- motivo sanitizado de recusa ou falha;
- instantes de criação e atualização;
- versão para controle de concorrência.

Estados mínimos do modelo proposto são `PENDING`, `PROCESSING`, `CAPTURED`, `DECLINED`, `RECONCILIATION_REQUIRED`, `REFUNDED` e `FAILED`. `FAILED` somente representa certeza de ausência de efeito financeiro; uma resposta desconhecida permanece em reconciliação.

As transições deverão ser explícitas. Uma mensagem repetida não poderá criar uma nova cobrança, e um retorno tardio do provedor não poderá regredir um estado terminal sem uma regra específica.

## 7. Contratos de integração do MVP

Os contratos financeiros e seus tópicos-alvo estão alinhados ao ADR 0002 e ao catálogo inicial da arquitetura. Os schemas ainda serão fechados e nenhum desses tópicos de pagamento está provisionado:

| Direção | Contrato | Tópico-alvo | Finalidade |
|---|---|---|---|
| `order` → `payment` | `ChargePayment` | `market.payment.commands.charge.v1` | Autorizar e capturar imediatamente um valor em `BRL` |
| `payment` → `order` | `PaymentCaptured` | `market.payment.events.captured.v1` | Informar captura concluída |
| `payment` → `order` | `PaymentDeclined` | `market.payment.events.declined.v1` | Informar recusa definitiva do provedor |
| `payment` → `order` | `PaymentFailed` | `market.payment.events.failed.v1` | Informar falha com certeza de que não houve efeito financeiro |
| `payment` → `order` | `PaymentReconciliationRequired` | `market.payment.events.reconciliation-required.v1` | Informar resultado financeiro desconhecido |
| `order` → `payment` | `RefundPayment` | `market.payment.commands.refund.v1` | Compensar uma captura que não pode permanecer |
| `payment` → `order` | `PaymentRefunded` | `market.payment.events.refunded.v1` | Informar reembolso concluído |
| `payment` → `order` | `PaymentRefundFailed` | `market.payment.events.refund-failed.v1` | Informar falha conhecida do reembolso |

Um resultado ambíguo de refund também exige reconciliação ou revisão manual; nunca será convertido implicitamente em `PaymentRefunded` ou `PaymentRefundFailed`.

Os contratos usarão `orderId` como chave de partição e um envelope versionado sem dados sensíveis. O catálogo-alvo acima está definido, mas ainda não foi materializado em `infrastructure/kafka/topics.yaml` nem no broker; atualmente, somente `market.order.events.created.v1` está catalogado e provisionado.

## 8. Persistência, atomicidade e idempotência

`payment_db` e `payment_user` já são criados por `docker/postgres/init/01-create-databases.sql` e foram provisionados no PostgreSQL local. Esse marco é somente de infraestrutura: o módulo ainda não possui datasource, driver PostgreSQL, JPA, Flyway, migrations ou schema próprio.

A implementação deverá considerar:

- migrations Flyway versionadas no próprio serviço;
- restrição única para a chave idempotente e para o identificador da mensagem consumida;
- tabela de inbox ou mecanismo equivalente para deduplicação durável;
- Transactional Outbox para publicar o resultado junto da mudança de estado;
- locking otimista ou outra proteção contra processamento concorrente;
- histórico de tentativas separado do estado consolidado quando isso trouxer auditabilidade;
- retenção e anonimização compatíveis com as regras futuras de privacidade.

A entrega Kafka será tratada como **at-least-once**. Além da deduplicação interna, a mesma chave idempotente deverá ser enviada ao provedor quando ele oferecer esse recurso.

## 9. Integração com o provedor

O provedor deverá ficar atrás de uma porta de aplicação, permitindo um adaptador fake para testes e um adaptador real quando o fornecedor for escolhido.

Requisitos mínimos:

- timeouts explícitos de conexão e resposta;
- circuit breaker e limites de concorrência quando aplicáveis;
- mapeamento estável entre códigos externos e erros do domínio;
- idempotency key propagada ao provedor;
- consulta ou reconciliação antes de repetir uma operação com resultado ambíguo;
- webhooks autenticados e idempotentes, caso o fornecedor dependa de callbacks;
- segredos apenas por configuração externa.

Retries automáticos não deverão ser aplicados cegamente a uma cobrança: uma falha de rede após o envio pode significar que o provedor concluiu a operação.

## 10. Segurança e observabilidade

Logs, traces, eventos e métricas nunca deverão conter PAN, CVV, token reutilizável, segredo do provedor ou dados pessoais desnecessários. Identificadores de pagamento e referências externas deverão ser mascarados quando necessário.

O serviço deverá expor métricas de:

- pagamentos capturados, recusados e com falha conhecida;
- latência do provedor;
- resultados pendentes de reconciliação;
- mensagens duplicadas;
- lag, retries, DLT e falhas de outbox;
- divergências entre o estado local e o estado do provedor.

Cada operação deverá propagar `correlationId` e trace distribuído sem incluir dados sensíveis.

## 11. Estratégia de testes

A implementação deverá incluir:

- testes unitários das transições e invariantes;
- testes dos casos de uso com um provedor fake determinístico;
- testes de integração com PostgreSQL e Kafka via Testcontainers;
- testes das migrations e restrições de idempotência;
- testes de contrato e serialização das mensagens;
- testes de mensagem duplicada, concorrência e redelivery;
- testes de timeout e resposta ambígua do provedor;
- testes de outbox, retry e DLT;
- teste ponta a ponta do caminho feliz e das compensações principais.

## 12. Decisões em aberto

Antes do desenvolvimento funcional, precisam ser definidos:

- provedor ou adquirente e ambiente sandbox;
- métodos de pagamento aceitos;
- formato do token do meio de pagamento e sua origem segura;
- detalhes do provedor para executar captura imediata em `BRL` e consultar seu estado;
- comportamento do provedor para reembolso e reconciliação, mantendo `RefundPayment` como compensação do MVP;
- tratamento de expiração, timeout e reconciliação;
- necessidade e autenticação de webhooks;
- schemas definitivos dos contratos já nomeados e parâmetros operacionais dos tópicos-alvo;
- política de retry e DLT por categoria de erro;
- retenção de dados e escopo de conformidade, incluindo PCI DSS;
- existência de uma API externa de consulta ou administração.

## 13. Critério de pronto do primeiro incremento

O primeiro incremento funcional estará pronto quando `ChargePayment` puder ser consumido de forma idempotente, a autorização com captura imediata em `BRL` puder ser executada contra um adaptador fake, o estado e a outbox forem persistidos atomicamente e `PaymentCaptured`, recusa, falha conhecida ou reconciliação puderem ser publicados e consumidos pelo `order`. O checkout MVP também exigirá `RefundPayment` idempotente para compensar uma captura, com testes de sucesso, recusa, resultado ambíguo, refund e mensagem duplicada.
