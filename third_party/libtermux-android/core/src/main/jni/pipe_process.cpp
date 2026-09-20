// mobby process-group pipe adapter. Child after fork uses only async-signal-safe calls.
#include <jni.h>
#include <unistd.h>
#include <sys/wait.h>
#include <signal.h>
#include <fcntl.h>
#include <errno.h>
#include <string>
#include <vector>

static std::vector<std::string> strings(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    for (int i = 0; i < env->GetArrayLength(values); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        const char* text = env->GetStringUTFChars(value, nullptr);
        result.emplace_back(text);
        env->ReleaseStringUTFChars(value, text);
        env->DeleteLocalRef(value);
    }
    return result;
}
extern "C" JNIEXPORT jintArray JNICALL
Java_com_libtermux_executor_PipeProcess_spawn(JNIEnv* env, jobject, jobjectArray args, jobjectArray environment, jstring directory) {
    auto arguments = strings(env, args);
    auto variables = strings(env, environment);
    std::vector<char*> argv, envp;
    for (auto& s : arguments) argv.push_back(s.data());
    for (auto& s : variables) envp.push_back(s.data());
    argv.push_back(nullptr); envp.push_back(nullptr);
    const char* raw = env->GetStringUTFChars(directory, nullptr);
    std::string cwd(raw);
    env->ReleaseStringUTFChars(directory, raw);
    int out[2], err[2];
    if (pipe2(out, O_CLOEXEC) != 0) return nullptr;
    if (pipe2(err, O_CLOEXEC) != 0) { close(out[0]); close(out[1]); return nullptr; }
    pid_t pid = fork();
    if (pid == 0) {
        if (setsid() < 0) _exit(126);
        int input = open("/dev/null", O_RDONLY);
        dup2(input, STDIN_FILENO); close(input);
        dup2(out[1], STDOUT_FILENO); dup2(err[1], STDERR_FILENO);
        close(out[0]); close(out[1]); close(err[0]); close(err[1]);
        if (chdir(cwd.c_str()) == 0) execve(argv[0], argv.data(), envp.data());
        const char message[] = "Runtime exec failed: check executable path, ABI, interpreter and Android execution permissions.\n";
        write(STDERR_FILENO, message, sizeof(message) - 1);
        _exit(127);
    }
    close(out[1]); close(err[1]);
    if (pid < 0) { close(out[0]); close(err[0]); return nullptr; }
    jint values[] = {pid, out[0], err[0]};
    auto result = env->NewIntArray(3);
    env->SetIntArrayRegion(result, 0, 3, values);
    return result;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_libtermux_executor_PipeProcess_poll(JNIEnv*, jobject, jint pid) {
    siginfo_t info{};
    int result = waitid(P_PID, pid, &info, WEXITED | WNOHANG | WNOWAIT);
    if ((result == 0 && info.si_pid == 0) || (result < 0 && errno == EINTR)) return -1;
    // A wait error is not process-exit evidence. Keep it distinct from every valid exit code.
    if (result < 0) return -2;
    // Keep the leader unreaped until group cleanup, preventing PID reuse.
    return info.si_code == CLD_EXITED ? info.si_status : 128 + info.si_status;
}
extern "C" JNIEXPORT void JNICALL
Java_com_libtermux_executor_PipeProcess_reap(JNIEnv*, jobject, jint pid) {
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
}

extern "C" JNIEXPORT void JNICALL
Java_com_libtermux_executor_PipeProcess_signalGroup(JNIEnv*, jobject, jint pid, jint signal) {
    kill(-pid, signal);
    kill(pid, signal); // Also covers cancellation before the child has called setsid().
}
