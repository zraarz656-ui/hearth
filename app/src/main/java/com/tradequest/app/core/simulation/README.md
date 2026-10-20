# core.simulation

Simulation clock, deterministic intraminute market path, replay processor and time warp. Owns the single authoritative price path shared by chart and trading engine.

No code lives here yet. Phase 0 establishes the package boundary only;
implementations land in the phase that owns them. See
`docs/architecture.md` for the dependency rules that govern this package.
