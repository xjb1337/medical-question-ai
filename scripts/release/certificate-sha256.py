#!/usr/bin/env python3
"""Read the unique signing certificate from successful apksigner PEM output."""
import base64
import hashlib
import re
import sys


def certificate_sha256(output):
    blocks = re.findall(
        r"-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----",
        output,
        re.DOTALL,
    )
    if not blocks:
        raise ValueError("apksigner returned no PEM signing certificate")
    certificates = {
        base64.b64decode(re.sub(r"\s+", "", block), validate=True)
        for block in blocks
    }
    if len(certificates) != 1 or not next(iter(certificates)):
        raise ValueError("Expected exactly one distinct signing certificate")
    return hashlib.sha256(next(iter(certificates))).hexdigest()


if __name__ == "__main__":
    try:
        print(certificate_sha256(sys.stdin.read()))
    except ValueError as error:
        sys.exit(str(error))
