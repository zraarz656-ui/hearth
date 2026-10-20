# core.database

Room persistence: market.db (read-heavy historical data) and tradequest.db (mutable player state). Domain models must not be Room entities.

No code lives here yet. Phase 0 establishes the package boundary only;
implementations land in the phase that owns them. See
`docs/architecture.md` for the dependency rules that govern this package.
