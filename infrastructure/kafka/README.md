# Infraestrutura Kafka

Os tópicos Kafka do projeto são definidos e versionados neste diretório. Os microsserviços utilizam os tópicos, mas não possuem permissão nem responsabilidade de criá-los durante o startup.

## Ambiente local

O serviço `kafka-init` do `compose.yaml` aguarda o Redpanda ficar saudável e executa `create-topics.sh`. O script é idempotente: cria somente tópicos ausentes.

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

## Tópicos atuais

| Tópico | Proprietário | Chave | Retenção local |
|---|---|---|---|
| `market.order.events.v1` | `order` | `orderId` | 7 dias |

O fator de replicação é `1` porque o ambiente inicial possui um único broker Redpanda no Kind/Compose. Um ambiente produtivo com três ou mais brokers deverá sobrescrever esse valor para, no mínimo, `3`.

As configurações declaradas em `topics.yaml` são a fonte de verdade. O script atual materializa essa definição para o ambiente local. No Kubernetes, ela será aplicada por um `Job` idempotente quando os artefatos do cluster forem implementados.
