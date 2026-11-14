# Onda Mobile

Aplicativo Android do Onda, construído com Capacitor e uma ponte nativa inicial
para a futura camada de mídia.

## Estado atual

- projeto Capacitor Android;
- WebView carregando `https://onda.dpbr.net`;
- plugin nativo `OndaMedia` com ponte para o serviço de chamada;
- teste automático da ponte JavaScript ↔ Kotlin no `logcat`;
- APK debug instalável;
- área segura do Android aplicada ao conteúdo da WebView;
- `CallService` em primeiro plano, com notificação persistente e ação
  **Encerrar**;
- permissões de microfone, notificação e foreground service;
- plugin `OndaMedia` com `ping`, `startCall`, `startCallService` e
  `stopCallService`;
- SDK LiveKit Android 2.18.2;
- conexão nativa ao LiveKit e publicação do microfone;
- áudio da chamada mantido com o app em segundo plano.

Testado no aparelho:

```text
Xiaomi 2311DRK48G
Android 16 / API 36
arm64-v8a
```

Resultado da ponte nativa:

```text
OndaBridge: "{\"ok\":true,\"platform\":\"android\",\"version\":\"0.0.1\"}"
OndaCall: "Connected to LiveKit room and microphone enabled."
```

Teste de chamada realizado com um LiveKit local:

```text
Room: devtest
Participante: android-test
Track: TR_... (MICROPHONE, audio/red)
Estado: ACTIVE
Bitrate: ~48 kbps
Conexão: udp / EXCELLENT
```

Integração web → nativo validada em produção:

- a sala Blazor detecta o Capacitor Android;
- envia URL e token LiveKit ao plugin;
- a WebView não abre uma conexão de mídia duplicada;
- microfone nativo publicado;
- botão de microfone controla o serviço nativo;
- chamada continua em segundo plano;
- notificação com ações **Silenciar/Ativar** e **Encerrar**;
- presença renovada pelo serviço nativo;
- reconexão do LiveKit após troca de rede;
- wake lock parcial durante a chamada;
- tarjas nativas no topo e na base para as barras do sistema;
- seleção de alto-falante, auricular, Bluetooth e fone com fio;
- câmera nativa publicada na sala;
- foreground service do tipo `camera` mantém a câmera ativa com a tela desligada;
- vídeos remotos renderizados no app por uma conexão de vídeo separada;

Ainda não implementado:

- renderização de vídeo nativo na interface;
- compartilhamento de tela;
- indicador de “falando” alimentado pelo LiveKit nativo;
- controle de volume individual do áudio remoto nativo.

## Requisitos

- JDK 21;
- Android SDK com Platform 36, Build-Tools 36 e Platform-Tools;
- aparelho Android com depuração USB habilitada.

Caminhos usados neste ambiente:

```text
JAVA_HOME=C:\Users\raian\AppData\Local\Android\Jdk\jdk-21
ANDROID_HOME=C:\Users\raian\AppData\Local\Android\Sdk
```

## Comandos

```powershell
npm install
npx cap sync android
cd android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Ou, na raiz:

```powershell
npm run build:debug
npm run install:debug
```

## Plugin nativo

```text
android/app/src/main/java/net/dpbr/onda/OndaMediaPlugin.java
```

Teste no `logcat`:

```powershell
adb logcat -s OndaBridge
```

O aplicativo injeta uma chamada ao plugin cerca de oito segundos após abrir.

## Configuração

O WebView está configurado em `capacitor.config.json` para carregar o site de
produção. Para apontar para outro servidor, altere `server.url`.

## Estrutura

```text
android/        projeto Android nativo
www/            fallback web local
capacitor.config.json
```

## Publicações

O workflow `.github/workflows/android.yml` compila o APK debug em cada push para
`main` e publica automaticamente uma release quando uma tag `v*` é enviada.

```powershell
git tag v0.1.0
git push origin v0.1.0
```

O APK publicado é **debug-signed**, adequado para testes e instalação via APK.
Para a Play Store será necessário configurar um keystore de release nos secrets
do GitHub e um build `assembleRelease` assinado.