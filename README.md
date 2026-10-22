# Nexus Android nativo — candidata para homologação

Migração do projeto `FLOCK1Z/syzygy-nexus-android`, baseada na revisão `ecc421e66d297ab0fc5489a9085b38b171ae8035`.

O identificador `com.syzygy.nexus` foi mantido. A antiga Activity que abria o site foi substituída por telas Kotlin/Jetpack Compose. O backend continua sendo o Nexus no Hugging Face. Nenhum backend, dado de usuário, secret ou versão de produção foi alterado para preparar esta candidata.

A interface segue a organização do ChatGPT (menu lateral, histórico, seletor de modelos, mensagens e compositor), com identidade Nexus. Não é o código do ChatGPT nem uma cópia completa de todas as funcionalidades. Uma comparação visual com a versão Android usada pelo proprietário ainda é necessária antes de declarar fidelidade de layout.

## Implementado na candidata

- Telas nativas de chat, autenticação, histórico, modelos, documentos, imagens, uso e configurações.
- Login/cadastro locais e recuperação com o link emitido pelo servidor; a política completa continua sendo validada no backend.
- Google por Credential Manager, enviando ID token para o backend existente. Depende de OAuth Android, pacote e assinaturas corretos.
- Resposta textual em streaming, interrupção de recebimento, Markdown, código, copiar e compartilhar.
- PDF, DOCX e TXT pelo seletor do Android; limite de oito documentos e 10 MB por arquivo. Leitura e interpretação usam a API existente do Nexus.
- Imagens pelo fluxo SSE do backend, quando houver um modelo permitido para a conta.
- Histórico local por conta, arquivo cifrado AES-GCM com chave Android Keystore, gravação atômica e backup Android desativado. Conversas temporárias não são gravadas nesse histórico, mas são processadas/contabilizadas pelo servidor.
- Consulta de Chat e Work separados no painel de uso. Atualização a cada 60 segundos e após mensagens, além de consulta manual. Não é um canal push de consumo.
- Work por missão persistida antes do envio, consulta de eventos, autorizações/recusas por ação e solicitação de parada. Reconectar não reenvia a tarefa; abrir uma missão salva consulta o mesmo job. Captura real do computador em painel nativo, somente quando solicitada.
- Captura e reprodução de voz nativas para os modelos cujo transporte é WebSocket PCM do Nexus. O microfone para ao encerrar a tela ou ao aplicativo ir para segundo plano. A rota do servidor mantém a cota e a conexão com o provedor.

## Ainda pendente, não apresentar como concluído

- Homologação em aparelho físico e teste das integrações com uma conta real.
- Voz por WebRTC, câmera, vídeo, controle manual do computador remoto, acompanhamento contínuo de sua tela, envio de novas instruções durante a missão, notificações push, sincronização de histórico entre dispositivos e outras funções do ChatGPT.
- Política de privacidade e exclusão de conta reais no backend, assinatura de upload, configuração do Play Console e envio à loja.
- Migração automática do histórico local guardado pela WebView antiga. A candidata não apaga esses dados, mas não possui importador.

O app não inclui botões que abrem o site como substituição dos recursos principais. Recursos ainda sem implementação nativa não devem ser anunciados como disponíveis.

## Compilar e validar

Requer JDK completo 17 (incluindo javac), Android SDK 36, Build Tools 35.0.0 e Gradle 8.13. Pode usar o workflow GitHub Actions incluído.

```
gradle wrapper --gradle-version 8.13
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:bundleRelease
```

O AAB da validação fica em `app/build/outputs/bundle/release/`. Sem chave release configurada, ele **não está assinado para publicação**. Para a distribuição real pela Play Store, siga [docs/PLAY_STORE.md](docs/PLAY_STORE.md).

O ID Web público do Google pode ser obtido da configuração pública do Nexus. Não use o ID Android no lugar do Web ID. Nunca configure um client_secret no aplicativo.

## Fluxos que precisam passar em Android real

Instalação pela trilha de testes do Play; acesso local/Google; senha errada e sessão expirada; alternar duas contas; reiniciar o app e recuperar histórico; digitar com teclado aberto; receber/cancelar streaming; anexar PDF/DOCX; criar imagem; quota; recuperação real e expiração/reutilização do link; áudio com permissão aceita/negada; interromper/silenciar/encerrar voz; rede móvel instável; bloquear tela e voltar ao app. Validar também atualização sobre a instalação anterior com a assinatura apropriada.

## Referência dos contratos

Backend `Jhosepher/SYZYGY_Neural`, revisão `ec1fb4e6e8d6039a0b0871be335ee14104dc882f`, conferida novamente em 22/10/2026. Não houve envio de mensagens de teste, consumo de modelo, criação de contas ou alterações no HF durante o desenvolvimento desta candidata.
