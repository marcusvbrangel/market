# payment

Microsserviço responsável pelo pagamento de uma compra no projeto **Market**.

## Estado atual

O serviço está em fase de bootstrap. Hoje ele contém somente:

- Java 21 e Spring Boot 4.0.7;
- o entrypoint `PaymentApplication`;
- `spring.application.name=payment`;
- um teste de carregamento do contexto Spring.

Na infraestrutura local, `payment_db` e `payment_user` já são criados pelo script de inicialização do PostgreSQL e foram provisionados em 20/08/2026. O módulo ainda não configura datasource e não possui JPA, driver PostgreSQL, Flyway, migrations ou repositories.

Também ainda não existem domínio de pagamento, endpoints, integração Kafka ou provedor de pagamento. O `payment` ainda não participa da saga da compra nem está incluído no Docker Compose como serviço executável; somente seu banco já existe no PostgreSQL compartilhado.

## Responsabilidade planejada

O `payment` deverá receber uma solicitação de pagamento do orquestrador `order`, executar a operação em um provedor externo, manter o estado e a trilha de auditoria da tentativa e publicar seu resultado de forma idempotente.

O contrato financeiro do MVP está aprovado no [ADR 0002](../docs/adr/0002-decisoes-checkout-mvp.md): `ChargePayment` executa autorização com captura imediata exclusivamente em `BRL`; `PaymentCaptured` representa sucesso. Um resultado financeiro ambíguo deve ser reconciliado, nunca presumido como sucesso ou falha. Se uma captura precisar ser compensada, o comando será `RefundPayment`. `AuthorizePayment`, `PaymentAuthorized` e `CancelPayment` não fazem parte do MVP.

Ele não será responsável pelo pedido, preço, estoque, notificação ou decisão global da saga. Dados brutos de cartão, como PAN e CVV, não deverão trafegar pelo Kafka, ser persistidos ou aparecer em logs; a integração deverá usar um token ou referência segura fornecida pelo provedor escolhido.

## Documentos

- [Especificação e análise](docs/spec.md)
- [Plano de implementação](docs/plan.md)
- [Checklist de tarefas](docs/tasks.md)
- [Arquitetura geral](../docs/architecture.md)

## Execução atual

```bash
./mvnw test
./mvnw spring-boot:run
```

Como o módulo ainda não possui starter web nem Actuator, ele não abre uma porta HTTP e encerra após inicializar o contexto. A porta operacional será definida somente quando uma interface HTTP realmente for adicionada.
