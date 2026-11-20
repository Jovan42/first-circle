# Banking Service

An in-memory banking library in plain Java 21. It supports opening accounts, deposits, withdrawals, transfers, balance checks and transaction history. Every operation is thread-safe, failures leave no partial state, and requests can be retried safely with an idempotency key.

It is a library (a "service" in the module sense), not a deployable application, so it exposes no HTTP API.

## Quick start

Requirements: **JDK 21+**. Maven is not needed, because the Maven wrapper is included.

```bash
./mvnw test        # build and run all tests
```

## Usage

```java
AccountService bank = new DefaultAccountService(
        new InMemoryAccountRepository(), new InMemoryLedger(), Clock.systemUTC());

Currency eur = Currency.getInstance("EUR");
AccountId alice = bank.openAccount(Money.of("100.00", eur));
AccountId bob   = bank.openAccount(Money.of("0", eur));

bank.deposit(alice, Money.of("25.50", eur));
bank.withdraw(alice, Money.of("10", eur));
TransactionId tx = bank.transfer(alice, bob, Money.of("40", eur));

bank.getBalance(alice);   // 75.50 EUR
bank.getHistory(bob);     // [TRANSFER_IN 40.00 EUR from alice, balanceAfter 40.00 EUR]

// Safe to retry: the second call is rejected and nothing moves twice
IdempotencyKey key = IdempotencyKey.of("payment-7f3a");
bank.transfer(alice, bob, Money.of("5", eur), key);
bank.transfer(alice, bob, Money.of("5", eur), key);   // DuplicateRequestException (carries the original tx id)
```

Failures are reported as unchecked exceptions extending `BankingException`:

| Exception | When |
|---|---|
| `InvalidAmountException` | amount is null, zero/negative, or more precise than the currency allows |
| `CurrencyMismatchException` | amount currency differs from the account currency |
| `InsufficientFundsException` | withdrawal or transfer would overdraw the account |
| `AccountNotFoundException` | unknown account id |
| `SameAccountTransferException` | transfer from an account to itself |
| `DuplicateRequestException` | idempotency key already used by a successful request |

## Project structure

```
io.github.jovan42.banking
├── domain/       Money, AccountId, TransactionId, IdempotencyKey, Account
├── ledger/       LedgerEntry, EntryType, Ledger, InMemoryLedger
├── repository/   AccountRepository, InMemoryAccountRepository
├── service/      AccountService (public API), DefaultAccountService
└── exception/    BankingException and the domain exceptions
```

## Assumptions

- **No overdrafts.** A balance can never go below zero.
- **One currency per account**, taken from the initial deposit. Operations in another currency are rejected; currency exchange is out of scope.
- **Amounts must fit the currency's precision.** For example, `10.001 EUR` and `1.5 JPY` are **rejected, not rounded**, because silently changing an amount the customer asked for is worse than refusing it. No operation here multiplies or divides, so nothing needs rounding.
- **Zero initial deposit is allowed.** Deposits, withdrawals and transfers must be positive.
- **In-memory only.** Data is lost when the process stops.
- **Thread safety:** every public `AccountService` method is safe to call from any number of threads.

## Design decisions

### Money
- `Money` is an immutable value object holding a `BigDecimal` amount and a `Currency`. It is never a `double`, because `0.1 + 0.2 != 0.3` in floating point.
- Amounts are normalised to the currency's scale (2 for EUR, 0 for JPY), so `10`, `10.0` and `10.00` are equal and `equals` is safe to use.
- **`BigDecimal` vs `long` cents:** both are exact. `BigDecimal` is clearer and handles each currency's scale through `Currency`. `long` minor units would be faster in a high-throughput engine, and that switch could be made inside `Money` without changing callers.

### Concurrency
- **One lock per account** (`ReentrantLock`). A balance check and the update that depends on it happen under the same lock, so concurrent withdrawals can't overdraw.
- **Transfers lock both accounts in `AccountId` order.** Without a fixed order, A→B and B→A running at the same time can each hold one lock and wait forever for the other. With a global order, a cycle of waits is impossible.
- **Why not a single global lock?** It would be simpler and correct, but it serialises every operation in the bank. Per-account locks let operations on unrelated accounts run in parallel.
- **Why `ReentrantLock` over `synchronized`?** The lock is an object that transfer code can take for two accounts in order, and it doesn't pin virtual threads on Java 21.
- Storage uses `ConcurrentHashMap` and `ConcurrentLinkedQueue`. Account creation and idempotency-key claims use atomic `putIfAbsent`.

### Failure atomicity without rollback
There is no transaction manager in memory, so each operation is ordered so that **nothing can fail after the first change**:
1. Get the transaction id and timestamp first, because the injected clock could fail.
2. With the locks held, run every check and compute the new balances **without applying them**.
3. Write the ledger entries. A transfer's two sides are written in one `appendAll` call.
4. Apply the balances. This step can no longer fail, because every check already passed under the locks.

