#!/usr/bin/env python3
"""
MoonTone debug CLI.

Forwards the app's debug control server (127.0.0.1:47748 inside Android)
through adb and sends JSON commands.

Usage:
  python3 moontone_cli.py status
  python3 moontone_cli.py connect 192.168.31.174
  python3 moontone_cli.py pair 192.168.31.174
  python3 moontone_cli.py disconnect
  python3 moontone_cli.py logs [-n 200]
  python3 moontone_cli.py cert > moontone_client.pem
  python3 moontone_cli.py ping
"""
import argparse
import json
import socket
import subprocess
import sys

PORT = 47748


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True)


def ensure_forward():
    adb("forward", "--remove", f"tcp:{PORT}")
    r = adb("forward", f"tcp:{PORT}", f"tcp:{PORT}")
    if r.returncode != 0:
        sys.exit(f"adb forward failed: {r.stderr.strip()}")


def send(cmd: dict, timeout: float = 30.0) -> dict:
    ensure_forward()
    with socket.create_connection(("127.0.0.1", PORT), timeout=timeout) as s:
        s.sendall((json.dumps(cmd) + "\n").encode())
        s.settimeout(timeout)
        f = s.makefile("r")
        line = f.readline()
        if not line:
            return {"ok": False, "error": "empty response from app"}
        return json.loads(line)


def main():
    ap = argparse.ArgumentParser(description="MoonTone debug CLI")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("status")
    sub.add_parser("disconnect")
    sub.add_parser("ping")
    p = sub.add_parser("connect")
    p.add_argument("host")
    p = sub.add_parser("pair")
    p.add_argument("host")
    p = sub.add_parser("micstart")
    p.add_argument("host")
    p.add_argument("-p", "--port", type=int, default=48100)
    sub.add_parser("micstop")
    sub.add_parser("micstatus")
    p = sub.add_parser("audiomode")
    p.add_argument("mode", choices=["LATENCY", "BALANCED", "QUALITY"])
    p = sub.add_parser("logs")
    p.add_argument("-n", "--lines", type=int, default=150)
    sub.add_parser("cert")

    args = ap.parse_args()
    if args.cmd == "status":
        r = send({"cmd": "status"})
    elif args.cmd == "disconnect":
        r = send({"cmd": "disconnect"})
    elif args.cmd == "ping":
        r = send({"cmd": "ping"})
    elif args.cmd == "connect":
        r = send({"cmd": "connect", "host": args.host})
    elif args.cmd == "pair":
        r = send({"cmd": "pair", "host": args.host})
    elif args.cmd == "micstart":
        r = send({"cmd": "micstart", "host": args.host, "port": args.port})
    elif args.cmd == "micstop":
        r = send({"cmd": "micstop"})
    elif args.cmd == "micstatus":
        r = send({"cmd": "micstatus"})
    elif args.cmd == "audiomode":
        r = send({"cmd": "audiomode", "mode": args.mode})
    elif args.cmd == "logs":
        r = send({"cmd": "logs", "lines": args.lines})
    elif args.cmd == "cert":
        r = send({"cmd": "cert"})
    else:
        sys.exit("unknown command")

    if r.get("ok"):
        if args.cmd == "logs":
            print(r.get("logs", ""))
        elif args.cmd == "cert":
            print(r.get("pem", ""))
        else:
            print(json.dumps(r, ensure_ascii=False, indent=2))
    else:
        print(json.dumps(r, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
