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
#include <string>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <cstring>
#include <cerrno>

#define TAG "LibTermux-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

extern "C" {

/**
 * Set file permissions recursively.
 * Returns 0 on success, -1 on error.
 */
JNIEXPORT jint JNICALL
Java_com_libtermux_utils_NativeUtils_setExecutableRecursive(
    JNIEnv* env,
    jclass  /* clazz */,
    jstring path_str
) {
    const char* path = env->GetStringUTFChars(path_str, nullptr);
    if (!path) return -1;

    int result = chmod(path, S_IRWXU | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH);
    env->ReleaseStringUTFChars(path_str, path);

    if (result != 0) {
        LOGE("chmod failed for path: %s, error: %s", path, strerror(errno));
        return -1;
    }
    return 0;
}

/**
 * Create a symlink.
 * Returns 0 on success, -1 on error.
 */
JNIEXPORT jint JNICALL
Java_com_libtermux_utils_NativeUtils_createSymlink(
    JNIEnv* env,
    jclass  /* clazz */,
    jstring target_str,
    jstring link_str
) {
    const char* target   = env->GetStringUTFChars(target_str, nullptr);
    const char* linkPath = env->GetStringUTFChars(link_str,   nullptr);

    if (!target || !linkPath) {
        if (target)   env->ReleaseStringUTFChars(target_str, target);
        if (linkPath) env->ReleaseStringUTFChars(link_str,   linkPath);
        return -1;
    }

    unlink(linkPath);
    int result = symlink(target, linkPath);

    if (result != 0) {
        LOGE("symlink(%s -> %s) failed: %s", target, linkPath, strerror(errno));
    }

    env->ReleaseStringUTFChars(target_str, target);
    env->ReleaseStringUTFChars(link_str,   linkPath);
    return result;
}

/**
 * Get the real path of a symlink (readlink).
 */
JNIEXPORT jstring JNICALL
Java_com_libtermux_utils_NativeUtils_readSymlink(
    JNIEnv* env,
    jclass  /* clazz */,
    jstring path_str
) {
    const char* path = env->GetStringUTFChars(path_str, nullptr);
    if (!path) return nullptr;

    char buf[4096];
    ssize_t len = readlink(path, buf, sizeof(buf) - 1);

    env->ReleaseStringUTFChars(path_str, path);

    if (len < 0) return nullptr;
    buf[len] = '\0';
    return env->NewStringUTF(buf);
}

/**
 * Get Android architecture as string.
 */
JNIEXPORT jstring JNICALL
Java_com_libtermux_utils_NativeUtils_getNativeArch(
    JNIEnv* env,
    jclass  /* clazz */
) {
#if defined(__aarch64__)
    return env->NewStringUTF("arm64-v8a");
#elif defined(__x86_64__)
    return env->NewStringUTF("x86_64");
#elif defined(__arm__)
    return env->NewStringUTF("armeabi-v7a");
#elif defined(__i386__)
    return env->NewStringUTF("x86");
#else
    return env->NewStringUTF("unknown");
#endif
}

/**
 * Check if a file exists.
 */
JNIEXPORT jboolean JNICALL
Java_com_libtermux_utils_NativeUtils_fileExists(
    JNIEnv* env,
    jclass  /* clazz */,
    jstring path_str
) {
    const char* path = env->GetStringUTFChars(path_str, nullptr);
    if (!path) return JNI_FALSE;
    struct stat st{};
    int result = stat(path, &st);
    env->ReleaseStringUTFChars(path_str, path);
    return (result == 0) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
