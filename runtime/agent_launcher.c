// Android-installable entry points for JavaScript CLIs; argv stays literal.
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <limits.h>

int main(int argc, char **argv) {
    const char *prefix = getenv("PREFIX");
    if (!prefix || !*prefix) { fputs("mobby PREFIX is missing\n", stderr); return 126; }
    const char *name = strrchr(argv[0], '/'); name = name ? name + 1 : argv[0];
    const char *script;
    if (strstr(name, "claude")) script = "/lib/node_modules/@anthropic-ai/claude-code/cli.js";
    else if (strcmp(name, "pi") == 0) script = "/lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js";
    else if (strstr(name, "npx")) script = "/lib/node_modules/npm/bin/npx-cli.js";
    else script = "/lib/node_modules/npm/bin/npm-cli.js";
    char node[PATH_MAX], entry[PATH_MAX];
    if (snprintf(node, sizeof(node), "%s/bin/node", prefix) >= sizeof(node) ||
        snprintf(entry, sizeof(entry), "%s%s", prefix, script) >= sizeof(entry)) return 126;
    char **args = calloc((size_t)argc + 2, sizeof(char*));
    if (!args) return 126;
    args[0] = node; args[1] = entry;
    for (int i = 1; i < argc; i++) args[i+1] = argv[i];
    execv(node, args);
    perror("Starting Node.js");
    free(args);
    return 127;
}
