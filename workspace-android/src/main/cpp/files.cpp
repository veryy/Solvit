#include <jni.h>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <linux/fs.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

// No replacement and no fallback to ordinary rename: EEXIST must preserve the concurrent file.
extern "C" JNIEXPORT jint JNICALL
Java_dev_solvit_workspace_AndroidWorkspaceImportPublisher_publishNative(
        JNIEnv* env, jobject, jstring directory, jstring source, jstring destination) {
    const char* parent = env->GetStringUTFChars(directory, nullptr);
    if (parent == nullptr) return ENOMEM;
    const char* from = env->GetStringUTFChars(source, nullptr);
    if (from == nullptr) { env->ReleaseStringUTFChars(directory, parent); return ENOMEM; }
    const char* to = env->GetStringUTFChars(destination, nullptr);
    if (to == nullptr) {
        env->ReleaseStringUTFChars(source, from);
        env->ReleaseStringUTFChars(directory, parent);
        return ENOMEM;
    }
    int result = EINVAL;
    if (from[0] != '\0' && to[0] != '\0' && std::strchr(from, '/') == nullptr &&
            std::strchr(to, '/') == nullptr && std::strcmp(from, ".") != 0 &&
            std::strcmp(from, "..") != 0 && std::strcmp(to, ".") != 0 && std::strcmp(to, "..") != 0) {
        const int descriptor = open(parent, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (descriptor < 0) result = errno;
        else {
            struct stat attributes {};
            if (fstatat(descriptor, from, &attributes, AT_SYMLINK_NOFOLLOW) != 0) result = errno;
            else if (!S_ISREG(attributes.st_mode)) result = EINVAL;
            else result = syscall(__NR_renameat2, descriptor, from, descriptor, to, RENAME_NOREPLACE) == 0 ? 0 : errno;
            close(descriptor);
        }
    }
    env->ReleaseStringUTFChars(destination, to);
    env->ReleaseStringUTFChars(source, from);
    env->ReleaseStringUTFChars(directory, parent);
    return result;
}

namespace {
struct CleanupUtfChars {
    JNIEnv* env;
    jstring input;
    const char* value;
    CleanupUtfChars(JNIEnv* e, jstring s) : env(e), input(s), value(e->GetStringUTFChars(s, nullptr)) {}
    ~CleanupUtfChars() { if (value != nullptr) env->ReleaseStringUTFChars(input, value); }
};
bool cleanup_basename(const char* name) {
    return name[0] != '\0' && std::strchr(name, '/') == nullptr &&
        std::strcmp(name, ".") != 0 && std::strcmp(name, "..") != 0;
}
}

// Host-only cross-directory claim/restore. Both parent FDs are pinned for the move;
// EEXIST/EXDEV/unsupported renameat2 retain the source and never fall back to copy/unlink.
extern "C" JNIEXPORT jint JNICALL
Java_dev_solvit_workspace_AndroidWorkspaceBackupMover_transferNative(
        JNIEnv* env, jobject, jstring sourceDirectory, jstring destinationDirectory,
        jstring source, jstring destination) {
    CleanupUtfChars fromParent(env, sourceDirectory);
    if (fromParent.value == nullptr) return ENOMEM;
    CleanupUtfChars toParent(env, destinationDirectory);
    if (toParent.value == nullptr) return ENOMEM;
    CleanupUtfChars from(env, source);
    if (from.value == nullptr) return ENOMEM;
    CleanupUtfChars to(env, destination);
    if (to.value == nullptr) return ENOMEM;
    if (!cleanup_basename(from.value) || !cleanup_basename(to.value)) return EINVAL;
    const int sourceFd = open(fromParent.value, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (sourceFd < 0) return errno;
    const int destinationFd = open(toParent.value, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (destinationFd < 0) { const int error = errno; close(sourceFd); return error; }
    struct stat attributes {};
    int result;
    if (fstatat(sourceFd, from.value, &attributes, AT_SYMLINK_NOFOLLOW) != 0) result = errno;
    else if (!S_ISREG(attributes.st_mode)) result = EINVAL;
    else result = syscall(__NR_renameat2, sourceFd, from.value, destinationFd, to.value, RENAME_NOREPLACE) == 0 ? 0 : errno;
    close(destinationFd);
    close(sourceFd);
    return result;
}
