"""Promote test accounts using an ADMIN login and Bearer JWT.

Usage (from repository root):
  python3 apps/chat-app/docs/experiments/scripts/promote-test-accounts.py \
    --accounts "$HOME/.hapbang/load-tests/accounts.private.json" \
    --admin-credentials "$HOME/.hapbang/load-tests/admin.private.json"

The admin file is a separate JSON object with email and password fields. Create it
outside the repository with mode 0600. It must contain an existing ACTIVE ADMIN
account on the manifest's baseUrl. No admin signup or promotion is performed.
The script logs in, refreshes the token, verifies /members/me, and sends the JWT
with each promotion request. Credentials and tokens are never printed or copied
into the test-account manifest. Reuse the same account manifest on retry.
Deploy the ADMIN-only member endpoint before using this script.
"""
import argparse
import json
import os
import time

from accounts import Api, private_path, save_private


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accounts', required=True, help='Existing private signup manifest')
    parser.add_argument('--admin-credentials', required=True,
                        help='Private JSON file containing an existing ADMIN email/password')
    args = parser.parse_args()
    os.umask(0o077)
    path = private_path(args.accounts)
    manifest = json.loads(path.read_text())
    members = manifest['accounts']
    if not 1 <= len(members) <= 100 or len({a['id'] for a in members}) != len(members):
        raise ValueError('Expected 1..100 distinct created test accounts')
    if any(a.get('creationStatus') != 'CREATED' for a in members):
        raise ValueError('Resolve incomplete signups first')
    api = Api(manifest['baseUrl'])
    admin_path = private_path(args.admin_credentials)
    if admin_path == path:
        raise ValueError('Admin credentials must be separate from the test-account manifest')
    if admin_path.stat().st_mode & 0o077:
        raise ValueError('Admin credential file must not be accessible by group/others; use chmod 600')
    admin_credentials = json.loads(admin_path.read_text())
    if not all(isinstance(admin_credentials.get(key), str) and admin_credentials[key]
               for key in ('email', 'password')):
        raise ValueError('Admin credential file requires email and password strings')
    _, token = api.login(admin_credentials)
    _, body = api.call('/api/v1/members/me', token=token)
    admin = body['data']
    if admin['role'] != 'ADMIN' or admin['status'] != 'ACTIVE':
        raise ValueError('An ACTIVE ADMIN account is required; no accounts were promoted')
    for index, member in enumerate(members, 1):
        status, body = api.call(f'/api/v1/members/admin/users/{member["id"]}/make-influencer', {}, token=token)
        result = body['data']
        if status != 200 or result['userId'] != member['id'] or result['role'] != 'INFLUENCER':
            raise ValueError(f'Account {index}: unexpected promotion response')
        member.update(role='INFLUENCER', promotionVerified=True)
        save_private(path, manifest)
        print(f'Promoted/confirmed {index}/{len(members)}, changed={result["changed"]}', flush=True)
        time.sleep(.1)
    print('Member roles updated. Run chat preflight after member events have propagated.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError) as error:
        print(f'Stopped: {error}; rerun the same manifest after resolving the error.')
        raise SystemExit(1)
    except (OSError, KeyError):
        print('Stopped: credential file/API schema error; credentials were not printed.')
        raise SystemExit(1)
