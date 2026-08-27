#!/usr/bin/env python3
"""PRTS spark 快速分析脚本（跨机器通用）。

依赖：pip install protobuf

获取 pb2 模块（三选一）：
  1) 已有 Spark-Profile-Converter：
       SPARK_PB2_DIR=/path/to/Spark-Profile-Converter
     它会从 <dir>/spark/spark_sampler_pb2.py 导入
  2) 只有单个 spark_sampler_pb2.py：
       SPARK_PB2_FILE=/path/to/spark_sampler_pb2.py
  3) 已安装成普通模块：
       SPARK_PB2_MODULE=spark.spark_sampler_pb2

用法：
  python3 spark_quick.py profile.sparkprofile [out.json]
"""
import os, sys, zlib, json, importlib.util
from collections import defaultdict

IDLE_PATTERNS = ('Unsafe.park', 'Unsafe.unpark', 'Thread.yield0', 'Object.wait', 'LockSupport.park')

def load_pb2():
    module_name = os.environ.get('SPARK_PB2_MODULE')
    if module_name:
        return importlib.import_module(module_name)

    pb2_file = os.environ.get('SPARK_PB2_FILE')
    if pb2_file:
        spec = importlib.util.spec_from_file_location('spark_sampler_pb2', pb2_file)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod

    pb2_dir = os.environ.get('SPARK_PB2_DIR')
    if pb2_dir:
        sys.path.insert(0, pb2_dir)
        from spark import spark_sampler_pb2
        return spark_sampler_pb2

    print('请设置 SPARK_PB2_DIR / SPARK_PB2_FILE / SPARK_PB2_MODULE 之一')
    sys.exit(2)

S = load_pb2()

def node_inc(node):
    return sum(node.times) if node.times else 0.0

def pkg_of(class_name):
    parts = class_name.split('.')
    if len(parts) >= 3 and parts[0] in ('net', 'com', 'org', 'io'):
        return '.'.join(parts[:3])
    return '.'.join(parts[:2])

def load_profile(path):
    raw = open(path, 'rb').read()
    try:
        raw = zlib.decompress(raw)
    except zlib.error:
        pass
    sd = S.SamplerData()
    sd.ParseFromString(raw)
    return sd

def main():
    path = sys.argv[1]
    sd = load_profile(path)
    md = sd.metadata
    ps = md.platform_statistics
    print(f'平台: {md.platform_metadata.name} {md.platform_metadata.minecraft_version} spark_v{md.platform_metadata.spark_version}')
    print(f'采样: {len(sd.threads)} 线程, {md.number_of_ticks} ticks, interval={md.interval}ms')
    if ps and ps.tps:
        print(f'TPS: {ps.tps.last1m:.2f} / {ps.tps.last5m:.2f} / {ps.tps.last15m:.2f}  players={ps.player_count}')
    if ps and ps.mspt:
        print(f'MSPT(5m): mean={ps.mspt.last5m.mean:.2f} median={ps.mspt.last5m.median:.2f} p95={ps.mspt.last5m.percentile95:.2f} max={ps.mspt.last5m.max:.2f}')

    method_self = defaultdict(float)
    mod_self = defaultdict(float)
    for thread in sd.threads:
        pool = thread.children
        def walk(idx):
            node = pool[idx]
            inc = node_inc(node)
            child = 0.0
            for c in node.children_refs:
                if 0 <= c < len(pool):
                    child += walk(c)
            self_t = inc - child
            key = f'{node.class_name}.{node.method_name}'
            method_self[key] += self_t
            mod = sd.class_sources.get(node.class_name, pkg_of(node.class_name))
            mod_self[mod] += self_t
            return inc
        for root in thread.children_refs:
            if 0 <= root < len(pool):
                walk(root)

    grand = sum(method_self.values()) or 1.0
    idle = sum(v for k, v in method_self.items() if any(p in k for p in IDLE_PATTERNS))
    active = grand - idle
    print(f'total={grand:.0f}ms idle={idle/grand*100:.1f}% active={active/grand*100:.1f}%')

    print('\nTop 30 active self-time:')
    rows = [(k, v) for k, v in method_self.items() if not any(p in k for p in IDLE_PATTERNS)]
    for i, (k, v) in enumerate(sorted(rows, key=lambda x: -x[1])[:30], 1):
        print(f'{i:2d} {v/active*100:6.2f}% {v:9.0f}  {k}')

    print('\n按来源 self-time (Top 20):')
    for i, (m, v) in enumerate(sorted(mod_self.items(), key=lambda x: -x[1])[:20], 1):
        print(f'{i:2d} {v/grand*100:6.2f}% {v:9.0f}  {m}')

    if len(sys.argv) > 2:
        out = {
            'file': os.path.basename(path),
            'grand_total': grand, 'idle': idle, 'active': active,
            'tps': {'1m': ps.tps.last1m, '5m': ps.tps.last5m, '15m': ps.tps.last15m} if ps and ps.tps else None,
            'top_methods': [{'method': k, 'self': v, 'pct_active': v/active*100}
                            for k, v in sorted(rows, key=lambda x: -x[1])[:60]],
            'by_mod': [{'mod': m, 'self': v, 'pct': v/grand*100}
                       for m, v in sorted(mod_self.items(), key=lambda x: -x[1])[:40]],
        }
        json.dump(out, open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        print(f'\n[JSON] -> {sys.argv[2]}')

if __name__ == '__main__':
    main()
