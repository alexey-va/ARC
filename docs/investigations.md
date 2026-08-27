# Trade-hall investigations

`ru.arc.investigation` owns the paid `Ревизорская проба` in the Origin
bank/auction hall. Denizen owns only four fixed, proximity-checked command
bridges and actor gestures:

- `367` Фома: `arc investigation open`;
- `372` Ставр: `arc investigation clue stavr`;
- `373` Прохор: `arc investigation clue prokhor`;
- `374` Гордей: `arc investigation clue gordey`.

## Behavior contract

The player sees their balance, the 100-coin fee, 300-coin reward, 90-second
limit and 20-hour cooldown before confirmation. The fee is withdrawn once,
after a durable case record exists. Two distinct witness statements unlock a
risky verdict; the third removes most ambiguity. A wrong verdict or timeout
ends the paid attempt without a reward. The cooldown begins only after exact
fee balance evidence exists.

Each case combines an independently selected seller, commodity, quantity,
price, witness wording and harmless suspicious detail. The decisive branch is
one of:

- arithmetic error in the public total;
- copied archive total that disagrees with the valid calculation;
- one forged seal field: symbol, wax colour, or registrar initials;
- a clean transaction where every decisive field agrees and the suspicious
  detail is a decoy.

`InvestigationCase.validated()` proves that a generated case has exactly one
fair verdict. The full generated evidence is stored, not regenerated after a
restart.

## State and recovery

`plugins/ARC/data/investigations/*.json` is a bounded, one-file-per-record
`arc-core` `DurableRecordJournal`. It is runtime state and must not be deployed
from Git. The lifecycle is:

`PREPARED -> WITHDRAWAL_STARTED -> ACTIVE -> FAILED | REWARD_STARTED -> COMPLETED`

Any uncertain external result enters `MANUAL_REVIEW`. RedisEconomy calls use a
unique reason containing the investigation UUID and are never retried. Startup
recovery searches exact provider history for that reason and amount. A proven
fee restores the same case (or expires it); a proven reward completes it. A
missing or unavailable history result remains locked for manual review.

## Container and cleanup ownership

- ARC module: case, fee, clues, timer, cooldown, verdict and reward.
- Investigation GUI: pre-payment disclosure, dossier, collected statements and
  final verdict buttons.
- Denizen scene: NPC look, equipment, swing, sound and the fixed command.
- Citizens: identity, skin, authored location and equipment only; no native
  click text on the four investigation actors.

Reload cancels the ARC expiry task and Denizen's existing scene reset restores
navigation margins, fake equipment, pose and scene flags. Player quit requires
no case cleanup because the durable timer continues intentionally.
