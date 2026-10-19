#define main bridge_application_main
#include "../../resources/linux-project-updater/bridge.c"
#undef main
#include "bridge_test_payload.h"
#include <sys/prctl.h>
#include <sys/wait.h>

static gboolean exit_race;
static unsigned race_stat_reads;
static int race_release_fd = -1;

gboolean __real_g_file_get_contents(const gchar *, gchar **, gsize *, GError **);

gboolean __wrap_g_file_get_contents(const gchar *filename, gchar **contents, gsize *length, GError **error) {
    const gboolean result = __real_g_file_get_contents(filename, contents, length, error);
    /* Release the real parent only after the second live state has been captured. */
    if (exit_race && g_str_has_suffix(filename, "/stat") && ++race_stat_reads == 2) {
        g_assert_true(result);
        const char release = 'x';
        g_assert_cmpint(write(race_release_fd, &release, 1), ==, 1);
    }
    return result;
}

static void write_fixture(const char *path, const char *data, gsize size, int mode) {
    g_assert_true(g_file_set_contents(path, data, size, NULL));
    g_assert_cmpint(chmod(path, mode), ==, 0);
}

static void assert_contents(const char *path, const char *expected, gsize expected_size) {
    g_autofree char *actual = NULL;
    gsize size;
    g_assert_true(g_file_get_contents(path, &actual, &size, NULL));
    g_assert_cmpuint(size, ==, expected_size);
    g_assert_cmpmem(actual, size, expected, expected_size);
}

static void assert_no_staging(const char *directory) {
    GDir *dir = g_dir_open(directory, 0, NULL);
    g_assert_nonnull(dir);
    const char *name;
    while ((name = g_dir_read_name(dir)) != NULL)
        g_assert_false(g_str_has_prefix(name, ".logseq-update-"));
    g_dir_close(dir);
}

#ifndef TEST_POSTCOMMIT_FAILURE
static void wait_for_launch(const char *result_path) {
    const gint64 deadline = g_get_monotonic_time() + 3000000;
    while (!g_file_test(result_path, G_FILE_TEST_EXISTS)) {
        g_assert_cmpint(g_get_monotonic_time(), <, deadline);
        g_usleep(1000);
    }
    assert_contents(result_path, "clear\n", 6);
}
#endif

