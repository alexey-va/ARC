# Investment market retirement

The owner closed the complete investment market on 2026-09-07. ARC no longer
loads its configuration, registers its commands or menus, subscribes to price
history, schedules dividends, or contacts any market provider. Finnhub, Polygon,
CoinGecko, Yahoo and Investing clients and stock-only Jsoup/Jetty libraries are
removed. Old runtime stock configuration cannot re-enable the deleted module.

No settlement, account reset, Redis deletion, or historical ledger rewrite is
part of this change. Redis `arc.stock_players`, `arc.stocks` and historical files
remain untouched. Ops reports the retired liability as incomplete/unknown,
never zero. Historical ledger source/action labels remain readable.

Read-only snapshot before retirement (2026-09-07, 1788788973682): 93 accounts,
3 long positions; trading balance 1,303,272.122526142 Vault; principal 92,307.53;
exposure 923,075.3; unrealized P/L 460,389.7; positive redeemable liability
1,856,083.672526142. These are different measures, not additive cash. This
snapshot is evidence, not a settlement instruction or a current price quote.

Effective policy before: 0.5% commission, leverage at most 10 for new orders,
principal limit 100,000 and exposure limit 1,000,000 Vault. Daily dividend rate
0.0002 (configured 0.02, clamped), next liability 27.6693 Vault. With unchanged
positions/prices, the illustration in `stock-market-retirement.json` removes
193.6851 Vault of new dividend liability per network week. Price-driven profit
and commission turnover are unknown; no guessed total mint/burn is reported.
After activation, new orders, withdrawals, dividends and price-driven automatic
closures cannot occur. No new arbitrage, stacking, reward, item, XP or token
path is added; accumulation/progression prices are not applicable to a removed
feature. EconomyShopGUI BUY/SELL and the planned contract transition are unchanged.

Removal takes effect only when the new JAR is loaded. Source publication and
on-disk delivery must not be reported as runtime activation.

Verification: 343 focused JVM tests passed with no failures or skips (commands,
module registry, Redis lifecycle, menu contracts, help center and configuration).
The built JAR contains no stock classes, bundled stock configuration, investment
command or stock-only libraries. The real-Paper command-tree scenario is added;
local execution was blocked before readiness by macOS native-library loading
(JLine, then JNA hardware reporting), so it is not claimed as passing. The full
JVM attempt also stalled in an unrelated ItemsAdder resource-pack shell test.
A task-local temporary directory was required for the focused MockBukkit tests.

GitHub Actions [run 34134795730](https://github.com/alexey-va/ARC/actions/runs/34134795730)
passed on source `0081907`: the complete JVM/package job, MySQL integration,
and both Paper profiles. The new market-retirement command-tree scenario passed
on real Paper 26.1.2, resolving the platform-verification gap left by the local
macOS native-loader failure. Final local integration verification passed 356
focused tests with no failures, errors or skips.
