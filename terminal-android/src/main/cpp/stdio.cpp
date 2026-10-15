#include <jni.h>
#include <algorithm>
#include <cerrno>
#include <climits>
#include <csignal>
#include <fcntl.h>
#include <iterator>
#include <memory>
#include <mutex>
#include <poll.h>
#include <string>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#include <unordered_map>
#include <vector>

namespace {
struct Descriptor {
    int value;
    explicit Descriptor(int fd = -1) : value(fd) {}
    Descriptor(const Descriptor &) = delete;
    Descriptor &operator=(const Descriptor &) = delete;
    ~Descriptor() { reset(); }
    void reset() { if (value >= 0) { ::close(value); value = -1; } }
    int release() { int old = value; value = -1; return old; }
};
void fail(JNIEnv *env, const char *message) { env->ThrowNew(env->FindClass("java/io/IOException"), message); }
std::string text(JNIEnv *env, jbyteArray input) {
    // JNI's GetStringUTFChars uses modified UTF-8; that corrupts supplementary characters.
    std::string result(static_cast<size_t>(env->GetArrayLength(input)), '\0');
    env->GetByteArrayRegion(input, 0, static_cast<jsize>(result.size()), reinterpret_cast<jbyte *>(result.data()));
    return result;
}
std::vector<std::string> strings(JNIEnv *env, jobjectArray array) {
    std::vector<std::string> result;
    for (jsize i = 0; i < env->GetArrayLength(array); ++i) {
        auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(array, i));
        result.push_back(text(env, item)); env->DeleteLocalRef(item);
        if (env->ExceptionCheck()) break;
    }
    return result;
}
// Only async-signal-safe syscalls in either child after fork. No JVM, allocations or locks.
void closeInterval(unsigned first, unsigned last, unsigned ceiling) {
    if (first > last) return;
#ifdef __NR_close_range
    if (syscall(__NR_close_range, first, last, 0) == 0) return;
#endif
    for (unsigned fd = first; fd <= std::min(last, ceiling); ++fd) syscall(__NR_close, fd);
}
void closeExcept(const int *sorted, size_t count, unsigned ceiling) {
    unsigned next = 3;
    for (size_t i = 0; i < count; ++i) {
        const unsigned keep = static_cast<unsigned>(sorted[i]);
        if (keep > next) closeInterval(next, keep - 1, ceiling);
        next = keep + 1;
    }
    closeInterval(next, UINT_MAX, ceiling);
}
[[noreturn]] void startupFailure(int fd, int error) {
    ssize_t sent;
    do { sent = ::write(fd, &error, sizeof(error)); } while (sent < 0 && errno == EINTR);
    _exit(127);
}
int decoded(int status) { return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status); }
pid_t wait(pid_t pid, int *status, int flags) {
    pid_t result;
    do { result = waitpid(pid, status, flags); } while (result < 0 && errno == EINTR);
    return result;
}
void pauseBriefly() { timespec duration{0, 10000000}; while (nanosleep(&duration, &duration) && errno == EINTR) {} }

