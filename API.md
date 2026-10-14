# DevStorageDrawer API

DevStorageDrawer exposes a stable, Folia-safe contract in
`com.teenkung.devstoragedrawer.api`. Add DevStorageDrawer as a compile-only
dependency and declare it as a hard `depend` (or a `softdepend` when the
integration is optional).

```yaml
depend: [DevStorageDrawer]
```

Resolve the API through Bukkit's service registry. `DevStorageDrawer#getApi()`
is also available as a convenience for a hard dependency.

```java
final DevStorageDrawerApi api = Bukkit.getServicesManager()
        .load(DevStorageDrawerApi.class);
if (api == null) {
    return;
}

final DrawerLocation drawer = DrawerLocation.from(block.getLocation());
api.query(drawer).thenAccept(result -> {
    if (result.status() == DrawerQueryStatus.FOUND) {
        final DrawerSnapshot snapshot = result.snapshot().orElseThrow();
        // Use the immutable logical view; do not access the barrel inventory directly.
    }
});
```

## Folia contract

All location-based operations return `CompletableFuture`s and schedule their
drawer work on the owning region. Never call `join()` or `get()` from a Paper,
Folia, entity, or region scheduler thread. Compose the future instead. API work
and events run on the appropriate drawer or global scheduler, but a continuation
registered after a future has already completed runs on the registering thread.
Always schedule before touching Bukkit state in a continuation.

`DrawerLocation` is the only location handle retained by the API. Do not cache a
`Block`, `Barrel`, or `Location` across an asynchronous callback.

## Transfers

The API always copies `ItemStack` values and never changes the stack or inventory
provided by the calling plugin. It is not a distributed transaction coordinator:
before invoking `deposit` or `withdraw`, create a durable operation/escrow record
in your own plugin. If a crash leaves the outcome unknown, retain that escrow and
resolve it through your own business-level or manual reconciliation; do not
blindly retry or release it. A future API version may add drawer-side operation
deduplication for exactly-once economy or shop workflows.

```java
final DrawerOperationContext context = DrawerOperationContext.forActor(
        this, "market purchase", player.getUniqueId());

api.deposit(drawer, offeredStack, offeredStack.getAmount(), context)
        .thenAccept(result -> {
            if (result.status() == DrawerTransferStatus.APPLIED) {
                // Mark your pre-existing escrow operation complete now.
            }
        });
```

`withdraw` returns exact stack-sized copies in
`DrawerTransferResult.withdrawnItems()`. The drawer debit is journaled and durable
before the future completes. Two plugins cannot share an atomic database commit,
so a caller that cannot consume withdrawn items must compensate with a deposit.
Use `DrawerTransferStatus` rather than inferring a result from a count: it
distinguishes full, empty, mismatched, recovering, corrupt, scheduler-unavailable,
and persistence-failure outcomes.

`reconcile` intentionally runs recovery for a drawer with a pending automation
journal before returning its current query result.

## Events

Subscribe through normal Bukkit listeners:

- `DrawerPreTransactionEvent` is cancellable and fires only for API deposits and withdrawals.
- `DrawerTransactionCompletedEvent` fires after a successful API transfer commits.
- `DrawerContentsChangedEvent` fires after every persisted drawer change, including players,
  automation, recovery, migration, external sales, and API operations.

All events are synchronous on the drawer's owning Folia region. Event payloads are
immutable snapshots or defensive item copies; they intentionally do not expose
PDC state, the real barrel mirror, storage strategies, or recovery journals.

## Tiers and compatibility

`tiers()` returns a status-bearing `DrawerTierQueryResult` with immutable
configured metadata, and `createTierItem(id, amount)` creates tagged tier barrel
items without command permissions.
`DevStorageDrawerApi.VERSION` is the semantic API version. Integrations should
compile against the API package only and must not use `block`, `domain`, `hopper`,
or `persistence` implementation classes. The older sell-wand bridge remains for
binary compatibility but is deprecated because it requires manual Folia ownership.

## Logical stock sales

`DrawerSaleApi` (version `1.0.0`) is a separate optional service for sale tools that already handle a
`PlayerInteractEvent` on the clicked block's owning region. Its methods are
**synchronous and region-confined**, unlike `DevStorageDrawerApi`. Resolve it through
`ServicesManager` only when DevStorageDrawer is enabled, and declare `softdepend`.

Call `probeSaleTarget(player, block, clickedFace)` during the interaction. `NOT_DRAWER` means
the tool may use its ordinary container path; every other status belongs to the
drawer and the tool must cancel the vanilla interaction. `DENIED` covers the drawer's
use permission and configured facing-face requirement. The selling plugin must also
check its own protection hooks before accepting the sale.

Prepare the seller's price and durable sale audit before scheduling the commit back
to the drawer region. Call `withExternalSale(location, expectedProbe, maxAmount,
operationId, callback)` there. It rechecks the exact logical template and requires
the live total to be at least the probed total. Matching hopper input during the
audit wait does not cancel the sale: it sells at most `min(maxAmount, expectedProbe.amount())`
and leaves newly arrived stock in the drawer. A decreased total or changed item still
rejects the quote before payment. No capacity or full-drawer condition is required. It
journals the debit and invokes the synchronous callback with one copied template
and a `long` count. Return `PAID`, `REJECTED` (definitely no payment), or `UNKNOWN`.
Drawer restores a rejected sale before returning, so compensation cannot race a
later hopper fill. Do not schedule payment asynchronously, create lists of stacks,
or price the barrel's proxy inventory. The API is for trusted integrations; only
the probe checks player permission, facing and configured withdrawal protection.

If payment throws, returns `UNKNOWN`, or final persistence cannot be confirmed,
the result is `RECONCILIATION_REQUIRED`. The `GUARDED_EXTERNAL_SALE` journal retains
the operation ID and before/after totals; it blocks another sale, normal transfers
and breaking. Both PREPARED and APPLIED journals survive automatic repair and restart.
Check the matching seller audit and actual economy payment before an administrator
resolves the stored journal. Never automatically retry, refund or clear it through
`/drawers repair`. The plugins do not share an atomic economy transaction.

On a ready drawer, the probe and sale commit each decode the state and inspect the
mirror once. The commit reuses that fresh internal view only within its synchronous
region callback; it never caches stock across the seller's audit wait. Hidden-only
debits reuse the unchanged barrel snapshot until payment, and a rejected hidden-only
debit restores the logical total after verifying the mirror without rebuilding it.
The PREPARED, APPLIED and final persistence boundaries remain intact. Unresolved
external-sale journals are rejected before another mirror scan or recovery attempt.
