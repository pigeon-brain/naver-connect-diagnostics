"""Count KICC FC34 swipe events during a bounded, receive-only UART session."""

import argparse
import functools
import json
import operator
import subprocess

from card_probe import DEFAULT_ADB, DEVICE, query_adb, verify_device

UART_LOCK = "/dev/.ts201-reader-probe-lock"


def count_events(data):
    count = 0
    start = 0
    while (start := data.find(b"\x02", start)) != -1:
        if start + 5 > len(data):
            break
        if data[start + 1] in (0xFC, 0xFD):
            high, low = data[start + 2:start + 4]
            digits = (high >> 4, high & 15, low >> 4, low & 15)
            if max(digits) <= 9:
                length = 1000 * digits[0] + 100 * digits[1] + 10 * digits[2] + digits[3]
                end = start + length + 5
                if length and end <= len(data):
                    if functools.reduce(operator.xor, memoryview(data)[start:end]) == 0:
                        count += data[start + 1] == 0xFC and data[start + 4] == 0x34
                        start = end
                        continue
        start += 1
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Current ADB transport ID")
    parser.add_argument("--adb", default=DEFAULT_ADB)
    parser.add_argument("--seconds", type=int, default=60)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 120:
        parser.error("--seconds must be between 1 and 120")

    run = functools.partial(query_adb, args.adb, args.serial)
    script = (
        "set -e; "
        f"lock={UART_LOCK}; mkdir \"$lock\" || exit 1; "
        "trap 'rmdir \"$lock\" || exit 93' EXIT; "
        f"exec 3<>{DEVICE}; original=$(stty -g <&3) || exit 91; "
        "cleanup() { status=$?; trap - EXIT; "
        "stty \"$original\" <&3 || status=92; "
        "rmdir \"$lock\" || status=93; exit \"$status\"; }; "
        "trap cleanup EXIT; trap 'exit 1' HUP INT TERM; "
        "stty raw -echo -ixon -ixoff -crtscts cs8 -cstopb -parenb clocal 115200 <&3; "
        f"rc=0; timeout -s KILL {args.seconds} dd bs=1 count=32768 <&3 2>/dev/null || rc=$?; "
        "case \"$rc\" in 0|137) ;; *) exit \"$rc\";; esac"
    )
    try:
        verify_device(run, False)
        print(json.dumps({"state": "starting", "window_seconds": args.seconds}), flush=True)
        result = subprocess.run(
            [args.adb, "-s", args.serial, "shell", "-T", script],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            timeout=args.seconds + 8, check=False,
        )
        if result.returncode:
            print(json.dumps({
                "error": {
                    91: "Could not read the original UART settings.",
                    92: "Could not restore the original UART settings.",
                    93: "Could not remove the UART lock.",
                }.get(result.returncode, "The UART listener failed to start or finish."),
                "exit_status": result.returncode,
            }))
            return 1
        print(json.dumps({"valid_fc34_frames": count_events(result.stdout)}))
    except (OSError, RuntimeError, subprocess.TimeoutExpired):
        print(json.dumps({"error": "ADB verification or the UART listener failed."}))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
