# DevStorageDrawer

DevStorageDrawer is a configurable single-item storage-drawer plugin for Paper and Folia 26.1.2. It uses tagged vanilla barrels as the physical storage mirror, keeps the logical item total in persistent data, and supports vanilla hopper automation without requiring NMS, reflection, ProtocolLib, or an NBT API.

## Requirements

- Java 25
- Paper or Folia 26.1.2
- Optional: backend Floodgate for Bedrock holograms; Geyser may run on a proxy
- Vault plus an economy provider for paid upgrades. `minedream-core-smp` can publish its Core economy through Vault when its economy bridge is in provide mode

Builds use the included Gradle wrapper and target the Paper API as a compile-only dependency.

## Features

- Configurable drawer tiers and capacity based on the stored item's native stack size
- Drawer items use a configurable enchantment glint without adding a real enchantment
- Independent `interaction.shortcut-withdrawals-enabled` and
  `interaction.shortcut-deposits-enabled` switches for disabling outside-GUI shortcuts
  while retaining the full storage and upgrade menus
- Single-item storage with deposits, withdrawals, shift interactions, and inventory-aware transfers
- Paged large-chest storage: right-click any face of the drawer to browse all logical stock, with 45 item slots and a navigation row. Cursor pickup, splitting, shift transfers, drag, hotbar/offhand swaps, double-click collection and slot drops transfer real items; sneak-right-click with an item uses quick deposit while an empty hand opens Upgrade directly
- The menu shows all logical stock while the barrel mirror serves hoppers. It accepts one item identity per drawer, respects logical capacity, and blocks transfers while a recovery/sale/break journal is pending
- Menu navigation icons accept vanilla materials and UniItems custom item keys from installed item providers
- In-place paid tier upgrades from the anvil control: Java dialogs and Bedrock forms share the configurable `redstone-upgrader`-style two-step details/confirmation flow. State, permissions, protection, route and price are rechecked before one economy withdrawal
- Vanilla barrel mirror compatible with hopper automation
- Sell-tool integrations can sell the quoted stock while hoppers keep filling the drawer; newly arrived items remain for the next sale
- Comparator output based on logical capacity
- Java ItemDisplay/TextDisplay rendering and two-line holograms above drawers, visible only to Floodgate players
- Filled drawers drop their contents as normal items in native-sized stacks, including in Creative mode
- Destruction confirmation dialog: confirm once to break the drawer immediately; contents drop first, followed by the drawer block. Confirmation rechecks reach, the owning region, drawer/item identity and the normal block-break protection event. Hopper quantity changes keep the confirmation valid; guarded drops debit the latest stock and block further transfers until completion
- Large breaks drop at most `destroy.stacks-per-tick` stacks per tick (default 16). The barrel retains undelivered stock and pauses transfers until breaking completes. If dropping stops, completed batches remain dropped and the remaining items stay in the barrel
- Persistent state recovery, repair, explicit capacity migration, and journaled transactions
- Folia-aware scheduling and a public API for integrations

Multi-item storage, crafting recipes, locking, voiding, controllers, remotes, and personal security are outside the current v1 scope.

Storage pages use a single open 54-slot window. Normal cursor placement, pickup, splitting, hotbar swaps, shift transfers and dragging run through vanilla container movement. Items remain in the slots where the player put them, and existing hidden stock does not immediately refill a slot the player emptied. A full visible page behaves like a full chest; use the next page or sneak-right-click quick deposit to store more. The arrows change pages; shift-clicking them jumps to the first or last page. The centre indicator shows the current page, total stock and capacity. A completely filled last page provides an empty next page for deposits if logical capacity remains. Views refresh every `storage-menu.refresh-ticks` ticks in `menus.yml` (default 4) and write only changed slots. Changing pages rebuilds that page from the latest total. No full logical inventory is allocated in memory.

