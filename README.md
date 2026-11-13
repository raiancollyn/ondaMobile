# Onda Mobile

Aplicativo Android do Onda, construído com Capacitor e uma ponte nativa inicial
para a futura camada de mídia.

## Estado atual

- projeto Capacitor Android;
- WebView carregando `https://onda.dpbr.net`;
- plugin nativo `OndaMedia` com o método `ping`;
- teste automático da ponte JavaScript ↔ Kotlin no `logcat`;
- APK debug instalável;
- área segura do Android aplicada ao conteúdo da WebView.

Testado no aparelho:

```text
Xiaomi 2311DRK48G
Android 16 / API 36
arm64-v8a
```

Resultado da ponte nativa:

```text
OndaBridge: "{\"ok\":true,\"platform\":\"android\",\"version\":\"0.0.1\"}"
```

Ainda não implementado:

- chamadas nativas LiveKit;
- voz em segundo plano;
- câmera;
- compartilhamento de tela;
- notificações de chamada.

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
