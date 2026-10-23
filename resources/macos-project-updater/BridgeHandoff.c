#include <errno.h>
#include <fcntl.h>
#include <libproc.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/event.h>
#include <sys/sysctl.h>
#include <unistd.h>
#include "BridgeHandoff.h"

static int has_target(pid_t pid, const char *target, char *buffer) {
    int mib[] = {CTL_KERN, KERN_PROCARGS2, pid};
    size_t length = ARG_MAX;
    if (sysctl(mib, 3, buffer, &length, NULL, 0) != 0) return errno == ESRCH ? 0 : -1;
    if (length <= sizeof(int)) { errno = EPROTO; return -1; }
    int argc;
    memcpy(&argc, buffer, sizeof(argc));
    char *cursor = buffer + sizeof(argc), *end = buffer + length;
    cursor += strnlen(cursor, (size_t)(end - cursor));
    while (cursor < end && *cursor == 0) ++cursor;
    int target_value = 0;
    for (int index = 0; index < argc && cursor < end; ++index) {
        const size_t size = strnlen(cursor, (size_t)(end - cursor));
        if (cursor + size == end) { errno = EPROTO; return -1; }
        if (target_value) return strcmp(cursor, target) == 0;
        target_value = strcmp(cursor, "--target") == 0;
        cursor += size + 1;
    }
    return 0;
}

/* One same-user snapshot, exact helper/target, then the kernel's exit event. No signals or polling. */
int logseq_bridge_wait_for_source_helper(const char *target) {
    const int queue = kqueue();
    if (queue < 0) return errno;
    int result = 0;
    pid_t source = 0;
    int matches = 0;
    pid_t *pids = NULL;
    char *arguments = NULL;
    if (fcntl(queue, F_SETFD, FD_CLOEXEC) != 0) { result = errno; goto done; }
    int capacity = proc_listpids(PROC_UID_ONLY, getuid(), NULL, 0);
    if (capacity <= 0) { result = errno ? errno : EIO; goto done; }
    capacity += 32 * (int)sizeof(pid_t);
    pids = calloc(1, (size_t)capacity);
    if (pids == NULL) { result = ENOMEM; goto done; }
    const int used = proc_listpids(PROC_UID_ONLY, getuid(), pids, capacity);
    if (used <= 0 || used >= capacity) { result = used < 0 ? errno : EOVERFLOW; goto done; }
    for (int index = 0; index < used / (int)sizeof(pid_t); ++index) {
        if (pids[index] <= 1 || pids[index] == getpid()) continue;
        char name[64];
        if (proc_name(pids[index], name, sizeof(name)) <= 0) {
            if (errno == ESRCH) continue;
            result = errno ? errno : EIO; goto done;
        }
        if (strcmp(name, "ProjectUpdater") != 0) continue;
        char executable[PROC_PIDPATHINFO_MAXSIZE];
        if (proc_pidpath(pids[index], executable, sizeof(executable)) <= 0) {
            if (errno == ESRCH) continue;
            result = errno ? errno : EIO; goto done;
        }
        const char *base = strrchr(executable, '/');
        if (base == NULL || strcmp(base + 1, "ProjectUpdater") != 0) continue;
        if (arguments == NULL) {
            arguments = malloc(ARG_MAX);
            if (arguments == NULL) { result = ENOMEM; goto done; }
        }
        const int exact = has_target(pids[index], target, arguments);
        if (exact < 0) { result = errno; goto done; }
        if (!exact) continue;
        if (++matches > 1) { result = EBUSY; goto done; }
        struct kevent change;
        EV_SET(&change, pids[index], EVFILT_PROC, EV_ADD | EV_ENABLE | EV_ONESHOT, NOTE_EXIT, 0, NULL);
        if (kevent(queue, &change, 1, NULL, 0, NULL) != 0) {
            if (errno == ESRCH) continue;
            result = errno; goto done;
        }
        source = pids[index];
    }
    /* A complete snapshot with no active match also permits a late launch or manual bridge retry. */
    if (source != 0) {
        struct kevent event;
        const struct timespec deadline = {60, 0};
        const int count = kevent(queue, NULL, 0, &event, 1, &deadline);
        if (count < 0) result = errno;
        else if (count == 0) result = ETIMEDOUT;
        else if (event.flags & EV_ERROR) result = (int)event.data;
        else if ((pid_t)event.ident != source || !(event.fflags & NOTE_EXIT)) result = EPROTO;
    }
done:
    free(arguments);
    free(pids);
    close(queue);
    return result;
}
