#!/usr/bin/env python3
"""Pleos Connect 에뮬레이터의 Virtual Console(웹 화면)을 내려받아 구조를 확인합니다.
사용: vc_fetch.py <base url> <저장 폴더>
"""
import os
import re
import sys
import time
import urllib.parse
import urllib.request

base, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
seen, queue, report = set(), ['/'], []
MAX_FILES, MAX_BYTES = 60, 6_000_000
READ_SECONDS, TOTAL_SECONDS = 8, 150      # 계속 흘러나오는 응답(이벤트 스트림 등)에 붙잡히지 않도록 제한
T0 = time.time()


def fetch(path):
    url = urllib.parse.urljoin(base, path)
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'chagok-ci'})
        with urllib.request.urlopen(req, timeout=6) as r:
            body, start = b'', time.time()
            while len(body) < MAX_BYTES and time.time() - start < READ_SECONDS:
                try:
                    chunk = r.read1(65536)
                except Exception:  # noqa: BLE001  (읽기 제한 시간 초과 등)
                    break
                if not chunk:
                    break
                body += chunk
            return r.status, r.headers.get('Content-Type', ''), body
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get('Content-Type', ''), e.read(2000)
    except Exception as e:  # noqa: BLE001
        return 0, type(e).__name__ + ': ' + str(e)[:120], b''


def save(path, body):
    name = re.sub(r'[^A-Za-z0-9._-]+', '_', path.strip('/')) or 'index.html'
    with open(os.path.join(out, name[:120]), 'wb') as f:
        f.write(body)


while queue and len(seen) < MAX_FILES and time.time() - T0 < TOTAL_SECONDS:
    path = queue.pop(0)
    if path in seen:
        continue
    seen.add(path)
    status, ctype, body = fetch(path)
    report.append(f'{status}\t{len(body)}\t{ctype}\t{path}')
    if status != 200 or not body:
        continue
    save(path, body)
    text = body.decode('utf-8', 'replace')
    found = set()
    if 'html' in ctype or path == '/':
        found |= set(m for _, m in re.findall(r'(src|href)=["\']([^"\'#]+)["\']', text))
    if 'javascript' in ctype or path.endswith('.js') or 'html' in ctype:
        found |= set(re.findall(r'["\'`]((?:\.{0,2}/)?[\w./-]+\.(?:js|mjs|json|css|map|html))["\'`]', text))
    for f in found:
        if f.startswith(('http://', 'https://', '//', 'data:')):
            continue
        p = urllib.parse.urljoin(path, f)
        if p not in seen and p not in queue:
            queue.append(p)

# 자주 쓰는 API 경로가 있는지 확인 (조회만)
for probe in ['/api', '/api/', '/api/v1', '/openapi.json', '/swagger.json', '/v3/api-docs', '/docs', '/health',
              '/api/vehicle', '/api/properties', '/api/property', '/vehicle', '/properties', '/status', '/api/status']:
    if probe in seen or time.time() - T0 > TOTAL_SECONDS:
        continue
    status, ctype, body = fetch(probe)
    report.append(f'{status}\t{len(body)}\t{ctype}\tPROBE {probe}')
    if status == 200 and body:
        save('probe' + probe, body)

with open(os.path.join(out, '_report.tsv'), 'w') as f:
    f.write('\n'.join(report) + '\n')
print('\n'.join(report))
