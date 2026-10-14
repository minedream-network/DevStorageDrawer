package com.teenkung.devstoragedrawer.receipt;

/** Durable state of a withdrawal receipt. */
public enum WithdrawalReceiptStatus {
    PREPARED,
    DELIVERABLE,
    /** Permanent delivery evidence; never becomes deliverable again. */
    COMPLETED
}
