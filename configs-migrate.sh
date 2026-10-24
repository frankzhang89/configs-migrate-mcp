#!/usr/bin/env bash

# Optional local overrides. Empty values use MIGRATE_JAVA_HOME, JAVA_HOME, then PATH.
CUSTOM_JAVA_HOME=''
JVM_OPTIONS=()

set -u
set -o pipefail
set +m

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)" || exit 1
LOG_DIR="$SCRIPT_DIR/log"
PID_FILE="$LOG_DIR/.configs-migrate.pid"
LAUNCHER_MARKER="-Dconfigs.migrate.launcher=$SCRIPT_DIR"
SERVICE_RUNNING=0

# Keep diagnostics visible in a terminal while leaving redirected automation non-blocking.
wait_for_exit() {
    local failure="${1:-0}"
    if [[ "$failure" == 1 ]]; then
        printf 'Press any key to exit...\n'
        [[ -t 0 && -t 1 ]] && IFS= read -r -s -n 1 || true
    else
        printf 'Press any key to close this launcher, or wait 5 seconds.\n'
        [[ "$SERVICE_RUNNING" == 0 ]] || printf 'The service stays running after this launcher closes.\n'
        [[ -t 0 && -t 1 ]] && IFS= read -r -s -n 1 -t 5 || true
    fi
}

# Validate timeouts without evaluating environment values as shell code.
timeout_seconds() {
    local name="$1" fallback="$2" value="${!1:-}"
    if [[ -z "$value" ]]; then printf '%s\n' "$fallback"; return; fi
    if [[ ! "$value" =~ ^[1-9][0-9]{0,5}$ ]]; then
        printf 'ERROR: %s must be a positive integer (at most six digits).\n' "$name" >&2
        return 1
    fi
    printf '%s\n' "$value"
}

# Resolve the launch-specific JDK without modifying the system JAVA_HOME or PATH.
resolve_java() {
    local requested="$1" selected_home executable version major
    selected_home="${requested:-${CUSTOM_JAVA_HOME:-${MIGRATE_JAVA_HOME:-${JAVA_HOME:-}}}}"
    if [[ -n "$selected_home" ]]; then
        executable="$selected_home/bin/java"
    else
        executable="$(command -v java)" || { printf 'ERROR: Java was not found on PATH.\n' >&2; return 1; }
    fi
    if [[ ! -x "$executable" ]]; then printf 'ERROR: Java executable not found: %s\n' "$executable" >&2; return 1; fi
    version="$("$executable" -version 2>&1)" || { printf 'ERROR: Cannot determine Java version: %s\n' "$executable" >&2; return 1; }
    major="$(printf '%s\n' "$version" | sed -nE 's/.*version "?([0-9]+).*/\1/p' | head -n 1)"
    if [[ -z "$major" || "$major" -lt 25 ]]; then
        printf 'ERROR: Java 25 or newer is required. Selected executable: %s\n' "$executable" >&2
        return 1
    fi
    readlink -f -- "$executable"
}

