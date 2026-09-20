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
#include <unistd.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <signal.h>
#include <cstring>
#include <vector>
#include <string>

#define TAG  "LibTermux-Process"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

extern "C" {

/**
 * Fork a process with the given executable, arguments, and environment,
 * connecting stdin/stdout/stderr to provided file descriptors.
 *
 * Returns the PID of the child, or -1 on error.
 */
JNIEXPORT jint JNICALL
Java_com_libtermux_utils_NativeUtils_forkProcess(
    JNIEnv*      env,
    jclass       /* clazz */,
    jstring      exec_str,
    jobjectArray args_arr,
    jobjectArray env_arr,
    jstring      cwd_str,
    jint         stdinFd,
    jint         stdoutFd,
    jint         stderrFd
) {
    const char* exec = env->GetStringUTFChars(exec_str, nullptr);
    const char* cwd  = cwd_str ? env->GetStringUTFChars(cwd_str, nullptr) : nullptr;

    // Build argv
    jsize argc = env->GetArrayLength(args_arr);
    std::vector<std::string> argStrings;
    argStrings.emplace_back(exec);
    for (jsize i = 0; i < argc; i++) {
        auto js = (jstring)env->GetObjectArrayElement(args_arr, i);
        const char* s = env->GetStringUTFChars(js, nullptr);
        argStrings.emplace_back(s);
        env->ReleaseStringUTFChars(js, s);
    }
    std::vector<const char*> argv;
    for (auto& s : argStrings) argv.push_back(s.c_str());
    argv.push_back(nullptr);

    // Build envp
    jsize envc = env->GetArrayLength(env_arr);
    std::vector<std::string> envStrings;
    for (jsize i = 0; i < envc; i++) {
        auto js = (jstring)env->GetObjectArrayElement(env_arr, i);
        const char* s = env->GetStringUTFChars(js, nullptr);
        envStrings.emplace_back(s);
        env->ReleaseStringUTFChars(js, s);
    }
    std::vector<const char*> envp;
    for (auto& s : envStrings) envp.push_back(s.c_str());
    envp.push_back(nullptr);

    pid_t pid = fork();

    if (pid == 0) {
        // Child process
        if (stdinFd  >= 0) { dup2(stdinFd,  STDIN_FILENO);  close(stdinFd);  }
        if (stdoutFd >= 0) { dup2(stdoutFd, STDOUT_FILENO); close(stdoutFd); }
        if (stderrFd >= 0) { dup2(stderrFd, STDERR_FILENO); close(stderrFd); }

        if (cwd) chdir(cwd);

        setsid();

        execve(exec, const_cast<char* const*>(argv.data()),
               const_cast<char* const*>(envp.data()));

        LOGE("execve failed: %s", strerror(errno));
        _exit(127);

    } else if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
    }

    env->ReleaseStringUTFChars(exec_str, exec);
    if (cwd_str && cwd) env->ReleaseStringUTFChars(cwd_str, cwd);

    return static_cast<jint>(pid);
}

/**
 * Send a signal to a process.
 */
JNIEXPORT jint JNICALL
Java_com_libtermux_utils_NativeUtils_sendSignal(
    JNIEnv* /* env */,
    jclass  /* clazz */,
    jint    pid,
    jint    signal
) {
    return kill(static_cast<pid_t>(pid), signal);
}

/**
 * Wait for a process to exit. Returns exit status.
 */
JNIEXPORT jint JNICALL
Java_com_libtermux_utils_NativeUtils_waitForProcess(
    JNIEnv* /* env */,
    jclass  /* clazz */,
    jint    pid
) {
    int status = 0;
    if (waitpid(static_cast<pid_t>(pid), &status, 0) < 0) {
        return -1;
    }
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

} // extern "C"
