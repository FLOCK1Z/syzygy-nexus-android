# Publicação Android pelo Google Play

O usuário instala e atualiza o Nexus pela Play Store. APK manual não é a distribuição planejada. O AAB é o arquivo enviado pelo desenvolvedor ao Play Console; o Google gera os pacotes adequados ao aparelho.

## Candidata atual

- Identificador existente preservado: `com.syzygy.nexus`.
- Interface Kotlin/Jetpack Compose. A tela principal não usa WebView.
- `compileSdk` e `targetSdk` 36, Java 17, Gradle 8.13.
- `versionCode` 2 e nome `2.0.0-native-preview`. Antes de cada envio, o versionCode deve ser maior que todos os já usados no Play Console. Não há confirmação de versões eventualmente enviadas fora deste repositório.
- O workflow de validação gera um AAB **não assinado para publicação**. Ele serve para revisão técnica; não deve ser enviado como lançamento final.
- A variante de teste usa `com.syzygy.nexus.nativepreview` para poder coexistir com o app antigo sem apagar seus dados. Isso não muda o pacote release `com.syzygy.nexus`.
- O APK debug gerado localmente é apenas uma ferramenta de homologação interna. Não substitui a distribuição pela loja e não é um APK de produção.

## Antes de publicar

1. Confirmar a conta de desenvolvedor e criar/selecionar o aplicativo no Play Console. Não criar outro pacote se já existir um app Nexus com esse identificador.
2. Configurar uma chave de upload sob controle do proprietário e habilitar Play App Signing. Não usar a assinatura debug do antigo workflow como assinatura de publicação.
3. Registrar o cliente OAuth Android no mesmo projeto do cliente Web do Nexus: pacote `com.syzygy.nexus` + SHA-1 da chave **App signing** do Play Console. Para builds internos instalados fora do Play, registrar também o SHA-1 da chave que assinou aquele build.
4. Confirmar o ID público Web em `GOOGLE_WEB_CLIENT_ID`. O código enviado ao servidor é um Google ID token; não há `client_secret` no app. O backend valida o token contra seu próprio client ID.
5. Publicar uma política de privacidade verdadeira, acessível, e configurar `NEXUS_PRIVACY_URL`. Preencher Segurança dos dados de acordo com as APIs, armazenamento, áudio e documentos realmente utilizados.
6. Implementar e validar a exclusão da conta e dos dados associados, com caminho dentro do app e recurso web. Configurar `NEXUS_DELETE_ACCOUNT_URL` somente para um fluxo funcional. Não foi identificado esse endpoint no backend inspecionado; preencher uma URL fictícia não resolve a exigência.
7. Homologar no Android: login local, Google, troca de contas, persistência, documentos, imagens, voz, rede instável e encerramento em segundo plano. A compilação não valida o recebimento de e-mail nem as integrações reais.
8. Preparar nome, descrição, ícone, capturas reais, classificação indicativa, declarações de conteúdo, suporte e acesso para a equipe de revisão do Google.
9. Subir um AAB **assinado** em uma trilha de teste da Play Store. Testadores instalam pela própria loja.
10. Cumprir o processo aplicável à conta. Contas pessoais novas podem precisar de teste fechado com ao menos 12 testadores inscritos continuamente por 14 dias e solicitação de acesso à produção. Confirmar o que o seu Play Console exige.
11. Publicar somente após aprovação do proprietário e revisão do Google. Não há publicação automática neste projeto.

## Assinatura local ou no CI privado

Configure fora dos arquivos públicos:

```
NEXUS_KEYSTORE=/caminho/privado/upload.jks
NEXUS_STORE_PASSWORD=...
NEXUS_KEY_ALIAS=...
NEXUS_KEY_PASSWORD=...
```

As variáveis de senha são lidas pelo Gradle. Nunca inclua o arquivo da chave, senhas, um JSON de conta de serviço, tokens HF ou segredos OAuth no ZIP, GitHub público ou chat. `gradle verifyPlayRelease :app:bundleRelease` recusa ausência de chave/URLs/ID público antes de preparar a publicação. Esse precheck não substitui teste funcional dos links e da assinatura.

As URLs públicas e o ID público podem ser definidos como propriedades Gradle ou variáveis de ambiente. Uma versão de teste pode ser compilada sem esses parâmetros; o app só mostra Google e links de privacidade/exclusão quando estiverem configurados.

## Atualizações futuras

Uma nova versão do app usa o mesmo pacote e assinatura de publicação, um versionCode superior, geração do AAB, teste na trilha interna/fechada e envio à loja. O usuário recebe a atualização pelo Google Play. Atualizações de dados/modelos no servidor continuam acontecendo pela API, sem baixar código executável para contornar a loja.

## Referências oficiais, verificadas em 22/10/2026

- https://developer.android.com/google/play/requirements/target-sdk
- https://support.google.com/googleplay/android-developer/answer/14151465
- https://support.google.com/googleplay/android-developer/answer/13327111
- https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation

## Instalação antiga e histórico

O APK antigo foi gerado como debug. Uma versão release assinada com outra chave não pode substituir diretamente essa instalação. Não desinstale o app antigo nem apague seus dados para testar: a variante debug nativa tem pacote separado. Antes de migrar para o pacote da loja no mesmo aparelho, é necessário definir como preservar/importar o histórico local da WebView antiga e conferir a assinatura existente. A candidata não tem migração automática desse histórico. Para Google na variante de teste, registre também `com.syzygy.nexus.nativepreview` com o SHA-1 da respectiva assinatura.

## Gerar o AAB assinado pelo GitHub

O workflow **Gerar AAB assinado para revisão** é manual. Ele não publica na loja. Configure na área Secrets do repositório os valores `NEXUS_KEYSTORE_BASE64`, `NEXUS_STORE_PASSWORD`, `NEXUS_KEY_ALIAS` e `NEXUS_KEY_PASSWORD`. A primeira variável é o arquivo da chave de upload codificado em base64; ela continua sendo secreta. Configure as URLs públicas nas variáveis do repositório `NEXUS_PRIVACY_URL` e `NEXUS_DELETE_ACCOUNT_URL`. O workflow valida a configuração, executa testes/lint e deixa o AAB assinado como artefato para revisão. A chave temporária é removida ao fim da etapa.

Antes de executar, revise o código da branch escolhida. Não compartilhe credenciais da conta Google no chat. A etapa posterior é o envio desse AAB à trilha de teste no seu Play Console.
