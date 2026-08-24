#!/usr/bin/env python3
"""PRTS 冒烟测试脚本（跨机器通用）。

前置：服务器已启动（自行启动，或面板启动），且开启 RCON。
环境变量：
  SMOKE_DIR=<服务器目录>             # 默认 ./server
  RCON_HOST / RCON_PORT / RCON_PASSWORD
用法：
  python3 smoke_test.py
输出：
  smoke-report.log（放服务器目录）
"""
import os, re, sys, datetime, subprocess, pathlib

SMOKE_DIR = os.environ.get('SMOKE_DIR', 'server')
LOG = pathlib.Path(SMOKE_DIR) / 'logs' / 'latest.log'

def rcon(cmd, timeout=15):
    # 延迟导入：rcon_cmd 在 import 时读取环境变量
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from rcon_cmd import rcon as _rcon
    return _rcon(cmd, timeout=timeout)

def check(name, ok, detail):
    return {'name': name, 'ok': bool(ok), 'detail': str(detail)[:300]}

def main():
    checks = []
    ts = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    try:
        log_text = LOG.read_text(encoding='utf-8', errors='replace')
        m = re.search(r'Done \((\d+\.\d+)s\)', log_text)
        checks.append(check('启动完成 Done', m is not None,
                            f'Done ({m.group(1)}s)' if m else '日志里没有 Done'))
        checks.append(check('无 FATAL', 'FATAL' not in log_text, '日志含 FATAL' if 'FATAL' in log_text else 'ok'))
    except FileNotFoundError:
        checks.append(check('启动完成 Done', False, f'找不到 {LOG}'))
        log_text = ''

    try:
        tps = rcon('tps')
        m = re.search(r'TPS from last 1m, 5m, 15m: [^\n]*', tps)
        checks.append(check('RCON /tps', bool(m), tps.replace('\n', ' ')))
    except Exception as e:
        checks.append(check('RCON /tps', False, repr(e)))

    try:
        status = rcon('servercore status')
        unsafe = re.search(r'BEPolicy:.*unsafe=(\d+)', status)
        journal = re.search(r'Journal:.*droppedOverflow=(\d+) droppedUnloaded=(\d+) failed=(\d+)', status)
        checks.append(check('BEPolicy unsafe=0',
                            unsafe is not None and unsafe.group(1) == '0',
                            (unsafe.group(0) if unsafe else '状态行缺失')))
        checks.append(check('Journal 无丢弃',
                            journal is not None and all(g == '0' for g in journal.groups()),
                            (journal.group(0) if journal else '状态行缺失')))
        checks.append(check('RCON /servercore status', True, 'ok'))
    except Exception as e:
        checks.append(check('RCON /servercore status', False, repr(e)))

    # 功能冒烟（可选，服务器在线时执行；不影响判定）
    try:
        out = rcon('list')
        checks.append(check('RCON /list', True, out.replace('\n', ' ')))
    except Exception as e:
        checks.append(check('RCON /list', False, repr(e)))

    lines = [f'# PRTS smoke report {ts}', f'SMOKE_DIR={SMOKE_DIR}']
    for c in checks:
        lines.append(f"[{'PASS' if c['ok'] else 'FAIL'}] {c['name']} :: {c['detail']}")
    failed = sum(1 for c in checks if not c['ok'])
    lines.append(f'RESULT: {"PASS" if failed == 0 else f"FAIL ({failed})"}')
    report = '\n'.join(lines)
    out_path = pathlib.Path(SMOKE_DIR) / 'smoke-report.log'
    out_path.write_text(report, encoding='utf-8')
    print(report)
    sys.exit(0 if failed == 0 else 1)

if __name__ == '__main__':
    main()
