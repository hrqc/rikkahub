import base64
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("decoder", Path(__file__).with_name("decode-dynamic-ui.py"))
decoder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(decoder)


def transport(*records):
    return "\n".join(decoder.PREFIX + base64.b64encode(json.dumps(row).encode()).decode()
                     for row in records).encode()


def row(kind="attempt", **extra):
    return {"schemaVersion": 1, "recordType": kind, "diagnosticRunId": "run-local", **extra}


class DecodeDiagnosticTest(unittest.TestCase):
    def test_page_failure_remains_failure_and_is_not_converted_to_success(self):
        records = decoder.decode_records(transport(
            row(outcome="PAGE_UNSTABLE"),
            row("test_outcome", outcome="PAGE_UNSTABLE", observationSuccesses=0, actionsUsed=0),
        ))
        self.assertEqual("PAGE_UNSTABLE", records[-1]["outcome"])
        self.assertEqual(0, records[-1]["observationSuccesses"])

    def test_runner_ok_without_sampling_is_not_evidence(self):
        with self.assertRaises(ValueError):
            decoder.decode_records(b"OK (1 test)\n")

    def test_truncated_or_duplicate_completion_and_mixed_runs_are_rejected(self):
        for rows in [(row(),), (row("test_outcome"), row("test_outcome")),
                     (row(diagnosticRunId="another"), row("test_outcome"))]:
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                decoder.decode_records(transport(*rows))

    def test_sensitive_content_is_rejected_at_nested_levels(self):
        for key in ("text", "contentDescription", "input_text", "apiKey", "fingerprint"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                decoder.decode_records(transport(row(gaps=[{key: "private"}]), row("test_outcome")))

    def test_transport_and_schema_corruption_are_rejected(self):
        for data in [decoder.PREFIX.encode() + b"###", transport(row("test_outcome", schemaVersion=2)),
                     transport(row("test_outcome", diagnosticRunId=""))]:
            with self.subTest(data=data), self.assertRaises(ValueError):
                decoder.decode_records(data)

    def test_decoded_size_limit_applies_before_writing(self):
        with self.assertRaises(ValueError):
            decoder.decode_records(transport(row(metadata="x" * decoder.MAX_DECODED_BYTES), row("test_outcome")))

    def test_complete_export_budget_includes_schedule_and_completion(self):
        # Individually bounded records can still exceed the total once timeline/outcome are included.
        parts = [row(metadata="x" * 30_000) for _ in range(4)]
        parts += [row("schedule", metadata="x" * 12_000), row("test_outcome")]
        with self.assertRaises(ValueError):
            decoder.decode_records(transport(*parts))

    def test_normalized_unicode_output_is_also_bounded(self):
        # Input escapes may normalize differently; enforce the bytes actually written as well.
        records = decoder.decode_records(transport(row(metadata="结构" * 100), row("test_outcome")))
        self.assertEqual("结构" * 100, records[0]["metadata"])


if __name__ == "__main__":
    unittest.main()
