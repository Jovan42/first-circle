package io.github.jovan42.banking.ledger;

/**
 * The kind of balance change a {@link LedgerEntry} records.
 */
public enum EntryType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER_IN,
    TRANSFER_OUT
}
