"""poolctl — mint/verify/audit the pre-signed drop pool (SPEC.md §14.4)."""

from __future__ import annotations

import argparse
import binascii
import json
import os
import sys

from reference import cbor, sodium
from reference.model import build_pool, build_slot

from . import mint, sigv4
from .mint import BucketConfig, mint_pool, pool_health, verify_pool


def _load_pool(path: str) -> dict:
    raw = open(path, "rb").read()
    text = raw.decode("ascii", errors="ignore").strip()
    try:
        data = bytes.fromhex(text) if text and all(c in "0123456789abcdefABCDEF" for c in text) else raw
    except ValueError:
        data = raw
    return cbor.decode(data)


def cmd_mint(args) -> int:
    prefix = bytes.fromhex(args.prefix) if args.prefix else os.urandom(16)
    if len(prefix) != 16:
        print("error: --prefix must be 32 hex chars (16 bytes)", file=sys.stderr)
        return 2
    cfg = BucketConfig(
        endpoint=args.endpoint,
        bucket=args.bucket,
        region=args.region,
        access_key=args.access_key,
        secret_key=args.secret_key,
        path_style=args.path_style,
    )
    operator_sk = None
    if args.operator_seed:
        _, operator_sk = sodium.ed25519_keypair(bytes.fromhex(args.operator_seed))
    pool = mint_pool(cfg, prefix, args.slots, args.days, now=args.now,
                     pool_id=args.pool_id, operator_sk=operator_sk)
    encoded = cbor.encode(pool)
    hexstr = encoded.hex()

    health = pool_health(pool, now=args.now)
    print(f"pool minted: {health['slots_total']} slots, dest_prefix={prefix.hex()}, "
          f"expires in {args.days} day(s)", file=sys.stderr)
    if not cfg.online:
        print("note: offline mode — placeholder URLs (no SigV4 signature)", file=sys.stderr)

    if args.out:
        mode = "wb" if args.binary else "w"
        with open(args.out, mode) as f:
            f.write(encoded if args.binary else hexstr)
        print(f"wrote {args.out}", file=sys.stderr)
    else:
        print(hexstr)
    return 0


def cmd_verify(args) -> int:
    pool = _load_pool(args.input)
    problems = verify_pool(pool)
    if problems:
        print("INVALID pool:")
        for p in problems:
            print(f"  - {p}")
        return 1
    h = pool_health(pool)
    print(f"OK: {h['slots_total']} slots, {h['slots_remaining']} remaining, "
          f"expires_at={h['pool_expires_at']}")
    return 0


def cmd_audit(args) -> int:
    pool = _load_pool(args.input)
    problems = verify_pool(pool)
    h = pool_health(pool)
    print(json.dumps({
        "pool_id": pool.get(1),
        "dest_prefix": (pool.get(2) or b"").hex(),
        "minted_at": pool.get(3),
        "expires_at": pool.get(4),
        "slots_total": h["slots_total"],
        "slots_remaining": h["slots_remaining"],
        "problems": problems,
    }, indent=2))
    return 0 if not problems else 1


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="poolctl", description="Blackout Relay pool tool")
    sub = p.add_subparsers(dest="cmd", required=True)

    m = sub.add_parser("mint", help="mint a pool of N slots")
    m.add_argument("--endpoint", required=True, help="e.g. <account>.r2.cloudflarestorage.com")
    m.add_argument("--bucket", required=True)
    m.add_argument("--region", default="auto")
    m.add_argument("--slots", type=int, default=50)
    m.add_argument("--days", type=int, default=7)
    m.add_argument("--prefix", help="16-byte dest_prefix hex (generated if omitted)")
    m.add_argument("--access-key")
    m.add_argument("--secret-key")
    m.add_argument("--path-style", action="store_true")
    m.add_argument("--operator-seed", help="32-byte hex to sign the pool")
    m.add_argument("--pool-id", type=int, default=1)
    m.add_argument("--now", type=int, help="fixed mint timestamp (testing)")
    m.add_argument("--out")
    m.add_argument("--binary", action="store_true", help="write raw CBOR instead of hex")
    m.set_defaults(func=cmd_mint)

    v = sub.add_parser("verify", help="verify a pool file (hex or CBOR)")
    v.add_argument("input")
    v.set_defaults(func=cmd_verify)

    a = sub.add_parser("audit", help="print pool inventory as JSON")
    a.add_argument("input")
    a.set_defaults(func=cmd_audit)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
