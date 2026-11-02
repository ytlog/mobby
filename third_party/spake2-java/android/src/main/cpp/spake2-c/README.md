# SPAKE2-C

Implementation of SPAKE2 protocol in C, fully compatible with the BoringSSL implementation.

## Get Started

If you have `make` installed, you can easily install the library in Linux or macOS by running
```sh
make install
```
or, to build a shared/dynamic library in Linux/macOS/Windows:
```sh
make build
```
In the latter case, you have to copy or add the contents of the `./include/` directory to your include path as well as add the shared library in the library path.

After installing the library, you can simply import `<spake2/spake2.h>`.

```c
// Create alice (client) and bob (server)
struct spake2_ctx_st *alice = SPAKE2_CTX_new(spake2_role_alice, kClientName, sizeof(kClientName), kServerName, sizeof(kServerName));
struct spake2_ctx_st *bob = SPAKE2_CTX_new(spake2_role_bob, kServerName, sizeof(kServerName), kClientName, sizeof(kClientName));
// TODO: Check if any of them returns NULL, and if so, fail operation

// The fields below are kept for compatibility with BoringSSL (if you use them)
// alice->disable_password_scalar_hack = (char) 1;
// bob->disable_password_scalar_hack = (char) 1;

// Generate message from password
int statusAlice = SPAKE2_generate_msg(alice, aMessage, &aMsgSize, SPAKE2_MAX_MSG_SIZE, kPassword, sizeof(kPassword));
int statusBob = SPAKE2_generate_msg(bob, bMessage, &bMsgSize, SPAKE2_MAX_MSG_SIZE, kPassword, sizeof(kPassword));
// TODO: Check if the status is 1 (success) or fail operation

// Get key from message
statusAlice = SPAKE2_process_msg(alice, aKey, &aKeyLen, sizeof(aKey), (uint8_t *) bMessage, bMsgSize);
statusBob = SPAKE2_process_msg(bob, bKey, &bKeyLen, sizeof(bKey), (uint8_t *) aMessage, aMsgSize);
// TODO: Check if the status is 1 (success) or fail operation

// At this point, aKey and bKey should be the same.
```

## C++ wrapper
Coming soon.

## Licenses

Portions of this library is available under the terms of Apache-2.0 license, LGPL-2.1 and MIT license. The library itself is licensed under LGPL-3.0 or later.

_SHA512 library is the only LGPL dependency. If you know a better SHA512 implementation than glibc's, feel free to share it. I am willing to adopt a permissive license instead of LGPL._
