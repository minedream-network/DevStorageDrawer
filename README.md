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
- Single-item storage with deposits, withdrawals, shift interactions, and inventory-aware transfers
- Paged large-chest storage: right-click any face of the drawer to browse all logical stock, with 45 item slots and a navigation row. Cursor pickup, splitting, shift transfers, drag, hotbar/offhand swaps, double-click collection and slot drops transfer real items; sneak-right-click keeps the quick-deposit shortcut
- The menu shows all logical stock while the barrel mirror serves hoppers. It accepts one item identity per drawer, respects logical capacity, and blocks transfers while a recovery/sale/break journal is pending
- Menu navigation icons accept vanilla materials and UniItems custom item keys from installed item providers
- In-place paid tier upgrades from the anvil control: Java dialogs and Bedrock forms share the configurable `redstone-upgrader`-style two-step details/confirmation flow. State, permissions, protection, route and price are rechecked before one economy withdrawal
- Vanilla barrel mirror compatible with hopper automation
- Sell-tool integrations can sell the quoted stock while hoppers keep filling the drawer; newly arrived items remain for the next sale
- Comparator output based on logical capacity
- Java ItemDisplay/TextDisplay rendering and two-line holograms above drawers, visible only to Floodgate players
- Filled drawers drop their contents as normal items in native-sized stacks, including in Creative mode
- Destruction confirmation dialog: confirm once to break the drawer immediately; contents drop first, followed by the drawer block. Confirmation rechecks reach, the owning region, drawer/item identity and the normal block-break protection event. Hopper quantity changes keep the confirmation valid; guarded drops debit the latest stock and block further transfers until completion
- Large breaks drop at most `breaking.stacks-per-tick` stacks per tick (default 16). The barrel retains undelivered stock and pauses transfers until breaking completes. If dropping stops, completed batches remain dropped and the remaining items stay in the barrel
- Persistent state recovery, repair, explicit capacity migration, and journaled transactions
- Folia-aware scheduling and a public API for integrations

Multi-item storage, crafting recipes, locking, voiding, controllers, remotes, and personal security are outside the current v1 scope.

Storage pages use a single open 54-slot window. Normal cursor placement, pickup, splitting, hotbar swaps, shift transfers and dragging run through vanilla container movement. Items remain in the slots where the player put them, and existing hidden stock does not immediately refill a slot the player emptied. A full visible page behaves like a full chest; use the next page or sneak-right-click quick deposit to store more. The arrows change pages; shift-clicking them jumps to the first or last page. The centre indicator shows the current page, total stock and capacity. A completely filled last page provides an empty next page for deposits if logical capacity remains. Views refresh every `storage-menu.refresh-ticks` ticks (default 4) and write only changed slots. Changing pages rebuilds that page from the latest total. No full logical inventory is allocated in memory.

Displayed stacks are projections. A guarded journal reserves each native move before vanilla executes it; accounting uses the actual change in item slots after the event returns, including later event cancellation and partial transfers. Rapid consecutive clicks settle the previous completed move before starting the next. Hoppers and sell integrations cannot consume the same stock while a move is unresolved. Uncertain delivery/final persistence retains the journal for reconciliation. Capacity-limited partial cursor placement and protected slot drops use explicit guarded transfers; invalid drags are cancelled without manually consuming the cursor. Closing or disabling settles completed moves and clears the projections without discarding stored stock or the player's real cursor. Stored bundles move as whole items; editing their contents inside the menu is blocked to preserve item identity.

Double-click collection checks the actual projected items against the current drawer identity and stock. A stale view after another player or seller empties the drawer cannot supply extra items. Break batches save a fresh barrel snapshot after clearing or restoring the live mirror, so snapshot updates cannot recreate already dropped stock.

