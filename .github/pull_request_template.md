## Summary

<!-- What changed and why. -->

## Critical invariants

- [ ] `LivingDropsEvent` handler stays at `EventPriority.LOWEST` with `receiveCanceled = false`.
- [ ] No force chunk-loading in shell search (`level.hasChunk(...)`-only).
- [ ] No imports of optional-mod classes outside `**/integrations/`.
- [ ] `VANILLA_DROP` cascade branch leaves `LivingDropsEvent` un-cancelled.
- [ ] `gradle.properties` pins unchanged, OR pin bumps documented in README.

(All four are also enforced by the `verify-plan-invariants` job in CI; this checklist is the human-readable mirror.)

## Test plan

- [ ] `gradle build` passes locally.
- [ ] Manual test scenarios relevant to this change (list them below).
- [ ] If the change touches the placement cascade, the affected step's log line was confirmed in-game.

## Notes

<!-- Tradeoffs, deferred work, follow-ups. -->
