/*
 * Copyright 2022 Muntashir Al-Islam
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _SPAKE2_H
#define _SPAKE2_H 1

#include<stdlib.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SPAKE2_MAX_MSG_SIZE 32
#define SPAKE2_MAX_KEY_SIZE 64

enum spake2_role_t {
  spake2_role_alice,
  spake2_role_bob,
};

enum spake2_state_t {
  spake2_state_init = 0,
  spake2_state_msg_generated,
  spake2_state_key_generated,
};

struct spake2_ctx_st {
  uint8_t private_key[32];
  uint8_t my_msg[32];
  uint8_t password_scalar[32];
  uint8_t password_hash[64];
  uint8_t *my_name;
  size_t my_name_len;
  uint8_t *their_name;
  size_t their_name_len;
  enum spake2_role_t my_role;
  enum spake2_state_t state;
  char disable_password_scalar_hack;
};

extern struct spake2_ctx_st *SPAKE2_CTX_new(enum spake2_role_t my_role,
				    const uint8_t *my_name, size_t my_name_len,
				    const uint8_t *their_name, size_t their_name_len);

extern void SPAKE2_CTX_free(struct spake2_ctx_st *ctx);

extern int SPAKE2_generate_msg(struct spake2_ctx_st *ctx,
				    uint8_t *out, size_t *out_len, size_t max_out_len,
                    const uint8_t *password, size_t password_len);

extern int SPAKE2_process_msg(struct spake2_ctx_st *ctx,
				    uint8_t *out_key, size_t *out_key_len,
                    size_t max_out_key_len, const uint8_t *their_msg,
                    size_t their_msg_len);

#ifdef __cplusplus
}
#endif

#endif // _SPAKE2_H
