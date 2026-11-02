import unittest
from transaction_processor import TransactionProcessor, Status

def txn(txn_id="T1", req_id="R1", sequence=1, account="A1", kind="CREDIT", amount=10):
    return {"transactionId": txn_id, "requestId": req_id, "sequence": sequence,
            "accountId": account, "type": kind, "amount": amount}

class ProcessorTests(unittest.TestCase):
    def test_duplicate_business_transaction_has_one_effect(self):
        p = TransactionProcessor()
        out = p.process_batch([txn(), txn(req_id="R2")])
        self.assertEqual({x.request_id: x.status for x in out}, {"R1": "PROCESSED", "R2": "DUPLICATE"})
        self.assertEqual(len(p.ledger), 1)

    def test_out_of_order_batch_is_sorted_by_sequence(self):
        p = TransactionProcessor()
        p.process_batch([txn("T2", "R2", 2), txn("T1", "R1", 1)])
        self.assertEqual([x["transactionId"] for x in p.ledger], ["T1", "T2"])

    def test_invalid_record_isolated(self):
        p = TransactionProcessor()
        out = p.process_batch([txn("BAD", "RB", 1, amount=-1), txn("GOOD", "RG", 2)])
        self.assertEqual(out[0].status, "FAILED")
        self.assertEqual(out[1].status, "PROCESSED")
        self.assertEqual(len(p.ledger), 1)

    def test_retry_then_success_does_not_duplicate_effect(self):
        p = TransactionProcessor(max_retries=2, transient_failures={"T1": 1})
        out = p.process_batch([txn()])
        self.assertEqual(out[0].status, "PROCESSED")
        self.assertEqual(out[0].attempts, 2)
        self.assertEqual(len(p.ledger), 1)

    def test_retry_exhaustion_marks_failed(self):
        p = TransactionProcessor(max_retries=1, transient_failures={"T1": 5})
        out = p.process_batch([txn()])
        self.assertEqual(out[0].status, "FAILED")
        self.assertEqual(out[0].attempts, 2)
        self.assertEqual(len(p.ledger), 0)

    def test_reused_request_id_for_different_transaction_rejected(self):
        p = TransactionProcessor()
        out = p.process_batch([txn("T1", "R1"), txn("T2", "R1", sequence=2)])
        self.assertEqual({x.transaction_id: x.status for x in out}, {"T1": "PROCESSED", "T2": "FAILED"})
        self.assertEqual(len(p.ledger), 1)

if __name__ == "__main__":
    unittest.main()
