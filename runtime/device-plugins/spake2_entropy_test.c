/* Regression for the vendored SPAKE2 library: libc PRNG state must not control pairing keys. */
#include <spake2/spake2.h>
#include <stdlib.h>
#include <string.h>

static int generate(unsigned char *message) {
    const unsigned char client[] = "adb pair client";
    const unsigned char server[] = "adb pair server";
    const unsigned char secret[] = "fake-test-pairing-secret";
    struct spake2_ctx_st *context = SPAKE2_CTX_new(spake2_role_alice, client, sizeof(client), server, sizeof(server));
    if (!context) return 0;
    size_t size = 0;
    int status = SPAKE2_generate_msg(context, message, &size, SPAKE2_MAX_MSG_SIZE, secret, sizeof(secret));
    SPAKE2_CTX_free(context);
    return status == 1 && size == SPAKE2_MAX_MSG_SIZE;
}

int main(void) {
    unsigned char first[SPAKE2_MAX_MSG_SIZE], second[SPAKE2_MAX_MSG_SIZE];
    srand(1);
    if (!generate(first)) return 2;
    srand(1);
    if (!generate(second)) return 2;
    return memcmp(first, second, sizeof(first)) == 0 ? 1 : 0;
}
