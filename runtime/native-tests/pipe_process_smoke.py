"""Validate installed native JNI pipes under the app UID, without installing a test APK."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import uuid

sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
adb = os.environ.get('MOBBY_TEST_ADB', str(sdk / 'platform-tools/adb'))
jdk = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))

def remote(args, **kwargs):
    return subprocess.check_output([adb, 'shell', shlex.join(['run-as', 'com.mdoer.app', *args])], **kwargs)

home = remote(['pwd'], text=True).strip()
# Overlay installation changes the APK directory before the app refreshes HOME symlinks.
apk = subprocess.check_output([adb, 'shell', 'pm', 'path', 'com.mdoer.app'], text=True).strip()
assert apk.startswith('package:') and '\n' not in apk, 'Expected a single installed APK'
native = str(Path(apk.removeprefix('package:')).parent / 'lib/arm64')
root = home + '/files/pipe-smoke-' + uuid.uuid4().hex
with tempfile.TemporaryDirectory(prefix='mobby-jni-probe-') as temporary:
    temp = Path(temporary)
    classes = temp / 'classes'; classes.mkdir()
    dex = temp / 'dex'; dex.mkdir()
    android = sdk / 'platforms/android-35/android.jar'
    source = Path(__file__).parent / 'com/libtermux/executor/PipeProcess.java'
    subprocess.run([str(jdk / 'bin/javac'), '-Xlint:-options', '-source', '8', '-target', '8', '-cp', str(android), '-d', str(classes), str(source)], check=True)
    subprocess.run([str(sdk / 'build-tools/35.0.0/d8'), '--lib', str(android), '--output', str(dex), *map(str, classes.rglob('*.class'))], check=True, env={**os.environ, 'JAVA_HOME': str(jdk)})
    try:
        remote(['mkdir', '-p', root])
        remote(['/system/bin/sh', '-c', 'cat > ' + shlex.quote(root + '/classes.dex') + ' && chmod 444 ' + shlex.quote(root + '/classes.dex')], input=(dex / 'classes.dex').read_bytes())
        result = remote(['env', 'CLASSPATH=' + root + '/classes.dex', 'LD_LIBRARY_PATH=' + native, 'timeout', '-s', 'KILL', '20', '/system/bin/app_process', '/system/bin', 'com.libtermux.executor.PipeProcess', native + '/liblibtermux_jni.so', root], text=True)
        print(result.strip())
    except subprocess.CalledProcessError as error:
        print((error.output or '')[-4000:])
        raise
    finally:
        remote(['rm', '-rf', root])
