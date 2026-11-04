"""Preflight 100 real members, then run 30/50/100 messages per second sequentially."""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time

from accounts import Api, private_path, save_private


def prepare(manifest, room_id):
    accounts = manifest['accounts']
    if len(accounts) != 100 or len({a['id'] for a in accounts}) != 100:
        raise ValueError('Exactly 100 distinct created accounts are required')
    users = []
    latest = set()
    host_id = None
    for index, account in enumerate(accounts, 1):
        api = Api(manifest['baseUrl'])
        user, token = api.login(account)
        if user['id'] != account['id']:
            raise ValueError(f'Account {index}: identity mismatch')
        if user['role'] not in ('INFLUENCER', 'VERIFIED_INFLUENCER', 'ADMIN') or user['status'] != 'ACTIVE':
            raise ValueError(f'Account {index}: role={user["role"]}, status={user["status"]}; chat eligibility required')
        _, body = api.call(f'/api/v1/chats/{room_id}', token=token)
        room = body['data']
        group = room.get('group') or {}
        if room['roomType'] != 'GROUP' or room['status'] != 'ACTIVE' or group.get('memberCount') != 100:
            raise ValueError('A dedicated ACTIVE group with exactly 100 members is required')
        if host_id is not None and host_id != group['hostUserId']:
            raise ValueError('Host changed during preflight')
        host_id = group['hostUserId']
        _, body = api.call(f'/api/v1/chats/{room_id}/messages?size=1', token=token)
        history = body['data']
        latest.add(max((m['roomSequence'] for m in history['messages']), default=0))
        users.append({'id': user['id'], 'token': token,
                      'participationVersion': history['participationVersion']})
        # Check replicated chat eligibility as well as the JWT role. Do not reuse this ticket.
        api.call('/api/v1/chats/ws-tickets', {}, token)
        if index % 25 == 0:
            print(f'Preflight {index}/100', flush=True)
        time.sleep(.1)
    if host_id not in {u['id'] for u in users} or len(latest) != 1:
        raise ValueError('Host must be in the manifest; keep the dedicated room idle during preflight')
    users.sort(key=lambda user: user['id'] != host_id)
    # Same count alone is insufficient: verify the entire active member set, including pagination.
    members, cursor = set(), None
    seen_cursors = set()
    while True:
        endpoint = f'/api/v1/chats/{room_id}/members?size=50'
        if cursor is not None:
            endpoint += f'&cursor={cursor}'
        _, body = api.call(endpoint, token=users[0]['token'])
        members.update(m['userId'] for m in body['data']['members'] if m['active'])
        cursor = body['data']['nextCursor']
        if cursor is None:
            break
        if cursor in seen_cursors:
            raise ValueError('Repeated member cursor')
        seen_cursors.add(cursor)
    if members != {u['id'] for u in users}:
        raise ValueError('Room members differ from the test accounts')
    return {'accounts': users, 'initialSequence': latest.pop()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accounts', required=True)
    parser.add_argument('--room', required=True, type=int)
    parser.add_argument('--output', required=True, help='New private directory outside the repository')
    parser.add_argument('--run', action='store_true', help='Send load after preflight; otherwise preflight only')
    args = parser.parse_args()
    os.umask(0o077)
    output = private_path(args.output)
    manifest = json.loads(private_path(args.accounts).read_text())
    if args.room < 1:
        raise ValueError('Positive room ID required')
    if args.run and not shutil.which('k6'):
        raise ValueError('k6 is not on PATH')
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    results = []
    runtime = output / 'runtime.private.json'
    try:
        for rate in ((30, 50, 100) if args.run else (30,)):
            prepared = prepare(manifest, args.room)
            if not args.run:
                print('Preflight passed; no chat messages sent.')
                return
            save_private(runtime, prepared)
            name = f'100-{rate}-30'
            env = dict(os.environ, CHAT_K6_PEOPLE='100', CHAT_K6_RATE=str(rate), CHAT_K6_SECONDS='30',
                       CHAT_K6_URL=manifest['baseUrl'].rstrip('/'), CHAT_K6_ROOM=str(args.room),
                       CHAT_K6_START=str(int(time.time() * 1000) + 15000),
                       CHAT_K6_SEQUENCE=str(prepared['initialSequence']),
                       CHAT_K6_PREFIX='k6-' + secrets.token_hex(8) + ':',
                       CHAT_K6_AUTH_FILE=str(runtime), CHAT_K6_ORIGIN=manifest['baseUrl'].rstrip('/'),
                       CHAT_K6_SUMMARY=str(output / (name + '.json')))
            print(f'START {name}', flush=True)
            with (output / (name + '.private.log')).open('w') as log:
                result = subprocess.run(['k6', 'run', '--quiet', '--no-color', str(
                    Path(__file__).with_name('group-chat.js'))], env=env,
                    stdout=log, stderr=subprocess.STDOUT, timeout=120)
            results.append({'condition': name, 'exitCode': result.returncode})
            save_private(output / 'matrix.json', results)
            runtime.unlink(missing_ok=True)
            if result.returncode:
                raise RuntimeError(f'{name} failed; remaining conditions were not run. See private output.')
            print(f'PASS {name}', flush=True)
            time.sleep(10)
    finally:
        runtime.unlink(missing_ok=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError) as error:
        print(f'Stopped: {error}')
        raise SystemExit(1)
    except (OSError, KeyError, subprocess.TimeoutExpired):
        print('Stopped: file/API schema/process error; inspect configuration without printing credentials.')
        raise SystemExit(1)
