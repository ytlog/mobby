"""ADB-shell smoke probe for the production privileged worker; never grants root or installs another app.

Pass a D8-built jar containing the privileged Java sources and androidTest ScreenProbeMain.
Only a temporary jar in /data/local/tmp is written; the tested app opens on a new display.
"""
import argparse
import json
import select
import socket
import struct
import subprocess
import time
import uuid


def probe(adb, jar, package):
    remote = '/data/local/tmp/mobby-screen-probe-' + uuid.uuid4().hex + '.jar'
    process = None
    forwarded = None
    stream = None
    def command(*args):
        return subprocess.check_output([adb, *args], timeout=20, text=True).strip()
    try:
        command('push', jar, remote)
        process = subprocess.Popen([adb, 'shell', 'CLASSPATH=' + remote, '/system/bin/app_process', '/system/bin',
                                    'com.github.ytlog.mobby.android.device.privileged.ScreenProbeMain'],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        deadline = time.monotonic() + 15
        endpoint = None
        while time.monotonic() < deadline:
            if not select.select([process.stdout], [], [], 1)[0]:
                continue
            line = process.stdout.readline()
            if not line:
                break
            try:
                candidate = json.loads(line)
                if 'token' in candidate and 'port' in candidate:
                    endpoint = candidate
                    break
            except ValueError:
                pass
        if endpoint is None:
            raise RuntimeError('worker did not start')
        forwarded = command('forward', 'tcp:0', 'tcp:' + str(endpoint['port']))
        with socket.create_connection(('127.0.0.1', int(forwarded)), timeout=20) as connection:
            stream = connection.makefile('rwb', buffering=0)
            def exact(size):
                data = b''
                while len(data) < size:
                    chunk = stream.read(size - len(data))
                    if not chunk:
                        raise RuntimeError('worker disconnected')
                    data += chunk
                return data
            def request(body):
                data = json.dumps(body).encode()
                stream.write(struct.pack('>i', len(data)) + data)
                size = struct.unpack('>i', exact(4))[0]
                if not 0 < size <= 4 * 1024 * 1024:
                    raise RuntimeError('invalid response size')
                result = json.loads(exact(size))
                if not result.get('ok'):
                    raise RuntimeError(str({k: result.get(k) for k in ('error', 'cause', 'detail')}))
                return result
            request({'token': endpoint['token']})
            opened = request({'action': 'open'})
            display = opened['displayId']
            assert display > 0
            listed = request({'action': 'apps'})
            assert package in listed['text'].splitlines(), 'test app is not launchable'
            request({'action': 'launch', 'args': {'package': package}})
            time.sleep(1)
            observed = request({'action': 'snapshot'})
            assert observed['displayId'] == display
            assert observed['packageName'] == package, 'target app escaped the selected display'
            assert observed.get('jpeg'), 'no virtual display frame'
            if package == 'dev.mobby.screenprobe':
                request({'action': 'click', 'args': {'query': 'Increment'}})
                assert 'Count: 1' in request({'action': 'snapshot'})['text']
                # The fixture's button occupies x=24..696 and y about 50..122 on the 720x1280 display.
                request({'action': 'tap', 'args': {'x': '0.5', 'y': '0.07'}})
                assert 'Count: 2' in request({'action': 'snapshot'})['text']
                request({'action': 'click', 'args': {'query': 'Probe input'}})
                request({'action': 'type', 'args': {'text': '虚拟屏中文输入'}})
                assert '虚拟屏中文输入' in request({'action': 'snapshot'})['text']
                before_scroll = request({'action': 'snapshot'})['text']
                request({'action': 'swipe', 'args': {'x': '0.5', 'y': '0.85', 'toX': '0.5', 'toY': '0.4'}})
                after_scroll = request({'action': 'snapshot'})['text']
                assert before_scroll != after_scroll, 'swipe did not change the fixture list'
                request({'action': 'back'})
            inspected = request({'action': 'inspect'})
            assert inspected['packageName'] == package
            if package == 'com.android.settings':
                request({'action': 'tap', 'args': {'x': '0.01', 'y': '0.01'}})
                before_scroll = request({'action': 'snapshot'})['text']
                request({'action': 'swipe', 'args': {'x': '0.5', 'y': '0.85', 'toX': '0.5', 'toY': '0.4'}})
                assert before_scroll != request({'action': 'snapshot'})['text'], 'settings list did not scroll'
            # Invalid coordinates must be refused before an input is injected.
            try:
                request({'action': 'tap', 'args': {'x': '-1', 'y': '0.5'}})
            except RuntimeError:
                pass
            else:
                raise AssertionError('invalid coordinates were accepted')
            # These global keys must not escape to the main display.
            for action in ['home', 'recents']:
                try:
                    request({'action': action})
                except RuntimeError:
                    pass
                else:
                    raise AssertionError(action + ' should be refused in virtual mode')
            if package == 'com.android.settings':
                request({'action': 'back'})
            request({'action': 'close'})
            print(json.dumps({'workerUid': endpoint['uid'], 'displayId': display, 'package': package,
                              'screenshot': observed['screenshotStatus'], 'nodeTree': bool(observed['text']),
                              'invalidInputRefused': True, 'globalKeysRefused': True, 'tapAndSwipeDelivered': True, 'closed': True}))
    finally:
        if stream:
            stream.close()
        if forwarded:
            command('forward', '--remove', 'tcp:' + forwarded)
        if process:
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
        command('shell', 'rm', '-f', remote)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', required=True)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--package', default='com.android.settings')
    args = parser.parse_args()
    probe(args.adb, args.jar, args.package)
