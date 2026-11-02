"""Run the opt-in screen device regression with an explicit host foreground handoff."""
import argparse
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial')
    parser.add_argument('--mode', required=True, choices=('current', 'adb', 'shizuku'))
    parser.add_argument('--preview', type=Path)
    parser.add_argument('--port', type=int, help='Optional local wireless-debugging connection port')
    args = parser.parse_args()
    adb = [args.adb] + (['-s', args.serial] if args.serial else [])
    version = int(subprocess.check_output(adb + ['shell', 'getprop', 'ro.build.version.sdk'], text=True).strip())
    if version < 33:
        raise SystemExit('This acceptance suite requires an unlocked Android 13+ device.')
    methods = {'current': 'currentScreenRetainsClickCoordinatesTextAndSwipe',
               'adb': 'builtinAdbVirtualScreenKeepsMobbyVisibleAndReleasesDisplay',
               'shizuku': 'shizukuVirtualScreenKeepsMobbyVisibleAndReleasesDisplay'}
    command = adb + ['shell', 'am', 'instrument', '-w', '-e', 'hostForeground', 'true']
    if args.mode != 'current':
        command += ['-e', args.mode, 'true']
    if args.port is not None:
        if args.mode != 'adb' or not 1 <= args.port <= 65535:
            raise SystemExit('--port requires ADB mode and a port from 1 to 65535.')
        command += ['-e', 'adbPort', str(args.port)]
    command += ['-e', 'class', 'com.github.ytlog.mobby.android.ScreenControlDeviceTest#' + methods[args.mode],
                'com.github.ytlog.mobby.android.test/androidx.test.runner.AndroidJUnitRunner']
    passed = False
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as process:
        for line in process.stdout:
            print(line.rstrip(), flush=True)
            if 'screen_probe_phase=foreground_required' in line:
                subprocess.run(adb + ['shell', 'am', 'start', '-W', '-n',
                                     'com.github.ytlog.mobby.android/.MainActivity', '-f', '0x24000000'],
                               stdout=subprocess.DEVNULL, check=True)
            if 'screen_probe_phase=virtual_preview' in line and args.preview:
                args.preview.parent.mkdir(parents=True, exist_ok=True)
                with args.preview.open('wb') as output:
                    subprocess.run(adb + ['exec-out', 'screencap', '-p'], stdout=output, check=True)
            if re.fullmatch(r'OK \(1 test\)\s*', line):
                passed = True
        if process.wait() != 0 or not passed:
            raise SystemExit('Device acceptance did not pass.')


if __name__ == '__main__':
    main()
