#include <jni.h>
#include <cerrno>
#include <climits>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <poll.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <unordered_map>
#include <vector>

extern char **environ;
namespace {
struct Fd {
    int value = -1;
    explicit Fd(int fd = -1) : value(fd) {}
    ~Fd() { if (value >= 0) ::close(value); }
    int release() { int fd = value; value = -1; return fd; }
};
struct Pty {
    std::mutex mutex;
    int fd;
    pid_t pid;
    int status = INT_MIN;
    Pty(int master, pid_t child) : fd(master), pid(child) {}
    void reap() {
        if (pid <= 0) return;
        int result = 0;
        pid_t waited;
        do { waited = waitpid(pid, &result, WNOHANG); } while (waited < 0 && errno == EINTR);
        if (waited == pid) {
            status = WIFEXITED(result) ? WEXITSTATUS(result) : 128 + WTERMSIG(result);
            pid = -1;
        } else if (waited < 0 && errno == ECHILD) { pid = -1; status = -1; }
    }
    void stop() {
        std::lock_guard<std::mutex> guard(mutex);
        reap();
        if (fd >= 0) {
            // Closing the controlling terminal also delivers a kernel hangup.
            pid_t foreground = tcgetpgrp(fd);
            if (pid > 0 && foreground > 0) kill(-foreground, SIGHUP);
            ::close(fd); fd = -1;
        }
        if (pid > 0) {
            kill(-pid, SIGHUP);
            for (int i = 0; i < 20 && pid > 0; ++i) {
                reap();
                if (pid > 0) { timespec delay{0, 10000000}; nanosleep(&delay, nullptr); }
            }
            if (pid > 0) {
                const pid_t child = pid;
                kill(-child, SIGKILL); kill(child, SIGKILL);
                int result = 0;
                pid_t waited;
                do { waited = waitpid(child, &result, 0); } while (waited < 0 && errno == EINTR);
                if (waited == child) status = WIFEXITED(result) ? WEXITSTATUS(result) : 128 + WTERMSIG(result);
                pid = -1;
            }
        }
    }
    ~Pty() { stop(); }
};
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Pty>> registry;
jlong nextHandle = 1;
void failure(JNIEnv *env, const std::string &message) {
    env->ThrowNew(env->FindClass("java/io/IOException"), message.c_str());
}
std::shared_ptr<Pty> lookup(JNIEnv *env, jlong id) {
    std::lock_guard<std::mutex> guard(registryMutex);
    auto found = registry.find(id);
    if (found == registry.end()) { failure(env, "终端已关闭"); return nullptr; }
    return found->second;
}
std::string string(JNIEnv *env, jstring value) {
    const char *text = env->GetStringUTFChars(value, nullptr);
    if (!text) return {};
    std::string result(text);
    env->ReleaseStringUTFChars(value, text);
    return result;
}
[[noreturn]] void childFailure(int pipe, int error) {
    // No allocations, locks or JVM calls after fork in the multithreaded app.
    ssize_t ignored = write(pipe, &error, sizeof(error));
    (void)ignored;
    _exit(127);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_solvit_terminal_NativePty_open(JNIEnv *env, jobject, jstring directory,
                                      jobjectArray command, jint rows, jint columns) {
    std::string cwd = string(env, directory);
    std::vector<std::string> args;
    const jsize count = env->GetArrayLength(command);
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(command, i));
        args.push_back(string(env, value)); env->DeleteLocalRef(value);
    }
    if (env->ExceptionCheck()) return 0;
    if (args.empty()) { failure(env, "缺少终端命令"); return 0; }
    std::vector<char *> argv;
    for (auto &arg : args) argv.push_back(arg.data());
    argv.push_back(nullptr);
    std::vector<std::string> variables;
    for (char **entry = environ; entry && *entry; ++entry) {
        if (strncmp(*entry, "TERM=", 5) && strncmp(*entry, "PS1=", 4) && strncmp(*entry, "PS2=", 4)) variables.emplace_back(*entry);
    }
    variables.emplace_back("TERM=xterm-256color");
    variables.emplace_back("PS1="); variables.emplace_back("PS2=");
    std::vector<char *> environment;
    for (auto &variable : variables) environment.push_back(variable.data());
    environment.push_back(nullptr);
    Fd master(::open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC | O_NONBLOCK));
    char name[128]{};
    if (master.value < 0 || grantpt(master.value) || unlockpt(master.value) || ptsname_r(master.value, name, sizeof(name))) {
        failure(env, std::string("PTY 创建失败: ") + strerror(errno)); return 0;
    }
    Fd slave(::open(name, O_RDWR | O_NOCTTY | O_CLOEXEC));
    winsize size{}; size.ws_row = static_cast<unsigned short>(rows); size.ws_col = static_cast<unsigned short>(columns);
    if (slave.value < 0 || ioctl(slave.value, TIOCSWINSZ, &size)) { failure(env, "PTY 从设备无法打开"); return 0; }
    int startup[2];
    if (pipe2(startup, O_CLOEXEC)) { failure(env, "终端启动管道创建失败"); return 0; }
    Fd startupRead(startup[0]), startupWrite(startup[1]);
    struct sigaction reset{}; reset.sa_handler = SIG_DFL; sigemptyset(&reset.sa_mask);
    sigset_t unblocked; sigemptyset(&unblocked);
    const pid_t pid = fork();
    if (pid == 0) {
        ::close(master.value); ::close(startupRead.value);
        if (setsid() < 0 || ioctl(slave.value, TIOCSCTTY, 0) < 0 || chdir(cwd.c_str()) < 0) childFailure(startupWrite.value, errno);
        for (int fd = 0; fd < 3; ++fd) if (dup2(slave.value, fd) < 0) childFailure(startupWrite.value, errno);
        if (slave.value > 2) ::close(slave.value);
        for (int signal : {SIGINT, SIGQUIT, SIGTERM, SIGHUP, SIGPIPE, SIGCHLD, SIGTSTP, SIGTTIN, SIGTTOU}) sigaction(signal, &reset, nullptr);
        sigprocmask(SIG_SETMASK, &unblocked, nullptr);
        execve(argv[0], argv.data(), environment.data());
        childFailure(startupWrite.value, errno);
    }
    if (pid < 0) { failure(env, "终端进程无法启动"); return 0; }
    ::close(startupWrite.release());
    auto session = std::make_shared<Pty>(master.release(), pid);
    pollfd startupPoll{startupRead.value, POLLIN | POLLHUP, 0};
    int ready;
    do { ready = poll(&startupPoll, 1, 2000); } while (ready < 0 && errno == EINTR);
    int error = 0;
    if (ready <= 0 || read(startupRead.value, &error, sizeof(error)) != 0) {
        session->stop(); failure(env, std::string("终端启动失败: ") + (error ? strerror(error) : "启动超时")); return 0;
    }
    std::lock_guard<std::mutex> guard(registryMutex);
    const jlong handle = nextHandle++;
    registry.emplace(handle, session);
    return handle;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_solvit_terminal_NativePty_read(JNIEnv *env, jobject, jlong id) {
    auto session = lookup(env, id); if (!session) return nullptr;
    std::lock_guard<std::mutex> guard(session->mutex);
    if (session->fd < 0) return nullptr;
    pollfd input{session->fd, POLLIN | POLLHUP, 0};
    int ready;
    do { ready = poll(&input, 1, 100); } while (ready < 0 && errno == EINTR);
    session->reap();
    if (ready == 0) return env->NewByteArray(0);
    if (ready < 0) { failure(env, "读取终端失败"); return nullptr; }
    char bytes[8192];
    ssize_t length;
    do { length = ::read(session->fd, bytes, sizeof(bytes)); } while (length < 0 && errno == EINTR);
    if (length == 0 || (length < 0 && errno == EIO)) return nullptr;
    if (length < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return env->NewByteArray(0);
    if (length < 0) { failure(env, "读取终端失败"); return nullptr; }
    auto result = env->NewByteArray(static_cast<jsize>(length));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(length), reinterpret_cast<jbyte *>(bytes));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_solvit_terminal_NativePty_write(JNIEnv *env, jobject, jlong id, jbyteArray input) {
    auto session = lookup(env, id); if (!session) return;
    const jsize length = env->GetArrayLength(input);
    if (length < 1 || length > 32768) { failure(env, "终端输入过长"); return; }
    std::vector<jbyte> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(input, 0, length, bytes.data());
    if (env->ExceptionCheck()) return;
    std::lock_guard<std::mutex> guard(session->mutex);
    if (session->fd < 0) { failure(env, "终端已关闭"); return; }
    size_t offset = 0;
    for (int attempt = 0; offset < bytes.size() && attempt < 20; ++attempt) {
        const ssize_t written = ::write(session->fd, bytes.data() + offset, bytes.size() - offset);
        if (written > 0) { offset += static_cast<size_t>(written); continue; }
        if (written < 0 && errno == EINTR) continue;
        if (written < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) { pollfd output{session->fd, POLLOUT, 0}; poll(&output, 1, 50); continue; }
        failure(env, "写入终端失败"); return;
    }
    if (offset != bytes.size()) failure(env, "终端输入超时");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_solvit_terminal_NativePty_resize(JNIEnv *env, jobject, jlong id, jint rows, jint columns) {
    auto session = lookup(env, id); if (!session) return;
    std::lock_guard<std::mutex> guard(session->mutex);
    winsize size{}; size.ws_row = static_cast<unsigned short>(rows); size.ws_col = static_cast<unsigned short>(columns);
    if (session->fd < 0 || ioctl(session->fd, TIOCSWINSZ, &size)) failure(env, "终端尺寸更新失败");
}
extern "C" JNIEXPORT jint JNICALL
Java_dev_solvit_terminal_NativePty_exitCode(JNIEnv *env, jobject, jlong id) {
    auto session = lookup(env, id); if (!session) return -1;
    std::lock_guard<std::mutex> guard(session->mutex); session->reap(); return session->status;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_solvit_terminal_NativePty_close(JNIEnv *, jobject, jlong id) {
    std::shared_ptr<Pty> session;
    {
        std::lock_guard<std::mutex> guard(registryMutex);
        auto found = registry.find(id); if (found == registry.end()) return;
        session = found->second; registry.erase(found);
    }
    session->stop();
}
