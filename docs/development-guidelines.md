# Diretrizes de desenvolvimento

Estas regras orientam todo código novo do projeto Market e devem ser aplicadas com pragmatismo proporcional ao piloto.

## Java 21 moderno e sequencial

- Use Java 21 sem recursos preview.
- Use `record` para DTOs e value objects imutáveis quando eles não tiverem identidade ou ciclo de vida próprio.
- Use sealed classes ou interfaces somente quando a hierarquia for realmente fechada e tornar estados inválidos irrepresentáveis.
- Use pattern matching e `switch` expressions quando reduzirem condicionais e deixarem a regra mais explícita.
- Use text blocks para conteúdo multilinha estático quando melhorarem a legibilidade.
- Use `var` somente em variáveis locais cujo tipo permaneça óbvio no lado direito.
- Prefira imutabilidade e cópias defensivas para coleções recebidas ou expostas.
- Mantenha o fluxo de execução da aplicação sequencial.
- Não crie `Thread` manualmente.
- Não use `ExecutorService`, `ForkJoinPool` ou pools próprios.
- Não use `CompletableFuture` para paralelizar ou desacoplar o fluxo da requisição.
- Não use `parallelStream()` nem converta streams sequenciais em paralelos.
- Não use programação reativa com Reactor, WebFlux, RxJava, Mutiny ou abstração equivalente.
- Não use virtual threads.
- Não use `@Async`.
- Configure listeners Kafka com concorrência `1` por instância enquanto esta diretriz estiver vigente.
- Configure schedulers com uma única execução sequencial por processo.
- Quando uma biblioteca retornar um futuro inevitavelmente, aguarde sua conclusão no mesmo fluxo e não componha trabalho paralelo.
- Não mantenha transação de banco aberta durante chamada HTTP, SMTP ou outra operação remota.
- Proteja o sistema contra requisições, mensagens e réplicas externas concorrentes por constraints, idempotência, locking transacional e máquina de estados.
- Não use locks em memória como garantia distribuída entre instâncias.
- Registre qualquer necessidade futura de concorrência interna, paralelismo, reatividade ou virtual threads em novo ADR antes de implementá-la.

## Java Efetivo e Clean Code

- Aplique as recomendações de Java Efetivo que forem compatíveis com Java 21 e com o contexto do projeto.
- Prefira composição a herança.
- Minimize mutabilidade, visibilidade e escopo.
- Valide parâmetros na fronteira mais próxima de sua entrada.
- Preserve invariantes também no construtor de entidades e value objects.
- Implemente `equals`, `hashCode` e `toString` de forma coerente quando não forem fornecidos adequadamente pelo tipo escolhido.
- Use nomes que expressem intenção de negócio.
- Mantenha métodos pequenos e coesos sem fragmentá-los artificialmente.
- Mantenha uma única razão real de mudança por classe ou componente.
- Remova código morto em vez de comentá-lo.
- Evite comentários que apenas repitam o código.
- Use comentários para explicar decisão, restrição ou motivo não evidente.
- Substitua números e textos mágicos por conceitos nomeados ou configuração externalizada.
- Não capture exceções sem tratamento, tradução ou contexto útil.
- Não use exceções para controle normal de fluxo.
- Preserve a causa original ao traduzir falhas técnicas.

## SOLID e arquitetura limpa

- Aplique responsabilidade única em torno de capacidades coesas, não de classes artificialmente pequenas.
- Estenda comportamento por composição e novos adaptadores quando isso evitar alteração arriscada do núcleo.
- Preserve substituibilidade nos contratos de portas e implementações.
- Mantenha interfaces pequenas e orientadas ao consumidor.
- Faça casos de uso e domínio dependerem de abstrações somente em fronteiras externas reais.
- Não crie interfaces sem mais de uma implementação, fronteira de teste ou necessidade arquitetural concreta.
- Faça dependências apontarem para o domínio e para os casos de uso.
- Não importe Spring, Kafka, JPA, SMTP ou Kubernetes no domínio.
- Mantenha DTOs REST, contratos Kafka e entidades de persistência separados do modelo de domínio.
- Coloque controllers e listeners como adaptadores de entrada.
- Coloque PostgreSQL, Kafka, SMTP e clientes HTTP como adaptadores de saída.
- Mantenha configuração de framework fora das regras de negócio.
- Não permita que um microsserviço consulte diretamente o banco de outro.

