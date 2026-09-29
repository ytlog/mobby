"""Exercise an installed non-debuggable app through its real Shell diagnostics UI.

Requires an idle, unlocked phone. Never uninstalls the app or changes gateways.
"""
import argparse
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = 'com.github.ytlog.mobby.android'


class Phone:
    def __init__(self, serial):
        self.adb = ['adb', '-s', serial]
        self.xml = '/data/local/tmp/mobby-release-smoke-' + uuid.uuid4().hex + '.xml'

    def shell(self, *args):
        return subprocess.check_output(self.adb + ['shell', *args], text=True)

    def state(self):
        subprocess.run(self.adb + ['shell', 'uiautomator', 'dump', self.xml],
                       capture_output=True, check=True)
        return ET.fromstring(self.shell('cat', self.xml))

    @staticmethod
    def texts(root):
        return [n.get(key, '') for n in root.iter('node') for key in ('text', 'content-desc')]

    def tap(self, node):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds', '')))
        if x2 <= x1 or y2 <= y1:
            raise AssertionError('Target control is not visible')
        self.shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))

    def click(self, *labels):
        root = self.state()
        if '打开会话抽屉' in labels and any(n.get('text') in ('稍后', 'Later') for n in root.iter('node')):
            self.click('稍后', 'Later')
            self.wait(lambda t: any(label in t for label in labels), 'Dismissed gateway introduction')
            root = self.state()
        nodes = [n for n in root.iter('node')
                 if n.get('text') in labels or n.get('content-desc') in labels]
        if len(nodes) != 1:
            raise AssertionError('Expected one control: ' + '/'.join(labels))
        self.tap(nodes[0])

    def command(self, command):
        nodes = [n for n in self.state().iter('node') if n.get('class') == 'android.widget.EditText']
        if len(nodes) != 1:
            raise AssertionError('Expected the Shell command field')
        self.tap(nodes[0])
        self.shell('input', 'keycombination', '113', '29')
        self.shell('input', 'text', shlex.quote(command.replace(' ', '%s')))
        self.shell('input', 'keyevent', '4')
        self.click('执行', 'Run')

    def wait(self, predicate, description, timeout=90):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            texts = self.texts(self.state())
            if predicate(texts):
                return texts
            time.sleep(0.25)
        raise AssertionError('Timed out: ' + description)

    def terminal(self, expected, marker):
        all_terminal = {'状态：完成', '状态：失败', '状态：已停止',
                        'Status: Done', 'Status: Failed', 'Status: Stopped'}
        texts = self.wait(lambda t: any(x in all_terminal for x in t), 'Shell terminal state')
        phase = next(x for x in texts if x in all_terminal)
        if phase not in expected or marker not in texts:
            raise AssertionError(f'Shell result was {phase}; expected marker present: {marker in texts}')

    def run(self, cli, repetitions):
        package = self.shell('dumpsys', 'package', PACKAGE)
        flags = re.search(r'^\s*flags=\[(.*?)\]', package, re.MULTILINE)
        if not flags or 'DEBUGGABLE' in flags.group(1):
            raise AssertionError('This check requires the installed release app')
        self.shell('am', 'force-stop', PACKAGE)
        self.shell('am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
        texts = self.wait(lambda t: any(x in t for x in (
            '打开会话抽屉', 'Open conversation drawer', '稍后', 'Later')), 'Home screen')
        if '稍后' in texts or 'Later' in texts:
            self.click('稍后', 'Later')
        # Cold startup may replace the home layout while the first tap is sent.
        # Retry navigation only; execution and terminal assertions never retry.
        for attempt in range(3):
            self.click('打开会话抽屉', 'Open conversation drawer')
            texts = self.texts(self.state())
            if '设置' in texts or 'Settings' in texts:
                break
        self.click('设置', 'Settings')
        self.wait(lambda t: '运行环境已就绪' in t or 'Runtime ready' in t, 'Runtime readiness', 120)
        self.click('Shell 诊断', 'Shell diagnostics')
        for iteration in range(repetitions):
            marker = f'MOBBY_RELEASE_OK_{iteration}'
            self.command('echo ' + marker)
            self.terminal({'状态：完成', 'Status: Done'}, marker)
        print('PASS release Shell execution and output', flush=True)
        self.command('echo MOBBY_RELEASE_EXPECTED_FAILURE; exit 7')
        self.terminal({'状态：失败', 'Status: Failed'}, 'MOBBY_RELEASE_EXPECTED_FAILURE')
        print('PASS nonzero exit remains a failure', flush=True)
        self.command('echo MOBBY_RELEASE_CANCEL_READY; sleep 60; echo MOBBY_RELEASE_UNEXPECTED')
        self.wait(lambda t: 'MOBBY_RELEASE_CANCEL_READY' in t, 'Running cancellable command')
        self.click('停止', 'Stop')
        self.terminal({'状态：已停止', 'Status: Stopped'}, 'MOBBY_RELEASE_CANCEL_READY')
        if 'MOBBY_RELEASE_UNEXPECTED' in self.texts(self.state()):
            raise AssertionError('Cancelled command continued')
        print('PASS cancellation remains cancelled and stops execution', flush=True)
        if cli:
            programs = ['node', 'pi', 'codex', 'claude', 'opencode']
            self.command(' && '.join(p + ' --version' for p in programs) + ' && echo MOBBY_RELEASE_CLI_OK')
            self.terminal({'状态：完成', 'Status: Done'}, 'MOBBY_RELEASE_CLI_OK')
            print('PASS Node, Pi, Codex, Claude Code and OpenCode launch', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--check-cli', action='store_true')
    parser.add_argument('--repeat', type=int, default=1)
    args = parser.parse_args()
    if args.repeat < 1:
        parser.error("--repeat must be positive")
    phone = Phone(args.serial)
    try:
        phone.run(args.check_cli, args.repeat)
    finally:
        phone.shell('rm', '-f', phone.xml)


if __name__ == '__main__':
    main()
