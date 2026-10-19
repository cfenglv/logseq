#include <CoreFoundation/CoreFoundation.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/event.h>
#include <unistd.h>

static int marker(const char *name) {
    const char *root = getenv("LOGSEQ_BRIDGE_TEST_ROOT");
    if (root == NULL) return EPERM;
    char file[PATH_MAX];
    if (snprintf(file, sizeof(file), "%s/%s", root, name) >= (int)sizeof(file)) return ENAMETOOLONG;
    const int fd = open(file, O_WRONLY | O_CREAT | O_EXCL, 0600);
    if (fd < 0) return errno;
    close(fd);
    return 0;
}

__attribute__((constructor)) static void boundary_loaded(void) {
    /* Foundation children can inherit this test interposer; the first load owns the marker. */
    const int error = marker("boundary-loaded");
    if (error != 0 && error != EEXIST) _exit(71);
}

static int observe_kevent(int queue, const struct kevent *changes, int count,
                         struct kevent *events, int capacity, const struct timespec *timeout) {
    const int result = kevent(queue, changes, count, events, capacity, timeout);
    const char *source = getenv("LOGSEQ_BRIDGE_TEST_SOURCE_PID");
    if (result == 0 && count == 1 && source != NULL && (pid_t)changes[0].ident == (pid_t)strtol(source, NULL, 10)) {
        const int error = marker("source-subscribed");
        if (error) { errno = error; return -1; }
    }
    return result;
}

static int owned_open(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                      const posix_spawnattr_t *attributes, char *const arguments[], char *const environment[]) {
    if (strcmp(file, "/usr/bin/open") != 0)
        return posix_spawn(pid, file, actions, attributes, arguments, environment);
    const char *target = getenv("LOGSEQ_BRIDGE_TEST_APP"), *tool = getenv("LOGSEQ_BRIDGE_TEST_TOOL");
    if (target == NULL || tool == NULL || arguments[0] == NULL || arguments[1] == NULL ||
        arguments[2] == NULL || arguments[3] != NULL || strcmp(arguments[1], "-n") != 0 || strcmp(arguments[2], target) != 0)
        return EPERM;
    const int error = marker("launch-requested");
    if (error) return error;
    char *owned[] = {(char *)tool, "--launch", (char *)target, NULL};
    return posix_spawn(pid, tool, actions, attributes, owned, environment);
}

static SInt32 owned_alert(CFTimeInterval timeout, CFOptionFlags flags, CFURLRef icon, CFURLRef sound,
                         CFURLRef localization, CFStringRef title, CFStringRef description,
                         CFStringRef button, CFStringRef alternate, CFStringRef other, CFOptionFlags *response) {
    (void)timeout; (void)flags; (void)icon; (void)sound; (void)localization;
    (void)title; (void)description; (void)button; (void)alternate; (void)other;
    const int error = marker("native-alert");
    if (response != NULL) *response = kCFUserNotificationDefaultResponse;
    return error;
}

__attribute__((used)) static const struct { const void *replacement; const void *original; }
interpose[] __attribute__((section("__DATA,__interpose"))) = {
    {(const void *)observe_kevent, (const void *)kevent},
    {(const void *)owned_open, (const void *)posix_spawn},
    {(const void *)owned_alert, (const void *)CFUserNotificationDisplayAlert},
};
