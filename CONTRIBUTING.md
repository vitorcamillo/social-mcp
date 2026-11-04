# Como contribuir

1. Faça um fork do repositório e crie uma branch focada.
2. Mantenha as mudanças restritas ao servidor público e aos seus testes sintéticos.
3. Execute `mvn -B -ntp test` e `mvn -B -ntp package` antes de abrir um pull request.
4. Execute uma varredura de segredos com redação, como `gitleaks dir --redact --no-banner .`.
5. Descreva o comportamento alterado, os testes executados e qualquer limitação específica da plataforma.

Nunca envie cookies, perfis de navegador, screenshots contendo dados de contas, URLs de proxy com credenciais, tokens de acesso ou instruções operacionais privadas. Relatos sobre possíveis problemas de segurança devem seguir [SECURITY.md](SECURITY.md).
