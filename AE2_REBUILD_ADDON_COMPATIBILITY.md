# AE2 Rebuild addon compatibility

The optional addon JARs are not bundled and are not hard dependencies. The adapters below use AE2's standard
`IPatternDetails`, `ICraftingProvider`, and `ICraftingMachine` contracts.

## Verified API shapes

- ExtendedAE 1.4.15: extended pattern providers, Extended Molecular Assembler, and Assembler Matrix crafting targets.
- AdvancedAE 1.3.5: `AdvPatternProviderLogic`, `AdvProcessingPattern`, and provider-selected processing targets.

Addon subclasses of AE2's crafting, processing, smithing, and stonecutting pattern types are normalized into the exact
pattern graph. Generic addon providers retain their physical pattern binding. A sealed work command is dispatched once
through the provider, and returned outputs and remainders must exactly match the command before custody advances.

The compatibility path is serialized per grid because these addon machines do not carry AE2 Rebuild's durable
`WorkCommandId`. A crash with an untagged command in flight stays recovery-required and is never automatically reissued.

AdvancedAE's custom crafting CPU remains a long-authoritative CPU implementation. Exact plans bypass that CPU and select
a native AE2 CPU, while AdvancedAE's providers and crafting targets remain usable by the exact command. This prevents an
addon mixin from narrowing an exact plan.
