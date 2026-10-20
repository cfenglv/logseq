#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <glib.h>
#include <gtk/gtk.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "bridge_payload.h"
#include "native_update_strings.h"

static gboolean fail(GError **error, const char *message) {
    g_set_error_literal(error, G_FILE_ERROR, G_FILE_ERROR_FAILED, message);
    return FALSE;
}

static gboolean fail_errno(GError **error, const char *operation) {
    const int saved = errno;
    g_set_error(error, G_FILE_ERROR, g_file_error_from_errno(saved),
                "%s: %s", operation, g_strerror(saved));
    return FALSE;
}

static gboolean same_file(const struct stat *left, const struct stat *right) {
    return left->st_dev == right->st_dev && left->st_ino == right->st_ino;
}

static gboolean read_proc(int process_fd, const char *name, char **data, GError **error) {
    g_autofree char *file = g_strdup_printf("/proc/self/fd/%d/%s", process_fd, name);
    return g_file_get_contents(file, data, NULL, error);
}

/* A pinned /proc directory cannot start referring to a reused PID. */
static int source_finished(int process_fd, GError **error) {
    g_autofree char *file = g_strdup_printf("/proc/self/fd/%d/stat", process_fd);
    g_autofree char *data = NULL;
    size_t capacity = 0;
    FILE *stream = fopen(file, "re");
    ssize_t count = -1;
    int saved_errno = errno;
    if (stream != NULL) {
        errno = 0;
        count = getdelim(&data, &capacity, '\0', stream);
        saved_errno = errno;
        fclose(stream);
    }
    if (count < 0) {
        if (saved_errno == ENOENT || saved_errno == ESRCH) return 1;
        if (saved_errno != 0) {
            errno = saved_errno;
            fail_errno(error, "Read source process state");
        } else {
            fail(error, "Invalid source process state");
        }
        return -1;
    }
    const char *tail = strrchr(data, ')');
    if (tail == NULL || strlen(tail) < 3) {
        fail(error, "Invalid source process state");
        return -1;
    }
    return tail[2] == 'Z' || tail[2] == 'X';
}

static gboolean wait_for_source_exit(int process_fd, GError **error) {
    const gint64 deadline = g_get_monotonic_time() + 15000000;
    int finished;
    while ((finished = source_finished(process_fd, error)) == 0) {
        if (g_get_monotonic_time() >= deadline) return fail(error, "Source client did not exit");
        g_usleep(10000);
    }
    return finished == 1;
}

static char *initial_environment(const char *data, gsize size, const char *key) {
    const gsize key_size = strlen(key);
    for (gsize offset = 0; offset < size;) {
        const char *value = data + offset;
        const gsize length = strnlen(value, size - offset);
        if (length > key_size && strncmp(value, key, key_size) == 0 && value[key_size] == '=')
            return g_strndup(value + key_size + 1, length - key_size - 1);
        offset += length + 1;
    }
    return NULL;
}