static void run_case(const char *name, gboolean versioned, gboolean corrupt, const char *existing, gboolean locked) {
    g_autofree char *root = g_dir_make_tmp("native-update-test-XXXXXX", NULL);
    g_autofree char *appdir = g_build_filename(root, "mounted-app", NULL);
    g_autofree char *payload_dir = g_build_filename(appdir, "bridge", "payload", NULL);
    g_autofree char *payload = g_build_filename(payload_dir, BRIDGE_PAYLOAD_NAME, NULL);
    g_autofree char *current = g_build_filename(root, versioned ? "Logseq-2.0.1-selfhost.7.AppImage" : "Notes.AppImage", NULL);
    g_autofree char *destination = versioned ? g_build_filename(root, BRIDGE_PAYLOAD_NAME, NULL) : g_strdup(current);
    g_autofree char *proof = g_build_filename(root, "launched", NULL);
    g_assert_cmpint(g_mkdir_with_parents(payload_dir, 0700), ==, 0);
    write_fixture(current, "bridge", 6, 0755);
    g_autofree char *embedded = g_strdup(TEST_PAYLOAD);
    if (corrupt) embedded[0] ^= 1;
    write_fixture(payload, embedded, sizeof(TEST_PAYLOAD) - 1, 0600);
    struct stat existing_identity;
    if (existing != NULL) write_fixture(destination, existing, strlen(existing), 0755);
    if (existing != NULL) g_assert_cmpint(stat(destination, &existing_identity), ==, 0);
    int held_lock = -1;
    if (locked) {
        held_lock = open(root, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        g_assert_cmpint(held_lock, >=, 0);
        g_assert_cmpint(flock(held_lock, LOCK_EX | LOCK_NB), ==, 0);
    }
    g_setenv("APPIMAGE", current, TRUE);
    g_setenv("APPDIR", appdir, TRUE);
    g_setenv("ARGV0", current, TRUE);
    g_setenv("APPIMAGE_EXIT_AFTER_INSTALL", "1", TRUE);
    g_setenv("BRIDGE_TEST_RESULT", proof, TRUE);
    g_autoptr(GError) error = NULL;
    const gboolean installed = install_and_launch(&error);
#ifdef TEST_POSTCOMMIT_FAILURE
    g_assert_false(installed);
    g_assert_nonnull(error);
    assert_contents(destination, TEST_PAYLOAD, sizeof(TEST_PAYLOAD) - 1);
    struct stat installed_stat;
    g_assert_cmpint(stat(destination, &installed_stat), ==, 0);
    g_assert_cmpuint(installed_stat.st_mode & 0777, ==, 0755);
#else
    if (corrupt || locked || (existing != NULL && strcmp(existing, TEST_PAYLOAD) != 0)) {
        g_assert_false(installed);
        g_assert_nonnull(error);
        if (corrupt) g_assert_cmpstr(error->message, ==, "Payload checksum does not match this bridge");
        if (locked) g_assert_true(g_str_has_prefix(error->message, "Lock install directory:"));
        assert_contents(current, "bridge", 6);
        if (existing != NULL) assert_contents(destination, existing, strlen(existing));
        g_assert_false(g_file_test(proof, G_FILE_TEST_EXISTS));
        if (locked) {
            assert_no_staging(root);
            close(held_lock);
            held_lock = -1;
            g_clear_error(&error);
            g_assert_true(install_and_launch(&error));
            g_assert_no_error(error);
            wait_for_launch(proof);
            assert_contents(destination, TEST_PAYLOAD, sizeof(TEST_PAYLOAD) - 1);
        }
    } else {
        g_assert_no_error(error);
        g_assert_true(installed);
        wait_for_launch(proof);
        assert_contents(destination, TEST_PAYLOAD, sizeof(TEST_PAYLOAD) - 1);
        struct stat installed_stat;
        g_assert_cmpint(stat(destination, &installed_stat), ==, 0);
        g_assert_cmpuint(installed_stat.st_mode & 0777, ==, 0755);
        if (existing != NULL) g_assert_true(same_file(&installed_stat, &existing_identity));
        if (versioned) g_assert_false(g_file_test(current, G_FILE_TEST_EXISTS));
    }
#endif
    assert_no_staging(root);
    if (held_lock >= 0) close(held_lock);
    printf("PASS %s\n", name);
    /* Delete only this test's known files, never a recursive user directory. */
    (void)unlink(proof); (void)unlink(current); (void)unlink(destination); (void)unlink(payload);
    g_assert_cmpint(rmdir(payload_dir), ==, 0);
    g_autofree char *bridge_dir = g_build_filename(appdir, "bridge", NULL);
    g_assert_cmpint(rmdir(bridge_dir), ==, 0);
    g_assert_cmpint(rmdir(appdir), ==, 0);
    g_assert_cmpint(rmdir(root), ==, 0);
}

#ifndef TEST_POSTCOMMIT_FAILURE
static void source_exit_during_identity_read(void) {
    int release[2], outcome[2], identity[2];
    g_assert_cmpint(pipe2(release, O_CLOEXEC), ==, 0);
    g_assert_cmpint(pipe2(outcome, O_CLOEXEC), ==, 0);
    g_assert_cmpint(pipe2(identity, O_CLOEXEC), ==, 0);
    g_assert_cmpint(prctl(PR_SET_CHILD_SUBREAPER, 1), ==, 0);
    const pid_t source = fork();
    g_assert_cmpint(source, >=, 0);
    if (source == 0) {
        close(outcome[0]); close(identity[0]);
        g_assert_cmpint(prctl(PR_SET_DUMPABLE, 0), ==, 0);
        const pid_t bridge = fork();
        g_assert_cmpint(bridge, >=, 0);
        if (bridge == 0) {
            close(release[0]); close(identity[1]);
            exit_race = TRUE;
            race_release_fd = release[1];
            g_autoptr(GError) error = NULL;
            const gboolean accepted = wait_for_source("/test-install", "/test-mount", &error);
            if (error != NULL) fprintf(stderr, "source identity result: %s\n", error->message);
            const char result = race_stat_reads >= 2 ? (accepted ? 'y' : 'n') : 'e';
            if (race_stat_reads < 2) g_assert_cmpint(write(release[1], &result, 1), ==, 1);
            g_assert_cmpint(write(outcome[1], &result, 1), ==, 1);
            close(release[1]); close(outcome[1]);
            _exit(0);
        }
        close(release[1]); close(outcome[1]);
        g_assert_cmpint(write(identity[1], &bridge, sizeof(bridge)), ==, sizeof(bridge));
        char signal;
        g_assert_cmpint(read(release[0], &signal, 1), ==, 1);
        g_assert_cmpint(signal, ==, 'x');
        _exit(0);
    }
    close(release[0]); close(release[1]); close(outcome[1]); close(identity[1]);
    pid_t bridge;
    g_assert_cmpint(read(identity[0], &bridge, sizeof(bridge)), ==, sizeof(bridge));
    close(identity[0]);
    char accepted;
    g_assert_cmpint(read(outcome[0], &accepted, 1), ==, 1);
    close(outcome[0]);
    int source_status, bridge_status;
    g_assert_cmpint(waitpid(source, &source_status, 0), ==, source);
    g_assert_cmpint(waitpid(bridge, &bridge_status, 0), ==, bridge);
    g_assert_true(WIFEXITED(source_status) && WEXITSTATUS(source_status) == 0);
    g_assert_true(WIFEXITED(bridge_status) && WEXITSTATUS(bridge_status) == 0);
    g_assert_cmpint(accepted, ==, 'y');
    puts("PASS source-exits-during-identity-read");
}

static gboolean title_seen, description_seen;

static void inspect_dialog_labels(GtkWidget *widget) {
    if (GTK_IS_LABEL(widget)) {
        const char *text = gtk_label_get_text(GTK_LABEL(widget));
        title_seen |= strcmp(text, BRIDGE_ERROR_TITLE_EN) == 0;
        description_seen |= strcmp(text, BRIDGE_ERROR_DESC_EN) == 0;
    }
    if (GTK_IS_CONTAINER(widget)) {
        GList *children = gtk_container_get_children(GTK_CONTAINER(widget));
        for (GList *child = children; child != NULL; child = child->next)
            inspect_dialog_labels(GTK_WIDGET(child->data));
        g_list_free(children);
    }
}

static gboolean close_error_dialog(gpointer unused) {
    (void)unused;
    GList *windows = gtk_window_list_toplevels();
    g_assert_cmpuint(g_list_length(windows), ==, 1);
    GtkWidget *dialog = GTK_WIDGET(windows->data);
    g_assert_true(GTK_IS_MESSAGE_DIALOG(dialog));
    inspect_dialog_labels(dialog);
    g_assert_true(title_seen && description_seen);
    gtk_dialog_response(GTK_DIALOG(dialog), GTK_RESPONSE_CLOSE);
    g_list_free(windows);
    return G_SOURCE_REMOVE;
}
#endif

int main(int argc, char **argv) {
#ifdef TEST_POSTCOMMIT_FAILURE
    (void)argc; (void)argv;
    run_case("post-commit-exec-failure-keeps-final", FALSE, FALSE, NULL, FALSE);
#else
    if (argc == 2 && strcmp(argv[1], "--failure-dialog") == 0) {
        g_unsetenv("APPIMAGE");
        g_unsetenv("APPDIR");
        g_setenv("LANG", "C", TRUE);
        g_setenv("LANGUAGE", "en", TRUE);
        g_idle_add(close_error_dialog, NULL);
        g_assert_cmpint(bridge_application_main(), ==, 1);
        g_assert_true(title_seen && description_seen);
        puts("PASS one-native-error-dialog-and-close");
        return 0;
    }
    run_case("stable-name-and-cleared-context", FALSE, FALSE, NULL, FALSE);
    run_case("versioned-name-and-bridge-cleanup", TRUE, FALSE, NULL, FALSE);
    run_case("unrelated-destination-preserved", TRUE, FALSE, "unrelated", FALSE);
    run_case("checksum-mismatch-preserves-bridge", FALSE, TRUE, NULL, FALSE);
    run_case("identical-final-is-not-replaced", TRUE, FALSE, TEST_PAYLOAD, FALSE);
    run_case("locked-entry-preserves-bridge-and-releases", FALSE, FALSE, NULL, TRUE);
    source_exit_during_identity_read();
#endif
    return 0;
}
