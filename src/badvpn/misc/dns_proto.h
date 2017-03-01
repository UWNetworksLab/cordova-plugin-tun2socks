/*
 * Copyright (C) uProxy
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the author nor the
 *    names of its contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Definitions for the DNS protocol.
 */

#ifndef BADVPN_MISC_DNS_PROTO_H
#define BADVPN_MISC_DNS_PROTO_H

#include <stdint.h>
#include <misc/byteorder.h>

B_START_PACKED
struct dns_header {
  uint16_t id;
  uint8_t qr_opcode_aa_tc_rd;
  uint8_t ra_z_rcode;
  uint16_t qdcount;
  uint16_t ancount;
  uint16_t nscount;
  uint16_t arcount;
} B_PACKED;
B_END_PACKED

// excludes variable length fields
B_START_PACKED
struct dns_question {
  uint16_t type;
  uint16_t cls;
} B_PACKED;
B_END_PACKED

B_START_PACKED
struct dns_A_answer {
  uint16_t cname;  // compressed name
  uint16_t type;
  uint16_t cls;
  uint32_t ttl;
  uint16_t rdlength;
  uint32_t rdata;
} B_PACKED;
B_END_PACKED

// DNS header field masks
#define DNS_QR 0x80
#define DNS_TC 0x02
#define DNS_Z  0x70
#define DNS_RCODE  0x0F


#define DNS_ID_STRLEN 6
#define DNS_MAX_NAME_LENGTH 255
#define DNS_COMPRESSED_NAME 0xC0

static void dns_get_header_id_str(char* id_str, uint8_t* data) {
  struct dns_header* dnsh = (struct dns_header*)data;
  sprintf(id_str, "%u", dnsh->id);
  id_str[DNS_ID_STRLEN - 1] = '\0';
}

static int dns_check(const uint8_t* data, int data_len,
                     struct dns_header *out_header) {
  ASSERT(data_len >= 0)
  ASSERT(out_header)

  // parse DNS header
  if (data_len < sizeof(struct dns_header)) {
    return 0;
  }
  memcpy(out_header, data, sizeof(*out_header));

  // verify DNS header is request
  return (out_header->qr_opcode_aa_tc_rd & DNS_QR) == 0 /* query */
            && (out_header->ra_z_rcode & DNS_Z) == 0 /* Z is Zero */
            && out_header->qdcount > 0 /* some questions */
            && !out_header->nscount && !out_header->ancount /* no answers */;
}

static int dns_get_name(char* name, uint8_t* data) {
  ASSERT(name);
  ASSERT(data);

  uint8_t n = *data++;
  while (n != 0) {
    while (n > 0) {
      *name++ = *data++;
      --n;
    }
    n = *data++;
    if (n != 0) {
      *name++ = '.';
    }
  }
  *name = '\0';
}

// Sets the query response bit.
static void dns_set_qr(uint8_t* data) {
  struct dns_header* header = (struct dns_header*)data;
  header->qr_opcode_aa_tc_rd |= DNS_QR;
}

static uint8_t dns_get_qr(uint8_t* data) {
  struct dns_header* header = (struct dns_header*)data;
  return (header->qr_opcode_aa_tc_rd & DNS_QR) >> 7;
}

static void dns_set_rcode(uint8_t* data, uint8_t rcode) {
  if (rcode > 5) {
    return;
  }
  struct dns_header* header = (struct dns_header*)data;
  if (rcode == 0) {
    header->ra_z_rcode &= ~DNS_RCODE;
  } else {
    header->ra_z_rcode |= (DNS_RCODE & rcode);
  }
}

static uint8_t dns_get_rcode(uint8_t* data) {
  struct dns_header* header = (struct dns_header*)data;
  return header->ra_z_rcode & DNS_RCODE;
}

static uint16_t dns_encode_compressed_name(uint8_t offset) {
  return ((DNS_COMPRESSED_NAME << 8) | offset);
}

// Encodes |name| onto |query|. Assumes enough memory has been allocated for
// |query|
static void dns_encode_name(char* name, uint8_t* query) {
  uint8_t n = 0;
  uint8_t* length_ptr = query;
  ++query;  // skip first length byte
  while (*name != '\0') {
    if (*name == '.') {
      *length_ptr = n;
      length_ptr = query++;
      n = 0;

      ++name;
      continue;
    }
    *query++ = *name++;
    ++n;
  }
  if (n > 0) {
    *length_ptr = n;
  }
  *query = '\0';
}


 #endif  // BADVPN_MISC_DNS_PROTO_H
