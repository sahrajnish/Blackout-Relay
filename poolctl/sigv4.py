"""
AWS Signature Version 4 — query-string presigning (S3-compatible, incl. R2).

Implements the canonical-request / string-to-sign / signing-key derivation from
the AWS S3 SigV4 specification. Used by `poolctl mint` to produce write-only,
prefix-scoped, expiring PUT URLs (SPEC.md §14.1).

No third-party dependencies (hashlib/hmac only).

Reference vector: the S3 "Authenticating Requests (AWS Signature Version 4)"
example — GET test.txt in examplebucket, us-east-1, 20130524T000000Z, 86400 s,
expected signature
  aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404
"""

from __future__ import annotations

import hashlib
import hmac
from urllib.parse import quote

ALGORITHM = "AWS4-HMAC-SHA256"
EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"


def _hmac(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def _signing_key(secret: str, datestamp: str, region: str, service: str) -> bytes:
    k_date = _hmac(("AWS4" + secret).encode("utf-8"), datestamp)
    k_region = _hmac(k_date, region)
    k_service = _hmac(k_region, service)
    return _hmac(k_service, "aws4_request")


def _enc_query(s: str) -> str:
    # Canonical query encoding: encode everything except unreserved characters,
    # including '/' -> %2F.
    return quote(s, safe="-_.~")


def _enc_path(s: str) -> str:
    # Canonical URI encoding: same, but '/' is preserved.
    return quote(s, safe="-_.~/")


def presign(
    method: str,
    host: str,
    path: str,
    access_key: str,
    secret_key: str,
    region: str,
    service: str = "s3",
    expires: int = 604800,        # 7 days (S3 max for SigV4)
    amz_datetime: str | None = None,
    extra_query: dict[str, str] | None = None,
) -> str:
    """Return the canonical query string including X-Amz-Signature.

    `path` is the object path, e.g. "/mybucket/prefix/0000/manifest.cbor".
    """
    import datetime as _dt

    if amz_datetime is None:
        amz_datetime = _dt.datetime.now(_dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    datestamp = amz_datetime[:8]

    credential = f"{access_key}/{datestamp}/{region}/{service}/aws4_request"
    params = {
        "X-Amz-Algorithm": ALGORITHM,
        "X-Amz-Credential": credential,
        "X-Amz-Date": amz_datetime,
        "X-Amz-Expires": str(expires),
        "X-Amz-SignedHeaders": "host",
    }
    if extra_query:
        params.update(extra_query)

    canonical_query = "&".join(
        f"{_enc_query(k)}={_enc_query(v)}" for k, v in sorted(params.items())
    )
    canonical_uri = "/" + _enc_path(path.lstrip("/")) if path.strip("/") else "/"

    canonical_request = "\n".join([
        method.upper(),
        canonical_uri,
        canonical_query,
        f"host:{host}\n",
        "host",
        UNSIGNED_PAYLOAD,
    ])

    string_to_sign = "\n".join([
        ALGORITHM,
        amz_datetime,
        f"{datestamp}/{region}/{service}/aws4_request",
        hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
    ])

    signing_key = _signing_key(secret_key, datestamp, region, service)
    signature = hmac.new(signing_key, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()

    return canonical_query + "&X-Amz-Signature=" + signature


def presigned_url(
    method: str,
    scheme: str,
    host: str,
    path: str,
    access_key: str,
    secret_key: str,
    region: str,
    service: str = "s3",
    expires: int = 604800,
    amz_datetime: str | None = None,
) -> str:
    q = presign(method, host, path, access_key, secret_key, region, service,
                expires, amz_datetime)
    return f"{scheme}://{host}/{path.lstrip('/')}?{q}"


# --- self-test against the published AWS vector ---------------------------

def _selftest() -> None:
    # AWS docs example (GET, query-parameter auth).
    host = "examplebucket.s3.amazonaws.com"
    path = "/test.txt"
    q = presign(
        "GET", host, path,
        access_key="AKIAIOSFODNN7EXAMPLE",
        secret_key="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        region="us-east-1",
        expires=86400,
        amz_datetime="20130524T000000Z",
    )
    sig = q.split("X-Amz-Signature=")[1]
    expected = "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"
    assert sig == expected, f"signature mismatch:\n got {sig}\n want {expected}"
    print(f"SigV4 selftest OK ({sig[:16]}…)")


if __name__ == "__main__":
    _selftest()
