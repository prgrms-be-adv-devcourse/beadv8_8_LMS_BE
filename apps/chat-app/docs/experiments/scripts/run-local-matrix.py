"""Run isolated k6 conditions sequentially. No production endpoint is used."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[5]
output = root / 'apps/chat-app/build/reports/chat-k6'
output.mkdir(parents=True, exist_ok=True)
results = []
for people in (25, 50, 100, 200):
    for rate in (20, 50, 100):
        name = f'{people}-{rate}-30'
        print(f'START {name}', flush=True)
        env = dict(os.environ, CHAT_K6_PEOPLE=str(people), CHAT_K6_RATE=str(rate), CHAT_K6_SECONDS='30')
        started = time.time()
        with (output / f'{name}-gradle.log').open('w') as log:
            run = subprocess.run(['./gradlew', ':chat-app:chatK6Performance', '--console=plain'],
                                 cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
        item = dict(condition=name, exitCode=run.returncode, elapsedSeconds=round(time.time()-started, 2))
        summary = output / f'{name}.json'
        if summary.exists() and summary.stat().st_mtime >= started:
            metrics = json.loads(summary.read_text())['metrics']
            for key in ('chat_receive_ms', 'chat_send_lag_ms', 'chat_received', 'chat_sent',
                        'chat_connected', 'chat_duplicate', 'chat_errors', 'chat_receipt_skipped',
                        'chat_read_frames', 'http_req_failed', 'checks'):
                item[key] = metrics.get(key, {}).get('values', {})
            item['thresholdsPassed'] = all(t['ok'] for metric in metrics.values()
                                           for t in metric.get('thresholds', {}).values())
        results.append(item)
        (output / 'matrix.json').write_text(json.dumps(results, indent=2))
        print('DONE ' + json.dumps(item), flush=True)
sys.exit(1 if any(item['exitCode'] for item in results) else 0)
