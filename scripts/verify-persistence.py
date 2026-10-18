#!/usr/bin/env python3
"""HTTP import and restart verification for a synthetic or supplied TFG JSON file."""
import argparse
import hashlib
import json
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

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--file', type=Path, help='TFG source JSON to import and verify; default is the synthetic TFG sample')
args = parser.parse_args()
source_path = args.file or ROOT / 'src/main/resources/static/samples/synthetic-tfg.json'
source_bytes = source_path.read_bytes()
source = json.loads(source_bytes)
assert isinstance(source, list) and source, 'Expected a nonempty TFG source array'
expected = {}
for entry in source:
    condition = 'SAFE' if entry['context_intended_to_be_safe'] else 'UNSAFE'
    for task, derivative in entry['task_derivatives'].items():
        identifier = f"{entry['base_id']}_{condition}_{task.upper()}_{entry['language'].upper()}"
        assert identifier not in expected
        expected[identifier] = (derivative['input']['query'], condition, task.upper(), entry['language'].upper(), derivative['input'].get('context'))
marker = 'sha256-' + hashlib.sha256(source_bytes).hexdigest()

def verify_export(exported):
    rows = list(csv.DictReader(io.StringIO(exported)))
    assert len(rows) == len(expected)
    assert {r['instance_id'] for r in rows} == expected.keys()
    for row in rows:
        query, condition, task, language, context = expected[row['instance_id']]
        # The CSV display export neutralizes spreadsheet formulas; stored source text is unchanged.
        stripped = query.lstrip()
        if (stripped and stripped[0] in '=+-@') or query.startswith(('\t', '\r', '\n')):
            query = "'" + query
        assert row['prepared_prompt_text'] == query
        assert (row['context_condition'], row['task_type'], row['language_variant']) == (condition, task, language)
        if isinstance(context, dict):
            assert json.loads(row['context_text']) == context
        else:
            assert row['context_text'] == (context or '')
        assert not row['human_label'] and not row['model_label']
        assert row['comparison_status'] == 'NOT_EVALUATED'
    return rows

proc = start()
try:
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    page = opener.open(BASE).read().decode()
    imported_row = next((row for row in re.findall(r'<tr\b[^>]*>.*?</tr>', page, re.S) if marker in html.unescape(row)), '')
    match = re.search(r'href="(/(?:datasets/[a-f0-9-]+|evaluation/[a-f0-9-]+/coverage))"', imported_row)
    if match:
        url = BASE + match.group(1)
        print('Reusing the previously imported matching dataset.')
    else:
        csrf = hidden(page, '_csrf')
        boundary = 'LIGTaskPersistenceBoundary'
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{source_path.name}"\r\nContent-Type: application/json\r\n\r\n'.encode() + source_bytes + f'\r\n--{boundary}--\r\n'.encode())
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
    assert 'Dataset composition' in before
    export_url = BASE + urllib.parse.urlsplit(url).path.removesuffix('/coverage') + '/export.csv'
    exported_before = opener.open(export_url).read().decode('utf-8-sig')
    rows_before = verify_export(exported_before)
    assert all(row['dataset_version'] == marker for row in rows_before)
    stop(proc)
    proc = start()
    after = urllib.request.urlopen(url).read().decode()
    exported_after = urllib.request.urlopen(export_url).read().decode('utf-8-sig')
    rows_after = verify_export(exported_after)
    assert rows_before == rows_after
    assert before == after, 'Dataset page changed after restart'
    print(f'PASS: HTTP import and restart persistence; {len(rows_after)} records; exact queries/context and blank human/model labels retained; same dataset URL: {url}')
finally:
    stop(proc)
