"""Tests for poolctl (stdlib unittest; run `python3 -m poolctl.test_poolctl`)."""

from __future__ import annotations

import unittest

from reference import cbor

from . import sigv4
from .mint import BucketConfig, mint_pool, pool_health, verify_pool

PREFIX = bytes(range(0xB0, 0xC0))  # 16 bytes
NOW = 1_700_000_000


class SigV4Test(unittest.TestCase):
    def test_aws_vector(self):
        q = sigv4.presign(
            "GET", "examplebucket.s3.amazonaws.com", "/test.txt",
            access_key="AKIAIOSFODNN7EXAMPLE",
            secret_key="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            region="us-east-1", expires=86400,
            amz_datetime="20130524T000000Z",
        )
        sig = q.split("X-Amz-Signature=")[1]
        self.assertEqual(
            sig,
            "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404",
        )


class MintTest(unittest.TestCase):
    def cfg_offline(self):
        return BucketConfig(endpoint="bucket.r2.cloudflarestorage.com", bucket="bucket")

    def test_offline_pool_structure(self):
        pool = mint_pool(self.cfg_offline(), PREFIX, slots=4, days=7,
                         now=NOW, pool_id=1)
        self.assertEqual(pool[1], 1)
        self.assertEqual(pool[2], PREFIX)
        self.assertEqual(pool[4], NOW + 7 * 86400)
        slots = pool[5]
        self.assertEqual(len(slots), 4)
        for i, s in enumerate(slots):
            self.assertEqual(s[1], i)
            self.assertEqual(s[3], NOW + 7 * 86400)
            urls = s[2]
            self.assertEqual(set(urls), {1, 2, 3})
            for a in (1, 2, 3):
                self.assertIn(PREFIX.hex(), urls[a])
                self.assertIn(f"{i:04d}", urls[a])
        self.assertEqual(verify_pool(pool), [])

    def test_offline_pool_roundtrips_cbor(self):
        pool = mint_pool(self.cfg_offline(), PREFIX, slots=2, days=7, now=NOW)
        decoded = cbor.decode(cbor.encode(pool))
        self.assertEqual(decoded[2], PREFIX)
        self.assertEqual(len(decoded[5]), 2)

    def test_online_pool_has_signatures(self):
        cfg = BucketConfig(
            endpoint="acct.r2.cloudflarestorage.com", bucket="mybucket",
            region="auto",
            access_key="AKIAIOSFODNN7EXAMPLE",
            secret_key="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        )
        pool = mint_pool(cfg, PREFIX, slots=1, days=7, now=NOW)
        url = pool[5][0][2][1]
        self.assertIn("X-Amz-Signature=", url)
        self.assertIn("X-Amz-Expires=604800", url)
        self.assertIn("X-Amz-Algorithm=AWS4-HMAC-SHA256", url)
        self.assertIn(PREFIX.hex(), url)

    def test_pool_health(self):
        pool = mint_pool(self.cfg_offline(), PREFIX, slots=3, days=7, now=NOW)
        h = pool_health(pool, now=NOW + 86400)
        self.assertEqual(h["slots_total"], 3)
        self.assertEqual(h["slots_remaining"], 3)
        self.assertFalse(h["pool_expired"])
        h2 = pool_health(pool, now=NOW + 8 * 86400)
        self.assertEqual(h2["slots_remaining"], 0)
        self.assertTrue(h2["pool_expired"])

    def test_verify_catches_missing_prefix(self):
        pool = mint_pool(self.cfg_offline(), PREFIX, slots=1, days=7, now=NOW)
        pool[5][0][2][1] = "https://evil.example.com/0000/manifest.cbor"
        problems = verify_pool(pool)
        self.assertTrue(any("lacks dest_prefix" in p for p in problems))

    def test_operator_signature_length(self):
        from reference import sodium
        _, sk = sodium.ed25519_keypair(bytes(range(32)))
        pool = mint_pool(self.cfg_offline(), PREFIX, slots=1, days=7,
                         now=NOW, operator_sk=sk)
        self.assertEqual(len(pool[6]), 64)
        self.assertEqual(verify_pool(pool), [])


if __name__ == "__main__":
    unittest.main()
