#!/bin/sh

# install.sh - instala (idempotente) o HotRouter a partir de um APK.
#
# Uso:
#   sh install.sh [url-do-apk]
#
# Sem argumento: baixa o .apk do ultimo release do REPO abaixo.
# Com argumento: usa a URL dada. Aceita .apk direto ou zip (extrai o .apk de dentro).
#
# GH_TOKEN (opcional, via env): vai como Bearer no download (so para URL privada/artifact).
#
# Por que o exploit Frida e necessario: o app so consegue falar com o shell root
# em telnet 127.0.0.1:23 se for instalado com uid <= 10999. Esse uid e herdado quando o
# app e instalado DURANTE a janela de injecao no system_server (fases 1-3).

set -u

PKG="com.castilhoduarte.hotrouter"
REPO="https://github.com/jucastilhoduarte/haval-hotrouter"
WORK="/data/local/tmp"
ROLLBACK_ENABLED=true

log() { echo "[$1] $(date +%H:%M:%S) $2"; }
die() { log "ERR" "$1"; exit 1; }

cleanup() {
    rc=$?
    [ "$rc" -eq 0 ] && exit 0
    [ "$ROLLBACK_ENABLED" = false ] && exit 0
    log "INFO" "Rollback..."
    rm -rf "$WORK/unz" 2>/dev/null || true
    rm -f "$WORK/artifact.bin" "$WORK/hotrouter_new.apk" 2>/dev/null || true
    log "INFO" "Rollback concluido"
}
trap cleanup EXIT

# get_release_apk <repo-url> - imprime a URL do primeiro asset .apk do ultimo release
get_release_apk() {
    repo=${1#https://github.com/}; repo=${repo%.git}
    curl -s "https://api.github.com/repos/$repo/releases/latest" \
        | grep browser_download_url | cut -d'"' -f4 | grep -i '\.apk$' | head -n1
}

# download <url> <out> <label>
download() {
    log "INFO" "Baixando $3..."
    if [ -n "${GH_TOKEN:-}" ]; then
        curl -L --fail --progress-bar -H "Authorization: Bearer $GH_TOKEN" -o "$2" "$1" \
            || die "Falha no download de $3"
    else
        curl -L --fail --progress-bar -o "$2" "$1" || die "Falha no download de $3"
    fi
    [ -s "$2" ] || die "$3 vazio/inexistente"
}

# download_cached <url> <out> <label> - pula se ja existe (binarios do frida)
download_cached() {
    [ -f "$2" ] && [ -s "$2" ] && { log "INFO" "$3 ja existe"; return; }
    download "$1" "$2" "$3"
}

app_installed() { pm path "$1" >/dev/null 2>&1; }

main() {
    cd "$WORK" || die "Falha ao acessar $WORK"

    URL="${1:-}"
    if [ -z "$URL" ]; then
        log "INFO" "Sem URL; resolvendo ultimo release de $REPO"
        URL=$(get_release_apk "$REPO")
        [ -n "$URL" ] || die "Nao achei .apk no ultimo release"
        log "INFO" "APK do release: $URL"
    fi

    # --- Fase 1: binarios do exploit (cacheados) ---
    log "INFO" "Fase 1: Downloads do exploit"
    download_cached "$REPO/releases/download/exploit-bins/fridaserver.rar" "fridaserver" "fridaserver"
    download_cached "$REPO/releases/download/exploit-bins/fridainject.rar" "fridainject" "fridainject"
    download_cached "$REPO/releases/download/exploit-bins/system_server.js" "system_server.js" "system_server.js"
    chmod +x fridaserver fridainject || die "Falha nas permissoes"

    # --- Fase 2: fridaserver (idempotente) ---
    log "INFO" "Fase 2: fridaserver"
    if pgrep fridaserver >/dev/null 2>&1; then
        log "INFO" "fridaserver ja rodando"
    else
        [ -x "./fridaserver" ] || die "fridaserver nao executavel"
        setsid ./fridaserver >/dev/null 2>&1 < /dev/null &
        sleep 2
        pgrep fridaserver >/dev/null 2>&1 || die "fridaserver nao iniciou"
        log "INFO" "fridaserver iniciado"
    fi

    # --- Fase 3: injecao no system_server (precisa estar ativa no momento do install
    #     pra o app instalado herdar uid <= 10999 e alcancar o telnet:23) ---
    log "INFO" "Fase 3: Injecao system_server"
    [ -f "system_server.js" ] || die "system_server.js nao encontrado"
    SYSTEM_PID=$(pidof system_server) || die "system_server nao encontrado"
    ./fridainject -p "$SYSTEM_PID" -s system_server.js &
    sleep 2
    log "INFO" "Injecao disparada (system_server pid=$SYSTEM_PID)"

    # --- Fase 4: baixar e resolver o APK alvo ---
    log "INFO" "Fase 4: Baixar APK alvo"
    rm -rf unz 2>/dev/null || true
    rm -f artifact.bin hotrouter_new.apk 2>/dev/null || true
    download "$URL" "artifact.bin" "artifact/apk"

    APK=""
    if command -v unzip >/dev/null 2>&1 && \
       unzip -l artifact.bin 2>/dev/null | grep -qiE '\.apk *$'; then
        log "INFO" "Artifact zip detectado, extraindo .apk"
        mkdir -p unz
        unzip -o artifact.bin -d unz >/dev/null 2>&1 || die "Falha ao extrair o zip"
        APK=$(find unz -type f -name '*.apk' | head -n1)
        [ -n "$APK" ] || die "Nenhum .apk dentro do zip"
    else
        log "INFO" "Download tratado como .apk direto"
        mv -f artifact.bin hotrouter_new.apk || die "Falha ao preparar apk"
        APK="$WORK/hotrouter_new.apk"
    fi
    [ -s "$APK" ] || die "APK final vazio"

    # --- Fase 5: instalar HotRouter ---
    # uninstall do proprio obrigatorio antes de reinstalar: assinatura propria difere de
    # uma versao anterior, entao pm install -r por cima falharia (UPDATE_INCOMPATIBLE).
    log "INFO" "Fase 5: Instalar $PKG"
    if app_installed "$PKG"; then
        log "INFO" "Desinstalando versao atual do HotRouter"
        pm uninstall "$PKG" >/dev/null 2>&1 || log "WARN" "uninstall falhou (seguindo)"
    fi
    pm install "$APK" || die "Falha na instalacao do HotRouter"

    # --- limpeza ---
    rm -rf unz 2>/dev/null || true
    rm -f artifact.bin hotrouter_new.apk 2>/dev/null || true
    ROLLBACK_ENABLED=false

    log "INFO" "Instalado: $(pm path "$PKG" 2>/dev/null)"
    echo "🎉 HotRouter instalado! Abra uma vez para ligar; depois ele sobe sozinho no boot."
}

main "$@"