Quick withdrawals retain permanent compact completion records in the receipt ledger before clearing player claim markers. Old `APPLIED` chunk journals cannot make completed receipts deliverable again. Missing receipts remain quarantined for manual verification; repair does not recreate them automatically. Ledger format 2 loads format 1 data and upgrades it on its next write; older plugin builds cannot read format 2. Keep the ledger with the world/playerdata backups. The ledger stops accepting new quick withdrawals at 100,000 records rather than deleting completion evidence; completed records discard their item payloads.

Refresh uses one delayed player task, and each view coalesces rapid native moves into one pending completion callback. Stock items are rebuilt only for changed slots; footer-only updates do not clone the item grid. Immutable count/journal snapshots share their private template and its serialized bytes. Template decoding uses an LRU cache limited to 128 entries and 2 MiB of serialized keys; templates larger than 64 KiB bypass caching. Cache hits compare the full bytes and return defensive item copies. Counts, capacities and journals are read fresh on every operation, and every required transaction persistence phase is retained.

## Release

The current release line is **1.0.1**. Release jars and their SHA-256 checksums
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
4. Configure `plugins/DevStorageDrawer/config.yml`, `tiers.yml`, `upgrade-menu.yml`, and `messages.yml` as needed.

The plugin does not add crafting recipes. Administrators issue configured drawer tiers with `/drawers give`.

## Commands and permissions

| Command | Permission | Purpose |
| --- | --- | --- |
| `/drawers give` | `devstoragedrawer.admin.give` | Give a configured drawer tier |
| `/drawers reload` | `devstoragedrawer.admin.reload` | Reload plugin configuration |
| `/drawers repair` | `devstoragedrawer.admin.repair` | Repair display entities and persisted state |
| `/drawers migrate` | `devstoragedrawer.admin.migrate` | Apply an explicit configured-tier capacity migration |

Players using placed drawers require `devstoragedrawer.use`; purchasing upgrades also requires `devstoragedrawer.upgrade`. A route may add its own permission. The parent permission `devstoragedrawer.admin` grants all administration permissions and defaults to server operators.

## Public API

Third-party integrations should use the contracts under `com.teenkung.devstoragedrawer.api`. The API is published through Bukkit's `ServicesManager` and exposes immutable snapshots, query operations, transfers, and drawer transaction events. See [API.md](API.md) for the integration contract and transaction ownership rules.

## Configuration

- `config.yml` controls general behavior, automation, rendering, Bedrock handling, and diagnostics.
- `tiers.yml` defines the admin-issued drawer tiers and their stack capacities.
- `upgrade-menu.yml` controls both nested Java dialogs and Bedrock forms using the same `dialogs.main-menu.dialog` / `dialogs.upgrade-confirm.dialog` shape as Core's `redstone-upgrader`.
- `messages.yml` contains user-facing messages.

### Drawer upgrades

The storage menu's anvil opens the current tier and capacity view, followed by a separate payment confirmation. A successful purchase changes the placed drawer's tier and capacity snapshot without moving or recreating its stored items. Pending recovery/sale/break journals block the purchase. If persistence fails after a confirmed withdrawal, the plugin attempts one Vault refund; an uncertain economy outcome is logged and is never retried automatically.

Upgrade paths normally live with their source tier:

```yaml
tiers:
  tier_1:
    capacity-stacks: 32
    upgrade:
      next-tier: tier_2
      cost: 1000
      permission: ""
```

The target must exist and have greater `capacity-stacks`. `cost` may be zero. Existing installations whose `tiers.yml` has no upgrade blocks use matching fallback routes under `config.yml` (`upgrade.routes`) for the bundled `tier_1` and `tier_2`; a tier-local route takes precedence. Run `/drawers reload` after editing the route, price, menu, or messages.

Drawers can be used from any face by default. For an existing installation, set
`interaction.require-facing-face: false` in its `config.yml` and run `/drawers reload`.
Set it to `true` to restrict interactions to the front face again.

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

### Custom menu icons

Set `storage-menu.items.<control>.material` in `config.yml` to a vanilla material
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
