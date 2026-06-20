# JLH6

Aplicativo Android para **minha head unit Haval/GWM**. Uma tela, um botão: abre as configurações do Android.

Não está em nenhuma loja. Instalado apenas no meu carro.

## Como instalar

Via telnet na head unit (`telnet <ip-da-multimidia> 23`):

```sh
# Instalar o JLH6 (baixa o último release automaticamente)
curl -fsSL https://raw.githubusercontent.com/jucastilhoduarte/jlh6/main/scripts/install-app.sh | sh

# Instalar o JLH6 a partir de uma URL específica
curl -fsSL https://raw.githubusercontent.com/jucastilhoduarte/jlh6/main/scripts/install-app.sh | sh -s <url-do-apk>

# Instalar qualquer APK
curl -fsSL https://raw.githubusercontent.com/jucastilhoduarte/jlh6/main/scripts/install-apk.sh | sh -s <url-do-apk>
```

Os scripts fazem o bypass do bloqueio de `pm install` via injeção Frida no `system_server`.
Os binários do exploit estão em: `https://github.com/jucastilhoduarte/jlh6/releases/tag/exploit-bins`

## Build / release

- **Pull request → `assembleDebug`**
- **Merge para `main` → `assembleRelease` assinado** → publicado como release do GitHub com APK

Segredos de assinatura no Actions: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`.
