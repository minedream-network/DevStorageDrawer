# Changelog

All notable changes to DevStorageDrawer are documented here.

## 1.1.0

- Added configurable paid tier upgrades with a durable reconciliation ledger.
- Separated upgrade behavior into `config.yml` and kept each price/route with its tier in `tiers.yml`.
- Consolidated storage controls plus Upgrade/Destroy Java and Bedrock UI into `menus.yml`.
- Preserved MiniMessage colors and supported decorations in Floodgate Bedrock forms.
- Fixed maximum-tier Java upgrade views falling back to an invalid empty multi-action dialog.
- Simplified paid-upgrade recovery: normal success and charged records whose loaded drawer proves the target tier finalize automatically; only genuinely unknown payments require manual review.
- Formatted player-facing quantities, capacities, prices, pages, parcel counts, and drawer displays with thousands separators.
- Removed unused parcel switches while retaining legacy recovery-parcel claiming.
- Advanced the complete configuration set to schema version 2, declared once in `config.yml`.
- Added guarded direct-stack drawer destruction and hardened deposit, withdrawal, sale, and reload paths.
- Added `interaction.shortcut-deposits-enabled` to disable Shift+right-click quick deposits independently.
- Added Shift+right-click with an empty hand as a direct shortcut to the Upgrade UI.
- Marked guarded drawer-break drops with the breaking player as their native thrower so
  drop-to-inventory listeners leave the transaction-owned item entities in the world.

## 1.0.1

- Added optional WorldGuard protection for player drawer withdrawals.
- Added the MIT License.

## 1.0

Initial public release.

- Configurable single-item barrel drawers with tiered capacities
- Paper and Folia support, including hopper automation and comparator output
- Java and Bedrock display support, safe break parcels, recovery, and capacity migration
- Public asynchronous integration API with immutable snapshots and transaction events
