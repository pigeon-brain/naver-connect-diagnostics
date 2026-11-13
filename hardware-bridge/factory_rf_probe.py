"""Run one NFC presence test and verify the reader returns to production mode."""

import argparse
import functools
import json
import subprocess
import time

from card_probe import DEFAULT_ADB, DEVICE, probe, query_adb, verify_device
from passive_msr_probe import UART_LOCK

FACTORY_ENTER = "@@@2777@@@"
FACTORY_EXIT = "@@@2778@@@"
RF_REQUEST = r"\002\000\013\001\016\001\346\000\001\001\237\166\003"


def factory_crc(data):
    crc = 0
    for value in data:
        crc ^= value << 8
        for _ in range(8):
            crc = ((crc << 1) ^ (0x8005 if crc & 0x8000 else 0)) & 0xFFFF
    return crc.to_bytes(2, "big")


def rf_status(data):
    payloads = []
    for offset, value in enumerate(data):
        if value != 2:
            continue
        frame = data[offset:offset + 15]
        if (len(frame) == 15 and frame[2:9] == b"\x0b\x01\x0e\x01\xe6\x00\x03"
                and frame[-1] == 3 and frame[-3:-1] == factory_crc(frame[1:-3])):
            payloads.append(frame[9:12])
    if len(payloads) != 1:
        raise RuntimeError("A unique validated NFC response was not received.")
    return {
        b"\x00\x01\x01": "type_a",
        b"\x00\x01\x02": "type_b",
        b"\x00\x01\x04": "unknown_card_type",
        b"\xf2\x01\xfb": "no_card_detected",
    }.get(payloads[0], "unknown_response")


def factory_exchange(run):
    script = (
        f"set -e; exec 3<>{DEVICE}; original=$(stty -g <&3); "
        "entered=0; "
        "cleanup() { status=$?; trap - EXIT; "
        f"if [ \"$entered\" -eq 1 ]; then printf '%s' '{FACTORY_EXIT}' >&3 || status=1; sleep 1 || status=1; fi; "
        "stty \"$original\" <&3 || status=1; exit \"$status\"; }; "
        "trap cleanup EXIT; trap 'exit 1' HUP INT TERM; "
        "stty raw -echo -ixon -ixoff -crtscts cs8 -cstopb -parenb clocal 115200 <&3; "
        f"entered=1; printf '%s' '{FACTORY_ENTER}' >&3; sleep 1; "
        f"printf '{RF_REQUEST}' >&3; "
        "rc=0; timeout -s KILL 2 dd bs=1 count=64 <&3 2>/dev/null || rc=$?; "
        "case \"$rc\" in 0|137) ;; *) exit \"$rc\";; esac; "
        f"printf '%s' '{FACTORY_EXIT}' >&3; entered=0; sleep 1"
    )
    return run("shell", "-T", script)


def smoke_test(run, result):
    verify_device(run, False)
    run("shell", f"mkdir {UART_LOCK}")
    try:
        if probe(run, "firmware") != {"command": "firmware", "model": "NC1-KIC", "firmware": "XR0012"}:
            raise RuntimeError("Expected reader NC1-KIC XR0012.")
        result["production_verified"] = False
        try:
            result["nfc_status"] = rf_status(factory_exchange(run))
        finally:
            for attempt in range(1, 4):
                result["production_attempts"] = attempt
                try:
                    production = probe(run, "ic-status")
                    result["production_verified"] = production["presence"] in ("inserted", "removed")
                except (OSError, RuntimeError, subprocess.TimeoutExpired):
                    pass
                if result["production_verified"]:
                    break
                if attempt < 3:
                    time.sleep(1)
        if not result["production_verified"]:
            raise RuntimeError("Production protocol recovery could not be verified.")
        if result["nfc_status"] == "unknown_response":
            raise RuntimeError("The NFC response status was not recognized.")
    finally:
        try:
            run("shell", f"rmdir {UART_LOCK}")
        except (OSError, RuntimeError, subprocess.TimeoutExpired):
            result["lock_cleanup_failed"] = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Current ADB transport ID")
    parser.add_argument("--adb", default=DEFAULT_ADB)
    parser.add_argument("--enter-factory", action="store_true", required=True)
    parser.add_argument("--allow-reader-sound", action="store_true", help="Explicitly allow the reader buzzer during factory entry and reset")
    args = parser.parse_args()
    if not args.allow_reader_sound:
        parser.error("Factory entry/reset can sound the reader buzzer. Blocked without --allow-reader-sound.")
    run = functools.partial(query_adb, args.adb, args.serial)
    result = {}
    try:
        smoke_test(run, result)
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        result["error"] = str(error) if isinstance(error, RuntimeError) else "ADB or UART probe failed."
    if result.get("lock_cleanup_failed"):
        result.setdefault("error", "Could not remove the UART lock.")
    print(json.dumps(result))
    return int("error" in result)


if __name__ == "__main__":
    raise SystemExit(main())
