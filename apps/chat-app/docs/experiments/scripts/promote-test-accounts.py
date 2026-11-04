"""Promote the test manifest's accounts through the public test-only member API."""
import argparse
import json
import os
import time

from accounts import Api, private_path, save_private


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accounts', required=True, help='Existing private signup manifest')
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
    for index, member in enumerate(members, 1):
        status, body = api.call(f'/api/v1/members/test/{member["id"]}/influencer', {})
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
