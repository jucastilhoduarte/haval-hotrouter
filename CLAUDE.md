# JLH6 — contexto para o Claude

## Superpowers

**Sempre use superpowers** para qualquer tarefa não trivial neste projeto:

- Nova feature ou mudança de comportamento → `superpowers:brainstorming` primeiro
- Implementação com múltiplas tarefas → `superpowers:subagent-driven-development`
- Só implementação direta (plano já claro) → `superpowers:writing-plans`

## O que é isso

Aplicativo Android para a head unit pessoal de um carro Haval/GWM. Uma tela:

1. **Configurações** (botão, topo) — abre `com.android.settings/.Settings`
2. **Starlink Router** (botão) — roteia tráfego do hotspot (`wlan2`) via Starlink (`wlan0`) usando iptables + ip rule via telnet root
3. **Recuperação automática** (switch, abaixo do botão router) — quando ligado, monitora a conectividade enquanto ATIVO e religa o roteamento sozinho se a conexão cair

## Regras absolutas — nunca quebre estas

- **Zero dependências de terceiros.** Apenas Android SDK. Apenas Java. Sem AndroidX, sem Compose, sem Kotlin, sem Shizuku, sem nada do Jetpack.
- `android.useAndroidX=false` em `gradle.properties` — deve permanecer assim.
- `minSdk = targetSdk = 28` — deliberado.
- `compileSdk = 35`, AGP 8.7.3, Gradle 8.14.3.
- PR → somente build debug. Merge em `main` → release assinada + `gh release create`.

## Arquivos principais

| Caminho | O que é |
|---------|---------|
| `app/src/main/java/com/castilhoduarte/jlh6/MainActivity.java` | Única Activity. Poll de estado a cada 500ms **enquanto estado ≠ DISABLED**. 1º tap em ativar checa accessibility; sem ele → dialog que abre `ACTION_ACCESSIBILITY_SETTINGS`. Switch de recuperação automática (persiste + arma/desarma o monitor via `setAutoRecovery`; `onResume` sincroniza o estado do switch sem disparar o listener). |
| `app/src/main/java/com/castilhoduarte/jlh6/RouterManager.java` | Singleton. State machine: DISABLED/STARTING/ACTIVE/PURGING. HandlerThread para background. Inclui o monitor de saúde (recuperação automática) e a rotina de `recover`. |
| `app/src/main/java/com/castilhoduarte/jlh6/TelnetRoot.java` | Cliente telnet mínimo para `127.0.0.1:23`. Sentinelas `__HR_BEG__`/`__HR_END__$?`. |
| `app/src/main/java/com/castilhoduarte/jlh6/JLH6App.java` | Application. `onCreate` → `restoreIfEnabled`. Roda sempre que o processo nasce (inclusive religado no boot pelo accessibility). |
| `app/src/main/java/com/castilhoduarte/jlh6/RouterAccessibilityService.java` | Âncora de autostart, habilitado **manualmente** pelo usuário na config do Android. `isEnabled()` checa o estado; `onServiceConnected` → `restoreIfEnabled`. |
| `app/src/main/java/com/castilhoduarte/jlh6/BootReceiver.java` | Reforço. `exported=true`. BOOT_COMPLETED / QUICKBOOT_POWERON / MY_PACKAGE_REPLACED → `restoreIfEnabled` direto (sem service). |
| `scripts/install-app.sh` | Instala o JLH6 via exploit Frida (bypass de pm install). |
| `scripts/install-apk.sh` | Instala qualquer APK via exploit Frida. |
| `scripts/test/TelnetRootTest.java` | 15 testes do TelnetRoot. JDK puro, sem Gradle. |

## Topologia de rede

| Interface | Papel |
|-----------|-------|
| `wlan2` | Hotspot da multimídia (clientes do carro) |
| `wlan0` | Starlink (uplink externo) |
| tabela `wlan0` | Tabela de roteamento separada com rota default via Starlink |
| prioridade `17999` | Prioridade da ip rule de desvio |

## State machine do RouterManager

```
DISABLED → [tap] → STARTING → [ping wlan0 OK + apply verificado] → ACTIVE
STARTING → [ping OK mas apply não verifica] → STARTING (reagenda doPing, até timeout)
STARTING → [10min sem ping/apply] → DISABLED (salva enabled=false, auto_recovery=false)
STARTING → [tap] → PURGING → DISABLED
ACTIVE   → [tap] → PURGING → DISABLED
ACTIVE   → [recuperação auto: 3 pings falham] → STARTING (purge → espera 5s → reativa)
```

- Ping loop: `ping -I wlan0 -c 1 -W 2 8.8.8.8` a cada 5s
- Timeout STARTING: 10 minutos
- Estado persistido: `SharedPreferences("router", "enabled")` e `SharedPreferences("router", "auto_recovery")`
- No boot: se `enabled=true`, sempre entra STARTING (nunca aplica regras sem ping); o monitor rearma sozinho ao chegar em ACTIVE se `auto_recovery=true`
- Tap durante PURGING: ignorado
- **apply/purge idempotentes e auto-verificados**: cada comando termina com uma cláusula de verificação (o `$?` final = estado confirmado) e roda via `execVerified` (retry com backoff, captura `Throwable`). `ACTIVE` só é marcado após apply **verificado** — nunca em apply parcial. `disable`/`recover` repetem o purge até verificar limpo. Verificação é por nome de regra → correta mesmo se `wlan0`/`wlan2` sumirem. Constantes: `APPLY_ATTEMPTS=3`, `PURGE_ATTEMPTS=4`, `VERIFY_BACKOFF_MS=500`.

## Recuperação automática (auto-recovery)

Modo opcional (switch na UI, persistido em `auto_recovery`). Só atua enquanto o router está intencionalmente ligado pelo usuário.