Displayed stacks are projections. A guarded journal reserves each native move before vanilla executes it; accounting uses the actual change in item slots after the event returns, including later event cancellation and partial transfers. Rapid consecutive clicks settle the previous completed move before starting the next. Hoppers and sell integrations cannot consume the same stock while a move is unresolved. Uncertain delivery/final persistence retains the journal for reconciliation. Capacity-limited partial cursor placement and protected slot drops use explicit guarded transfers; invalid drags are cancelled without manually consuming the cursor. Closing or disabling settles completed moves and clears the projections without discarding stored stock or the player's real cursor. Stored bundles move as whole items; editing their contents inside the menu is blocked to preserve item identity.

Double-click collection checks the actual projected items against the current drawer identity and stock. A stale view after another player or seller empties the drawer cannot supply extra items. Break batches save a fresh barrel snapshot after clearing or restoring the live mirror, so snapshot updates cannot recreate already dropped stock.

Quick withdrawals retain permanent compact completion records in the receipt ledger before clearing player claim markers. Old `APPLIED` chunk journals cannot make completed receipts deliverable again. Missing receipts remain quarantined for manual verification; repair does not recreate them automatically. A `DELIVERABLE` receipt is delivered automatically only after this server process has verified and cleared its matching drawer journal; if a restart leaves a receipt without that proof, keep it for manual reconciliation rather than infer a debit from a journal-free chunk snapshot. An uncertain playerdata save is also quarantined because it may already have persisted the items and claim marker. Ledger format 2 loads format 1 data and upgrades it on its next write; older plugin builds cannot read format 2. Keep the ledger with the world/playerdata backups. The ledger stops accepting new quick withdrawals at 100,000 records rather than deleting completion evidence; completed records discard their item payloads.

Sneak-right-click quick deposits now record a drawer-side transfer intent before removing items from a player, save the drawer credit, save playerdata, and then release the intent. If either save outcome is uncertain, the remaining journal blocks automatic drawer recovery and records the player ID and operation ID for manual reconciliation. Playerdata and chunk data are separate persistence domains; a hard power loss or storage rollback can still leave an ambiguous transfer, so keep both in the same backup set and verify the player's inventory before resolving a journal.

Refresh uses one delayed player task, and each view coalesces rapid native moves into one pending completion callback. Stock items are rebuilt only for changed slots; footer-only updates do not clone the item grid. Immutable count/journal snapshots share their private template and its serialized bytes. Template decoding uses an LRU cache limited to 128 entries and 2 MiB of serialized keys; templates larger than 64 KiB bypass caching. Cache hits compare the full bytes and return defensive item copies. Counts, capacities and journals are read fresh on every operation, and every required transaction persistence phase is retained.

## Release

