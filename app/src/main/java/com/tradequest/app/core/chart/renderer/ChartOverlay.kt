package com.tradequest.app.core.chart.renderer

/**
 * Which line of a trade bracket a chart is drawing (Step 10.3).
 *
 * # Why this is a chart type and not the trading type
 *
 * `core/chart` is a renderer: it draws geometry and must not know what an order or
 * a position is (`docs/architecture.md` rule 3 keeps it free of the feature layer,
 * and the same reasoning applies to the trading domain). The feature layer maps a
 * `TradeOverlay` into these, so the renderer draws three kinds of line without ever
 * having read a `Position`. That also means the renderer can be exercised with a
 * hand-built overlay and no trading engine at all.
 */
enum class OverlayLineKind { ENTRY, STOP, TARGET }

/**
 * One horizontal trade line to draw, in price units (Steps 10.3, 10.4).
 *
 * [isDraggable] is carried so the renderer can draw a grab handle only on lines a
 * drag can actually move. Drawing a handle on a non-draggable line would advertise
 * an interaction that does nothing.
 */
data class OverlayLine(
    val kind: OverlayLineKind,
    val priceUnits: Int,
    val isDraggable: Boolean,
)

/**
 * A trade's bracket: its lines, and which side it is on (Steps 10.3, 10.5).
 *
 * [isPreview] marks the not-yet-submitted sizer bracket, which is drawn with the
 * same geometry but is not a real order. Keeping it in the same type means the
 * shading logic is written once; the flag is what stops the renderer drawing it as
 * though it were live.
 */
data class OverlayGroup(
    val id: String,
    val isBuy: Boolean,
    val lines: List<OverlayLine>,
    val isPreview: Boolean = false,
) {
    val entry: OverlayLine? get() = lines.firstOrNull { it.kind == OverlayLineKind.ENTRY }
    val stop: OverlayLine? get() = lines.firstOrNull { it.kind == OverlayLineKind.STOP }
    val target: OverlayLine? get() = lines.firstOrNull { it.kind == OverlayLineKind.TARGET }
}

/**
 * An entry or exit arrow drawn on the bar where it happened (Step 10.8).
 *
 * # Why an index rather than a timestamp
 *
 * The renderer already maps buffer indices to pixels; a timestamp would have to be
 * searched for on every frame. The feature layer resolves the bar once, when the
 * marker is built, and the renderer stays an array lookup.
 *
 * [isEntry] distinguishes the two arrows so the renderer can point them in opposite
 * directions — an entry points into the trade, an exit out of it.
 */
data class TradeMarker(
    val index: Int,
    val priceUnits: Int,
    val isEntry: Boolean,
    val isBuy: Boolean,
)

/**
 * How prominently a news flag is drawn (Steps 12.3, 12.4).
 *
 * A chart-level enum rather than the news domain's `NewsImpact`, for the same
 * reason [OverlayLineKind] is chart-level: the renderer draws a red flag or an
 * amber flag, and must not have read a `NewsRelease` to do it. The feature layer
 * maps impact to severity, so an unrecognised impact simply produces no flag
 * rather than a compile error in the renderer.
 */
enum class FlagSeverity { HIGH, MEDIUM }

/**
 * A news marker drawn above the bar where a release lands (Steps 12.3, 12.4).
 *
 * # Why an index rather than a timestamp
 *
 * The renderer maps buffer indices to pixels directly; a timestamp would have to
 * be searched for every frame. The feature layer resolves the bar once, when the
 * flag is built.
 *
 * # Why the flag carries a label
 *
 * The plan calls the markers tappable, and a tap has to open something that names
 * the release. Carrying the headline here means the hit test can report what was
 * tapped without a second lookup that could disagree with what was drawn.
 */
data class NewsFlag(
    val index: Int,
    val severity: FlagSeverity,
    val headline: String,
)
