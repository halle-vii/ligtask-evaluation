#!/usr/bin/env python3
"""Real HTTP import, terminate application, restart and verify persisted synthetic records."""
import html
import csv
import io
import http.cookiejar
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import urllib.parse

ROOT = Path(__file__).resolve().parent.parent
BASE = 'http://127.0.0.1:18080'
LOG = ROOT / '.local/persistence-check.log'
LOG.parent.mkdir(exist_ok=True)
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'

def start():
    log = LOG.open('ab')
    proc = subprocess.Popen([JAVA, '-jar', str(ROOT / 'target/ligtask-evaluation-0.1.0.jar'), '--server.port=18080'], cwd=ROOT, stdout=log, stderr=log)
    log.close()
    for _ in range(150):
        if proc.poll() is not None:
            raise RuntimeError(f'Java exited; inspect {LOG}')
        try:
            urllib.request.urlopen(BASE, timeout=1).close()
            return proc
        except OSError:
            time.sleep(.2)
    proc.terminate()
    raise RuntimeError(f'Startup timeout; inspect {LOG}')

def stop(proc):
    proc.terminate()
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()

def hidden(page, name):
    for tag in re.findall(r'<input\b[^>]*>', page):
        attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', tag))
        if attrs.get('name') == name:
            return html.unescape(attrs['value'])
    raise AssertionError(f'Missing {name}')

proc = start()
try:
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    page = opener.open(BASE).read().decode()
    match = re.search(r'href="(/(?:datasets/[a-f0-9-]+|evaluation/[a-f0-9-]+/coverage))"[^>]*>SYNTHETIC LIGTask demonstration', page)
    if match:
        url = BASE + match.group(1)
        print('Reusing the previously imported synthetic demo.')
    else:
        csrf = hidden(page, '_csrf')
        boundary = 'LIGTaskPersistenceBoundary'
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic-provisional-v1.json"\r\nContent-Type: application/json\r\n\r\n'.encode() + (ROOT / 'samples/synthetic-provisional-v1.json').read_bytes() + f'\r\n--{boundary}--\r\n'.encode())
        req = urllib.request.Request(BASE + '/preview?' + urllib.parse.urlencode({'_csrf': csrf}), body, headers={'Content-Type': f'multipart/form-data; boundary={boundary}'})
        preview = opener.open(req).read().decode()
        assert 'Confirm import' in preview and 'Import blocked' not in preview
        payload = urllib.parse.urlencode({'token': hidden(preview, 'token'), '_csrf': hidden(preview, '_csrf')}).encode()
        response = opener.open(BASE + '/import', payload)
        url = response.url
        assert '/evaluation/' in url and url.endswith('/coverage')
    response = opener.open(url)
    url = response.url
    before = response.read().decode()
    assert 'Dataset composition' in before and 'demo-1' in before and 'SYNTHETIC' in before
    export_url = BASE + urllib.parse.urlsplit(url).path.removesuffix('/coverage') + '/export.csv'
    exported_before = opener.open(export_url).read().decode('utf-8-sig')
    rows_before = list(csv.DictReader(io.StringIO(exported_before)))
    assert len(rows_before) == 24 and 'paanyaya' in exported_before
    stop(proc)
    proc = start()
    after = urllib.request.urlopen(url).read().decode()
    exported_after = urllib.request.urlopen(export_url).read().decode('utf-8-sig')
    rows_after = list(csv.DictReader(io.StringIO(exported_after)))
    assert len(rows_after) == 24 and rows_before == rows_after
    assert before == after, 'Dataset page changed after restart'
    print(f'PASS: HTTP import and restart persistence; 24 records; UTF-8 retained; same dataset URL: {url}')
finally:
    stop(proc)
