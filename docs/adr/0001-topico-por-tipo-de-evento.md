# ADR 0001 — Tópico Kafka por tipo de evento

- **Status:** aceita
- **Data:** 20 de agosto de 2026
- **Escopo:** evento de integração `OrderCreated` produzido pelo microsserviço `order`

## Contexto

O primeiro fluxo assíncrono implementado publica somente o evento `OrderCreated`. Um nome de tópico genérico para todos os eventos de pedido não deixava explícito quais contratos poderiam circular pelo tópico e permitiria que novos tipos fossem adicionados sem uma fronteira operacional clara.

O projeto ainda é um piloto de estudos, não possui consumidores implementados e não exige uma janela de compatibilidade com a configuração anterior. Isso permite adotar desde já uma convenção específica por tipo de evento.

## Decisão

Os eventos de integração usarão, inicialmente, um tópico por tipo de evento, seguindo o formato:

```text
market.<contexto>.events.<fato>.v<versão-maior-do-contrato>
```

Para `OrderCreated`, os identificadores oficiais são:

| Camada | Identificador |
|---|---|
| Tópico Kafka | `market.order.events.created.v1` |
| Propriedade Spring | `market.kafka.topics.order-created-events` |
| Variável de ambiente | `ORDER_CREATED_EVENTS_TOPIC` |
| Binding Java | `KafkaTopicProperties.orderCreatedEvents()` |
| Tipo do evento | `OrderCreated` |
| Versão do contrato | `1` |

Regras da convenção:

- `market` identifica a plataforma;
- `order` identifica o bounded context proprietário;
- `events` diferencia fatos publicados de futuros comandos;
- `created` representa o fato no passado e em minúsculas;
- `v1` é a versão maior do contrato, não a versão da aplicação, do Kafka ou da infraestrutura;
- o tópico é destinado exclusivamente a `OrderCreated` enquanto estiver em `v1`; no publicador atual, essa exclusividade é uma precondição e ainda não é validada por roteamento técnico;
- a chave é `orderId`, preservando a afinidade e a ordem dos eventos do mesmo pedido enquanto o número de partições e a estratégia de particionamento forem mantidos;
- alterações incompatíveis de contrato exigem um novo tópico de versão maior;
- alterações compatíveis e aditivas podem permanecer na mesma versão desde que os consumidores tolerem campos desconhecidos.

O contrato completo, os headers e as garantias estão descritos em [`order/docs/kafka-outbox.md`](../../order/docs/kafka-outbox.md).

## Migração aplicada no piloto

Em 20 de agosto de 2026:

1. o nome físico, a propriedade Spring, a variável de ambiente e o accessor Java foram alinhados;
2. infraestrutura, código, testes e documentação foram atualizados sem alias de compatibilidade e sem publicação dupla;
3. o tópico atual foi provisionado no Redpanda local com três partições e retenção de sete dias;
4. o tópico genérico anterior foi removido do broker local;
5. os artefatos gerados foram reconstruídos para eliminar referências obsoletas;
6. a suíte completa do `order`, com 23 testes, passou incluindo PostgreSQL e Kafka reais via Testcontainers.

A remoção física do tópico anterior foi aceita somente por se tratar de um ambiente local de estudos sem consumidores. A exclusão apagou de forma não recuperável pelo broker qualquer mensagem que ainda estivesse armazenada nele.

## Consequências

Benefícios:

- o propósito do tópico é identificável sem inspecionar o payload;
- consumidores assinam apenas o contrato de que precisam;
- retenção, permissões e evolução podem ser tratadas por evento;
- a presença de um tipo inesperado passa a ser uma violação explícita do contrato.

Custos e restrições:

- o número de tópicos crescerá com o catálogo de eventos;
- cada novo evento exigirá definição de infraestrutura, configuração, observabilidade e testes próprios;
- uma jornada que dependa de vários tipos deverá correlacioná-los por `orderId` e metadados;
- o publicador atual não roteia por `eventType`; portanto, somente `OrderCreated` pode entrar no conjunto de registros publicáveis até que exista roteamento explícito.

## Evolução e rollback

Em ambientes compartilhados ou produtivos, uma troca de tópico deverá usar coexistência temporária, migração de consumidores, monitoramento de lag e remoção posterior. A substituição destrutiva aplicada neste piloto não é o procedimento aprovado para produção.

Um rollback local exige recriar o tópico desejado e reconfigurar produtor e consumidores. Mensagens removidas não são restauradas pela recriação do tópico; somente um backup ou replay a partir de outra fonte durável permitiria recuperá-las.

## Referências

- [Arquitetura geral](../architecture.md)
- [Contrato Kafka e Transactional Outbox](../../order/docs/kafka-outbox.md)
- [Infraestrutura Kafka](../../infrastructure/kafka/README.md)
- [Catálogo declarativo de tópicos](../../infrastructure/kafka/topics.yaml)
