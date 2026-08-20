# notification

Microsserviço passivo e stateless responsável exclusivamente pelo envio de atualizações de pedido por e-mail.

## Estado atual

O starter de e-mail e a conexão SMTP com o MailHog estão configurados. O consumidor Kafka, o contrato `NotifyOrderMilestone`, os templates, o envio com `JavaMailSender`, retry e DLT ainda fazem parte do plano de implementação; nenhum e-mail de negócio é enviado pelo código atual.

## Responsabilidade

O `notification` deverá:

- consumir comandos `NotifyOrderMilestone` de `market.notification.commands.notify-order-milestone.v1` pelo Kafka;
- renderizar o template correspondente ao marco recebido;
- enviar o e-mail por SMTP;
- confirmar o offset somente depois do envio;
- aplicar retries limitados e encaminhar falhas permanentes para DLT;
- produzir logs, métricas e alertas operacionais.

O serviço não participa das decisões da saga, não publica respostas para o `order`, não acessa bancos de outros serviços e não possui banco, Inbox ou Outbox.

Sem persistência local existe uma janela de duplicação: se o SMTP aceitar a mensagem e o processo cair antes do commit do offset Kafka, o comando será entregue novamente. O `notificationId` deverá ser usado como chave idempotente quando o provedor oferecer esse recurso. No piloto, prioriza-se a entrega, aceitando a possibilidade de e-mail duplicado.

## MailHog local

O Docker Compose executa [MailHog](https://github.com/mailhog/MailHog) como servidor SMTP de desenvolvimento:

| Recurso | Endereço |
|---|---|
| SMTP a partir da máquina local | `localhost:1025` |
| SMTP a partir da rede do Compose | `mailhog:1025` |
| Interface web | `http://localhost:8025` |

Iniciar somente o MailHog:

```bash
docker compose -f compose.yaml up -d mailhog
```

Parar o container sem remover outros serviços:

```bash
docker compose -f compose.yaml stop mailhog
```

O armazenamento do MailHog está configurado em memória. As mensagens capturadas desaparecem quando o container é removido ou recriado.

## Configuração SMTP

O [`application.yaml`](src/main/resources/application.yaml) usa os seguintes defaults:

| Variável | Default | Uso |
|---|---|---|
| `MAIL_HOST` | `localhost` | Host SMTP ao executar o serviço diretamente na máquina |
| `MAIL_PORT` | `1025` | Porta SMTP |
| `MAIL_SMTP_AUTH` | `false` | Habilita autenticação SMTP em outro ambiente |
| `MAIL_SMTP_STARTTLS_ENABLED` | `false` | Habilita STARTTLS em outro ambiente |
| `NOTIFICATION_MAIL_FROM` | `no-reply@market.local` | Remetente do ambiente local |

Quando o `notification` for incluído no Docker Compose, deverá receber `MAIL_HOST=mailhog`. Os defaults sem autenticação e STARTTLS são adequados somente ao MailHog; outros ambientes deverão sobrescrevê-los.

Endereços reais não devem ser usados no piloto enquanto Kafka, MailHog e seus painéis estiverem sem autenticação e controle de acesso.
