"""Lightweight, production-aware transaction processing demo (standard library only)."""
from __future__ import annotations

from dataclasses import dataclass, asdict
from enum import Enum
from typing import Any, Iterable
import json
import logging

logger = logging.getLogger("transaction_processor")


class Status(str, Enum):
    RECEIVED = "RECEIVED"
    PROCESSING = "PROCESSING"
    PROCESSED = "PROCESSED"
    DUPLICATE = "DUPLICATE"
    FAILED = "FAILED"
    RETRY_PENDING = "RETRY_PENDING"
    PENDING = "PENDING"


@dataclass
class TransactionResult:
    transaction_id: str | None
    request_id: str | None
    status: str
    message: str
    attempts: int = 0

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class TransactionProcessor:
    """
    In-memory demo processor.

    Idempotency key: transactionId (the business transaction identity).
    Ordering: records are processed in ascending sequence number within the
    supplied batch; sequence gaps are reported but do not block unrelated work.
    This is a batch-ordering strategy, not a durable cross-batch sequence gate.
    """
    REQUIRED = ("transactionId", "requestId", "sequence", "accountId", "type", "amount")
    TYPES = {"CREDIT", "DEBIT"}

    def __init__(self, max_retries: int = 2, transient_failures: dict[str, int] | None = None):
        if max_retries < 0:
            raise ValueError("max_retries must be >= 0")
        self.max_retries = max_retries
        self.transient_failures = dict(transient_failures or {})
        self.state: dict[str, dict[str, Any]] = {}
        self.request_ids: set[str] = set()
        self.ledger: list[dict[str, Any]] = []
        self.results: list[TransactionResult] = []
        self._failure_attempts: dict[str, int] = {}

    def _validate(self, record: dict[str, Any]) -> str | None:
        for field in self.REQUIRED:
            if field not in record or record[field] is None or record[field] == "":
                return f"Missing mandatory field: {field}"
        if not isinstance(record["transactionId"], str) or not isinstance(record["requestId"], str):
            return "transactionId and requestId must be strings"
        if not isinstance(record["accountId"], str) or not record["accountId"]:
            return "accountId must be a non-empty string"
        if record["type"] not in self.TYPES:
            return "type must be CREDIT or DEBIT"
        if isinstance(record["sequence"], bool) or not isinstance(record["sequence"], int) or record["sequence"] < 1:
            return "sequence must be a positive integer"
        amount = record["amount"]
        if isinstance(amount, bool) or not isinstance(amount, (int, float)) or amount <= 0:
            return "amount must be a positive number"
        return None

    def process_batch(self, records: Iterable[dict[str, Any]]) -> list[TransactionResult]:
        batch_results: list[TransactionResult] = []
        accepted: list[dict[str, Any]] = []

        # Validate and detect duplicates before scheduling valid work.
        for record in records:
            if not isinstance(record, dict):
                result = TransactionResult(None, None, Status.FAILED.value, "Record must be a JSON object")
                batch_results.append(result)
                logger.warning("invalid_record message=%s", result.message)
                continue
            error = self._validate(record)
            txn_id, req_id = record.get("transactionId"), record.get("requestId")
            if error:
                result = TransactionResult(txn_id, req_id, Status.FAILED.value, error)
                batch_results.append(result)
                logger.warning("validation_failed transaction_id=%s request_id=%s reason=%s", txn_id, req_id, error)
                continue

            txn_id = record["transactionId"]
            if txn_id in self.state:
                result = TransactionResult(txn_id, req_id, Status.DUPLICATE.value,
                                           f"Business transaction already known; current state={self.state[txn_id]['status']}")
                batch_results.append(result)
                logger.info("duplicate_detected transaction_id=%s request_id=%s", txn_id, req_id)
                continue

            # A request ID reused for a different transaction is suspicious; don't silently
            # treat it as the same business transaction.
            if req_id in self.request_ids:
                result = TransactionResult(txn_id, req_id, Status.FAILED.value,
                                           "requestId was already used for a different transaction")
                batch_results.append(result)
                logger.warning("request_id_conflict transaction_id=%s request_id=%s", txn_id, req_id)
                continue

            self.state[txn_id] = {"status": Status.RECEIVED.value, "record": dict(record), "attempts": 0}
            self.request_ids.add(req_id)
            accepted.append(dict(record))
            logger.info("transaction_received transaction_id=%s sequence=%s", txn_id, record["sequence"])

        # Sort deterministically. Sequence is an ordering hint within this batch.
        accepted.sort(key=lambda r: (r["sequence"], r["accountId"], r["transactionId"]))
        previous_by_account: dict[str, int] = {}
        for record in accepted:
            account = record["accountId"]
            seq = record["sequence"]
            previous = previous_by_account.get(account)
            if previous is not None and seq > previous + 1:
                logger.warning("sequence_gap account_id=%s missing_sequences=%s-%s", account, previous + 1, seq - 1)
            previous_by_account[account] = seq
            result = self._process_one(record)
            batch_results.append(result)

        self.results.extend(batch_results)
        return batch_results

    def _process_one(self, record: dict[str, Any]) -> TransactionResult:
        txn_id, req_id = record["transactionId"], record["requestId"]
        state = self.state[txn_id]
        state["status"] = Status.PROCESSING.value
        attempts = 0
        while True:
            attempts += 1
            state["attempts"] = attempts
            # Simulated transient fault: this transaction fails the configured number
            # of initial attempts, then succeeds. The counter survives retries in-process.
            remaining = self.transient_failures.get(txn_id, 0)
            already_failed = self._failure_attempts.get(txn_id, 0)
            if already_failed < remaining:
                self._failure_attempts[txn_id] = already_failed + 1
                state["status"] = Status.RETRY_PENDING.value
                logger.warning("transient_failure transaction_id=%s attempt=%s", txn_id, attempts)
                if attempts > self.max_retries:
                    state["status"] = Status.FAILED.value
                    return TransactionResult(txn_id, req_id, Status.FAILED.value,
                                             "Retry limit exhausted after transient failure", attempts)
                continue

            # Exactly one in-memory ledger effect per transaction ID.
            if not state.get("effect_applied", False):
                self.ledger.append({
                    "transactionId": txn_id,
                    "accountId": record["accountId"],
                    "type": record["type"],
                    "amount": record["amount"],
                    "sequence": record["sequence"],
                })
                state["effect_applied"] = True
            state["status"] = Status.PROCESSED.value
            logger.info("transaction_processed transaction_id=%s attempts=%s", txn_id, attempts)
            return TransactionResult(txn_id, req_id, Status.PROCESSED.value, "Processed successfully", attempts)

    def summary(self) -> dict[str, Any]:
        counts = {status.value: 0 for status in Status}
        for result in self.results:
            counts[result.status] = counts.get(result.status, 0) + 1
        # Include current transaction state counts separately from request outcomes.
        current_counts = {status.value: 0 for status in Status}
        for item in self.state.values():
            current_counts[item["status"]] = current_counts.get(item["status"], 0) + 1
        return {
            "request_outcomes": counts,
            "current_transaction_states": current_counts,
            "unique_transactions": len(self.state),
            "ledger_effects": len(self.ledger),
        }


def load_jsonl(path: str) -> list[dict[str, Any]]:
    records = []
    with open(path, "r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as exc:
                records.append({"_malformed_line": line_no, "_parse_error": str(exc)})
    return records


def main() -> None:
    import argparse
    parser = argparse.ArgumentParser(description="Run the background transaction processor demo")
    parser.add_argument("--input", default="sample_input.jsonl", help="Input JSON Lines file")
    parser.add_argument("--max-retries", type=int, default=2)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    records = load_jsonl(args.input)
    # The sample intentionally simulates one transient failure for TXN-2005.
    processor = TransactionProcessor(max_retries=args.max_retries, transient_failures={"TXN-2005": 1})
    results = processor.process_batch(records)
    print(json.dumps({"results": [r.to_dict() for r in results], "summary": processor.summary(),
                      "ledger": processor.ledger}, indent=2))


if __name__ == "__main__":
    main()
