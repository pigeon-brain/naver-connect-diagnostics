"""Probe the NC1-KIC reader using a fixed version or IC status request."""

import argparse
import functools
import json
import operator
import re
import subprocess


DEFAULT_ADB = "adb"
DEVICE = "/dev/ttyS1"
FIRMWARE = r"\002\375\000\001\063\315"
IC_STATUS = r"\002\000\010\001\373\021\062\060\061\003\005\272"
ACK = r"\006\006\006"


def frames(data, command):
    for start, byte in enumerate(data):
        if byte != 2 or start + 4 > len(data):
            continue
        if command == "firmware":
            if data[start + 1] != 0xFD or data[start + 2:start + 4] != b"\x00\x26":
                continue
            frame = data[start:start + 31]
            if (len(frame) == 31 and frame[4:6] == b"\x00\x33"
                    and functools.reduce(operator.xor, frame) == 0):
                yield frame
        else:
            length = int.from_bytes(data[start + 1:start + 3], "big") + 4
            frame = data[start:start + length]
            if (len(frame) == length and length == 14 and frame[4:7] == b"\xfb\x11\x32"
                    and frame[-3] == 3 and frame[-2:] == kicc_crc(frame[1:-2])):
                yield frame


def kicc_crc(data):
    crc = 0xFFFF
    for value in data:
        for _ in range(8):
            crc = (crc >> 1) ^ (0x8005 if (crc ^ value) & 1 else 0)
            value >>= 1
    return (crc ^ 0xFFFF).to_bytes(2, "little")


def query_adb(adb, serial, *args):
    result = subprocess.run(
        [adb, "-s", serial, *args], capture_output=True, timeout=8, check=False,
    )
    if result.returncode:
        raise RuntimeError("ADB or device command failed.")
    return result.stdout


def verify_device(run, repair):
    if run("shell", "getprop ro.product.model").strip() != b"Npay_connect":
        raise RuntimeError("Expected device model Npay_connect.")
    if run("shell", "id -u").strip() != b"0":
        raise RuntimeError("Root ADB is required on the selected device.")
    if run("shell", "cat /sys/class/tty/ttyS1/dev").strip() != b"4:65":
        raise RuntimeError("Expected kernel ttyS1 device number 4:65.")
    driver = run("shell", "readlink -f /sys/class/tty/ttyS1/device/driver").strip()
    if driver.rsplit(b"/", 1)[-1] != b"mt6577-uart":
        raise RuntimeError("Expected ttyS1 MediaTek UART driver.")
    if repair:
        run("shell", f"if [ ! -e {DEVICE} ] && [ ! -L {DEVICE} ]; then "
                     f"mknod -m 600 {DEVICE} c 4 65 && "
                     f"chcon u:object_r:serial_device:s0 {DEVICE} || exit 1; fi")
    try:
        run("shell", f"test ! -L {DEVICE} && test -c {DEVICE} && "
                     f"test \"$(stat -c %t:%T {DEVICE})\" = 4:41")
    except RuntimeError as error:
        raise RuntimeError("ttyS1 character node is missing or does not match the kernel device.") from error


def exchange(run, request, *, response=True):
    # Keep the existing tty configuration and use a short bounded read.
    script = (
        f"set -e; exec 3<>{DEVICE}; original=$(stty -g <&3); "
        "trap 'stty \"$original\" <&3' EXIT; "
        "trap 'exit 1' HUP INT TERM; "
        "stty raw -echo -ixon -ixoff -crtscts cs8 -cstopb -parenb clocal 115200 <&3; "
        f"printf '{request}' >&3; "
    )
    if response:
        script += "timeout -s KILL 2 dd bs=1 count=64 <&3 2>/dev/null || [ $? -eq 137 ]"
    return run("exec-out", script)


def probe(run, command):
    identity = probe(run, "firmware") if command == "ic-status" else None
    received = exchange(run, FIRMWARE if command == "firmware" else IC_STATUS)
    matches = list(frames(received, command))
    if len(matches) != 1:
        raise RuntimeError("A unique validated reader response was not received.")
    frame = matches[0]
    if command == "ic-status":
        exchange(run, ACK, response=False)
        presence = {48: "inserted", 49: "removed"}.get(frame[10], "unknown") if identity["firmware"] == "XR0012" else "unverified"
        return {"command": command, "status_byte": frame[10], "presence": presence, "reader": identity}
    if frame[6:13] != b"NC1-KIC" or not re.fullmatch(rb"[A-Z0-9]{6}", frame[22:28]):
        raise RuntimeError("Reader model or firmware field did not match the expected format.")
    return {"command": command, "model": "NC1-KIC", "firmware": frame[22:28].decode("ascii")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Current ADB transport ID")
    parser.add_argument("--adb", default=str(DEFAULT_ADB))
    parser.add_argument("--repair-node", action="store_true", help="Create the missing verified ttyS1 device node")
    parser.add_argument("command", choices=("firmware", "ic-status"))
    args = parser.parse_args()
    run = functools.partial(query_adb, args.adb, args.serial)
    try:
        verify_device(run, args.repair_node)
        lock = "/dev/.ts201-reader-probe-lock"
        try:
            run("shell", f"mkdir {lock}")
        except RuntimeError as error:
            raise RuntimeError("Reader UART is busy or its lock could not be created.") from error
        try:
            result = probe(run, args.command)
        finally:
            run("shell", f"rmdir {lock}")
        print(json.dumps(result))
    except subprocess.TimeoutExpired:
        print(json.dumps({"command": args.command, "error": "ADB or device command timed out."}))
        return 1
    except (OSError, RuntimeError) as error:
        print(json.dumps({"command": args.command, "error": str(error)}))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
