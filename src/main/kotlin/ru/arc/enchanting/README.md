# Direct enchantment books

ARC gives EliteMobs books the same book-on-equipment gesture as AdvancedEnchantments,
without a confirmation inventory. Books stay provider-owned: EM books only apply
to compatible EM gear, and AE retains its native enchantments, dust, scrolls and
application validation. There is no conversion between the two providers.

New ordinary books have 70–100% success and 1% destruction **conditional on failure**.
EM rolls and persists these values once in INTEGER PDC keys `arc:elite_book_success`
and `arc:elite_book_destroy`. The exact persisted chances appear on the book.
Existing AE success chances are preserved, while destruction is capped at 1%;
zero risk stays zero. AE uses its own configured factory and apply engine. The
ARC adapter refreshes old tooltips and normalizes risk before the native click
handler validates the book. ARC cancels its `EnchantApplyEvent` after native
validation and settles its own independent rolls via the shared inventory helper.
AE 9.24.15 otherwise ignores stored success for application and reverses the
conditional destruction comparison. Native `AEAPI.applyEnchant` still handles the
enchantment and slot accounting; white scrolls and destruction-event vetoes protect
the target on a destructive roll. Native admin/explicit-rate books can retain success
outside the ordinary generation range. A separate EM application fee is removed;
book acquisition prices are unchanged. See the ops economy assessment dated
2026-10-09 for the non-neutral crystal-sink and book-consumption changes.

For EM, `UpgradeSystem.preview` and `upgrade` retain native enchantment validation
and application. ARC also checks Bukkit enchantment applicability and authored
material restrictions. Provider work completes on clones before any consumption.
One target and one book are settled in place only if source inputs still match.
Failure consumes a book and preserves the target; the separate destruction roll
can remove the target. Repeated/stale clicks cannot settle the same inputs twice.

Survival uses the server cursor. Creative uses Paper's `InventoryCreativeEvent`:
the packet proposes a replacement item, so ARC first admits the proposed book to
inventory before deferring application. This protects the stack when Paper clears
the denied creative cursor, including incompatible targets. A legacy book without
persisted odds is refreshed on first contact and requires another gesture so the
player can see the odds before it is consumed.

The old native `/em enchant` command remains a provider entry point; the guild NPC
and player guide route are retired. The direct flow does not consume lucky tickets
or invoke the native purchase/menu/challenge API. Provider updates require a new
compatibility check against the active artifacts.

Inspected artifacts: EliteMobs 10.9.8 SHA-256
`7c01d9fc2375d7f23d3a875f6e63f905672bbb73ab735c2672f747f4d79b2b24`
and AdvancedEnchantments 9.24.15 SHA-256
`110ff13c95c46fb1bbd1604f88ad7cecb19294cb658e0a5f5072b2164e6382e6`.
AE's public book-factory signature differs from compile-time 8.7.4, so its isolated
reflection adapter binds the verified public methods once at module startup.

Both book styles use a short title, leading blank row, effects/purpose, a shared
compatibility/chances block, and one drag-action footer with non-italic roots.
The EM presentation rebuilds canonical book lore without equipment price, binding,
source or retired enchanter instructions. Unrelated provider metadata is preserved.
Focused tests cover custody, stale inputs, probability boundaries, persistent rates
and presentation ownership. Protocol QA plus authoritative server inventory readback
is required for the actual creative and survival gestures; tests alone do not prove it.
