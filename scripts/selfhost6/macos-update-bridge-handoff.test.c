#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/event.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;
static pid_t expected_source;
static int release_source = -1;
static int registered_source, observed_exit;

static int observed_kevent(int queue, const struct kevent *changes, int changes_count,
                          struct kevent *events, int capacity, const struct timespec *timeout) {
    const int result = kevent(queue, changes, changes_count, events, capacity, timeout);
    if (result == 0 && changes_count == 1 && (pid_t)changes[0].ident == expected_source) {
        ++registered_source;
        assert(write(release_source, "X", 1) == 1);
        close(release_source); release_source = -1;
    }
    if (result == 1 && events[0].filter == EVFILT_PROC &&
        (pid_t)events[0].ident == expected_source && (events[0].fflags & NOTE_EXIT)) ++observed_exit;
    return result;
}

/* Test-only observation: the real exit happens only after the actual subscription succeeds. */
#define kevent(...) observed_kevent(__VA_ARGS__)
#include "../../resources/macos-project-updater/BridgeHandoff.c"
#undef kevent

static pid_t source(const char *fixture, const char *target, int *release) {
    int input[2], ready[2];
    assert(pipe(input) == 0 && pipe(ready) == 0);
    for (int index = 0; index < 2; ++index) {
        assert(fcntl(input[index], F_SETFD, FD_CLOEXEC) == 0);
        assert(fcntl(ready[index], F_SETFD, FD_CLOEXEC) == 0);
    }
    posix_spawn_file_actions_t actions;
    assert(posix_spawn_file_actions_init(&actions) == 0);
    assert(posix_spawn_file_actions_adddup2(&actions, input[0], 198) == 0);
    assert(posix_spawn_file_actions_adddup2(&actions, ready[1], 199) == 0);
    char *arguments[] = {(char *)fixture, "--target", (char *)target, "198", "199", NULL};
    pid_t pid;
    assert(posix_spawn(&pid, fixture, &actions, NULL, arguments, environ) == 0);
    posix_spawn_file_actions_destroy(&actions);
    close(input[0]); close(ready[1]);
    char byte;
    assert(read(ready[0], &byte, 1) == 1 && byte == 'R');
    close(ready[0]);
    *release = input[1];
    return pid;
}

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "--boundary") == 0) {
        errno = 0;
        const int fd = open(argv[2], O_RDONLY);
        assert(fd < 0 && (errno == EACCES || errno == EPERM));
        return 0;
    }
    if (argc == 3 && strcmp(argv[1], "--wait-final") == 0) {
        const pid_t pid = (pid_t)strtol(argv[2], NULL, 10);
        assert(pid > 1);
        const int queue = kqueue(); assert(queue >= 0);
        struct kevent change, event;
        EV_SET(&change, pid, EVFILT_PROC, EV_ADD | EV_ONESHOT, NOTE_EXIT, 0, NULL);
        if (kevent(queue, &change, 1, NULL, 0, NULL) != 0) {
            assert(errno == ESRCH); close(queue); return 0;
        }
        const struct timespec timeout = {10, 0};
        assert(kevent(queue, NULL, 0, &event, 1, &timeout) == 1 && (event.fflags & NOTE_EXIT));
        close(queue); return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--final") == 0) {
        const char *root = getenv("LOGSEQ_BRIDGE_TEST_ROOT");
        assert(root != NULL);
        char file[PATH_MAX];
        assert(snprintf(file, sizeof(file), "%s/final-pid", root) < (int)sizeof(file));
        FILE *pid = fopen(file, "wx"); assert(pid != NULL);
        assert(fprintf(pid, "%ld\n", (long)getpid()) > 0 && fclose(pid) == 0);
        assert(snprintf(file, sizeof(file), "%s/final-input", root) < (int)sizeof(file));
        const int input = open(file, O_RDONLY); assert(input >= 0);
        assert(write(198, "R", 1) == 1); close(198);
        char byte;
        assert(read(input, &byte, 1) == 1); close(input);
        assert(snprintf(file, sizeof(file), "%s/final-exited", root) < (int)sizeof(file));
        FILE *exited = fopen(file, "wx"); assert(exited != NULL);
        assert(fputs("normal-exit\n", exited) >= 0 && fclose(exited) == 0);
        return 0;
    }
    if (argc == 3 && strcmp(argv[1], "--launch") == 0) {
        char executable[PATH_MAX];
        assert(snprintf(executable, sizeof(executable), "%s/Contents/MacOS/Logseq", argv[2]) < (int)sizeof(executable));
        int ready[2]; assert(pipe(ready) == 0);
        for (int index = 0; index < 2; ++index) assert(fcntl(ready[index], F_SETFD, FD_CLOEXEC) == 0);
        posix_spawn_file_actions_t actions;
        assert(posix_spawn_file_actions_init(&actions) == 0);
        assert(posix_spawn_file_actions_adddup2(&actions, ready[1], 198) == 0);
        for (int fd = 0; fd < 3; ++fd)
            assert(posix_spawn_file_actions_addopen(&actions, fd, "/dev/null", fd == 0 ? O_RDONLY : O_WRONLY, 0) == 0);
        unsetenv("DYLD_INSERT_LIBRARIES");
        char *arguments[] = {executable, "--final", NULL};
        pid_t child;
        assert(posix_spawn(&child, executable, &actions, NULL, arguments, environ) == 0);
        posix_spawn_file_actions_destroy(&actions); close(ready[1]);
        char byte; assert(read(ready[0], &byte, 1) == 1 && byte == 'R'); close(ready[0]);
        return 0;
    }
    if (argc == 3 && strcmp(argv[1], "--lock") == 0) {
        const int directory = open(argv[2], O_RDONLY | O_CLOEXEC); assert(directory >= 0);
        assert(flock(directory, LOCK_EX | LOCK_NB) == 0); close(directory);
        return 0;
    }
    if (argc == 3 && strcmp(argv[1], "--target") == 0) {
        assert(write(STDOUT_FILENO, "R", 1) == 1);
        char byte; assert(read(STDIN_FILENO, &byte, 1) >= 0);
        return 0;
    }
    if (argc == 5 && strcmp(argv[1], "--target") == 0) {
        assert(write(atoi(argv[4]), "R", 1) == 1);
        close(atoi(argv[4]));
        char byte;
        assert(read(atoi(argv[3]), &byte, 1) >= 0);
        return 0;
    }
    assert(argc == 4);
    int other_release;
    const pid_t other = source(argv[1], argv[3], &other_release);
    expected_source = source(argv[1], argv[2], &release_source);
    assert(logseq_bridge_wait_for_source_helper(argv[2]) == 0);
    assert(registered_source == 1 && observed_exit == 1 && release_source == -1);
    int status;
    assert(waitpid(expected_source, &status, 0) == expected_source && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    puts("PASS exact-source-kernel-exit-before-handoff");
    assert(waitpid(other, &status, WNOHANG) == 0);
    puts("PASS unrelated-target-left-running");
    close(other_release);
    assert(waitpid(other, &status, 0) == other && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(logseq_bridge_wait_for_source_helper(argv[2]) == 0);
    assert(registered_source == 1 && observed_exit == 1);
    puts("PASS already-exited-source-needs-no-wait");
    return 0;
}
