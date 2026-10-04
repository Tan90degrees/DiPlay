#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Foreground ADB-only helper. No daemon, su changes, global rules or system files.
pkg=com.shihab.diplay.legacy
run_as=/system/bin/run-as
ip6=/system/bin/ip6tables
app_uid=$1
max_seconds=$2
case "$app_uid" in ''|*[!0-9]*) exit 2 ;; esac
case "$max_seconds" in ''|*[!0-9]*) exit 2 ;; esac
[ "$app_uid" -ge 10000 ] && [ "$max_seconds" -ge 60 ] && [ "$max_seconds" -le 14400 ] || exit 2
root_identity=$(/system/bin/id)
case "$root_identity" in uid=0\(*|uid=0\ *) ;; *) echo DIPLAY_HELPER_NOT_ROOT; exit 2 ;; esac
app_identity=$("$run_as" "$pkg" /system/bin/id)
app_actual_uid=${app_identity#uid=}
app_actual_uid=${app_actual_uid%%[!0-9]*}
[ "$app_actual_uid" = "$app_uid" ] || { echo DIPLAY_HELPER_UID_CHANGED; exit 2; }
[ -x "$ip6" ] || { echo DIPLAY_HELPER_NO_IP6TABLES; exit 2; }
"$run_as" "$pkg" /system/bin/mkdir files/adb-root-helper-lock 2>/dev/null || {
    echo DIPLAY_HELPER_ALREADY_RUNNING_OR_STALE_LOCK; exit 2;
}
installed=0
tag=
iface=
last_state=
guard=
clock() { IFS='. ' read -r now rest < /proc/uptime; }
status() {
    [ -n "$tag" ] || return 0
    clock
    # Every interpolated field below has already passed the strict grammar.
    "$run_as" "$pkg" /system/bin/sh -c "printf '%s\\n' '$tag $1 $app_uid $now' > files/adb-ipv6-status.new && mv files/adb-ipv6-status.new files/adb-ipv6-status" || return 1
    if [ "$last_state" != "$1" ]; then echo "DIPLAY_HELPER_$1"; last_state=$1; fi
}
rule() {
    "$ip6" -t filter "$@" -o "$iface" -m owner --uid-owner "$app_uid" \
        -s fe80::2/128 -d fe80::/64 -m comment --comment "$tag" -j RETURN
}
remove_rule() {
    if [ "$installed" = 1 ]; then
        rule -D st_filter_OUTPUT || { status ERROR_REMOVE; return 1; }
        installed=0
    fi
    status REMOVED
}
cleanup() {
    trap '' 1 2 15
    remove_rule
    [ -z "$guard" ] || kill "$guard" 2>/dev/null
    "$run_as" "$pkg" /system/bin/rmdir files/adb-root-helper-lock 2>/dev/null
}
trap cleanup 0
trap 'exit 0' 1 2 15
owner_pid=$$
# Explicit fd redirection prevents a background shell substituting /dev/null.
(while IFS= read -r keepalive; do :; done; kill -TERM "$owner_pid" 2>/dev/null) <&0 &
guard=$!
clock
end_time=$((now + max_seconds))
echo DIPLAY_HELPER_WAITING_FOR_APP
while :; do
    clock
    [ "$now" -lt "$end_time" ] || { echo DIPLAY_HELPER_DEADLINE; exit 0; }
    request=$("$run_as" "$pkg" /system/bin/cat files/adb-ipv6-request 2>/dev/null)
    valid=1
    [ "${#request}" -le 128 ] || valid=0
    IFS=' ' read -r action next_iface next_tag next_uid expiry extra <<EOF
$request
EOF
    case "$action" in OPEN|CLOSE) ;; *) valid=0 ;; esac
    case "$next_iface" in tun*) digits=${next_iface#tun}; case "$digits" in ''|*[!0-9]*) valid=0 ;; esac; [ "${#digits}" -le 5 ] || valid=0 ;; *) valid=0 ;; esac
    case "$next_tag" in diplay_*) suffix=${next_tag#diplay_}; case "$suffix" in *[!a-f0-9]*) valid=0 ;; esac; [ "${#suffix}" = 32 ] || valid=0 ;; *) valid=0 ;; esac
    [ "$next_uid" = "$app_uid" ] && [ -z "$extra" ] || valid=0
    case "$expiry" in ''|*[!0-9]*) valid=0 ;; *) [ "${#expiry}" -le 10 ] || valid=0 ;; esac
    if [ "$valid" = 1 ]; then
        [ "$expiry" -ge "$now" ] && [ "$expiry" -le "$((now + 10))" ] || valid=0
    fi
    if [ "$valid" != 1 ]; then
        # App death, stale heartbeat or malformed request never retains an exception.
        if [ -n "$tag" ]; then remove_rule || exit 2; tag=; iface=; fi
    elif [ "$action" = CLOSE ]; then
        if [ "$tag" = "$next_tag" ] && [ "$iface" = "$next_iface" ]; then remove_rule || exit 2; fi
    else
        if [ "$tag" != "$next_tag" ] || [ "$iface" != "$next_iface" ]; then
            remove_rule || exit 2
            tag=$next_tag; iface=$next_iface; last_state=
            rules=$("$ip6" -t filter -S st_filter_OUTPUT 2>/dev/null) || { status ERROR_CHAIN; exit 2; }
            case "$rules" in
                *"-j REJECT"*)
                    installed=1
                    rule -I st_filter_OUTPUT 1 || { installed=0; status ERROR_INSERT; exit 2; }
                    ;;
                *) installed=0 ;;
            esac
        fi
        if [ "$installed" = 1 ]; then status READY || exit 2; else status NO_REJECT || exit 2; fi
    fi
    sleep 1
done
