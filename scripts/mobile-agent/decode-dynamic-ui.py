"""Decode bounded, metadata-only instrumentation records into a local JSONL artifact.

No ADB, network, shell execution, or device permissions. The original log is retained.
"""
from __future__ import annotations

import argparse
import base64
import binascii
import json
from pathlib import Path

PREFIX = "INSTRUMENTATION_STATUS: dynamic_ui_jsonl_b64="
MAX_LOG_BYTES = 2 * 1024 * 1024
MAX_DECODED_BYTES = 128 * 1024
MAX_RECORDS = 512
FORBIDDEN_KEYS = {
    "text", "description", "contentdescription", "content_description", "inputtext",
    "input_text", "password", "eventtext", "event_text", "screenshot", "image_base64",
    "authorization", "api_key", "apikey", "conversationid", "assistantid", "fingerprint",
}


def _check_metadata(value: object) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key.lower() in FORBIDDEN_KEYS:
                raise ValueError("Unexpected content field in diagnostic export")
            _check_metadata(child)
    elif isinstance(value, list):
        for child in value:
            _check_metadata(child)


def decode_records(raw: bytes) -> list[dict]:
    if len(raw) > MAX_LOG_BYTES:
        raise ValueError("Instrumentation log exceeds decode budget")
    records: list[dict] = []
    total = 0
    for line in raw.decode("utf-8-sig", errors="strict").splitlines():
        if not line.startswith(PREFIX):
            continue
        if len(records) >= MAX_RECORDS:
            raise ValueError("Too many diagnostic records")
        encoded = line[len(PREFIX):]
        if len(encoded) > (MAX_DECODED_BYTES * 4 // 3 + 4):
            raise ValueError("Diagnostic record exceeds decode budget")
        try:
            payload = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError) as error:
            raise ValueError("Invalid diagnostic transport encoding") from error
        total += len(payload) + 1  # Include the JSONL newline in the complete transport budget.
        if total > MAX_DECODED_BYTES:
            raise ValueError("Decoded export exceeds budget")
        try:
            record = json.loads(payload.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as error:
            raise ValueError("Invalid diagnostic JSON") from error
        if not isinstance(record, dict) or record.get("schemaVersion") != 1:
            raise ValueError("Unsupported diagnostic schema")
        if not isinstance(record.get("recordType"), str) or not record["recordType"]:
            raise ValueError("Missing diagnostic record type")
        if not isinstance(record.get("diagnosticRunId"), str):
            raise ValueError("Missing diagnostic run identity")
        _check_metadata(record)
        records.append(record)
    if not records:
        raise ValueError("No diagnostic records; a runner OK line is not sampling evidence")
    outcomes = [row for row in records if row["recordType"] == "test_outcome"]
    if len(outcomes) != 1:
        raise ValueError("Missing or duplicate diagnostic completion record")
    run_id = outcomes[0]["diagnosticRunId"]
    if not run_id or any(row["diagnosticRunId"] != run_id for row in records):
        raise ValueError("Mixed or missing diagnostic run identity")
    if sum(len(json.dumps(row, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) + 1
           for row in records) > MAX_DECODED_BYTES:
        raise ValueError("Normalized JSONL export exceeds budget")
    return records


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.log.stat().st_size > MAX_LOG_BYTES:
        raise SystemExit("Instrumentation log exceeds decode budget")
    try:
        records = decode_records(args.log.read_bytes())
    except (ValueError, UnicodeError, RecursionError):
        raise SystemExit("Diagnostic export rejected; inspect the local log without publishing it") from None
    # Exclusive creation prevents replacing earlier evidence or the input log by mistake.
    with args.output.open("x", encoding="utf-8", newline="\n") as destination:
        for record in records:
            destination.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
    outcome = next(row for row in records if row["recordType"] == "test_outcome")
    print(f"Decoded {len(records)} metadata records. "
          f"Observation successes: {outcome.get('observationSuccesses', 'unknown')}; "
          f"diagnostic-only successes: {outcome.get('diagnosticReadSuccesses', 0)}; "
          f"outcome: {outcome.get('outcome', 'unknown')}. This is diagnostic evidence, not business acceptance.")


if __name__ == "__main__":
    main()
