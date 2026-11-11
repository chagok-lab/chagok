#!/usr/bin/env python3
"""Pleos Connect 에뮬레이터의 Virtual Console(HTTP, 8088 포트)로 차량 신호를 바꿔 가며
차곡 앱이 Vehicle SDK 신호만으로 운행을 기록하는지 확인합니다. (GitHub Actions 에서 사용)

사용: pleos_drive.py <결과 폴더>
흐름: 누적거리 설정 → 앱 실행 → 기어 D·주행 → 기어 P(용도 확인 카드) → 1탭 → 운전석 도어 열림 → 60초 뒤 확정
      → 두 번째 운행 → 앱 종료 중 주행(누락 구간) → 재실행 시 자동 보완 → 리포트 → 서식 내보내기

Virtual Console 이 쓰는 주소 (에뮬레이터 2.1.0 의 /vehicle/catalog 에서 확인):
  PUT /vehicle/odometer?traveled_distance_km=<km>     누적거리 (PERF_ODOMETER)
  PUT /vehicle/gear?gear=Park|Reverse|Neutral|Drive    기어
  PUT /vehicle/body?door_fl=true|false                 운전석 도어
  PUT /vehicle/controls?speed=<kph>&accelerator=&brake= 속도·페달
  POST /browser/session?client=<id>                    콘솔 화면이 1초마다 보내는 접속 신호
"""
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

OUT = sys.argv[1]
BASE = 'http://127.0.0.1:8088'
PKG = 'com.chagok.app'
T0 = time.time()
RESULT = os.path.join(OUT, 'result.txt')
os.makedirs(OUT, exist_ok=True)


def log(msg):
    print(f'[drive +{time.time() - T0:6.1f}s] {msg}', flush=True)


def result(key, value):
    with open(RESULT, 'a') as f:
        f.write(f'{key}={value}\n')
    log(f'RESULT {key}={value}')


def ev(name):
    with open(os.path.join(OUT, 'events_real.tsv'), 'a') as f:
        f.write(f'{time.time() - T0:.1f}\t{name}\n')
    log(f'EVENT {name}')


# ---------- Virtual Console (HTTP)
def http(method, path, timeout=8):
    req = urllib.request.Request(BASE + path, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(3_000_000).decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read(2000).decode('utf-8', 'replace')
    except Exception as e:  # noqa: BLE001
        return 0, f'{type(e).__name__}: {e}'


def get_json(path):
    status, body = http('GET', path)
    if status != 200:
        log(f'GET {path} -> {status} {body[:200]}')
        return None
    try:
        return json.loads(body)
    except Exception:  # noqa: BLE001
        return None


def put(path, quiet=False, **params):
    q = urllib.parse.urlencode(params)
    status, body = http('PUT', f'{path}?{q}')
    if not quiet or status != 200:
        log(f'PUT {path}?{q} -> {status} {body[:140]}')
    return status


def save(name, obj):
    with open(os.path.join(OUT, name), 'w') as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)


def walk(obj, path=''):
    if isinstance(obj, dict):
        for k, v in obj.items():
            yield from walk(v, f'{path}.{k}' if path else str(k))
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            yield from walk(v, f'{path}[{i}]')
    else:
        yield path, obj


def features(catalog):
    """catalog 에서 조작 가능한 신호(write_endpoint 가 있는 항목) 목록"""
    items = catalog.get('features') if isinstance(catalog, dict) else None
    return [f for f in (items or []) if isinstance(f, dict) and f.get('write_endpoint')]


class Heartbeat(threading.Thread):
    """Virtual Console 웹 화면이 열려 있는 것처럼 1초마다 접속 신호를 보냄"""

    def __init__(self):
        super().__init__(daemon=True)
        self.client = f'chagok-ci-{int(T0)}'
        self.stop_flag = False

    def run(self):
        while not self.stop_flag:
            http('POST', '/browser/session?client=' + self.client, timeout=3)
            time.sleep(1)

    def finish(self):
        self.stop_flag = True
        http('POST', '/browser/session?client=' + self.client + '&closing=1', timeout=3)


def flags(state):
    """신호 전달 통로(DDS) 준비 상태 요약"""
    out = {}
    for key in ('gear', 'odometer', 'body', 'controls', 'ev_battery'):
        part = state.get(key) or {}
        out[key] = {k: part.get(k) for k in ('bridge_ready', 'emulator_active', 'source') if k in part}
    out['browser'] = state.get('browser')
    return out


