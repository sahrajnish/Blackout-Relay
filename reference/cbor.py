"""
Deterministic CBOR (RFC 8949 §4.2.1 "Core Deterministic Encoding").

Normative rules from SPEC.md §27.1:
  - Definite-length items only.
  - Shortest-form integer/length encodings.
  - Map keys sorted by encoded byte length, then bytewise.
  - No floating point, no tags, no indefinite strings, no duplicate keys.
  - A decoder must reject a value that is not byte-identical to its canonical
    re-encoding.

The wire format uses only unsigned/negative integers, byte strings, text
strings, arrays and maps. Floats, tags, and simple values are rejected.
"""

from __future__ import annotations

# Major types
MAJOR_UINT = 0
MAJOR_NINT = 1
MAJOR_BYTES = 2
MAJOR_TEXT = 3
MAJOR_ARRAY = 4
MAJOR_MAP = 5
MAJOR_TAG = 6
MAJOR_SIMPLE = 7

__all__ = ["encode", "decode", "dumps", "loads", "CBORError"]


class CBORError(ValueError):
    pass


def _head(major: int, arg: int) -> bytes:
    """Encode an item head for a major type given the argument value (int)."""
    if arg < 24:
        return bytes([(major << 5) | arg])
    if arg < 0x100:
        return bytes([(major << 5) | 24, arg])
    if arg < 0x10000:
        return bytes([(major << 5) | 25]) + arg.to_bytes(2, "big")
    if arg < 0x100000000:
        return bytes([(major << 5) | 26]) + arg.to_bytes(4, "big")
    if arg < 0x10000000000000000:
        return bytes([(major << 5) | 27]) + arg.to_bytes(8, "big")
    raise CBORError(f"value out of range for CBOR: {arg}")


def _encode_uint(v: int) -> bytes:
    if not isinstance(v, int) or isinstance(v, bool):
        raise CBORError(f"not a uint: {v!r}")
    if v < 0:
        raise CBORError(f"negative value not a uint: {v}")
    return _head(MAJOR_UINT, v)


def _encode_nint(v: int) -> bytes:
    if not isinstance(v, int) or isinstance(v, bool):
        raise CBORError(f"not an int: {v!r}")
    if v >= 0:
        raise CBORError(f"non-negative value not a nint: {v}")
    # v = -1 - n  =>  n = -1 - v
    return _head(MAJOR_NINT, -1 - v)


def _encode_bytes(b: bytes) -> bytes:
    if not isinstance(b, (bytes, bytearray)):
        raise CBORError(f"not bytes: {type(b)!r}")
    return _head(MAJOR_BYTES, len(b)) + bytes(b)


def _encode_text(s: str) -> bytes:
    if not isinstance(s, str):
        raise CBORError(f"not str: {type(s)!r}")
    raw = s.encode("utf-8")
    return _head(MAJOR_TEXT, len(raw)) + raw


def _encode_array(items) -> bytes:
    out = bytearray(_head(MAJOR_ARRAY, len(items)))
    for it in items:
        out += _encode_value(it)
    return bytes(out)


def _encode_map(pairs) -> bytes:
    # pairs: iterable of (key, value). Keys must be int or str.
    encoded_keys = []
    for key, _ in pairs:
        if isinstance(key, bool) or not isinstance(key, (int, str)):
            raise CBORError(f"unsupported map key type: {type(key)!r}")
        if isinstance(key, int) and key >= 0:
            enc = _encode_uint(key)
        elif isinstance(key, int) and key < 0:
            enc = _encode_nint(key)
        else:
            enc = _encode_text(key)
        encoded_keys.append(enc)
    # Sort by encoded byte length, then bytewise (RFC 8949 core deterministic).
    order = sorted(range(len(encoded_keys)),
                   key=lambda i: (len(encoded_keys[i]), encoded_keys[i]))
    out = bytearray(_head(MAJOR_MAP, len(pairs)))
    items = list(pairs)
    for i in order:
        out += encoded_keys[i]
        out += _encode_value(items[i][1])
    return bytes(out)


def _encode_value(v) -> bytes:
    if isinstance(v, bool):
        raise CBORError("booleans/simple values are not used on the wire")
    if isinstance(v, int):
        return _encode_uint(v) if v >= 0 else _encode_nint(v)
    if isinstance(v, (bytes, bytearray)):
        return _encode_bytes(v)
    if isinstance(v, str):
        return _encode_text(v)
    if isinstance(v, (list, tuple)):
        return _encode_array(v)
    if isinstance(v, dict):
        return _encode_map(v.items())
    raise CBORError(f"unsupported value type: {type(v)!r}")