static gboolean wait_for_source(const char *install_directory, const char *app_directory,
                                GError **error) {
    const pid_t parent = getppid();
    if (parent == 1) return TRUE; /* The original client has already exited. */
    g_autofree char *process_path = g_strdup_printf("/proc/%ld", (long)parent);
    const int process_fd = open(process_path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (process_fd < 0) {
        if (errno == ENOENT || errno == ESRCH) return TRUE;
        return fail_errno(error, "Open source process");
    }
    gboolean result = FALSE;
    g_autofree char *status = NULL;
    g_autofree char *environment = NULL;
    g_autofree char *source_directory = NULL;
    g_autofree char *source_image = NULL;
    g_autofree char *expected_executable = NULL;
    g_autofree char *source_parent = NULL;
    g_autofree char *source_parent_real = NULL;
    g_autofree char *environment_path = NULL;
    char executable[PATH_MAX + 1];
    gsize environment_size = 0;
    unsigned real_uid, effective_uid;
    int finished = source_finished(process_fd, error);
    if (finished != 0) { result = finished == 1; goto done; }
    if (!read_proc(process_fd, "status", &status, error)) goto gone_or_error;
    const char *uid = strstr(status, "\nUid:");
    if (uid == NULL || sscanf(uid, "\nUid: %u %u", &real_uid, &effective_uid) != 2) {
        fail(error, "Invalid source process owner");
        goto done;
    }
    if (real_uid != getuid() || effective_uid != geteuid()) { result = TRUE; goto done; }
    const ssize_t count = readlinkat(process_fd, "exe", executable, PATH_MAX);
    if (count < 0) { fail_errno(error, "Read source executable"); goto gone_or_error; }
    executable[count] = '\0';
    const char *base = strrchr(executable, '/');
    if (base == NULL || strcmp(base + 1, "logseq") != 0) { result = TRUE; goto done; }
    environment_path = g_strdup_printf("/proc/self/fd/%d/environ", process_fd);
    if (!g_file_get_contents(environment_path, &environment, &environment_size, error)) goto gone_or_error;
    source_directory = initial_environment(environment, environment_size, "APPDIR");
    source_image = initial_environment(environment, environment_size, "APPIMAGE");
    if (source_directory == NULL || source_image == NULL) {
        fail(error, "Missing source AppImage identity");
        goto gone_or_error;
    }
    expected_executable = g_build_filename(source_directory, "logseq", NULL);
    source_parent = g_path_get_dirname(source_image);
    source_parent_real = realpath(source_parent, NULL);
    if (source_parent_real == NULL) { fail_errno(error, "Resolve source directory"); goto done; }
    if (strcmp(executable, expected_executable) != 0 ||
        strcmp(source_parent_real, install_directory) != 0 ||
        strcmp(source_directory, app_directory) == 0) {
        fail(error, "Source AppImage does not belong to this update");
        goto done;
    }
    result = wait_for_source_exit(process_fd, error);
    goto done;

gone_or_error:
    /* Identity can disappear before task exit. Wait only for this pinned parent, never assume it is gone. */
    if (wait_for_source_exit(process_fd, NULL)) {
        g_clear_error(error);
        result = TRUE;
    }
done:
    close(process_fd);
    return result;
}

/* Copy and hash in one pass; output_fd == -1 verifies an existing final file. */
static gboolean copy_payload(int input_fd, int output_fd, GError **error) {
    struct stat input;
    if (fstat(input_fd, &input) != 0) return fail_errno(error, "Inspect payload");
    if (!S_ISREG(input.st_mode) || input.st_size != BRIDGE_PAYLOAD_SIZE)
        return fail(error, "Unexpected payload size or type");
    g_autoptr(GChecksum) digest = g_checksum_new(G_CHECKSUM_SHA256);
    unsigned char buffer[65536];
    guint64 total = 0;
    for (;;) {
        const ssize_t count = read(input_fd, buffer, sizeof(buffer));
        if (count < 0) {
            if (errno == EINTR) continue;
            return fail_errno(error, "Read payload");
        }
        if (count == 0) break;
        total += count;
        if (total > BRIDGE_PAYLOAD_SIZE) return fail(error, "Payload grew during copy");
        g_checksum_update(digest, buffer, count);
        for (ssize_t offset = 0; output_fd >= 0 && offset < count;) {
            const ssize_t written = write(output_fd, buffer + offset, count - offset);
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) return fail_errno(error, "Write payload");
            offset += written;
        }
    }
    if (total != BRIDGE_PAYLOAD_SIZE || strcmp(g_checksum_get_string(digest), BRIDGE_PAYLOAD_SHA256) != 0)
        return fail(error, "Payload checksum does not match this bridge");
    return TRUE;
}

static gboolean path_is_file(int directory_fd, const char *name, const struct stat *identity) {
    struct stat current;
    return fstatat(directory_fd, name, &current, AT_SYMLINK_NOFOLLOW) == 0 &&
           S_ISREG(current.st_mode) && same_file(&current, identity);
}

static gboolean launch_final(const char *directory, const char *name, GError **error) {
    g_autofree char *target = g_build_filename(directory, name, NULL);
    char *arguments[] = {target, NULL};
    g_auto(GStrv) environment = g_get_environ();
    const char *context[] = {"APPIMAGE", "APPDIR", "ARGV0", "APPIMAGE_EXIT_AFTER_INSTALL"};
    for (gsize index = 0; index < G_N_ELEMENTS(context); index++)
        environment = g_environ_unsetenv(environment, context[index]);
    /* GLib detaches the child and reports exec failure. Do not retain our FUSE mount with exec. */
    return g_spawn_async(directory, arguments, environment, G_SPAWN_DEFAULT, NULL, NULL, NULL, error);
}