- **Monitor de saúde** (`doMonitor`): armado só quando `state==ACTIVE` **e** `auto_recovery=true`. Faz o mesmo ping (`ping -I wlan0 ... 8.8.8.8`) a cada 5s e conta falhas consecutivas. Roda no mesmo `HandlerThread`, separado do loop de ativação (`doPing`).
- **Gatilho**: 3 falhas consecutivas (`RECOVERY_FAIL_THRESHOLD`) → `recover()`.
- **Recovery** (`recover`): `state=STARTING` → `execPurge()` (cleanup existente) → espera 5s → reativa via `startPingLoop` (revalida com ping, reaplica regras, volta a ACTIVE → rearma o monitor). Reusa STARTING ("ATIVANDO...") na UI — sem estado novo. Limite: cada reativação herda o timeout de 10min do `doPing`.
- **Pontos de armado**: caminho de sucesso do `doPing` (cobre 1ª ativação, recovery e boot); e ao ligar o switch enquanto ACTIVE. Desligar o switch → `stopMonitor` (conexão segue ACTIVE).
- **Precedência manual (override)**:
  - `disable()` (tap manual no botão) limpa `auto_recovery=false`, para o monitor e cancela callbacks do `bg`. Próxima ativação exige remarcar o switch.
  - O give-up do timeout de 10min também limpa `auto_recovery=false`.
  - A lambda de reativação do `recover` rechecka `KEY_ENABLED` antes de religar — se o usuário desativou durante a espera pós-purge, recovery não sobrepõe o OFF manual.

## Autostart (religar no boot)

Mecanismo em camadas, ancorado no **AccessibilityService** (Android trava autostart de apps de terceiros; um accessibility habilitado é religado pelo sistema todo boot e fica imune a kill/limites de background):

1. **Âncora** — `RouterAccessibilityService`, habilitado **manualmente uma vez** pelo usuário. UX: 1º tap no botão ativar → `RouterAccessibilityService.isEnabled` checa; se desligado → dialog → `ACTION_ACCESSIBILITY_SETTINGS` → usuário liga "JLH6" na lista → volta → tap de novo ativa. Persiste em secure settings entre boots. Sem telnet.
2. No boot o sistema religa o processo → `JLH6App.onCreate` e `RouterAccessibilityService.onServiceConnected` chamam `restoreIfEnabled`.
3. **Reforço**: `BootReceiver` (`exported=true`) em BOOT_COMPLETED / QUICKBOOT_POWERON / MY_PACKAGE_REPLACED.

Sem foreground service, sem notificação permanente. O loop de ping roda no processo ancorado pelo accessibility (prioridade perceptível → não é morto).

> **Bug histórico:** `BootReceiver` era `exported="false"` → BOOT_COMPLETED vem do `system_server` (uid ≠ app) e nunca era entregue. Receiver de boot **tem** que ser `exported="true"`.

## Comandos telnet executados

### Apply (ativar roteamento — idempotente + auto-verificado)
```sh
echo 1 > /proc/sys/net/ipv4/ip_forward
# ip rule idempotente: remove todas as nossas regras, depois adiciona uma
while ip rule | grep -q 'iif wlan2 lookup wlan0'; do ip rule del from all iif wlan2 lookup wlan0 priority 17999 2>/dev/null || break; done
ip rule add from all iif wlan2 lookup wlan0 priority 17999
iptables -t nat -C POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null || iptables -t nat -I POSTROUTING 1 -o wlan0 -j MASQUERADE
iptables -C FORWARD -i wlan2 -o wlan0 -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i wlan2 -o wlan0 -j ACCEPT
iptables -C FORWARD -i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT
# verificação (comando final → $?): forwarding on E as 4 regras presentes
grep -qx 1 /proc/sys/net/ipv4/ip_forward 2>/dev/null && ip rule | grep -q 'iif wlan2 lookup wlan0' && iptables -t nat -C POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null && iptables -C FORWARD -i wlan2 -o wlan0 -j ACCEPT 2>/dev/null && iptables -C FORWARD -i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null
```

### Purge (desativar — loop até limpo + auto-verificado)
```sh
while ip rule | grep -q "iif wlan2 lookup wlan0"; do ip rule del ...; done
while iptables -t nat -C POSTROUTING ...; do iptables -t nat -D POSTROUTING ...; done
while iptables -C FORWARD -i wlan2 ...; do iptables -D FORWARD ...; done
while iptables -C FORWARD -i wlan0 ...; do iptables -D FORWARD ...; done
# verificação (comando final → $?): NENHUMA das nossas regras presente
! ip rule | grep -q 'iif wlan2 lookup wlan0' && ! iptables -t nat -C POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null && ! iptables -C FORWARD -i wlan2 -o wlan0 -j ACCEPT 2>/dev/null && ! iptables -C FORWARD -i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null
```

## Exploit Frida (por que existe)

A head unit bloqueia `pm install` de APKs externos. Os scripts injetam no `system_server` via Frida para remover essa restrição durante a instalação.

Binários do exploit em: `https://github.com/jucastilhoduarte/jlh6/releases/tag/exploit-bins`

## CI (`.github/workflows/build.yml`)

- **PR**: `assembleDebug`
- **Push em main**: `assembleRelease` assinado + `gh release create`

Secrets: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`.

## Design da UI

Tema escuro, landscape, 21:9. Sem ActionBar. Empilhado verticalmente, centralizado, de cima para baixo: botão **Configurações** (engrenagem + texto), botão **Starlink Router** (wifi + texto), **switch** de recuperação automática. Botões retangulares.

## Pacote / assinatura

- `applicationId = com.castilhoduarte.jlh6`
- Assinado com chave pessoal do dono. Keystore em `~/Desktop/haval-actions-secrets`.
