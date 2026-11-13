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
- plugin `OndaMedia` com `ping`, `startCallService` e `stopCallService`.

Testado no aparelho:

```text
Xiaomi 2311DRK48G
Android 16 / API 36
arm64-v8a
```

Resultado da ponte nativa:

```text
OndaBridge: "{\"ok\":true,\"platform\":\"android\",\"version\":\"0.0.1\"}"
OndaCall: "Foreground call service started."
```

O serviço ficou ativo com o app em segundo plano e com a notificação
`Chamada de voz em andamento`.

Ainda não implementado:

- conexão nativa ao LiveKit;
- publicação de microfone;
- câmera;
- compartilhamento de tela;
- integração da chamada nativa com a UI da sala.

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