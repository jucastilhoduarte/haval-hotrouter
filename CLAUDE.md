# JLH6 — contexto para o Claude

## O que é isso

Aplicativo Android de propósito único para o sistema de entretenimento (head unit) pessoal de um carro Haval/GWM. Uma tela com um único botão que abre as configurações do Android. Nada mais.

## Regras absolutas — nunca quebre estas

- **Zero dependências de terceiros.** Apenas Android SDK. Apenas Java. Sem AndroidX, sem Compose, sem Kotlin, sem Shizuku, sem nada do Jetpack.
- `android.useAndroidX=false` em `gradle.properties` — deve permanecer assim.
- `minSdk = targetSdk = 28` — deliberado.
- `compileSdk = 35`, AGP 8.7.3, Gradle 8.14.3.
- PR → somente build debug. Merge em `main` → release assinada + `gh release create`.

## Arquivos principais

| Caminho | O que é |
|---------|---------|
| `app/src/main/java/com/castilhoduarte/jlh6/MainActivity.java` | Única tela. Botão central → abre `com.android.settings/.Settings`. |
| `scripts/install-app.sh` | Instala o JLH6 via exploit Frida (bypass de pm install da multimidia). |
| `scripts/install-apk.sh` | Instala qualquer APK via exploit Frida. |

## Exploit Frida (por que existe)

A head unit bloqueia `pm install` de APKs externos. Os scripts injetam no `system_server` via Frida para remover essa restrição durante a instalação.

Binários do exploit em: `https://github.com/jucastilhoduarte/jlh6/releases/tag/exploit-bins`

## CI (`.github/workflows/build.yml`)

- **PR**: `assembleDebug`
- **Push em main**: `assembleRelease` assinado + `gh release create`

Secrets: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`.

## Design da UI

Tema escuro, landscape, 21:9. Sem ActionBar. Um botão retangular grande centralizado na tela.

## Pacote / assinatura

- `applicationId = com.castilhoduarte.jlh6`
- Assinado com chave pessoal do dono. Keystore em `~/Desktop/haval-actions-secrets`.
