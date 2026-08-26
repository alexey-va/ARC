# ARC permission namespace

Static ARC permissions use lowercase dot-separated segments:
`arc.<module>.<submodule>.<action>`. New static nodes must not contain hyphens
or underscores. Every static node referenced by ARC code or bundled YAML must
also be declared with an explicit default in `src/main/resources/plugin.yml`.

## Renamed nodes

| Former node | Canonical node |
|---|---|
| `arc.admin.givejobsboost` | `arc.jobs.boost.give` |
| `arc.baltop` | `arc.balance.top` |
| `arc.board-announce` | `arc.board.announce` |
| `arc.boost.large` | `arc.jobs.boost.large` |
| `arc.buildertools.*` | `arc.builder.tools.*` |
| `arc.buildings.bypass-cooldown` | `arc.buildings.cooldown.bypass` |
| `arc.bypass-invulnerable` | `arc.join.invulnerability.bypass` |
| `arc.bypass-portal` | `arc.portal.bypass` |
| `arc.chat-notify` | `arc.chat.notify` |
| `arc.command.buildbook` | `arc.build.book.give` |
| `arc.buildings.build` | `arc.build.book.use` |
| `arc.deconstruction*`, `arc.crown` | `arc.builder.tools.*` |
| `arc.eliteloot` | `arc.elite.loot.admin` |
| `arc.give` | `arc.item.give` |
| `arc.hide.*` | `arc.command.hide.*` |
| `arc.items-catalog.*` | `arc.items.catalog.*` |
| `arc.jobsboosts` | `arc.jobs.boost.use` |
| `arc.join-message-gui` | `arc.join.message.gui` |
| `arc.leafdecay.bypass` | `arc.leaf.decay.bypass` |
| `arc.locpool.admin` | `arc.location.pool.admin` |
| `arc.portal.origin-gate` | `arc.portal.origin.gate` |
| `arc.portal.tp-by-other` | `arc.portal.teleport.by.other` |
| `arc.portal.tp-other` | `arc.portal.teleport.other` |
| `arc.pouch` | `arc.pouch.give` |
| `arc.rate-own` | `arc.board.rate.own` |
| `arc.rtp-respawn` | `arc.rtp.respawn` |
| `arc.sound-follow` | `arc.sound.follow` |
| `arc.stocks.prunehistory` | `arc.stocks.history.prune` |
| `arc.stocks.update-images` | `arc.stocks.images.update` |
| `arc.treasure-hunt` | `arc.treasure.hunt.admin` |
| `arc.treasures.admin` | `arc.treasure.pool.admin` |

The former names are not runtime aliases. Deploy the matching LuckPerms desired
state together with the ARC JAR so grants and checks switch as one rollout.
