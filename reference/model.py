"""
Model builders, faithful to the CDDL schemas in SPEC.md.

Field numbers here are normative (SPEC.md §7.3, §9.3, §9.4, §9.5, §14.1).
Only canonical CBOR of these structures is produced.
"""

from __future__ import annotations

import hashlib

from . import cbor, sodium

# Identifier derivations (SPEC.md §7.4)
def account_id(account_pk: bytes) -> bytes:
    """account_id = SHA-256(account_pk)[0:16]."""
    return hashlib.sha256(account_pk).digest()[:16]


def dest_prefix(account_pk: bytes) -> bytes:
    """dest_prefix = account_id (16 bytes)."""
    return account_id(account_pk)


# --- Identity document (SPEC.md §7.3) --------------------------------------

def build_identity(ed25519_pk: bytes, x25519_pk: bytes, created_at: int,
                   key_epoch: int, ed25519_sk: bytes) -> dict:
    """Returns a dict whose canonical CBOR is the signed identity document."""
    body = {
        1: ed25519_pk,
        2: x25519_pk,
        3: created_at,
        4: key_epoch,
    }
    sig = sodium.ed25519_sign(cbor.encode(body), ed25519_sk)
    return {**body, 5: sig}


def identity_sig_input(ed25519_pk, x25519_pk, created_at, key_epoch) -> bytes:
    body = {1: ed25519_pk, 2: x25519_pk, 3: created_at, 4: key_epoch}
    return cbor.encode(body)


# --- Manifest (SPEC.md §9.3) -----------------------------------------------

def build_manifest(fields: dict, ed25519_sk: bytes) -> dict:
    """Sign fields 1..24 and attach field 25 (sig) over their canonical CBOR."""
    body = {k: v for k, v in fields.items() if k != 25}
    sig = sodium.ed25519_sign(cbor.encode(body), ed25519_sk)
    return {**body, 25: sig}


def manifest_sig_input(fields: dict) -> bytes:
    """Canonical CBOR of fields 1..24 (the exact bytes the sig covers)."""
    return cbor.encode({k: v for k, v in fields.items() if k != 25})


# --- Pool (SPEC.md §14.1) ---------------------------------------------------

def build_slot(slot_index: int, urls: dict, slot_expires_at: int) -> dict:
    return {1: slot_index, 2: urls, 3: slot_expires_at}


def build_pool(pool_id: int, dest_prefix_bytes: bytes, minted_at: int,
               expires_at: int, slots: list, operator_sk: bytes | None = None) -> dict:
    body = {
        1: pool_id,
        2: dest_prefix_bytes,
        3: minted_at,
        4: expires_at,
        5: slots,
    }
    if operator_sk is not None:
        body[6] = sodium.ed25519_sign(cbor.encode(body), operator_sk)
    return body


# --- ProofFrame (SPEC.md §9.4, v2 seam) -------------------------------------

def build_proof_frame(bundle_id: bytes, account_pk: bytes, content_hash: bytes,
                      created_at: int, lat_e7: int, lon_e7: int,
                      ed25519_sk: bytes) -> dict:
    body = {
        1: 1,  # v
        2: bundle_id,
        3: account_pk,
        4: content_hash,
        5: created_at,
        6: lat_e7,
        7: lon_e7,
    }
    return {**body, 8: sodium.ed25519_sign(cbor.encode(body), ed25519_sk)}


# --- Wire envelope (SPEC.md §9.5) -------------------------------------------

def build_envelope(etype: int, bundle_id: bytes, seq: int, total: int,
                   hops_taken: int, payload: bytes, transport_sk: bytes) -> dict:
    body = {
        1: etype,
        2: bundle_id,
        3: seq,
        4: total,
        5: hops_taken,
        6: payload,
    }
    return {**body, 7: sodium.ed25519_sign(cbor.encode(body), transport_sk)}
