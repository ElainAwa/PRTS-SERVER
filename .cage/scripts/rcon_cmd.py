#!/usr/bin/env python3
"""Minimal RCON client for PRTS test server (port 25575, password prts123)."""
import socket, struct, sys, time, subprocess, os

PORT = int(os.environ.get('RCON_PORT', '25575'))
PASSWORD = os.environ.get('RCON_PASSWORD', 'prts123')

def _default_host():
    env = os.environ.get('RCON_HOST')
    if env:
        return env
    try:
        out = subprocess.run(['ip', 'route', 'show', 'default'], capture_output=True, text=True, timeout=5).stdout
        gw = out.split()[2]
        return gw
    except Exception:
        return '127.0.0.1'

HOST = _default_host()

def _pack(req_id, ptype, payload):
    body = struct.pack('<ii', req_id, ptype) + payload.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(body)) + body

def _recv_pkt(sock):
    raw = b''
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk:
            raise ConnectionError('closed')
        raw += chunk
    length = struct.unpack('<i', raw)[0]
    while len(raw) < 4 + length:
        chunk = sock.recv(4 + length - len(raw))
        if not chunk:
            raise ConnectionError('closed')
        raw += chunk
    req_id, ptype = struct.unpack('<ii', raw[4:12])
    payload = raw[12:4 + length]
    return req_id, ptype, payload

def rcon(cmd, timeout=10.0):
    s = socket.create_connection((HOST, PORT), timeout=timeout)
    try:
        s.settimeout(timeout)
        s.sendall(_pack(1, 3, PASSWORD))
        rid, ptype, payload = _recv_pkt(s)
        if ptype != 2:
            # auth response is type 2 with the assigned id; retry with id=rid
            pass
        s.sendall(_pack(rid, 2, cmd))
        rid2, ptype2, payload2 = _recv_pkt(s)
        # read until type 0 response (RCON sends empty type 0 terminator)
        if ptype2 != 0:
            rid3, ptype3, payload3 = _recv_pkt(s)
            payload2 = payload3
        return payload2.rstrip(b'\x00').decode('utf-8', 'replace')
    finally:
        s.close()

if __name__ == '__main__':
    cmd = ' '.join(sys.argv[1:])
    if not cmd:
        print('usage: rcon_cmd.py <command>')
        sys.exit(1)
    try:
        print(rcon(cmd))
    except Exception as e:
        print(f'RCON_ERROR: {e}')
        sys.exit(2)