A failure at any step leaves balances and the ledger unchanged. It is the same idea as write-ahead logging: record what you're about to do, then do it.

### Ledger
- Every balance change appends an immutable `LedgerEntry` to an **append-only ledger**. Each entry holds the type, amount, balance after the change, timestamp, and, for transfers, the other account.
- A transfer is recorded **double-entry style**: a `TRANSFER_OUT` and a `TRANSFER_IN` that share one `TransactionId`.
- **The balance is cached on the account, and the ledger is the audit trail.** Reading a balance is instant. The alternative, summing the ledger on every read, makes reads O(n). Tests check that every account's ledger adds up exactly to its cached balance.

### Idempotency (safe retries)
- Deposits, withdrawals and transfers have an overload that takes an `IdempotencyKey`. The key is stored on the ledger entries, and **the ledger refuses to record the same key twice**, the in-memory equivalent of a unique constraint on an `idempotency_key` column.
- Because the ledger is written before balances change, a rejected duplicate changes nothing. A request that failed never reached the ledger, so its key stays free and the client can retry.
- A retry gets `DuplicateRequestException` carrying the **original transaction id**, so the client knows the first attempt succeeded.

### Storage behind interfaces
`AccountRepository` and `Ledger` are interfaces with in-memory implementations. The service depends only on the interfaces, so database-backed versions could be added without touching business logic.

### Trade-off: public internals
Splitting by layer means `Account`, `Ledger` and the repositories have to be `public`, so a caller could use `Account` directly and bypass the ledger. For a published library, a `module-info.java` that exports only the API packages would hide them.

## Testing

134 tests (JUnit 5 + AssertJ), run with `./mvnw test`:

- **Unit tests:** `Money` edge cases (precision, scale, `0.1 + 0.2`, mixed currencies), account rules, ledger entry validation, repository behaviour.
- **Service tests:** every operation and the ledger entries it writes, with timestamps from a fixed `Clock`. Every failure case checks that **balances and history are unchanged**.
- **Failure injection:** a ledger and a clock that throw on demand prove that a mid-operation failure leaves no partial state.
- **Concurrency tests:** threads are released together by a `CountDownLatch`, and the tests check invariants rather than a particular thread order:
  - concurrent withdrawals never overdraw
  - concurrent deposits are never lost
  - random concurrent transfers keep the **total money constant**
  - opposite and circular transfers finish without **deadlock** (protected by a timeout)
  - every account's ledger adds up to its balance, step by step, and every transfer has exactly one matching other side
  - concurrent retries with the same key apply exactly once
- The concurrency tests were checked by breaking the code on purpose: removing locks, using a naive lock order, and replacing `putIfAbsent` with a separate check and insert. They fail in each case.

## What I'd add for production

- **Persistence:** a relational database, with both balance updates and both ledger rows in one transaction. Use row locks (`SELECT … FOR UPDATE`) or optimistic locking (a `version` column with retries).
- **Running on several servers:** in-process locks only work in one JVM. Use database locking, or partition accounts so a single writer owns each one.
- **Idempotency keys** with an expiry time (e.g. 24 hours), scoped per client, and possibly a stored response returned to retries.
- **Reliable events** (outbox pattern), e.g. `TransferCompleted` for other systems.
- **Limits and compliance:** daily limits, fraud checks, audit of who performed each operation.
- **Ledger lookup by transaction id**, and reversals recorded as new compensating entries, never by editing history.
- **Observability:** metrics such as lock wait time and failure rates, and structured logs that include transaction ids.

## AI usage

I used **Claude Code** (Anthropic's AI coding assistant) throughout the task, as a pair programmer working step by step under my direction:

- **Planning:** we broke the task into small steps (value types, account, ledger, repository, service, idempotency, concurrency tests, README). I reviewed and committed each step separately.
- **Implementation:** Claude wrote most of the code and tests from my instructions. I reviewed each change, asked for explanations, and pushed back where I disagreed. For example, I asked for `get`-prefixed accessors, removed code that wasn't used yet, and chose the package layout.
- **Design discussions:** several decisions came out of questions I asked during review:
  - Asking "do we need a rollback if a transfer fails halfway?" led to the write-ahead ordering. Claude had first written the ledger after the balances.
  - Claude's first idempotency design was a separate wrapper with its own key store. I thought it was over-engineered and suggested storing the key in the ledger and rejecting duplicates there, which became the final, much simpler design.
- **Verification:** at my request, Claude checked that the concurrency tests actually catch bugs by breaking the code on purpose in scratch copies. This found two weak spots that we fixed: a race test that only caught the bug some of the time, and stress tests that would hang instead of fail on a deadlock.

All design decisions, reviews and commits are mine. AI made the work faster, but I understand and can explain every part of the code.