def encode(v) -> bytes:
    """Encode a Python value as canonical CBOR bytes."""
    return _encode_value(v)


dumps = encode


# --- Decoding (strict) ------------------------------------------------------

def _read_arg(data: bytes, pos: int, info: int) -> tuple[int, int]:
    """Read an argument value; returns (arg, new_pos). Rejects non-shortest."""
    if info < 24:
        return info, pos
    if info == 24:
        if pos + 1 > len(data):
            raise CBORError("truncated uint8 argument")
        val = data[pos]
        if val < 24:
            raise CBORError("non-shortest uint8 argument")
        return val, pos + 1
    if info == 25:
        if pos + 2 > len(data):
            raise CBORError("truncated uint16 argument")
        val = int.from_bytes(data[pos:pos + 2], "big")
        if val < 0x100:
            raise CBORError("non-shortest uint16 argument")
        return val, pos + 2
    if info == 26:
        if pos + 4 > len(data):
            raise CBORError("truncated uint32 argument")
        val = int.from_bytes(data[pos:pos + 4], "big")
        if val < 0x10000:
            raise CBORError("non-shortest uint32 argument")
        return val, pos + 4
    if info == 27:
        if pos + 8 > len(data):
            raise CBORError("truncated uint64 argument")
        val = int.from_bytes(data[pos:pos + 8], "big")
        if val < 0x100000000:
            raise CBORError("non-shortest uint64 argument")
        return val, pos + 8
    if info == 31:
        raise CBORError("indefinite-length items are not allowed")
    raise CBORError(f"bad additional info: {info}")


def _decode_value(data: bytes, pos: int) -> tuple[object, int]:
    if pos >= len(data):
        raise CBORError("unexpected end of input")
    head = data[pos]
    major = head >> 5
    info = head & 0x1F
    pos += 1

    if major == MAJOR_UINT:
        arg, pos = _read_arg(data, pos, info)
        return arg, pos
    if major == MAJOR_NINT:
        arg, pos = _read_arg(data, pos, info)
        return -1 - arg, pos
    if major == MAJOR_BYTES:
        length, pos = _read_arg(data, pos, info)
        if pos + length > len(data):
            raise CBORError("truncated byte string")
        return bytes(data[pos:pos + length]), pos + length
    if major == MAJOR_TEXT:
        length, pos = _read_arg(data, pos, info)
        if pos + length > len(data):
            raise CBORError("truncated text string")
        raw = data[pos:pos + length]
        try:
            return raw.decode("utf-8"), pos + length
        except UnicodeDecodeError as e:
            raise CBORError("invalid UTF-8 in text string") from e
    if major == MAJOR_ARRAY:
        count, pos = _read_arg(data, pos, info)
        out = []
        for _ in range(count):
            item, pos = _decode_value(data, pos)
            out.append(item)
        return out, pos
    if major == MAJOR_MAP:
        count, pos = _read_arg(data, pos, info)
        out = {}
        prev_key_enc = None
        for _ in range(count):
            key, pos = _decode_value(data, pos)
            # Recompute canonical key encoding to enforce deterministic order.
            key_enc = _encode_value(key)
            if prev_key_enc is not None:
                if (len(key_enc), key_enc) <= (len(prev_key_enc), prev_key_enc):
                    raise CBORError("map keys out of deterministic order")
            prev_key_enc = key_enc
            if key in out:
                raise CBORError("duplicate map key")
            val, pos = _decode_value(data, pos)
            out[key] = val
        return out, pos
    if major == MAJOR_TAG:
        raise CBORError("tags are not allowed")
    if major == MAJOR_SIMPLE:
        raise CBORError("floats/simple values are not allowed")
    raise CBORError(f"unsupported major type: {major}")


def decode(data: bytes) -> object:
    """Decode canonical CBOR bytes; reject anything non-deterministic."""
    value, pos = _decode_value(data, 0)
    if pos != len(data):
        raise CBORError("trailing bytes after CBOR value")
    # Canonical round-trip check.
    if encode(value) != data:
        raise CBORError("value is not byte-identical to its canonical encoding")
    return value


loads = decode
