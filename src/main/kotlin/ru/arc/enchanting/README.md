# EliteMobs book application

ARC adds a left/right book-on-item gesture in the player's inventory for genuine
EliteMobs enchantment books. Vanilla and AdvancedEnchantments books are excluded.
One item and one book move into the existing EM enchantment menu; cancel/close
uses EM's normal input return. The menu shows the resulting item, price and
exclusive outcome probabilities, and accepts an optional native lucky ticket.

ARC cancels only the confirmation click in a menu it opened. EM 10.9.8's native
`ItemEnchantMenuEvents` listener runs at HIGHEST with `ignoreCancelled=true`, so
it cannot also charge the cancelled click. ARC guards repeated/reentrant clicks
and calls the public `EnchantmentAcquisition` API for payment, item custody,
settlement and recovery. It never edits the player's balance itself. Inputs and
the native quote must still match the displayed preview before purchase.

The native random intervals and price are retained. In this ARC flow, an outcome
in the native challenge interval succeeds immediately. The displayed success
probability is therefore `quote.success + quote.challenge`; ordinary failure and
direct destruction probabilities are unchanged. There is no arena transfer or
challenge-defeat penalty. Existing `/em enchant` and other provider-owned entry
points retain their native behavior; the retired guild enchanter and help links
are configured in ruscrafting-ops.

Compatibility was inspected against active EliteMobs 10.9.8 SHA-256
`7c01d9fc2375d7f23d3a875f6e63f905672bbb73ab735c2672f747f4d79b2b24`
and AdvancedEnchantments 9.24.15 SHA-256
`110ff13c95c46fb1bbd1604f88ad7cecb19294cb658e0a5f5072b2164e6382e6`.
Do not replace the acquisition calls with event replay or cancellation of
`WorldInstanceEvent`: the active EM artifact throws on rejected arena launch and
aborts the purchase. Recheck the listener priority and public API on EM updates.

Player copy lives in `modules/enchanting.yml`. The EliteLoot presentation pass
rebuilds provider-issued books from native enchantment levels, generated custom
enchantment rows and authored applicability. It omits the equipment template
(level/prestige, unbound status, resale and source), removes the exact English
and Russian retired enchanter instructions, and groups the scope, effects and
drag action with single blank rows. Native stored-enchantment tooltip rows are
hidden because the styled lore already shows them. Provider PDC, price and
actual binding remain intact; existing books refresh on join/inventory changes.
AE keeps its native application, combining, scrolls and dust; it has no complete
public apply/resume API suitable for a second confirmation screen in 9.24.15.

Focused checks: `EliteBookTransferTest`, `EliteEnchantmentOutcomeTest`, and
`EliteEnchantmentBookPresentationTest`. These prove transfer/error cases,
probability boundaries and presentation ownership; they do not substitute for
observing the native client gesture.
