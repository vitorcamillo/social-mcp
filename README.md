# Social MCP

![Demonstração do social-mcp](docs/demo.png)

*Um rascunho de post criado pelo MCP fica parado: sem gate de texto configurado, a aprovação é bloqueada e nada é publicado.*

O Social MCP é um servidor MCP em Java 21 para operar sessões de redes sociais em um Chrome desktop real por meio do [javaDriveless](https://github.com/vitorcamillo/javaDriveless). Ele mantém um perfil de navegador por rede e separa a navegação somente para leitura das ações públicas.

O servidor é deliberadamente conservador: as ações públicas são preparadas em um lote, verificadas contra as cotas e os disjuntores de cada rede e só podem ser executadas depois de uma aprovação explícita no terminal. Um kill switch e um journal somente de acréscimo oferecem controle operacional local. Este projeto não contorna CAPTCHAs, sistemas antispam, restrições de contas nem regras de plataformas. Use-o somente com contas que você controla e siga os termos de cada plataforma.

## Requisitos

- Java 21 e Maven 3.9+
- Google Chrome com um display desktop utilizável (`DISPLAY` no Linux)
- `javaDriveless` 1.12.2 instalado separadamente ou compilado localmente

Compile primeiro a biblioteca do navegador:

```bash
git clone https://github.com/vitorcamillo/javaDriveless.git
cd javaDriveless
mvn -B -ntp install
```

Depois compile este servidor:

```bash
git clone https://github.com/vitorcamillo/social-mcp.git
cd social-mcp
bash scripts/install-javadriveless-public.sh  # SDK publico 1.12.3, origem e hashes verificados
mvn -B -ntp package
./bin/social-mcp --cli status
```

Por padrão, o launcher armazena o estado em `~/.social-mcp-public`. Defina `SOCIAL_MCP_HOME` para um diretório isolado ao executar testes ou mais de uma instalação. O diretório contém configuração, entradas do journal, lotes, screenshots e perfis do navegador; nunca o compartilhe nem o versione.

## Configuração do gate

Por padrão, não há comando de gate configurado. A aprovação falha de forma fechada até que você configure um gate local em `config.json`, dentro de `defaults.gate.command`. O comando recebe `pre --batch <path> --config <path> --json` e deve escrever um objeto JSON como `{"resultado":"PASS"}` (ou `WARN`/`FAIL`) e retornar um status de saída compatível. Mantenha o executável do gate local e revise seu código antes de usá-lo. Depois de revisar o rascunho completo, aprove-o explicitamente com `./bin/social-mcp --cli approve <batch-id> humano "reviewed"`; não existe um gate de exemplo que aprove tudo.

A aprovação pelo CLI registra o papel informado por quem chama (`humano` ou `orquestrador`); ela não funciona como um provedor de identidade. Trate o acesso ao host e ao diretório `SOCIAL_MCP_HOME` como confiável. Um cliente MCP ou agente com acesso ao shell pode chamar o CLI, portanto use as permissões do sistema operacional e revisão humana para contas reais.

## Ferramentas MCP

O servidor se comunica por JSON-RPC delimitado por novas linhas em stdio. Ele expõe status, navegação, leitura, screenshots, criação de lotes em rascunho, execução de lotes aprovados, controles de estado e inspeção do journal. Cookies de sessão nunca fazem parte deste repositório: faça login interativamente no perfil pertencente ao usuário ou importe um arquivo de sessão fornecido por ele e proteja o diretório de runtime.

O código público contém somente testes sintéticos. Ele não contém uma conta, um perfil de navegador, uma exportação de cookies, uma credencial de proxy, um plano de marketing nem um serviço hospedado.

## Contribuição

Consulte [CONTRIBUTING.md](CONTRIBUTING.md). Inclua um teste focado para mudanças de comportamento e nunca inclua dados de runtime, screenshots, cookies, tokens de acesso ou identificadores de contas em uma issue ou pull request.

## Licença

Este projeto é distribuído sob a Licença MIT. Consulte [LICENSE](LICENSE).