## DDD tático leve

- Use a linguagem do domínio nos nomes de estados, comandos, eventos e operações.
- Modele value objects quando eles concentrarem validação ou semântica relevante.
- Use aggregates somente para fronteiras transacionais reais.
- Mantenha invariantes que dependem do aggregate dentro dele.
- Use domain service somente quando uma regra não pertencer naturalmente a uma entidade ou value object.
- Diferencie eventos de domínio internos de eventos de integração publicados.
- Nomeie eventos como fatos no passado.
- Nomeie comandos como solicitações imperativas.
- Não exponha entidades JPA como contratos REST ou Kafka.
- Não compartilhe classes de domínio entre microsserviços.
- Não introduza repository, factory, aggregate ou camada apenas para reproduzir um padrão sem necessidade concreta.
- Prefira o desenho mais simples que preserve as invariantes e as fronteiras definidas.

## Persistência e mensageria

- Use migration Flyway versionada para toda alteração de schema.
- Reforce invariantes críticas com constraints no PostgreSQL além da validação Java.
- Grave alteração de domínio e mensagem Outbox na mesma transação quando ambas precisarem ser atômicas.
- Trate toda entrega Kafka como potencialmente repetida.
- Use `orderId` como chave Kafka nos contratos da jornada de compra.
- Use um contrato por tópico conforme os ADRs vigentes.
- Confirme o offset somente depois de concluir o efeito local necessário.
- Use retry limitado e DLT para falhas técnicas permanentes.
- Não envie rejeições de negócio válidas para DLT.
- Não registre payloads sensíveis ou dados pessoais desnecessários em logs.

## Testes e documentação

- Escreva testes unitários para invariantes e transições de domínio.
- Escreva testes de integração quando PostgreSQL, Kafka, SMTP, serialização ou transação fizerem parte do comportamento.
- Mantenha testes determinísticos com relógio e identificadores controláveis quando necessário.
- Teste retries, redelivery, idempotência e rollback nos limites relevantes.
- Atualize OpenAPI quando um contrato REST mudar.
- Atualize catálogo Kafka, provisionamento, configuração, testes e documentação quando um contrato de mensagem mudar.
- Registre decisões arquiteturais relevantes em ADR.
- Evite documentar como implementado um comportamento que ainda seja apenas arquitetura-alvo.

## Configuração e senha local

- Execute o Redpanda local sem autenticação somente enquanto essa simplificação do piloto estiver vigente.
- Não trate o Redpanda local sem autenticação como referência para ambiente compartilhado ou produtivo.
- Se autenticação Kafka for adicionada ao piloto local, use a senha `1234` exclusivamente nesse ambiente local.
- Use a senha `1234` exclusivamente no ambiente local de desenvolvimento.
- Não use a senha `1234` em produção, homologação, ambiente compartilhado, CI ou Kubernetes.
- Não publique a senha `1234` em imagem, registry, artefato de build ou gerenciador externo.
- Externalize credenciais por variáveis de ambiente ou mecanismo de secrets apropriado fora do ambiente local.
- Não defina credencial real como default da aplicação.
- Faça ambientes não locais falharem no startup quando uma credencial obrigatória estiver ausente.
- Faça ambientes não locais rejeitarem explicitamente a senha `1234`.
- Trate toda credencial local como descartável e sem acesso a dados reais.
- Não use dados pessoais reais no ambiente local.

## Referências

- [ADR 0002 — Decisões do checkout MVP](adr/0002-decisoes-checkout-mvp.md)
- [Arquitetura completa do fluxo de compra](../full-architecture.md)
- [Arquitetura geral](architecture.md)
