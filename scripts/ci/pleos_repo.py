#!/usr/bin/env python3
"""Pleos Connect 에뮬레이터 SDK 저장소 XML / devices.xml 을 읽어 셸 변수로 출력합니다.
사용: pleos_repo.py repo <crp-sys-img.xml> <xml url>
      pleos_repo.py device <devices.xml>
"""
import shlex
import sys
import urllib.parse
import xml.etree.ElementTree as ET


def local(tag):
    return tag.split('}')[-1]


def child_text(elem, name):
    for e in elem.iter():
        if local(e.tag) == name and (e.text or '').strip():
            return e.text.strip()
    return ''


def emit(key, value):
    print(f"{key}={shlex.quote(str(value))}")


def repo(path, base):
    root = ET.parse(path).getroot()
    pkgs = [e for e in root.iter() if local(e.tag) == 'remotePackage']
    pkgs.sort(key=lambda p: 0 if 'x86_64' in (p.get('path') or '') else 1)
    if not pkgs:
        emit('PKG_PATH', '')
        return
    p = pkgs[0]
    emit('PKG_COUNT', len(pkgs))
    emit('PKG_PATH', p.get('path') or '')
    emit('PKG_API', child_text(p, 'api-level'))
    emit('PKG_ABI', child_text(p, 'abi'))
    emit('PKG_DISPLAY', child_text(p, 'display-name'))
    tag_id = tag_display = ''
    for e in p.iter():
        if local(e.tag) == 'tag':
            for c in e:
                if local(c.tag) == 'id':
                    tag_id = (c.text or '').strip()
                if local(c.tag) == 'display':
                    tag_display = (c.text or '').strip()
            break
    emit('PKG_TAG', tag_id)
    emit('PKG_TAG_DISPLAY', tag_display)
    rev = [e for e in p.iter() if local(e.tag) == 'revision']
    if rev:
        emit('PKG_REVISION', '.'.join((c.text or '').strip() for c in rev[0]))
    archives = [e for e in p.iter() if local(e.tag) == 'archive']
    chosen = None
    for a in archives:
        host = child_text(a, 'host-os')
        if host in ('', 'linux'):
            chosen = a
            break
    if chosen is None and archives:
        chosen = archives[0]
    if chosen is not None:
        url = child_text(chosen, 'url')
        emit('ZIP_URL', urllib.parse.urljoin(base, url))
        emit('ZIP_SIZE', child_text(chosen, 'size'))
        emit('ZIP_SHA', child_text(chosen, 'checksum'))


def density(text):
    table = {'ldpi': 120, 'mdpi': 160, 'tvdpi': 213, 'hdpi': 240, 'xhdpi': 320, 'xxhdpi': 480, 'xxxhdpi': 640}
    t = (text or '').strip().lower()
    if t in table:
        return table[t]
    digits = ''.join(ch for ch in t if ch.isdigit())
    return int(digits) if digits else 160


def device(path):
    root = ET.parse(path).getroot()
    devs = [e for e in root.iter() if local(e.tag) == 'device']
    emit('DEV_COUNT', len(devs))
    if not devs:
        return
    d = devs[0]
    emit('DEV_ID', child_text(d, 'id'))
    emit('DEV_NAME', child_text(d, 'name'))
    emit('DEV_W', child_text(d, 'x-dimension'))
    emit('DEV_H', child_text(d, 'y-dimension'))
    emit('DEV_DENSITY', density(child_text(d, 'pixel-density')))
    emit('DEV_DIAGONAL', child_text(d, 'diagonal-length'))
    emit('DEV_RAM', child_text(d, 'ram'))


if __name__ == '__main__':
    if len(sys.argv) >= 4 and sys.argv[1] == 'repo':
        repo(sys.argv[2], sys.argv[3])
    elif len(sys.argv) >= 3 and sys.argv[1] == 'device':
        device(sys.argv[2])
