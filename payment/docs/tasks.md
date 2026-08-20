# Checklist do `payment`

Este checklist reflete o estado observado em 20/08/2026. Itens marcados estão presentes no repositório; os demais formam o backlog proposto e ainda dependem das decisões registradas na [especificação](spec.md).

## 1. Bootstrap

- [x] Criar o módulo Maven `payment`.
- [x] Configurar Java 21 e Spring Boot 4.0.7.
- [x] Criar `PaymentApplication`.
- [x] Configurar `spring.application.name=payment`.
- [x] Adicionar teste de carregamento do contexto.
- [ ] Definir uma porta operacional quando uma interface HTTP for adicionada.
- [ ] Preencher metadados úteis do `pom.xml`.

## 2. Contrato funcional

- [ ] Escolher provedor e ambiente sandbox.
- [ ] Definir meios de pagamento aceitos.
- [x] Adotar `ChargePayment` com autorização e captura imediata em `BRL`.
- [x] Adotar `PaymentCaptured` como sucesso e reconciliação para resultado ambíguo.
- [x] Adotar `RefundPayment` como compensação e excluir `AuthorizePayment`, `PaymentAuthorized` e `CancelPayment` do MVP.
- [x] Definir que valor e moeda vêm do pedido precificado; `BRL` é a única moeda do MVP.
- [ ] Definir origem e formato seguro do token do meio de pagamento.
- [x] Aprovar a etapa financeira e suas compensações centrais na saga.
- [x] Aprovar os nomes financeiros centrais `ChargePayment`, `PaymentCaptured` e `RefundPayment`.
- [x] Adotar os nomes dos tópicos-alvo definidos no catálogo inicial de `full-architecture.md`.
- [ ] Fechar os schemas e parâmetros operacionais dos tópicos de `payment`.
- [x] Registrar captura imediata, BRL e reconciliação no [ADR 0002](../../docs/adr/0002-decisoes-checkout-mvp.md).

## 3. Domínio e aplicação

- [ ] Modelar o agregado `Payment` e seus value objects.
- [ ] Definir estados e transições válidas.
- [ ] Implementar o caso de uso `ChargePayment` com captura imediata.
- [ ] Implementar `RefundPayment` idempotente.
- [ ] Criar uma porta independente para o provedor.
- [ ] Criar um provedor fake determinístico para testes.
- [ ] Impedir nova cobrança para o mesmo comando ou chave idempotente.
- [ ] Tratar retornos tardios e estados terminais.

## 4. Persistência

- [x] Provisionar `payment_db` e `payment_user` no PostgreSQL local.
- [ ] Configurar o datasource do `payment` para seu banco exclusivo.
- [ ] Adicionar JPA, driver PostgreSQL e Flyway.
- [ ] Criar migrations de pagamento e tentativas.
- [ ] Criar mecanismo durável de inbox/deduplicação.
- [ ] Criar Transactional Outbox.
- [ ] Adicionar restrições únicas de idempotência.
- [ ] Proteger atualizações concorrentes.
- [ ] Definir retenção e anonimização de dados.

## 5. Kafka e saga

- [ ] Implementar consumidor idempotente de `ChargePayment` e validar valor em `BRL`.
- [ ] Validar envelope, schema e versão.
- [ ] Usar `orderId` como chave de partição.
- [ ] Publicar por Outbox `PaymentCaptured`, `PaymentDeclined`, `PaymentFailed` sem efeito financeiro ou `PaymentReconciliationRequired`.
- [ ] Configurar retry limitado e DLT.
- [ ] Provisionar os tópicos declarativamente.
- [ ] Integrar envio do comando e consumo do resultado no `order`.
- [ ] Integrar liberação do estoque em falha definitiva.
- [ ] Integrar `RefundPayment` quando uma captura precisar ser compensada.
- [ ] Publicar o resultado do refund e encaminhar resultado ambíguo para reconciliação ou revisão manual.

## 6. Provedor de pagamento

- [ ] Implementar adaptador sandbox.
- [ ] Externalizar credenciais e segredos.
- [ ] Propagar idempotency key ao provedor.
- [ ] Configurar timeouts, circuit breaker e limites de concorrência.
- [ ] Mapear códigos do provedor para erros do domínio.
- [ ] Implementar reconciliação de resposta ambígua.
- [ ] Implementar webhook autenticado e idempotente, se necessário.

## 7. Segurança e observabilidade

- [ ] Garantir ausência de PAN, CVV e segredos em banco, Kafka e logs.
- [ ] Definir mascaramento de referências externas.
- [ ] Adicionar Actuator e probes.
- [ ] Adicionar logs estruturados, métricas e tracing.
- [ ] Criar alertas para falhas, reconciliação, lag, DLT e outbox.
- [ ] Avaliar e documentar escopo de PCI DSS e privacidade.
- [ ] Documentar runbook operacional.

## 8. Testes e entrega

- [ ] Cobrir invariantes e transições com testes unitários.
- [ ] Testar captura imediata, recusa, falha conhecida, resposta ambígua e refund.
- [ ] Testar duplicidade, concorrência e redelivery.
- [ ] Testar migrations e persistência com PostgreSQL real.
- [ ] Testar contratos, Kafka, inbox, outbox, retry e DLT.
- [ ] Criar teste ponta a ponta da saga e das compensações.
- [ ] Criar imagem Docker.
- [ ] Incluir o serviço no Docker Compose.
- [ ] Criar manifests Kubernetes e probes.
- [ ] Incluir o módulo no CI/CD do monorepo.
