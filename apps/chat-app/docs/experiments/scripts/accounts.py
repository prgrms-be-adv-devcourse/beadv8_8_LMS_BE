"""Create test users via signup; keep credentials outside the repository (0600)."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[5]


def private_path(value):
    path = Path(value).expanduser().resolve()
    if path == ROOT or ROOT in path.parents:
        raise ValueError('Credential/output files must be outside the repository')
    return path


def save_private(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    tmp = path.with_name(path.name + '.tmp')
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'w') as out:
        json.dump(value, out, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


class Api:
    def __init__(self, base):
        parsed = urllib.parse.urlsplit(base)
        if (parsed.scheme != 'https' or not parsed.hostname or parsed.username
                or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/')):
            raise ValueError('An HTTPS origin is required')
        self.base = base.rstrip('/')
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, path, data=None, token=None):
        headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(self.base + path,
            data=None if data is None else json.dumps(data).encode(), headers=headers)
        try:
            with self.opener.open(request, timeout=15) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            try:
                code = json.load(error).get('code', 'UNKNOWN')
            except (ValueError, AttributeError):
                code = 'UNPARSEABLE'
            raise RuntimeError(f'{path}: HTTP {error.code}, code={code}') from None
        except (OSError, ValueError):
            raise RuntimeError(f'{path}: network/response error; result may be unknown') from None

    def login(self, account):
        _, result = self.call('/api/v1/members/login',
                              {k: account[k] for k in ('email', 'password')})
        user = result['data']
        _, result = self.call('/api/v1/members/auth/refresh', {})
        return user, result['data']['accessToken']


def create(args):
    path = private_path(args.file)
    if path.exists():
        manifest = json.loads(path.read_text())
        if manifest['baseUrl'].rstrip('/') != args.base_url.rstrip('/'):
            raise ValueError('Existing manifest belongs to a different origin')
        if len(manifest['accounts']) != args.count:
            raise ValueError('Existing account count differs; reuse the original count')
    else:
        batch = 'k6-' + time.strftime('%Y%m%d-%H%M%S', time.gmtime()) + '-' + secrets.token_hex(3)
        manifest = {'baseUrl': args.base_url.rstrip('/'), 'batch': batch, 'accounts': [
            {'email': f'{batch}-{i:03d}@example.invalid',
             'password': secrets.token_urlsafe(24) + 'A7!', 'nickname': f'부하{batch[-6:]}{i:03d}',
             'creationStatus': 'PLANNED'} for i in range(1, args.count + 1)]}
        save_private(path, manifest)
    api = Api(manifest['baseUrl'])
    for index, account in enumerate(manifest['accounts'], 1):
        if account['creationStatus'] == 'CREATED':
            continue
        # A timeout/interruption may occur AFTER commit. Never blindly retry signup.
        if account['creationStatus'] != 'PLANNED':
            raise ValueError(f'Account {index}: reconcile the previous signup before retrying')
        account['creationStatus'] = 'REQUESTED'
        save_private(path, manifest)
        status, response = api.call('/api/v1/members/signup',
                                    {k: account[k] for k in ('email', 'password', 'nickname')})
        user = response['data']
        if status != 201 or user['email'] != account['email']:
            raise ValueError(f'Account {index}: unexpected signup response')
        account.update(creationStatus='CREATED', id=user['id'], role=user['role'],
                       status=user['status'], httpStatus=status)
        save_private(path, manifest)
        print(f'Created {index}/{len(manifest["accounts"])}', flush=True)
        time.sleep(.1)
    print(f'Complete: {len(manifest["accounts"])} accounts. Credentials: {path}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True, help='HTTPS origin')
    parser.add_argument('--file', required=True, help='Private manifest outside the repository; reused on retry')
    parser.add_argument('--count', type=int, choices=range(1, 101), default=100, metavar='1..100')
    args = parser.parse_args()
    os.umask(0o077)
    try:
        create(args)
    except (RuntimeError, ValueError, KeyError, OSError) as error:
        # No response bodies, passwords or tokens in output.
        print(f'Stopped: {error}' if isinstance(error, (RuntimeError, ValueError)) else
              f'Stopped: {type(error).__name__}')
        raise SystemExit(1)


if __name__ == '__main__':
    main()