struct Channel {
    std::mutex mutex;
    Descriptor input, output, errors, control, outcome;
    pid_t pid;
    int result = INT_MIN;
    int workerResult = INT_MIN;
    Channel(int in, int out, int err, int lifetime, int status, pid_t child)
        : input(in), output(out), errors(err), control(lifetime), outcome(status), pid(child) {}
    void collect() {
        if (workerResult == INT_MIN && outcome.value >= 0) {
            int value = INT_MIN;
            if (::read(outcome.value, &value, sizeof(value)) == sizeof(value)) workerResult = value;
        }
        if (pid > 0) {
            int status = 0;
            const pid_t reaped = wait(pid, &status, WNOHANG);
            if (reaped == pid || (reaped < 0 && errno == ECHILD)) {
                // The supervisor kills its group before exiting. Never signal a reaped PID.
                pid = -1;
                // It may have written the status between our first read and waitpid.
                if (workerResult == INT_MIN && outcome.value >= 0) {
                    int value = INT_MIN;
                    if (::read(outcome.value, &value, sizeof(value)) == sizeof(value)) workerResult = value;
                }
                result = workerResult == INT_MIN ? -1 : workerResult;
            }
        }
    }
    void stop() {
        std::lock_guard<std::mutex> guard(mutex);
        input.reset(); control.reset();
        for (int attempt = 0; pid > 0 && attempt < 100; ++attempt) { collect(); if (pid > 0) pauseBriefly(); }
        if (pid > 0) {
            const pid_t child = pid;
            kill(-child, SIGKILL); kill(child, SIGKILL);
            int status = 0; wait(child, &status, 0); pid = -1;
            if (result == INT_MIN) result = -1;
        }
        output.reset(); errors.reset(); outcome.reset();
    }
    ~Channel() { stop(); }
};
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Channel>> registry;
jlong nextId = 1;
std::shared_ptr<Channel> lookup(JNIEnv *env, jlong id) {
    std::lock_guard<std::mutex> guard(registryMutex);
    auto found = registry.find(id);
    if (found == registry.end()) { fail(env, "stdio 进程已关闭"); return nullptr; }
    return found->second;
}
bool pair(JNIEnv *env, int (&values)[2], bool socket = false) {
    if ((socket ? socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, values) : pipe2(values, O_CLOEXEC)) == 0) return true;
    fail(env, "stdio 管道创建失败"); return false;
}
void nonblocking(int fd) { fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_solvit_terminal_NativeStdio_open(JNIEnv *env, jobject, jbyteArray directory,
    jobjectArray command, jobjectArray variables) {
    auto cwd = text(env, directory);
    auto args = strings(env, command), values = strings(env, variables);
    if (env->ExceptionCheck()) return 0;
    if (args.empty()) { fail(env, "stdio 缺少命令"); return 0; }
    std::vector<char *> argv, environment;
    for (auto &arg : args) argv.push_back(arg.data());
    argv.push_back(nullptr);
    for (auto &value : values) environment.push_back(value.data());
    environment.push_back(nullptr);
    int ends[2];
    if (!pair(env, ends, true)) return 0;
    Descriptor inParent(ends[0]), inChild(ends[1]);
    if (!pair(env, ends)) return 0;
    Descriptor outParent(ends[0]), outChild(ends[1]);
    if (!pair(env, ends)) return 0;
    Descriptor errParent(ends[0]), errChild(ends[1]);
    if (!pair(env, ends)) return 0;
    Descriptor controlChild(ends[0]), controlParent(ends[1]);
    if (!pair(env, ends)) return 0;
    Descriptor statusParent(ends[0]), statusChild(ends[1]);
    if (!pair(env, ends)) return 0;
    Descriptor startupParent(ends[0]), startupChild(ends[1]);
    int keep[] = {inChild.value, outChild.value, errChild.value, controlChild.value, statusChild.value, startupChild.value};
    std::sort(std::begin(keep), std::end(keep));
    rlimit limit{};
    const unsigned ceiling = getrlimit(RLIMIT_NOFILE, &limit) == 0
        ? static_cast<unsigned>(std::min<rlim_t>(limit.rlim_cur, INT_MAX)) : 65536;
    struct sigaction reset{}; reset.sa_handler = SIG_DFL; sigemptyset(&reset.sa_mask);
    struct sigaction ignored = reset; ignored.sa_handler = SIG_IGN;
    sigset_t unblocked; sigemptyset(&unblocked);
    const pid_t pid = fork();
    if (pid == 0) {
        closeExcept(keep, sizeof(keep) / sizeof(keep[0]), ceiling);
        for (int signal : {SIGCHLD, SIGPIPE, SIGTERM, SIGHUP, SIGINT, SIGQUIT}) sigaction(signal, &reset, nullptr);
        sigprocmask(SIG_SETMASK, &unblocked, nullptr);
        if (setsid() < 0 || chdir(cwd.c_str()) < 0) startupFailure(startupChild.value, errno);
        // The supervisor survives TERM long enough to kill and reap its whole process group.
        for (int signal : {SIGTERM, SIGHUP, SIGINT, SIGPIPE}) sigaction(signal, &ignored, nullptr);
        const pid_t worker = fork();
        if (worker < 0) startupFailure(startupChild.value, errno);
        if (worker == 0) {
            if (dup2(inChild.value, STDIN_FILENO) < 0 || dup2(outChild.value, STDOUT_FILENO) < 0 ||
                dup2(errChild.value, STDERR_FILENO) < 0) startupFailure(startupChild.value, errno);
            const int preserved[] = {startupChild.value};
            closeExcept(preserved, 1, ceiling);
            for (int signal : {SIGTERM, SIGHUP, SIGINT, SIGPIPE}) sigaction(signal, &reset, nullptr);
            execve(argv[0], argv.data(), environment.data());
            startupFailure(startupChild.value, errno);
        }
        ::close(inChild.value); ::close(outChild.value); ::close(errChild.value); ::close(startupChild.value);
        int workerStatus = 0;
        bool reaped = false;
        while (true) {
            if (wait(worker, &workerStatus, WNOHANG) == worker) { reaped = true; break; }
            pollfd lifetime{controlChild.value, POLLIN | POLLHUP, 0};
            const int ready = poll(&lifetime, 1, 50);
            if (ready > 0 || (ready < 0 && errno != EINTR)) break;
        }
        if (reaped) {
            const int code = decoded(workerStatus);
            ssize_t sent;
            do { sent = ::write(statusChild.value, &code, sizeof(code)); } while (sent < 0 && errno == EINTR);
        }
        // Keep the supervisor PID alive until the group is gone: no PID-reuse signal race.
        kill(-getpid(), SIGTERM);
        for (int attempt = 0; attempt < 20; ++attempt) {
            if (!reaped && wait(worker, &workerStatus, WNOHANG) == worker) reaped = true;
            pauseBriefly();
        }
        kill(-getpid(), SIGKILL);
        _exit(127);
    }
    if (pid < 0) { fail(env, "stdio 进程无法启动"); return 0; }
    inChild.reset(); outChild.reset(); errChild.reset(); controlChild.reset(); statusChild.reset(); startupChild.reset();
    nonblocking(inParent.value); nonblocking(outParent.value); nonblocking(errParent.value); nonblocking(statusParent.value);
    auto channel = std::make_shared<Channel>(inParent.release(), outParent.release(), errParent.release(),
        controlParent.release(), statusParent.release(), pid);
    pollfd startup{startupParent.value, POLLIN | POLLHUP, 0};
    int ready;
    do { ready = poll(&startup, 1, 3000); } while (ready < 0 && errno == EINTR);
    int error = 0;
    if (ready <= 0 || ::read(startupParent.value, &error, sizeof(error)) != 0) {
        channel->stop(); fail(env, "stdio 命令启动失败或超时"); return 0;
    }
    std::lock_guard<std::mutex> guard(registryMutex);
    if (nextId == LLONG_MAX) { fail(env, "stdio 句柄已耗尽"); return 0; }
    const jlong id = nextId++; registry.emplace(id, channel); return id;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_solvit_terminal_NativeStdio_read(JNIEnv *env, jobject, jlong id, jboolean stderrStream) {
    auto channel = lookup(env, id); if (!channel) return nullptr;
    Descriptor duplicate;
    {
        std::lock_guard<std::mutex> guard(channel->mutex);
        const int fd = stderrStream ? channel->errors.value : channel->output.value;
        if (fd < 0) return nullptr;
        duplicate.value = fcntl(fd, F_DUPFD_CLOEXEC, 3);
    }
    if (duplicate.value < 0) { fail(env, "stdio 读取失败"); return nullptr; }
    pollfd input{duplicate.value, POLLIN | POLLHUP, 0};
    int ready;
    do { ready = poll(&input, 1, 100); } while (ready < 0 && errno == EINTR);
    if (ready == 0) return env->NewByteArray(0);
    if (ready < 0) { fail(env, "stdio 读取失败"); return nullptr; }
    char buffer[8192]; ssize_t count;
    do { count = ::read(duplicate.value, buffer, sizeof(buffer)); } while (count < 0 && errno == EINTR);
    if (count == 0) return nullptr;
    if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return env->NewByteArray(0);
    if (count < 0) { fail(env, "stdio 读取失败"); return nullptr; }
    auto output = env->NewByteArray(static_cast<jsize>(count));
    if (output) env->SetByteArrayRegion(output, 0, static_cast<jsize>(count), reinterpret_cast<jbyte *>(buffer));
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_solvit_terminal_NativeStdio_write(JNIEnv *env, jobject, jlong id, jbyteArray input) {
    auto channel = lookup(env, id); if (!channel) return;
    const jsize count = env->GetArrayLength(input);
    if (count < 1 || count > 262144) { fail(env, "stdio 输入超过限制"); return; }
    std::vector<jbyte> bytes(static_cast<size_t>(count));
    env->GetByteArrayRegion(input, 0, count, bytes.data()); if (env->ExceptionCheck()) return;
    std::lock_guard<std::mutex> guard(channel->mutex);
    if (channel->input.value < 0) { fail(env, "stdio 输入已关闭"); return; }
    size_t offset = 0;
    for (int attempt = 0; offset < bytes.size() && attempt < 100; ++attempt) {
        const ssize_t sent = send(channel->input.value, bytes.data() + offset, bytes.size() - offset, MSG_NOSIGNAL);
        if (sent > 0) { offset += static_cast<size_t>(sent); continue; }
        if (sent < 0 && errno == EINTR) continue;
        if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            pollfd output{channel->input.value, POLLOUT, 0}; poll(&output, 1, 10); continue;
        }
        fail(env, "stdio 写入失败"); return;
    }
    if (offset != bytes.size()) fail(env, "stdio 输入超时");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_solvit_terminal_NativeStdio_closeInput(JNIEnv *env, jobject, jlong id) {
    auto channel = lookup(env, id); if (!channel) return;
    std::lock_guard<std::mutex> guard(channel->mutex); channel->input.reset();
}
extern "C" JNIEXPORT jint JNICALL
Java_dev_solvit_terminal_NativeStdio_exitCode(JNIEnv *env, jobject, jlong id) {
    auto channel = lookup(env, id); if (!channel) return INT_MIN;
    std::lock_guard<std::mutex> guard(channel->mutex); channel->collect(); return channel->result;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_solvit_terminal_NativeStdio_close(JNIEnv *, jobject, jlong id) {
    std::shared_ptr<Channel> channel;
    {
        std::lock_guard<std::mutex> guard(registryMutex);
        auto found = registry.find(id); if (found == registry.end()) return;
        channel = found->second; registry.erase(found);
    }
    channel->stop();
}
