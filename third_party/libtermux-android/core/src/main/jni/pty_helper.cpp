/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
#include <jni.h>
#include <android/log.h>
#include <pty.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <termios.h>
#include <string>
#include <cstring>

#define TAG  "LibTermux-PTY"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

/**
 * Open a pseudo-terminal (PTY) master/slave pair.
 * Returns int[2]: { masterFd, slaveFd }
 */
JNIEXPORT jintArray JNICALL
Java_com_libtermux_utils_NativeUtils_openPty(
    JNIEnv* env,
    jclass  /* clazz */
) {
    int masterFd = -1;
    int slaveFd  = -1;
    char slaveName[256];

    masterFd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (masterFd < 0) {
        LOGE("posix_openpt failed: %s", strerror(errno));
        return nullptr;
    }

    if (grantpt(masterFd) < 0 || unlockpt(masterFd) < 0) {
        LOGE("grantpt/unlockpt failed: %s", strerror(errno));
        close(masterFd);
        return nullptr;
    }

    if (ptsname_r(masterFd, slaveName, sizeof(slaveName)) != 0) {
        LOGE("ptsname_r failed: %s", strerror(errno));
        close(masterFd);
        return nullptr;
    }

    slaveFd = open(slaveName, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slaveFd < 0) {
        LOGE("open slave pty failed: %s", strerror(errno));
        close(masterFd);
        return nullptr;
    }

    // Set terminal size: 80x24
    struct winsize ws{};
    ws.ws_col = 80;
    ws.ws_row = 24;
    ioctl(masterFd, TIOCSWINSZ, &ws);

    jintArray result = env->NewIntArray(2);
    jint fds[2] = { masterFd, slaveFd };
    env->SetIntArrayRegion(result, 0, 2, fds);

    LOGI("PTY opened: master=%d slave=%d (%s)", masterFd, slaveFd, slaveName);
    return result;
}

/**
 * Resize PTY window.
 */
JNIEXPORT jint JNICALL
Java_com_libtermux_utils_NativeUtils_resizePty(
    JNIEnv* /* env */,
    jclass  /* clazz */,
    jint    masterFd,
    jint    cols,
    jint    rows
) {
    struct winsize ws{};
    ws.ws_col = static_cast<unsigned short>(cols);
    ws.ws_row = static_cast<unsigned short>(rows);
    int result = ioctl(masterFd, TIOCSWINSZ, &ws);
    if (result < 0) {
        LOGE("ioctl TIOCSWINSZ failed: %s", strerror(errno));
    }
    return result;
}

/**
 * Close a PTY file descriptor.
 */
JNIEXPORT void JNICALL
Java_com_libtermux_utils_NativeUtils_closePty(
    JNIEnv* /* env */,
    jclass  /* clazz */,
    jint    fd
) {
    if (fd >= 0) close(fd);
}

} // extern "C"