The current release line is **1.1.0**. Release jars and their SHA-256 checksums
are distributed through the [GitHub Releases page](https://github.com/Teenkung123/DevStorageDrawer/releases).
Verify the checksum before copying a jar to a production server.

## Build and test

```powershell
.\gradlew.bat clean test
.\gradlew.bat build
```

The bundled plugin jar is `build/libs/DevStorageDrawer-<version>.jar`.
The `-plain.jar` build omits UniItems; install the bundled jar on the server.

## Installation

1. Build the project with the Gradle wrapper.
2. Copy the jar from `build/libs/` into the server's `plugins/` directory.
3. Start or restart the Paper/Folia server.
4. Configure `plugins/DevStorageDrawer/config.yml`, `tiers.yml`, `menus.yml`, and `messages.yml` as needed.

The plugin does not add crafting recipes. Administrators issue configured drawer tiers with `/drawers give`.

## Commands and permissions

| Command | Permission | Purpose |
| --- | --- | --- |
| `/drawers give` | `devstoragedrawer.admin.give` | Give a configured drawer tier |
| `/drawers reload` | `devstoragedrawer.admin.reload` | Reload plugin configuration |
| `/drawers repair` | `devstoragedrawer.admin.repair` | Repair display entities and persisted state |
| `/drawers migrate` | `devstoragedrawer.admin.migrate` | Apply an explicit configured-tier capacity migration |
| `/drawers upgrade pending/resolve` | `devstoragedrawer.admin.upgrade-reconcile` | Inspect and resolve durable paid-upgrade intents |

Players using placed drawers require `devstoragedrawer.use`; purchasing upgrades also requires `devstoragedrawer.upgrade`. A route may add its own permission. The parent permission `devstoragedrawer.admin` grants all administration permissions and defaults to server operators.

## Public API

Third-party integrations should use the contracts under `com.teenkung.devstoragedrawer.api`. The API is published through Bukkit's `ServicesManager` and exposes immutable snapshots, query operations, transfers, and drawer transaction events. See [API.md](API.md) for the integration contract and transaction ownership rules.

## Configuration

- `config.yml` controls global behavior, automation, rendering, and upgrade/destroy runtime settings.
- `tiers.yml` defines drawer tiers, capacities, prices, permissions, and upgrade routes.
- `menus.yml` contains storage-menu controls plus every Upgrade/Destroy Java dialog and Bedrock form.
- `messages.yml` contains user-facing messages.

Bedrock form titles, content, labels, and descriptions in `menus.yml` accept MiniMessage colors
and the supported `bold`, `italic`, and `obfuscated` decorations. Hex colors are mapped to the
nearest Bedrock palette color. Unsupported Java-only decorations are omitted, and each separately
rendered fragment is reset so formatting does not bleed into the next button line.

### Drawer upgrades

The storage menu's anvil opens the current tier and capacity view, followed by a separate payment confirmation. A successful purchase changes the placed drawer's tier and capacity snapshot without moving or recreating its stored items. Pending recovery/sale/break journals block the purchase. A paid purchase writes a durable intent before calling Vault and records the confirmed charge before saving the new tier. If the charge or barrel write is uncertain, the operation remains pending and blocks another upgrade of that drawer. The plugin does not automatically retry, refund, or clear an uncertain payment. Keep `plugins/DevStorageDrawer/upgrade-intents/` with world backups.

Operators with `devstoragedrawer.admin.upgrade-reconcile` can run `/drawers upgrade pending [page]` to inspect operation IDs, status, player, location, route, and cost. Normal paid upgrades are verified and compacted automatically. A loaded barrel at the exact recorded target tier also automatically resolves a `CHARGED` or legacy `COMMITTED` record. Only `PAYMENT_UNKNOWN`, or a known charge whose barrel does not prove the target tier, needs administrator review. Verify both the economy transaction and the placed barrel before running `/drawers upgrade resolve <uuid> <committed|refunded|no-charge>`. `committed` records a verified charge and applied tier; `refunded` records that a possible or confirmed charge was compensated; `no-charge` is accepted only for `PAYMENT_UNKNOWN` after verifying that no debit occurred. Resolution unlocks the drawer and compacts the terminal ledger entry; it does not move money or edit the barrel. Only unresolved intents count toward the 100,000-record safety limit.

Global upgrade behavior lives in `config.yml`, while each tier's route and price live in `tiers.yml`:

```yaml
upgrade:
  enabled: true
  session-seconds: 45
  maximum-distance: 8
```

```yaml
tiers:
  tier_1:
    # item, capacity-stacks, placement-permission...
    upgrade:
      next-tier: tier_2
      cost: 1000
      permission: ""
```

The target must exist and have greater `capacity-stacks`. `cost` may be zero. There is no second
route source or compatibility fallback. All Upgrade and Destroy dialog/form presentation lives in
`menus.yml`. Run `/drawers reload` after editing behavior, routes, prices, menus, or messages.

Release 1.1.0 uses `config-version: 2` once in `config.yml`; that value governs the complete
configuration set. The other YAML files must not declare their own version. This release
intentionally has no legacy configuration fallback. Replace or manually rewrite the old files,
add `menus.yml`, and remove the obsolete `upgrade-menu.yml` and `destroy-menu.yml` before starting
an existing installation.

Drawers can be used from any face by default. For an existing installation, set
`interaction.require-facing-face: false` in its `config.yml` and run `/drawers reload`.
Set it to `true` to restrict interactions to the front face again.

Shortcut withdrawal is disabled by default. `interaction.shortcut-withdrawals-enabled: false`
blocks left-click one-item withdrawal, Shift+left-click stack withdrawal, and attacking a
drawer display. Set `interaction.shortcut-deposits-enabled: false` to also block
Shift+right-click held-stack and matching-inventory quick deposits. Shift+right-click with an
empty hand opens Upgrade directly. Right-click storage GUI access, GUI deposits/withdrawals,
paid upgrades, sell tools, breaking, and Hopper/Automation remain available.
Set it to `true` and run `/drawers reload` to restore the outside-GUI withdrawal shortcuts.

### Floodgate holograms

Players identified by `FloodgateApi.isFloodgatePlayer(UUID)` see two invisible
ArmorStand name tags above each filled drawer: the item name, then its logical
total / capacity. Name tags face the viewer and remain readable from every side.
Java players see the existing face-mounted displays. No hologram entities are
created when the backend Floodgate API is unavailable; Java drawers keep working.

```yaml
bedrock:
  enabled: true
  required: false
  hologram:
    height: 1.35
    line-spacing: 0.25
  armor-stand:
    show-item: false
    show-name: true
    marker: true
    invisible: true
```

`height` positions the top armor stand relative to the drawer's block base
(0.5–4 blocks); `line-spacing` separates the rows (0.1–1 blocks). The actual name
tag placement is handled by the client. The optional helmet item is disabled by
default; Java custom item models require a matching Bedrock resource pack.
Existing configurations with `bedrock.enabled: false` must enable it explicitly.
After installing the updated jar and restarting, `/drawers reload` applies visual
settings to loaded drawers; chunk-load repair covers drawers loaded later.

Install Floodgate on this Paper/Folia backend. If Geyser/Floodgate runs on a proxy,
enable `send-floodgate-data: true` on proxy Floodgate and use matching `key.pem`
files, as described in the [Floodgate API setup](https://geysermc.org/wiki/floodgate/api/#using-the-api-on-backend-servers).
The renderer does not infer Bedrock identity from usernames, UUID patterns, or a
Geyser connection alone.

Existing drawers retain their capacity snapshot. Use the explicit migration command when a configured tier's capacity must be changed safely.

Each tier item has `item.enchantment-glint: true` by default. This applies the native
visual glint to drawer items created by `/drawers give`, the public API, and drawer
break drops without adding enchantments or enchantment lore. Set it to `false` under
a tier and run `/drawers reload` to disable the glint for newly created items.

### Custom menu icons

Set `storage-menu.items.<control>.material` in `menus.yml` to a vanilla material
or a [UniItem provider key](https://github.com/ProjectUnified/UniItem/tree/2.7.1).
Controls are `previous`, `next`, `first` (previous disabled), `last` (next disabled),
`page`, `upgrade`, and `separator`. Their slots and click actions stay fixed.

```yaml
storage-menu:
  refresh-ticks: 4
  items:
    previous:
      material: "nexo:previous_page"
    next:
      material: "itemsadder:my_pack:next_page"
    page:
      material: "mmoitems:MISCELLANEOUS:MENU_ICON"
    separator:
      material: BLACK_STAINED_GLASS_PANE
```

UniItems is bundled in the plugin jar; install the item provider you use (such as
Nexo, ItemsAdder, or MMOItems). Custom model data, textures, and provider metadata
are retained; the menu applies its name and lore from `messages.yml` and displays
one item. Missing or incompatible provider items use the original vanilla icon
and log a warning once per control per reload. Run `/drawers reload` after editing
icons or reloading the provider's items; existing open menus refresh their footer.
Omitted controls keep their vanilla defaults, so existing configs remain valid.

## License

This project is licensed under the [MIT License](LICENSE).
