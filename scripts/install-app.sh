#!/bin/sh

# install-app.sh - instala (idempotente) o JLH6 a partir do ultimo release.
#
# Uso:
#   sh install-app.sh
#
# Por que o exploit Frida e necessario: a multimidia bloqueia pm install
# de APKs externos. A injecao no system_server remove essa restricao.

set -u

PKG="com.castilhoduarte.jlh6"
REPO="https://github.com/jucastilhoduarte/jlh6"
WORK="/data/local/tmp"
ROLLBACK_ENABLED=true

log() { echo "[$1] $(date +%H:%M:%S) $2"; }
die() { log "ERR" "$1"; exit 1; }

cleanup() {
    rc=$?
    [ "$rc" -eq 0 ] && exit 0
    [ "$ROLLBACK_ENABLED" = false ] && exit 0
    log "INFO" "Rollback..."
    rm -f "$WORK/jlh6_new.apk" 2>/dev/null || true
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
    curl -L --fail --progress-bar -o "$2" "$1" || die "Falha no download de $3"
    [ -s "$2" ] || die "$3 vazio/inexistente"
}

# download_cached <url> <out> <label> - pula se ja existe (binarios do frida)
download_cached() {
    [ -f "$2" ] && [ -s "$2" ] && { log "INFO" "$3 ja existe"; return; }
    download "$1" "$2" "$3"
}

main() {
    cd "$WORK" || die "Falha ao acessar $WORK"

    log "INFO" "Resolvendo ultimo release de $REPO"
    URL=$(get_release_apk "$REPO")
    [ -n "$URL" ] || die "Nao achei .apk no ultimo release"
    log "INFO" "APK do release: $URL"

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

    # --- Fase 3: injecao no system_server ---
    log "INFO" "Fase 3: Injecao system_server"
    [ -f "system_server.js" ] || die "system_server.js nao encontrado"
    SYSTEM_PID=$(pidof system_server) || die "system_server nao encontrado"
    ./fridainject -p "$SYSTEM_PID" -s system_server.js &
    sleep 2
    log "INFO" "Injecao disparada (system_server pid=$SYSTEM_PID)"

    # --- Fase 4: baixar APK ---
    log "INFO" "Fase 4: Baixar APK alvo"
    rm -f jlh6_new.apk 2>/dev/null || true
    download "$URL" "jlh6_new.apk" "JLH6 APK"
    APK="$WORK/jlh6_new.apk"

    # --- Fase 5: instalar JLH6 (in-place, preserva acessibilidade) ---
    # pm install -r atualiza por cima mantendo dados e os secure settings de
    # acessibilidade (enabled_accessibility_services). NAO desinstalar: o uninstall
    # purga o componente da lista de a11y -> perde o autostart e exige reativar na mao.
    # Requer assinatura igual entre versoes (releases assinados com a chave do dono).
    # -d permite reinstalar mesmo com versionCode igual ou menor.
    log "INFO" "Fase 5: Instalar $PKG (in-place)"
    pm install -r -d "$APK" \
        || die "Falha na instalacao do JLH6 (UPDATE_INCOMPATIBLE => build atual tem outra assinatura; nesse caso o uninstall manual seria necessario e a acessibilidade teria de ser reativada)"

    # --- limpeza ---
    rm -f jlh6_new.apk 2>/dev/null || true
    ROLLBACK_ENABLED=false

    log "INFO" "Instalado: $(pm path "$PKG" 2>/dev/null)"
    echo "JLH6 instalado com sucesso."
}

main