# Require a single colocated JAR, avoiding accidental selection of another application version.
resolve_jar() {
    local candidate resolved
    local -a candidates=()
    if [[ -n "${MIGRATE_JAR:-}" ]]; then
        candidate="$MIGRATE_JAR"
        [[ "$candidate" == /* ]] || candidate="$SCRIPT_DIR/$candidate"
        if [[ ! -f "$candidate" ]]; then printf 'ERROR: JAR not found: %s\n' "$candidate" >&2; return 1; fi
        resolved="$(readlink -f -- "$candidate")" || return 1
        if [[ "$(dirname -- "$resolved")" != "$SCRIPT_DIR" ]]; then
            printf 'ERROR: MIGRATE_JAR must select a JAR in the launcher directory.\n' >&2
            return 1
        fi
        printf '%s\n' "$resolved"
        return
    fi
    shopt -s nullglob
    candidates=("$SCRIPT_DIR"/configs-migrate-mcp*.jar)
    shopt -u nullglob
    if [[ ${#candidates[@]} -ne 1 ]]; then
        printf 'ERROR: Place exactly one configs-migrate-mcp*.jar beside this launcher, or set MIGRATE_JAR to its filename.\n' >&2
        return 1
    fi
    printf '%s\n' "${candidates[0]}"
}

# Match an exact JVM argument so a stale PID can never identify an unrelated process.
is_managed_process() {
    local service_pid="$1" argument
    [[ "$service_pid" =~ ^[1-9][0-9]*$ && -r "/proc/$service_pid/cmdline" ]] || return 1
    while IFS= read -r -d '' argument; do
        [[ "$argument" == "$LAUNCHER_MARKER" ]] && return 0
    done 2>/dev/null < "/proc/$service_pid/cmdline"
    return 1
}

# Recover identity from /proc even if the state file was removed or became stale.
managed_pid() {
    local proc_file service_pid
    local -a owned=()
    for proc_file in /proc/[0-9]*/cmdline; do
        service_pid="${proc_file#/proc/}"
        service_pid="${service_pid%%/*}"
        if is_managed_process "$service_pid"; then owned+=("$service_pid"); fi
    done
    if [[ ${#owned[@]} -gt 1 ]]; then
        printf 'ERROR: Multiple managed processes found: %s. Resolve them before proceeding.\n' "${owned[*]}" >&2
        return 1
    fi
    [[ ${#owned[@]} -eq 0 ]] || printf '%s\n' "${owned[0]}"
    return 0
}

# Obtain listening owners from ss, falling back to socket inodes when process details are hidden.
listener_pids() {
    local port="$1" owners socket_inodes proc_file link target inode service_pid
    owners="$(ss -H -ltnp "sport = :$port" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -nu)" || true
    if [[ -n "$owners" ]]; then printf '%s\n' "$owners"; return; fi
    socket_inodes="$(awk -v hex="$(printf '%04X' "$port")" '$4 == "0A" && toupper(substr($2, length($2)-3)) == hex {print $10}' /proc/net/tcp /proc/net/tcp6 2>/dev/null)"
    [[ -n "$socket_inodes" ]] || return 0
    for proc_file in /proc/[0-9]*/fd; do
        service_pid="${proc_file#/proc/}"
        service_pid="${service_pid%%/*}"
        for link in "$proc_file"/*; do
            target="$(readlink -- "$link" 2>/dev/null)" || continue
            [[ "$target" == socket:\[*\] ]] || continue
            inode="${target#socket:[}"
            inode="${inode%]}"
            if grep -Fxq -- "$inode" <<< "$socket_inodes"; then printf '%s\n' "$service_pid"; break; fi
        done
    done | sort -nu
}

# Report port owners without killing another application's process.
show_port_conflict() {
    local port="$1" service_pid name executable owners
    printf 'Port conflict: %s\n' "$port"
    owners="$(listener_pids "$port")"
    if [[ -z "$owners" ]]; then
        printf 'Owner PID, name and executable path: unavailable (listener exited or access denied).\n'
        return
    fi
    while IFS= read -r service_pid; do
        name="$(cat "/proc/$service_pid/comm" 2>/dev/null)" || name='unavailable (access denied or process exited)'
        executable="$(readlink -f "/proc/$service_pid/exe" 2>/dev/null)" || executable='unavailable (access denied or process exited)'
        printf 'Owner PID: %s\nProcess name: %s\nExecutable path: %s\n' "$service_pid" "$name" "$executable"
    done <<< "$owners"
}

# Read Spring Boot's effective bind failure instead of approximating YAML and environment precedence.
show_startup_failure() {
    local output_log="$1" error_log="$2" port
    port="$(cat -- "$output_log" "$error_log" 2>/dev/null | sed -nE 's/.*CONFIGS_MIGRATE_PORT_CONFLICT port=([0-9]+).*/\1/p' | head -n 1)"
    if [[ -z "$port" ]]; then
        port="$(cat -- "$output_log" "$error_log" 2>/dev/null | sed -nE 's/.*[Pp]ort ([0-9]+) (was already|is already) in use.*/\1/p' | head -n 1)"
    fi
    [[ -z "$port" ]] || show_port_conflict "$port"
    return 0
}

# A missing process is normal on the first restart; always verify identity again before signaling.
stop_service() {
    local timeout="$1" service_pid deadline
    service_pid="$(managed_pid)" || return 1
    if [[ -z "$service_pid" ]]; then
        rm -f -- "$PID_FILE"
        printf 'The service is not running. Continuing without error.\n'
        return
    fi
    printf 'Stopping service PID %s...\n' "$service_pid"
    is_managed_process "$service_pid" && kill -TERM "$service_pid" 2>/dev/null || true
    deadline=$((SECONDS + timeout))
    while (( SECONDS < deadline )); do
        if ! is_managed_process "$service_pid"; then
            rm -f -- "$PID_FILE"
            printf 'Service stopped (PID %s).\n' "$service_pid"
            return
        fi
        sleep 0.2
    done
    # Only the same marked process may receive a forced stop after its graceful shutdown deadline.
    if is_managed_process "$service_pid"; then
        printf 'Graceful shutdown timed out; terminating managed PID %s.\n' "$service_pid"
        kill -KILL "$service_pid" 2>/dev/null || true
    fi
    for ((attempt = 0; attempt < 25; attempt++)); do
        if ! is_managed_process "$service_pid"; then
            rm -f -- "$PID_FILE"
            printf 'Service stopped (PID %s).\n' "$service_pid"
            return
        fi
        sleep 0.2
    done
    printf 'ERROR: Service PID %s did not stop. Start was not attempted.\n' "$service_pid" >&2
    return 1
}

# Detach Java from the terminal and require both startup completion and an owned listening socket.
start_service() {
    local java="$1" jar="$2" timeout="$3" service_pid output_log error_log stamp text port deadline owners
    shift 3
    service_pid="$(managed_pid)" || return 1
    if [[ -n "$service_pid" ]]; then
        printf 'The service is already running. PID: %s; log directory: %s\n' "$service_pid" "$LOG_DIR"
        SERVICE_RUNNING=1
        return
    fi
    stamp="$(date '+%Y%m%d-%H%M%S')-$$"
    output_log="$LOG_DIR/configs-migrate-$stamp.out.log"
    error_log="$LOG_DIR/configs-migrate-$stamp.err.log"
    : > "$output_log" || return 1
    : > "$error_log" || return 1
    printf 'Java: %s\nStarting: %s\n' "$java" "$jar"
    # Business overrides retain precedence; force HTTP after them and close Java's lock descriptor.
    (
        cd -- "$SCRIPT_DIR" || exit 1
        exec nohup setsid "$java" "${JVM_OPTIONS[@]}" "$LAUNCHER_MARKER" -Dspring.output.ansi.enabled=never -jar "$jar" \
            "--spring.config.location=file:$SCRIPT_DIR/application.yml" "$@" --spring.ai.mcp.server.stdio=false
    ) </dev/null >"$output_log" 2>"$error_log" 9>&- &
    service_pid=$!
    printf '%s\n' "$service_pid" > "$PID_FILE"
    deadline=$((SECONDS + timeout))
    while (( SECONDS < deadline )); do
        if ! kill -0 "$service_pid" 2>/dev/null; then
            wait "$service_pid" 2>/dev/null || true
            rm -f -- "$PID_FILE"
            show_startup_failure "$output_log" "$error_log"
            printf 'ERROR: Startup failed. Logs: %s ; %s\n' "$output_log" "$error_log" >&2
            return 1
        fi
        # The explicit ready signal also works when INFO logs are disabled or their format is customized.
        port="$(sed -nE "s/.*CONFIGS_MIGRATE_READY pid=$service_pid port=([0-9]+).*/\1/p" "$output_log" | head -n 1)"
        if [[ -z "$port" ]] && grep -Eq 'Started[[:space:]]+[^[:space:]]+[[:space:]]+in[[:space:]]+[0-9.]+[[:space:]]+seconds' "$output_log"; then
            port="$(sed -nE 's/.*Tomcat started on port(\(s\))?:? ([0-9]+).*/\2/p' "$output_log" | head -n 1)"
        fi
        if [[ -n "$port" ]]; then
            owners="$(listener_pids "$port")"
            if grep -Fxq -- "$service_pid" <<< "$owners"; then
                printf 'Service started successfully.\nPID: %s\nService port: %s\nOutput log: %s\nError log: %s\n' \
                    "$service_pid" "$port" "$output_log" "$error_log"
                SERVICE_RUNNING=1
                return
            fi
        fi
        sleep 0.25
    done
    # Retain state on timeout so a later stop/restart can recover a slow startup.
    show_startup_failure "$output_log" "$error_log"
    printf 'ERROR: Readiness was not reached within %s seconds (PID %s retained). Logs: %s ; %s\n' \
        "$timeout" "$service_pid" "$output_log" "$error_log" >&2
    return 1
}

# Parse command and JDK options without re-evaluating application arguments through a shell.
main() {
    local action='' requested_java_home='' argument jar java start_timeout stop_timeout dependency
    local -a application_arguments=()
    for argument in "$@"; do
        case "$argument" in
            start|stop|restart)
                if [[ -n "$action" ]]; then printf 'ERROR: Specify only one action.\n' >&2; return 1; fi
                action="$argument" ;;
            --java-home=*) requested_java_home="${argument#--java-home=}" ;;
            # Reject stdio before restart can stop a healthy HTTP service.
            --spring.ai.mcp.server.stdio=false) ;;
            --spring.ai.mcp.server.stdio|--spring.ai.mcp.server.stdio=*)
                printf 'ERROR: These scripts manage HTTP only. Launch Java directly with --spring.ai.mcp.server.stdio=true from the MCP client.\n' >&2
                return 1 ;;
            --*) application_arguments+=("$argument") ;;
            *) printf 'ERROR: Unknown argument: %s. Use start, stop or restart, plus --key=value overrides.\n' "$argument" >&2; return 1 ;;
        esac
    done
    action="${action:-restart}"
    printf 'Action: %s\n' "$action"
    for dependency in flock ss setsid nohup readlink; do
        if ! command -v "$dependency" >/dev/null 2>&1; then printf 'ERROR: Required Linux command not found: %s\n' "$dependency" >&2; return 1; fi
    done
    mkdir -p -- "$LOG_DIR" || return 1
    # flock serializes this deployment only; Java must not inherit descriptor 9.
    exec 9>"$LOG_DIR/.configs-migrate.lock"
    if ! flock -n 9; then printf 'ERROR: Another launcher operation is already running for this directory.\n' >&2; return 1; fi
    stop_timeout="$(timeout_seconds MIGRATE_STOP_TIMEOUT 30)" || return 1
    if [[ "$action" == stop ]]; then stop_service "$stop_timeout"; return $?; fi
    # Validate a restart's replacement configuration and JDK before stopping a healthy instance.
    jar="$(resolve_jar)" || return 1
    if [[ ! -f "$SCRIPT_DIR/application.yml" ]]; then printf 'ERROR: application.yml is missing from %s\n' "$SCRIPT_DIR" >&2; return 1; fi
    java="$(resolve_java "$requested_java_home")" || return 1
    start_timeout="$(timeout_seconds MIGRATE_START_TIMEOUT 60)" || return 1
    if [[ "$action" == restart ]]; then stop_service "$stop_timeout" || return 1; fi
    start_service "$java" "$jar" "$start_timeout" "${application_arguments[@]}"
}

# Sourcing exposes helpers for validation without running lifecycle operations.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    if main "$@"; then
        wait_for_exit
        exit 0
    else
        wait_for_exit 1
        exit 1
    fi
fi
