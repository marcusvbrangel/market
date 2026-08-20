# Infraestrutura Kafka

Os tópicos Kafka do projeto são catalogados e versionados neste diretório. Por convenção arquitetural, os microsserviços utilizam os tópicos, mas não são responsáveis por criá-los durante o startup. O piloto local ainda não aplica autenticação ou ACLs que imponham tecnicamente essa separação.

O contrato produzido pelo `order` está detalhado em [`order/docs/kafka-outbox.md`](../../order/docs/kafka-outbox.md). A decisão de nomenclatura está registrada no [ADR 0001](../../docs/adr/0001-topico-por-tipo-de-evento.md).

Este README é a referência para topologia e provisionamento. Payload, headers, retry e garantias pertencem ao documento contratual do `order`; navegação pela interface pertence ao guia do Redpanda Console.

## Convenção de nomes

Os eventos de integração usam inicialmente um tópico por tipo de evento:

```text
market.<contexto>.events.<fato>.v<versão-maior-do-contrato>
```

O sufixo de versão representa uma mudança maior do contrato. Ele não acompanha a versão do microsserviço, do broker ou da infraestrutura.

## Ambiente local

O serviço `kafka-init` do `compose.yaml` aguarda o Redpanda ficar saudável e executa `create-topics.sh`.

```bash
docker compose -f compose.yaml up -d
docker compose -f compose.yaml logs kafka-init
```

O Redpanda Console fica disponível em:

```text
http://localhost:8088
```

O painel usa a conexão interna com o broker, o Schema Registry e a Admin API do Redpanda. Ele permite consultar tópicos, partições, configurações e mensagens do ambiente local.

O guia completo de inicialização, navegação, consulta de eventos e diagnóstico está em [redpanda-console.md](redpanda-console.md).

Para reaplicar apenas o provisionamento:

```bash
docker compose -f compose.yaml run --rm kafka-init
```

O script é idempotente para criação: cria tópicos ausentes e descreve os existentes. Ele não renomeia, exclui nem reconcilia configurações de um tópico já criado.

## Tópicos atuais

| Tópico | Evento | Versão do contrato | Chave | Partições | Replicação | Retenção |
|---|---|---:|---|---:|---:|---|
| `market.order.events.created.v1` | `OrderCreated` | `1` | `orderId` | `3` | `1` | 7 dias |

O tópico usa `cleanup.policy=delete` e retenção de `604800000 ms`. O fator de replicação é `1` porque o ambiente local possui um único broker Redpanda. Um ambiente produtivo com três ou mais brokers deverá usar fator de replicação mínimo `3`.

## Mapeamento da aplicação

| Camada | Identificador |
|---|---|
| Tópico Kafka | `market.order.events.created.v1` |
| Propriedade Spring | `market.kafka.topics.order-created-events` |
| Variável de ambiente | `ORDER_CREATED_EVENTS_TOPIC` |
| Binding Java | `KafkaTopicProperties.orderCreatedEvents()` |

O bootstrap server do `order` é configurado por `KAFKA_BOOTSTRAP_SERVERS`, com default local `localhost:19092`.

## Catálogo e materialização

[`topics.yaml`](topics.yaml) é o catálogo declarativo dos contratos e da topologia desejada. O script [`create-topics.sh`](create-topics.sh) materializa o ambiente local, mas atualmente repete os valores de forma explícita e não interpreta o YAML.

Enquanto essa geração não for automatizada, uma mudança deverá manter sincronizados:

- `topics.yaml`;
- `create-topics.sh`;
- `order/src/main/resources/application.yaml`;
- testes do produtor e de integração;
- documentação do contrato.

No Kubernetes, o catálogo deverá ser materializado por um `Job` idempotente quando os manifests forem implementados.

## Verificação operacional

Listar tópicos:

```bash
docker compose -f compose.yaml exec -T redpanda \
  rpk topic list --brokers redpanda:9092
```

Descrever o tópico atual:

```bash
docker compose -f compose.yaml exec -T redpanda \
  rpk topic describe market.order.events.created.v1 --brokers redpanda:9092
```

Após o refactor de 20 de agosto de 2026, o ambiente local foi verificado com `_schemas` e `market.order.events.created.v1`. O tópico genérico anterior foi removido porque o projeto é um piloto sem consumidores. Essa remoção foi destrutiva e não recupera mensagens sem uma fonte externa de replay ou backup.

Em ambientes compartilhados, mudanças de nome deverão usar coexistência, migração dos consumidores e remoção somente depois da validação de lag e retenção.