static gboolean install_and_launch(GError **error) {
    const char *image = g_getenv("APPIMAGE"), *app_directory = g_getenv("APPDIR");
    if (image == NULL || !g_path_is_absolute(image) || app_directory == NULL || !g_path_is_absolute(app_directory))
        return fail(error, "Bridge must run from its AppImage");
    g_autofree char *image_real = realpath(image, NULL);
    if (image_real == NULL) return fail_errno(error, "Resolve bridge AppImage");
    g_autofree char *directory = g_path_get_dirname(image_real);
    g_autofree char *bridge_name = g_path_get_basename(image_real);
    g_autofree char *payload_path = g_build_filename(app_directory, "bridge", "payload", BRIDGE_PAYLOAD_NAME, NULL);
    g_autofree char *staging_path = NULL;
    g_autofree char *staging_name = NULL;
    const char *target_name = g_regex_match_simple("[0-9]+\\.[0-9]+\\.[0-9]+", bridge_name, 0, 0)
        ? BRIDGE_PAYLOAD_NAME : bridge_name;
    const gboolean same_name = strcmp(target_name, bridge_name) == 0;
    const int directory_fd = open(directory, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (directory_fd < 0) return fail_errno(error, "Open install directory");
    gboolean result = FALSE;
    int current_fd = -1, payload_fd = -1, staging_fd = -1;
    struct stat bridge_identity, target_identity;
    if (flock(directory_fd, LOCK_EX | LOCK_NB) != 0) {
        fail_errno(error, "Lock install directory"); goto done;
    }
    current_fd = openat(directory_fd, bridge_name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (current_fd < 0) { fail_errno(error, "Open current AppImage"); goto done; }
    if (fstat(current_fd, &bridge_identity) != 0) { fail_errno(error, "Inspect current AppImage"); goto done; }
    if (!S_ISREG(bridge_identity.st_mode)) { fail(error, "Current AppImage is not a regular file"); goto done; }
    if (!wait_for_source(directory, app_directory, error)) goto done;

    /* A completed identical installation is safe; an unrelated destination is not ours to replace. */
    const int existing_fd = same_name ? dup(current_fd)
        : openat(directory_fd, target_name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (existing_fd >= 0) {
        g_autoptr(GError) verify_error = NULL;
        const gboolean identical = copy_payload(existing_fd, -1, &verify_error);
        const int inspect = fstat(existing_fd, &target_identity);
        close(existing_fd);
        if (identical && inspect == 0 && (target_identity.st_mode & S_IXUSR) != 0) {
            if (!path_is_file(directory_fd, target_name, &target_identity)) {
                fail(error, "Final AppImage changed before launch"); goto done;
            }
            goto launch;
        }
        if (!same_name) { fail(error, "Final AppImage destination already exists"); goto done; }
    } else if (same_name || errno != ENOENT) {
        fail_errno(error, "Inspect final AppImage destination"); goto done;
    }

    payload_fd = open(payload_path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (payload_fd < 0) { fail_errno(error, "Open embedded payload"); goto done; }
    staging_path = g_build_filename(directory, ".logseq-update-XXXXXX", NULL);
    staging_fd = g_mkstemp_full(staging_path, O_RDWR | O_CLOEXEC, 0600);
    if (staging_fd < 0) { fail_errno(error, "Create update staging file"); goto done; }
    staging_name = g_path_get_basename(staging_path);
    if (!copy_payload(payload_fd, staging_fd, error)) goto done;
    if (fchmod(staging_fd, 0755) != 0) { fail_errno(error, "Set final AppImage executable mode"); goto done; }
    if (fsync(staging_fd) != 0) { fail_errno(error, "Flush final AppImage"); goto done; }
    const int closed = close(staging_fd);
    staging_fd = -1;
    if (closed != 0) { fail_errno(error, "Close final AppImage"); goto done; }
    if (!path_is_file(directory_fd, bridge_name, &bridge_identity)) {
        fail(error, "Bridge AppImage changed before replacement"); goto done;
    }
    /* renameat2 is invoked directly to keep the libc ABI at the supported baseline. */
    const int committed = same_name
        ? renameat(directory_fd, staging_name, directory_fd, target_name)
        : syscall(SYS_renameat2, directory_fd, staging_name, directory_fd, target_name, 1 /* RENAME_NOREPLACE */);
    if (committed != 0) { fail_errno(error, "Commit final AppImage"); goto done; }
    g_clear_pointer(&staging_name, g_free);

launch:
    if (!launch_final(directory, target_name, error)) goto done;
    /* A cleanup failure cannot roll back a verified final installation. */
    if (!same_name && path_is_file(directory_fd, bridge_name, &bridge_identity))
        (void)unlinkat(directory_fd, bridge_name, 0);
    result = TRUE;
done:
    if (staging_name != NULL) (void)unlinkat(directory_fd, staging_name, 0);
    if (staging_fd >= 0) close(staging_fd);
    if (payload_fd >= 0) close(payload_fd);
    if (current_fd >= 0) close(current_fd);
    close(directory_fd);
    return result;
}

int main(void) {
    g_autoptr(GError) error = NULL;
    if (install_and_launch(&error)) return 0;
    fprintf(stderr, "Linux update failed: %s\n", error->message);
    const char *const *languages = g_get_language_names();
    gboolean chinese = FALSE;
    for (gsize index = 0; languages[index] != NULL; index++) {
        if (g_str_has_prefix(languages[index], "zh_CN") || g_str_has_prefix(languages[index], "zh_SG") ||
            g_str_has_prefix(languages[index], "zh-Hans")) { chinese = TRUE; break; }
    }
    if (gtk_init_check(NULL, NULL)) {
        GtkWidget *dialog = gtk_message_dialog_new(NULL, GTK_DIALOG_MODAL, GTK_MESSAGE_ERROR,
            GTK_BUTTONS_CLOSE, "%s", chinese ? BRIDGE_ERROR_TITLE_ZH_CN : BRIDGE_ERROR_TITLE_EN);
        gtk_message_dialog_format_secondary_text(GTK_MESSAGE_DIALOG(dialog), "%s",
            chinese ? BRIDGE_ERROR_DESC_ZH_CN : BRIDGE_ERROR_DESC_EN);
        gtk_dialog_run(GTK_DIALOG(dialog));
        gtk_widget_destroy(dialog);
    }
    return 1;
}
