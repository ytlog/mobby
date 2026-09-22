// Android PIE entry for OpenCode.
// The official ARM64 musl program is ET_EXEC and asks for /lib/ld-musl-aarch64.so.1,
// which Android will not start. This process is the opencode command; it hands that
// program to the bundled static musl loader. LD_PRELOAD is cleared so a bionic preload
// is not mapped into the musl process, and so musl is not forced into later shell tools.
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <limits.h>

int main(int argc, char **argv) {
    const char *prefix = getenv("PREFIX");
    if (!prefix || !*prefix || strlen(prefix) > 512) {
        fputs("mobby PREFIX is missing\n", stderr);
        return 126;
    }
    char loader[PATH_MAX], program[PATH_MAX], libdir[PATH_MAX];
    if (snprintf(loader, sizeof(loader), "%s/lib/opencode/ld-musl-aarch64.so.1", prefix) >= (int)sizeof(loader) ||
        snprintf(program, sizeof(program), "%s/lib/opencode/opencode", prefix) >= (int)sizeof(program) ||
        snprintf(libdir, sizeof(libdir), "%s/lib/opencode", prefix) >= (int)sizeof(libdir))
        return 126;
    const char *old = getenv("LD_LIBRARY_PATH");
    size_t need = strlen(libdir) + 1;
    if (old && *old) need += strlen(old) + 1;
    char *merged = malloc(need);
    if (!merged) return 126;
    if (old && *old) snprintf(merged, need, "%s:%s", libdir, old);
    else snprintf(merged, need, "%s", libdir);
    if (setenv("LD_LIBRARY_PATH", merged, 1) != 0) { free(merged); return 126; }
    free(merged);
    unsetenv("LD_PRELOAD");
    char **args = calloc((size_t)argc + 2, sizeof(char *));
    if (!args) return 126;
    args[0] = loader;
    args[1] = program;
    for (int i = 1; i < argc; i++) args[i + 1] = argv[i];
    execv(loader, args);
    perror("Starting OpenCode");
    free(args);
    return 127;
}
