# core.market

Import, validation, aggregation and read access for historical XAUUSD data. Owns HistoricalCandle and the dataset manifest. Never writes trading state.

No code lives here yet. Phase 0 establishes the package boundary only;
implementations land in the phase that owns them. See
`docs/architecture.md` for the dependency rules that govern this package.