# ---------- 기기 (adb)
def adb(*args, timeout=40, binary=False):
    try:
        p = subprocess.run(['adb', *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        log(f'adb timeout: {args[:3]}')
        return b'' if binary else ''
    return p.stdout if binary else p.stdout.decode('utf-8', 'replace')


def shot(name):
    data = adb('exec-out', 'screencap', '-p', binary=True)
    with open(os.path.join(OUT, name + '.png'), 'wb') as f:
        f.write(data)


def box(bounds):
    m = re.match(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', bounds or '')
    return tuple(map(int, m.groups())) if m else None


def dump(name=None):
    adb('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
    xml = adb('exec-out', 'cat', '/sdcard/ui.xml')
    nodes = []
    try:
        for n in ET.fromstring(xml).iter('node'):
            label = ((n.get('text') or '') + ' ' + (n.get('content-desc') or '')).strip()
            if label and box(n.get('bounds')):
                nodes.append((label, box(n.get('bounds'))))
    except Exception:  # noqa: BLE001
        pass
    if name:
        with open(os.path.join(OUT, f'texts_{name}.txt'), 'w') as f:
            f.write('\n'.join(f'{b} | {t}' for t, b in nodes) + '\n')
    return nodes


def has(nodes, text):
    return any(text in t for t, _ in nodes)


def find(nodes, text):
    for t, b in nodes:
        if text in t:
            return b
    return None


def tap(nodes, text):
    b = find(nodes, text)
    if not b:
        log(f'tap target not found: {text}')
        return False
    x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    log(f"tap '{text}' at {x} {y}")
    adb('shell', 'input', 'tap', str(x), str(y))
    return True


def snap(name):
    nodes = dump(name)
    shot(name)
    log(f'screen {name}: ' + ' / '.join(t for t, _ in nodes)[:420])
    return nodes


def wait_for(text, seconds, absent=False):
    end = time.time() + seconds
    nodes = []
    while time.time() < end:
        nodes = dump()
        if has(nodes, text) != absent:
            return nodes
        time.sleep(0.7)
    return None


def app_start():
    adb('shell', 'am', 'start', '-W', '-n', f'{PKG}/.MainActivity')


def app_stop():
    adb('shell', 'am', 'force-stop', PKG)


# ---------- 화면 녹화 (한 번에 최대 180초이므로 이어서 녹화)
class Recorder(threading.Thread):
    def __init__(self):
        super().__init__(daemon=True)
        self.stop_flag = False
        self.parts = []

    def run(self):
        i = 0
        while not self.stop_flag and i < 4:
            i += 1
            path = f'/sdcard/chagok_real_{i}.mp4'
            self.parts.append((i, time.time() - T0))
            subprocess.run(['adb', 'shell', 'screenrecord', '--time-limit', '175', '--bit-rate', '8000000', path],
                           capture_output=True, timeout=200)

    def finish(self):
        self.stop_flag = True
        adb('shell', 'pkill', '-INT', 'screenrecord')
        time.sleep(4)
        with open(os.path.join(OUT, 'record_parts.tsv'), 'w') as f:
            for i, start in self.parts:
                f.write(f'{i}\t{start:.1f}\n')
                adb('pull', f'/sdcard/chagok_real_{i}.mp4', os.path.join(OUT, f'chagok_real_{i}.mp4'), timeout=120)


# ---------- 시나리오
def main():
    hb = Heartbeat()
    hb.start()
    time.sleep(3)
    state = get_json('/vehicle/state')
    catalog = get_json('/vehicle/catalog')
    result('vc_state', 'yes' if state else 'no')
    result('vc_catalog', 'yes' if catalog else 'no')
    if state is None:
        return
    save('vc_state_start.json', state)
    if catalog is not None:
        save('vc_catalog.json', catalog)
    log('bridge: ' + json.dumps(flags(state), ensure_ascii=False))
    feats = features(catalog) if catalog else []
    with open(os.path.join(OUT, 'vc_features.txt'), 'w') as f:
        for ft in feats:
            c = ft.get('control') or {}
            prop = ft.get('car_property') or {}
            f.write(' | '.join(str(x) for x in [ft.get('id'), ft.get('label'), ft.get('write_endpoint'), c.get('kind'),
                                                c.get('field') or c.get('field_by_area'), ft.get('unit'), prop.get('name'), prop.get('hex_id')]) + '\n')
    log(f'catalog features: {len(feats)}')
    odo_ok = any(ft.get('id') == 'perf-odometer' for ft in feats)
    result('odometer_writable', 'yes' if odo_ok else 'no')
    if not odo_ok:
        return

    odo = {'km': 12480.2}

    def set_odo(km, quiet=True):
        odo['km'] = round(km, 1)
        return put('/vehicle/odometer', quiet=quiet, traveled_distance_km=odo['km'])

    def park():
        """속도를 0 으로 만든 뒤 기어 P (콘솔 화면의 동작 순서와 같음)"""
        put('/vehicle/controls', quiet=True, speed=0, accelerator=0, brake=0)
        time.sleep(0.5)
        status = put('/vehicle/gear', gear='Park')
        gear = (get_json('/vehicle/state') or {}).get('gear') or {}
        log(f'console gear actual: {gear.get("actual")} bridge_ready={gear.get("bridge_ready")}')
        return status

    def console_odo():
        st = get_json('/vehicle/state') or {}
        return (st.get('odometer') or {}).get('traveled_distance_km')

    def drive_km(distance, step=0.8, pause=0.35):
        """누적거리를 조금씩 올려 주행을 재현 (콘솔의 속도 값도 함께 올림)"""
        target = round(odo['km'] + distance, 1)
        put('/vehicle/controls', quiet=True, speed=60, accelerator=30, brake=0)
        while odo['km'] < target - 1e-6:
            set_odo(min(target, odo['km'] + step))
            time.sleep(pause)
        put('/vehicle/controls', quiet=True, speed=0, accelerator=0, brake=100)
        time.sleep(0.5)
        put('/vehicle/controls', quiet=True, speed=0, accelerator=0, brake=0)
        log(f'drove to odometer {odo["km"]} (console {console_odo()})')

    # 0) 차를 세운 상태로 맞추고, 앱을 켜기 전에 누적거리를 정함 (앱의 첫 값이 기준점이 됨)
    app_stop()
    put('/vehicle/controls', speed=0, accelerator=0, brake=0)
    put('/vehicle/gear', gear='Park')
    put('/vehicle/body', door_fl='false')
    result('odometer_write_status', set_odo(12480.2, quiet=False))
    time.sleep(2)
    result('odometer_console_value', console_odo())
    log('bridge: ' + json.dumps(flags(get_json('/vehicle/state') or {}), ensure_ascii=False))
    adb('shell', 'pm', 'clear', PKG)
    adb('logcat', '-c')
    rec = Recorder()
    rec.start()
    time.sleep(2)
    app_start()
    ev('launch')
    nodes = wait_for('12,480.2', 25)
    if nodes is None:
        # 값이 앱까지 오지 않으면: 다시 한 번 써 보고 상태를 남김
        log('odometer not shown yet; writing again')
        set_odo(12480.2, quiet=False)
        nodes = wait_for('12,480.2', 20)
    time.sleep(2)
    nodes = snap('r0_idle')
    result('real_idle_shows_odometer', 'yes' if has(nodes, '12,480.2') else 'no')
    result('real_signal_line', next((t for t, _ in nodes if t.startswith('차량 신호')), 'none'))
    if not has(nodes, '12,480.2'):
        save('vc_state_no_signal.json', get_json('/vehicle/state') or {})
        rec.finish()
        hb.finish()
        return

    # 1) 첫 번째 운행: 기어 D → 18.4 km → 기어 P → 카드 → 추천 용도 1탭 → 문 열림 → 60초 뒤 확정
    put('/vehicle/gear', gear='Drive')
    ev('trip1_gear_d')
    time.sleep(1)
    drive_start = time.time()
    threading.Thread(target=drive_km, args=(18.4,), daemon=True).start()
    nodes = wait_for('기록 중', 20)
    result('real_trip1_recording', 'yes' if nodes else 'no')
    ev('trip1_recording')
    snap('r1_driving')
    while odo['km'] < 12498.6 - 1e-6 and time.time() - drive_start < 60:
        time.sleep(0.5)
    nodes = wait_for('18.4 km', 15)                 # 앱이 마지막 누적거리까지 받은 뒤에 주차
    result('real_trip1_distance_while_driving', 'yes' if nodes else 'no')
    snap('r1_driving_end')
    park()
    ev('trip1_gear_p')
    nodes = wait_for('용도를 확인', 15)
    result('real_trip1_card_on_park', 'yes' if nodes else 'no')
    nodes = snap('r1_confirm')
    result('real_trip1_distance', 'yes' if has(nodes, '18.4 km') else 'no')
    time.sleep(1.5)
    if not tap(nodes, '추천'):
        tap(nodes, '일반업무')
    ev('trip1_purpose')
    time.sleep(2)
    put('/vehicle/body', door_fl='true')
    ev('trip1_door_open')
    time.sleep(4)
    nodes = snap('r1_door_open')
    result('real_door_open_seen', 'yes' if has(nodes, '운전석 도어 열림') else 'no')
    nodes = wait_for('오늘 운행 1건', 80)
    result('real_trip1_sealed', 'yes' if nodes else 'no')
    ev('trip1_sealed')
    snap('r1_sealed')
    put('/vehicle/body', door_fl='false')
    time.sleep(2)

    # 2) 두 번째 운행: 23.7 km, 비업무로 확정
    put('/vehicle/gear', gear='Drive')
    ev('trip2_gear_d')
    time.sleep(1)
    drive_start = time.time()
    threading.Thread(target=drive_km, args=(23.7,), daemon=True).start()
    nodes = wait_for('기록 중', 20)
    result('real_trip2_recording', 'yes' if nodes else 'no')
    while odo['km'] < 12522.3 - 1e-6 and time.time() - drive_start < 60:
        time.sleep(0.5)
    wait_for('23.7 km', 15)
    snap('r2_driving_end')
    park()
    ev('trip2_gear_p')
    nodes = wait_for('용도를 확인', 15)
    result('real_trip2_card_on_park', 'yes' if nodes else 'no')
    nodes = snap('r2_confirm')
    time.sleep(1.5)
    tap(nodes, '비업무')
    ev('trip2_purpose')
    nodes = wait_for('오늘 운행 2건', 80)
    result('real_trip2_sealed', 'yes' if nodes else 'no')
    ev('trip2_sealed')
    nodes = snap('r2_sealed')
    result('real_report_ratio', next((t for t, _ in nodes if t.endswith('%')), 'none'))

    # 3) 앱이 꺼져 있는 동안 12.4 km 주행 → 다시 켜면 누락 구간을 찾아 채우는지
    app_stop()
    ev('app_stopped')
    put('/vehicle/gear', gear='Drive')
    drive_km(12.4, step=1.6, pause=0.2)
    park()
    time.sleep(2)
    app_start()
    ev('app_restarted')
    nodes = wait_for('오늘 운행 3건', 30)
    result('real_gap_filled', 'yes' if nodes else 'no')
    time.sleep(2)
    nodes = snap('r3_gap')
    result('real_gap_card', 'yes' if has(nodes, '용도를 확인') else 'no')
    time.sleep(2)
    if has(nodes, '용도를 확인'):
        tap(nodes, '일반업무')
        ev('gap_purpose')
        time.sleep(3)

    # 4) 리포트와 서식 내보내기
    nodes = snap('r4_report')
    result('real_report_three_trips', 'yes' if has(nodes, '오늘 운행 3건') else 'no')
    ev('report')
    time.sleep(3)
    if tap(nodes, '서식 내보내기'):
        ev('export')
        time.sleep(3)
        nodes = snap('r5_export')
        result('real_export_dialog', 'yes' if has(nodes, '서식을 저장했습니다') else 'no')
        time.sleep(4)
        tap(nodes, '닫기')
        ev('export_closed')
        time.sleep(2)
    ev('end')
    rec.finish()
    hb.finish()

    # 내보낸 CSV (사용자 10 의 앱 전용 폴더)
    for base in ('/storage/emulated/10', '/sdcard', '/storage/emulated/0'):
        csv = adb('shell', f'cat {base}/Android/data/{PKG}/files/*.csv 2>/dev/null')
        if csv.strip():
            with open(os.path.join(OUT, 'export_real.csv'), 'w') as f:
                f.write(csv)
            break
    result('real_export_csv_bytes', os.path.getsize(os.path.join(OUT, 'export_real.csv')) if os.path.exists(os.path.join(OUT, 'export_real.csv')) else 0)
    final = get_json('/vehicle/state')
    if final is not None:
        save('vc_state_end.json', final)
    app_pid = adb('shell', 'pidof', PKG).strip()
    log_all = adb('logcat', '-d', '-v', 'time', timeout=60)
    with open(os.path.join(OUT, 'logcat_real_app.txt'), 'w') as f:
        f.write('\n'.join(line for line in log_all.splitlines() if 'Chagok' in line or (app_pid and f'({app_pid:>5})' in line))[-200_000:])
    result('real_fatal_exceptions_app', sum(1 for i, line in enumerate(log_all.splitlines()) if 'FATAL EXCEPTION' in line and PKG in ' '.join(log_all.splitlines()[i:i + 3])))


if __name__ == '__main__':
    try:
        main()
    except Exception as e:  # noqa: BLE001
        import traceback
        traceback.print_exc()
        result('real_scenario_error', f'{type(e).__name__}: {e}'[:200])
    finally:
        # 차량 상태를 정차로 되돌림
        put('/vehicle/controls', quiet=True, speed=0, accelerator=0, brake=0)
        put('/vehicle/gear', quiet=True, gear='Park')
        put('/vehicle/body', quiet=True, door_fl='false')
