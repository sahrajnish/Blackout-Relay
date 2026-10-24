"""
Pool minting and verification (SPEC.md §14.1, §14.4).

A pool is a set of single-use "slots", each carrying pre-signed PUT URLs for one
bundle's artefacts. The app consumes one slot per capture and never sees the R2
secret key.
"""

from __future__ import annotations

import hashlib
import os
import time
from dataclasses import dataclass

from reference import cbor, model, sodium
from reference.model import build_pool, build_slot

from . import sigv4

ARTEFACTS = {1: "manifest.cbor", 2: "thumb.enc", 3: "proxy.enc"}


@dataclass
class BucketConfig:
    endpoint: str            # e.g. "<account_id>.r2.cloudflarestorage.com"
    bucket: str
    region: str = "auto"
    access_key: str | None = None
    secret_key: str | None = None
    path_style: bool = False

    @property
    def online(self) -> bool:
        return bool(self.access_key and self.secret_key)

    def host(self) -> str:
        return self.endpoint if self.path_style else f"{self.bucket}.{self.endpoint}"

    def object_path(self, key: str) -> str:
        if self.path_style:
            return f"/{self.bucket}/{key}"
        return f"/{key}"


def object_key(dest_prefix_hex: str, slot: int, artefact_file: str) -> str:
    return f"{dest_prefix_hex}/{slot:04d}/{artefact_file}"


def slot_expires_epoch(now: int, days: int) -> int:
    return now + days * 86400


def mint_pool(
    cfg: BucketConfig,
    dest_prefix: bytes,
    slots: int,
    days: int,
    *,
    now: int | None = None,
    pool_id: int = 1,
    operator_sk: bytes | None = None,
) -> dict:
    if len(dest_prefix) != 16:
        raise ValueError("dest_prefix must be 16 bytes")
    now = int(now if now is not None else time.time())
    expires = slot_expires_epoch(now, days)
    prefix_hex = dest_prefix.hex()

    slot_dicts = []
    for i in range(slots):
        urls = {}
        for art_id, filename in ARTEFACTS.items():
            key = object_key(prefix_hex, i, filename)
            if cfg.online:
                url = sigv4.presigned_url(
                    "PUT", "https", cfg.host(), cfg.object_path(key),
                    cfg.access_key, cfg.secret_key, cfg.region,
                    expires=days * 86400,
                )
            else:
                url = f"https://{cfg.host()}/{key}"
            urls[art_id] = url
        slot_dicts.append(build_slot(i, urls, expires))

    return build_pool(pool_id, dest_prefix, now, expires, slot_dicts, operator_sk)


def pool_health(pool: dict, now: int | None = None) -> dict:
    now = int(now if now is not None else time.time())
    slots = pool.get(5, [])
    expires = pool.get(4, 0)
    remaining = sum(1 for s in slots if s.get(3, 0) > now)
    return {
        "slots_total": len(slots),
        "slots_remaining": remaining,
        "pool_expires_at": expires,
        "pool_expired": expires <= now,
        "soonest_slot_expiry": min((s.get(3, 0) for s in slots), default=0),
    }


def verify_pool(pool: dict) -> list[str]:
    """Structural checks on a decoded pool. Returns a list of problems."""
    problems = []
    if pool.get(1) is None:
        problems.append("missing pool_id")
    prefix = pool.get(2)
    if not isinstance(prefix, (bytes, bytearray)) or len(prefix) != 16:
        problems.append("dest_prefix must be 16 bytes")
        prefix_hex = None
    else:
        prefix_hex = bytes(prefix).hex()
    slots = pool.get(5)
    if not isinstance(slots, list):
        problems.append("slots must be an array")
        return problems
    seen = set()
    for s in slots:
        idx = s.get(1)
        if idx in seen:
            problems.append(f"duplicate slot_index {idx}")
        seen.add(idx)
        urls = s.get(2)
        if not isinstance(urls, dict) or not all(a in urls for a in (1, 2, 3)):
            problems.append(f"slot {idx}: missing URLs")
            continue
        if prefix_hex:
            for a in (1, 2, 3):
                if prefix_hex not in urls[a]:
                    problems.append(f"slot {idx}: URL lacks dest_prefix")
    sig = pool.get(6)
    if sig is not None:
        body = {k: v for k, v in pool.items() if k != 6}
        # Operator key is not embedded in the pool; verification is opt-in.
        if len(sig) != 64:
            problems.append("pool signature must be 64 bytes")
    return problems
