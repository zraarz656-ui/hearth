package com.tradequest.app.core.trading

import com.tradequest.app.core.accounting.CommissionSchedule
import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.simulation.path.CANDLE_DURATION_MS
import com.tradequest.app.core.simulation.path.MarketPathStep
import com.tradequest.app.core.simulation.path.MarketPathWindow

/** Everything the engine needs that does not change during a replay. */
data class TradingContext(
    val seasonId: String,
    val contract: ContractSpec = ContractSpec.XAUUSD,
    val commission: CommissionSchedule = CommissionSchedule.STANDARD,
    /** Release instants of high-impact events, for spread widening and slippage. */
    val newsReleaseTimesUtcMs: List<Long> = emptyList(),
    /**
     * Fee charged per trading day a position is held, per lot, in micro-dollars.
     *
     * Zero by default: the plan specifies no swap rate, and inventing one would
     * fabricate a cost. The field exists so the value is explicit, so a later
     * phase can set it from data rather than discovering it is missing.
     */
    val swapMicrosPerLotPerDay: Long = 0L,
)

/** A trigger, resolved to the instant it fires and the bid it fills at. */
internal data class TriggerHit(
    val atSimUtcMs: Long,
    val inGap: Boolean,
    /** The bid used for the fill. Differs from the trigger level across a gap. */
    val fillBidUnits: Int,
)

/**
 * The trading execution engine (Steps 5.2-5.13).
 *
 * # How it works
 *
 * [state] is immutable; every operation replaces it. There is no hidden mutable
 * field and no wall-clock dependence, so replaying the same path with the same
 * commands produces the same result on every device. That is the property
 * product rule 2.4 requires, and the property the tests pin.
 *
 * # Chronological processing (Step 5.9)
 *
 * [advanceTo] walks the path **segment by segment**, and within each segment
 * handles things in the plan's order. This is deliberately not a single sweep for
 * fills followed by a sweep for stops. Two reasons:
 *
 *  - A position opened by a pending order can be stopped out inside the same
 *    minute (Step 5.10). A two-pass design would report it open, or would stop
 *    it out against movement that preceded its entry.
 *  - A trailing stop must be ratcheted by price as it happens. If the whole
 *    interval's extremes were computed up front, a stop could be moved by a
 *    price the position had not yet reached, which is future leakage.
 *
 * So the engine walks *forward* through waypoints, and after each waypoint it
 * re-checks whether any stop or target has been hit at that waypoint's price.
 *
 * # What this engine deliberately does not do
 *
 * No margin, no stop-out, no daily loss. Those are Steps 6.5-6.9 and belong to
 * Phase 6. [TradingState.balanceMicros] moves only by realised PnL and
 * commission, which are this phase's responsibility.
 */
class TradingEngine(
    private val window: MarketPathWindow,
    private val context: TradingContext,
    initialState: TradingState,
) {

    var state: TradingState = initialState
        private set

    private val contract: ContractSpec get() = context.contract

    // ---------------------------------------------------------------- commands

    /**
     * Applies [command] and returns the result.
     *
     * A command is refused when it names something that does not exist, when its
     * size is not tradable, or when it is issued before the engine has already
     * processed that instant. That last check is the "no look-back fills" rule: a
     * command cannot be inserted into history the engine has already walked,
     * because the engine would then be filling an order against price movement
     * that happened before the order existed.
     */
    fun submit(command: TradingCommand): CommandResult {
        if (command.atSimUtcMs < state.processedToSimUtcMs) {
            return CommandResult.Rejected(
                RejectionReason.LOOK_BACK_COMMAND,
                "command at ${command.atSimUtcMs} precedes processed time " +
                    "${state.processedToSimUtcMs}",
            )
        }

        return when (command) {
            is TradingCommand.PlaceOrder -> placeOrder(command)
            is TradingCommand.ModifyOrder -> modifyOrder(command)
            is TradingCommand.CancelOrder -> cancelOrder(command)
            is TradingCommand.ModifyPosition -> modifyPosition(command)
            is TradingCommand.ClosePosition -> closePosition(command)
        }
    }

    private fun placeOrder(command: TradingCommand.PlaceOrder): CommandResult {
        if (!contract.isValidLotUnits(command.volumeHundredths)) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_VOLUME,
                "volume ${command.volumeHundredths} is not a valid size for ${contract.symbol}",
            )
        }
        if (command.type.isPending && command.priceUnits == null) {
            return CommandResult.Rejected(
                RejectionReason.MISSING_PRICE,
                "a ${command.type} order requires a price",
            )
        }
        if (!command.type.isPending && command.priceUnits != null) {
            return CommandResult.Rejected(
                RejectionReason.UNEXPECTED_PRICE,
                "a market order does not take a price",
            )
        }
        command.stopLossPips?.let {
            if (it <= 0) return CommandResult.Rejected(
                RejectionReason.INVALID_STOP_DISTANCE, "stop distance must be positive",
            )
        }
        command.takeProfitPips?.let {
            if (it <= 0) return CommandResult.Rejected(
                RejectionReason.INVALID_TARGET_DISTANCE, "target distance must be positive",
            )
        }

        val order = TradeOrder(
            orderId = command.orderId,
            seasonId = context.seasonId,
            placedAtSimUtcMs = command.atSimUtcMs,
            type = command.type,
            side = command.side,
            volumeHundredths = command.volumeHundredths,
            requestedPriceUnits = command.priceUnits,
            stopLossPips = command.stopLossPips,
            takeProfitPips = command.takeProfitPips,
            trailingStop = command.trailingStop,
            breakeven = command.breakeven,
            status = OrderStatus.PENDING,
            resolvedAtSimUtcMs = null,
            rejectionReason = null,
        )

        // A market order fills at once, at the executable price for its side.
        if (command.type == OrderType.MARKET) {
            val quote = quoteAt(command.atSimUtcMs)
                ?: return CommandResult.Rejected(
                    RejectionReason.MARKET_CLOSED,
                    "no price is available at ${command.atSimUtcMs}",
                )
            val before = state
            fillOrder(order, quote, command.atSimUtcMs, inGap = false)
            return CommandResult.Accepted(
                execution = state.executions.drop(before.executions.size).firstOrNull(),
                position = state.positions.drop(before.positions.size).firstOrNull(),
            )
        }

        state = state.copy(orders = state.orders + order)
        return CommandResult.Accepted()
    }

    private fun modifyOrder(command: TradingCommand.ModifyOrder): CommandResult {
        val order = state.orders.firstOrNull { it.orderId == command.orderId }
            ?: return CommandResult.Rejected(RejectionReason.ORDER_NOT_FOUND, command.orderId)
        if (!order.isPending) {
            return CommandResult.Rejected(
                RejectionReason.ORDER_NOT_PENDING,
                "order ${order.orderId} is ${order.status}",
            )
        }

        val updated = order.copy(
            requestedPriceUnits = applyModification(order.requestedPriceUnits, command.priceUnits),
            stopLossPips = applyModification(order.stopLossPips, command.stopLossPips),
            takeProfitPips = applyModification(order.takeProfitPips, command.takeProfitPips),
        )

        if (updated.type.isPending && updated.requestedPriceUnits == null) {
            return CommandResult.Rejected(
                RejectionReason.MISSING_PRICE,
                "a pending order cannot lose its price",
            )
        }

        state = state.copy(
            orders = state.orders.map { if (it.orderId == order.orderId) updated else it },
        )
        return CommandResult.Accepted()
    }

    private fun cancelOrder(command: TradingCommand.CancelOrder): CommandResult {
        val order = state.orders.firstOrNull { it.orderId == command.orderId }
            ?: return CommandResult.Rejected(RejectionReason.ORDER_NOT_FOUND, command.orderId)
        if (!order.isPending) {
            return CommandResult.Rejected(
                RejectionReason.ORDER_NOT_PENDING,
                "order ${order.orderId} is ${order.status}",
            )
        }
        state = state.copy(
            orders = state.orders.map {
                if (it.orderId == order.orderId) {
                    it.copy(
                        status = OrderStatus.CANCELLED,
                        resolvedAtSimUtcMs = command.atSimUtcMs,
                    )
                } else {
                    it
                }
            },
        )
        return CommandResult.Accepted()
    }

    private fun modifyPosition(command: TradingCommand.ModifyPosition): CommandResult {
        val position = state.positions.firstOrNull { it.positionId == command.positionId }
            ?: return CommandResult.Rejected(RejectionReason.POSITION_NOT_FOUND, command.positionId)
        if (!position.isOpen) {
            return CommandResult.Rejected(
                RejectionReason.POSITION_NOT_FOUND,
                "position ${position.positionId} is closed",
            )
        }

        val newStopPips = applyModification(position.stopLossPips, command.stopLossPips)
        newStopPips?.let {
            if (it <= 0) return CommandResult.Rejected(
                RejectionReason.INVALID_STOP_DISTANCE, "stop distance must be positive",
            )
        }
        val newTargetPips = applyModification(position.takeProfitPips, command.takeProfitPips)
        newTargetPips?.let {
            if (it <= 0) return CommandResult.Rejected(
                RejectionReason.INVALID_TARGET_DISTANCE, "target distance must be positive",
            )
        }

        // A manual change to the stop distance resets the effective level: the user
        // has just said where they want it. A trailing stop re-tightens it on the
        // next favourable move.
        //
        // A `Keep` must *not* reset it. Recomputing from `stopLossPips` on a Keep
        // would silently undo every trailing ratchet, moving the stop back to the
        // original distance — the opposite of what the user asked for by leaving
        // the field alone.
        val updated = position.copy(
            stopLossPips = newStopPips,
            takeProfitPips = newTargetPips,
            trailingStop = applyModification(position.trailingStop, command.trailingStop),
            breakeven = applyModification(position.breakeven, command.breakeven),
            activeStopUnits = when (command.stopLossPips) {
                is Modification.Keep -> position.activeStopUnits
                is Modification.Set -> stopLevelFor(
                    position.side, position.openPriceUnits, newStopPips!!, contract,
                )
                Modification.Clear -> null
            },
            targetUnits = when (command.takeProfitPips) {
                is Modification.Keep -> position.targetUnits
                is Modification.Set -> targetLevelFor(
                    position.side, position.openPriceUnits, newTargetPips!!, contract,
                )
                Modification.Clear -> null
            },
            // A newly supplied breakeven rule may fire again.
            breakevenApplied = if (command.breakeven is Modification.Keep) {
                position.breakevenApplied
            } else {
                false
            },
        )

        state = state.copy(
            positions = state.positions.map {
                if (it.positionId == position.positionId) updated else it
            },
        )
        return CommandResult.Accepted(position = updated)
    }

    private fun closePosition(command: TradingCommand.ClosePosition): CommandResult {
        val position = state.positions.firstOrNull { it.positionId == command.positionId }
            ?: return CommandResult.Rejected(RejectionReason.POSITION_NOT_FOUND, command.positionId)
        if (!position.isOpen) {
            return CommandResult.Rejected(
                RejectionReason.POSITION_NOT_FOUND,
                "position ${position.positionId} is already closed",
            )
        }

        val closeVolume = command.volumeHundredths ?: position.remainingVolumeHundredths
        validateCloseVolume(position, closeVolume)?.let { return it }

        val quote = quoteAt(command.atSimUtcMs)
            ?: return CommandResult.Rejected(
                RejectionReason.MARKET_CLOSED,
                "no price is available at ${command.atSimUtcMs}",
            )

        closePartially(
            position = position,
            closeVolumeHundredths = closeVolume,
            quote = quote,
            atSimUtcMs = command.atSimUtcMs,
            reason = CloseReason.MANUAL,
            inGap = false,
        )
        return CommandResult.Accepted()
    }

    /** Validates a full or partial close, returning a rejection or null when it is fine. */
    private fun validateCloseVolume(position: Position, closeVolume: Int): CommandResult.Rejected? {
        val remaining = position.remainingVolumeHundredths
        if (closeVolume <= 0 || closeVolume > remaining) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_VOLUME,
                "cannot close $closeVolume of $remaining",
            )
        }
        if (closeVolume == remaining) return null
        val leftover = remaining - closeVolume
        // A partial close must leave a remainder the contract can still trade.
        // Rounding the remainder would silently change the position's size, so an
        // impossible split is refused and the UI offers 50% or 100% instead
        // (Step 5.12).
        if (!contract.isValidLotUnits(leftover)) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_PARTIAL_CLOSE,
                "closing $closeVolume would leave $leftover, below the minimum",
            )
        }
        return null
    }

    // ----------------------------------------------------------------- advance

    /**
     * Processes everything that happens in `[processedTo, toSimUtcMs)` and returns
     * the events, in order (Steps 5.9, 5.10).
     *
     * The walk is **forward and chronological**:
     *
     * 1. **Gaps** are crossed as single steps, using [GapExecution] (Step 5.11).
     * 2. **Pending orders** are checked first at each instant, so an order that
     *    fills does so before the stop checks that follow see the new position.
     * 3. **Protective rules** (trailing, breakeven) are applied against the price
     *    just reached, so the ratchet only ever sees price that has happened.
     * 4. **Stops and targets** are checked at that same price, so a position can
     *    be opened and stopped out inside one minute.
     *
     * The upper bound is exclusive, so two consecutive advances that meet at an
     * instant cannot process it twice.
     */
    fun advanceTo(toSimUtcMs: Long): List<TradingEvent> {
        val from = state.processedToSimUtcMs
        if (toSimUtcMs <= from) return emptyList()

        val events = mutableListOf<TradingEvent>()
        var cursor = from

        while (cursor < toSimUtcMs) {
            // Truncate the segment at toSimUtcMs, so the trailing partial range is
            // processed rather than skipped. Without this, an advance smaller than
            // the waypoint spacing would never contain a boundary and no stop or
            // target would ever be evaluated.
            val boundary = nextStepBoundaryAtOrAfter(cursor) ?: toSimUtcMs
            val end = minOf(boundary, toSimUtcMs)
            events += processSegment(cursor, end)
            cursor = end
        }

        state = state.copy(processedToSimUtcMs = maxOf(state.processedToSimUtcMs, toSimUtcMs))
        return events
    }

    /**
     * The next instant after [cursor] at which something can change: a waypoint
     * of the containing candle, the start of the next candle, or the end of a gap.
     */
    private fun nextStepBoundaryAtOrAfter(cursor: Long): Long? {
        val index = window.stepIndexContaining(cursor)
        if (index < 0) {
            // Inside a gap or before the window. The next boundary is the start of
            // the next candle, if any.
            val nextIndex = window.stepIndexAtOrAfter(cursor)
            if (nextIndex >= window.steps.size) return null
            return window.steps[nextIndex].path.timestampUtcMs
        }

        val step = window.steps[index]
        val candleStart = step.path.timestampUtcMs
        val elapsed = (cursor - candleStart).toInt()

        for (waypoint in step.path.waypoints) {
            if (waypoint.elapsedMs > elapsed) return candleStart + waypoint.elapsedMs
        }
        return candleStart + CANDLE_DURATION_MS
    }

    /**
     * Processes `[fromUtcMs, toUtcMs)`, where [toUtcMs] is the next waypoint, the
     * next candle boundary, or the end of a gap.
     *
     * # The order, and why it is this order
     *
     * 1. **Gaps** (Step 5.11). No path exists, so only the post-gap price is usable.
     * 2. **Pending orders**, so a resting order fills at the instant its level was
     *    crossed (the plan's event order puts these first).
     * 3. **Stops and targets**, against the stop as it stands *on entry* to the
     *    segment.
     * 4. **Protective rules**, applied to the price reached at the segment's end.
     *
     * Steps 3 and 4 are in this order deliberately. Price is linear between two
     * waypoints, so a segment is monotonic and its extreme is at an endpoint. If
     * the trail were advanced *before* the exit check, a rising segment would move
     * a long's stop up to just under its own end price and then the exit check
     * would find that stop inside the very segment that created it — stopping the
     * position out against a price it had only just reached. Moving the trail
     * afterwards means the new level first applies from the next segment, which is
     * the earliest instant at which it was genuinely in force.
     */
    private fun processSegment(fromUtcMs: Long, toUtcMs: Long): List<TradingEvent> {
        val events = mutableListOf<TradingEvent>()

        // A gap: no path exists, so only the price after it is usable. Stop-like
        // triggers fill at that price; limit-like ones at their own level.
        val gap = window.gapContaining(fromUtcMs)
        if (gap != null) {
            events += applyGapTriggers(gap, fromUtcMs, toUtcMs)
            return events
        }

        events += applyPendingOrderTriggers(fromUtcMs, toUtcMs)
        events += applyExitTriggers(fromUtcMs, toUtcMs)

        // Only now ratchet trailing and breakeven stops, using the price just
        // reached. See the ordering note above.
        events += applyProtectiveRules(fromUtcMs, toUtcMs)

        return events
    }

    /** Fills any pending order whose level is crossed in `[from, to)`. */
    private fun applyPendingOrderTriggers(fromUtcMs: Long, toUtcMs: Long): List<TradingEvent> {
        val events = mutableListOf<TradingEvent>()

        // Repeatedly take the earliest order trigger in this segment. Each fill
        // removes an order from the pending set, so this terminates.
        var guard = 0
        while (++guard <= MAX_SEGMENT_STEPS) {
            val hit = earliestOrderTrigger(fromUtcMs, toUtcMs) ?: break
            val order = state.orders.firstOrNull {
                it.orderId == hit.id && it.isPending
            } ?: break

            val quote = quoteAt(hit.atSimUtcMs) ?: break
            val before = state
            fillOrder(order, quote, hit.atSimUtcMs, inGap = hit.inGap)
            state.executions.drop(before.executions.size).firstOrNull()?.let { execution ->
                events += TradingEvent.OrderFilled(
                    atSimUtcMs = hit.atSimUtcMs,
                    order = state.orders.first { it.orderId == order.orderId },
                    execution = execution,
                    inGap = hit.inGap,
                )
            }
            state.positions.drop(before.positions.size).forEach {
                events += TradingEvent.PositionOpened(hit.atSimUtcMs, it)
            }
        }
        return events
    }

    /** Closes any position whose stop or target is reached in `[from, to)`. */
    private fun applyExitTriggers(fromUtcMs: Long, toUtcMs: Long): List<TradingEvent> {
        val events = mutableListOf<TradingEvent>()

        var guard = 0
        while (++guard <= MAX_SEGMENT_STEPS) {
            val hit = earliestExitTrigger(fromUtcMs, toUtcMs) ?: break
            val position = state.positions.firstOrNull {
                it.positionId == hit.id && it.isOpen
            } ?: break

            val quote = quoteAt(hit.atSimUtcMs) ?: break
            val reason = if (hit.kind == TriggerKind.STOP_LOSS) {
                CloseReason.STOP_LOSS
            } else {
                CloseReason.TAKE_PROFIT
            }
            val closedBefore = state.closedTrades.size
            closePartially(
                position = position,
                closeVolumeHundredths = position.remainingVolumeHundredths,
                quote = quote,
                atSimUtcMs = hit.atSimUtcMs,
                reason = reason,
                inGap = hit.inGap,
            )
            state.closedTrades.drop(closedBefore).firstOrNull()?.let { trade ->
                events += TradingEvent.PositionClosed(hit.atSimUtcMs, position.positionId, trade)
            }
        }
        return events
    }

    /** Fills any trigger whose level is crossed inside a gap. */
    private fun applyGapTriggers(
        gap: com.tradequest.app.core.simulation.path.MarketGap,
        fromUtcMs: Long,
        toUtcMs: Long,
    ): List<TradingEvent> {
        val events = mutableListOf<TradingEvent>()
        val at = maxOf(gap.fromUtcMs, fromUtcMs)
        if (at >= toUtcMs) return events

        val bid = window.priceAt(gap.toUtcMs) ?: return events
        val spread = SpreadModel.spreadUnitsFor(at, context.newsReleaseTimesUtcMs)

        // Pending orders first.
        for (order in state.pendingOrders) {
            val kind = when (order.type) {
                OrderType.LIMIT -> TriggerKind.PENDING_ORDER_LIMIT
                OrderType.STOP -> TriggerKind.PENDING_ORDER_STOP
                OrderType.MARKET -> continue
            }
            if (order.placedAtSimUtcMs > at) continue
            val trigger = TriggerDerivation.forOrder(order, spread, contract) ?: continue
            if (!trigger.isSatisfiedAt(bid)) continue

            val fillBid = GapExecution.fillBidUnits(kind, trigger.thresholdUnits, bid)
            val quote = Quote(fillBid, spread)
            val before = state
            fillOrder(order, quote, at, inGap = true)
            state.executions.drop(before.executions.size).firstOrNull()?.let { execution ->
                events += TradingEvent.OrderFilled(
                    atSimUtcMs = at,
                    order = state.orders.first { it.orderId == order.orderId },
                    execution = execution,
                    inGap = true,
                )
            }
            state.positions.drop(before.positions.size).forEach {
                events += TradingEvent.PositionOpened(at, it)
            }
        }

        // Then stops and targets on whatever is open.
        for (position in state.openPositions.toList()) {
            val stopTrigger = TriggerDerivation.forStop(position, spread)
            val targetTrigger = TriggerDerivation.forTarget(position, spread)

            val stopHit = stopTrigger != null && stopTrigger.isSatisfiedAt(bid)
            val targetHit = targetTrigger != null && targetTrigger.isSatisfiedAt(bid)

            // A stop takes precedence over a target when both are satisfied by the
            // same gap price: the gap is adverse movement, and assuming the target
            // filled first would flatter the result.
            val chosen = when {
                stopHit -> TriggerKind.STOP_LOSS to stopTrigger
                targetHit -> TriggerKind.TAKE_PROFIT to targetTrigger
                else -> null
            } ?: continue

            val (kind, trigger) = chosen
            val fillBid = GapExecution.fillBidUnits(kind, trigger!!.thresholdUnits, bid)
            val quote = Quote(fillBid, spread)
            val reason = if (kind == TriggerKind.STOP_LOSS) {
                CloseReason.STOP_LOSS
            } else {
                CloseReason.TAKE_PROFIT
            }
            val closedBefore = state.closedTrades.size
            closePartially(
                position = position,
                closeVolumeHundredths = position.remainingVolumeHundredths,
                quote = quote,
                atSimUtcMs = at,
                reason = reason,
                inGap = true,
            )
            state.closedTrades.drop(closedBefore).firstOrNull()?.let { trade ->
                events += TradingEvent.PositionClosed(at, position.positionId, trade)
            }
        }

        return events
    }

    // ------------------------------------------------------------ trigger search

    /** The earliest pending-order trigger strictly inside `(from, to)`, or null. */
    private fun earliestOrderTrigger(fromUtcMs: Long, toUtcMs: Long): ResolvedTrigger? {
        var best: ResolvedTrigger? = null
        for (order in state.pendingOrders) {
            val kind = when (order.type) {
                OrderType.LIMIT -> TriggerKind.PENDING_ORDER_LIMIT
                OrderType.STOP -> TriggerKind.PENDING_ORDER_STOP
                OrderType.MARKET -> continue
            }
            val hit = firstTrigger(
                kind = kind,
                id = order.orderId,
                fromUtcMs = fromUtcMs,
                beforeUtcMs = toUtcMs,
                activatedAtUtcMs = order.placedAtSimUtcMs,
                derive = { TriggerDerivation.forOrder(order, it, contract) },
            )
            best = betterOf(best, hit)
        }
        return best
    }

    /** The earliest stop or target trigger strictly inside `(from, to)`, or null. */
    private fun earliestExitTrigger(fromUtcMs: Long, toUtcMs: Long): ResolvedTrigger? {
        var best: ResolvedTrigger? = null
        for (position in state.openPositions) {
            val activatedAt = maxOf(fromUtcMs, position.openedAtSimUtcMs)
            val stop = firstTrigger(
                kind = TriggerKind.STOP_LOSS,
                id = position.positionId,
                fromUtcMs = activatedAt,
                beforeUtcMs = toUtcMs,
                activatedAtUtcMs = activatedAt,
                derive = { TriggerDerivation.forStop(position, it) },
            )
            val target = firstTrigger(
                kind = TriggerKind.TAKE_PROFIT,
                id = position.positionId,
                fromUtcMs = activatedAt,
                beforeUtcMs = toUtcMs,
                activatedAtUtcMs = activatedAt,
                derive = { TriggerDerivation.forTarget(position, it) },
            )
            // A stop wins a tie: the adverse interpretation is the honest one.
            best = betterOf(best, betterOf(stop, target))
        }
        return best
    }

    private fun betterOf(a: ResolvedTrigger?, b: ResolvedTrigger?): ResolvedTrigger? {
        if (b == null) return a
        if (a == null) return b
        return when {
            b.atSimUtcMs < a.atSimUtcMs -> b
            b.atSimUtcMs > a.atSimUtcMs -> a
            b.kind.priority < a.kind.priority -> b
            b.kind.priority > a.kind.priority -> a
            b.id < a.id -> b
            else -> a
        }
    }

    /**
     * The earliest instant in `(fromUtcMs, beforeUtcMs)` at which [derive]'s
     * trigger is satisfied, or null.
     *
     * The search is bounded by the segment, so a trigger cannot be resolved
     * beyond the price the engine has actually walked to. That is what keeps a
     * trailing stop honest.
     */
    private fun firstTrigger(
        kind: TriggerKind,
        id: String,
        fromUtcMs: Long,
        beforeUtcMs: Long,
        activatedAtUtcMs: Long,
        derive: (Int) -> BidTrigger?,
    ): ResolvedTrigger? {
        var cursor = maxOf(fromUtcMs, activatedAtUtcMs)
        while (cursor < beforeUtcMs) {
            val nextChange = SpreadTimeline.nextChangeAfter(
                afterUtcMs = cursor,
                newsReleaseTimesUtcMs = context.newsReleaseTimesUtcMs,
                searchUntilUtcMs = beforeUtcMs,
            )
            val intervalEnd = nextChange ?: beforeUtcMs
            val spread = SpreadModel.spreadUnitsFor(cursor, context.newsReleaseTimesUtcMs)
            val trigger = derive(spread)

            if (trigger != null) {
                val hit = firstSatisfaction(trigger, kind, cursor, intervalEnd)
                if (hit != null) {
                    return ResolvedTrigger(
                        kind = kind,
                        id = id,
                        atSimUtcMs = hit.atSimUtcMs,
                        trigger = trigger,
                        spreadUnits = spread,
                        inGap = hit.inGap,
                        fillBidUnits = hit.fillBidUnits,
                    )
                }
            }

            if (intervalEnd <= cursor) break
            cursor = intervalEnd
        }
        return null
    }

    /**
     * The first instant in `[fromUtcMs, beforeUtcMs)` at which [trigger] is
     * satisfied, or null.
     *
     * 1. **Already satisfied at [fromUtcMs].** A spread widening can do this
     *    without the bid moving, so it is checked first.
     * 2. **Across a gap.** A gap has no path, so it is one step, priced by
     *    [GapExecution].
     * 3. **Inside a candle.** Solved exactly on the path, so a level touched
     *    between two 1 Hz ticks is still found.
     */
    private fun firstSatisfaction(
        trigger: BidTrigger,
        kind: TriggerKind,
        fromUtcMs: Long,
        beforeUtcMs: Long,
    ): TriggerHit? {
        val startBid = window.priceAt(fromUtcMs)
        if (startBid != null && trigger.isSatisfiedAt(startBid)) {
            return TriggerHit(fromUtcMs, inGap = false, fillBidUnits = startBid)
        }

        val startIndex = window.stepIndexContaining(fromUtcMs)
            .let { if (it >= 0) it else window.stepIndexAtOrAfter(fromUtcMs) }

        for (index in startIndex until window.steps.size) {
            val step = window.steps[index]
            val candleStart = step.path.timestampUtcMs
            if (candleStart >= beforeUtcMs) return null

            gapHit(step, trigger, kind, fromUtcMs, beforeUtcMs)?.let { return it }

            val fromElapsed = if (fromUtcMs > candleStart) {
                (fromUtcMs - candleStart).toInt()
            } else {
                0
            }
            val toElapsed = if (beforeUtcMs < candleStart + CANDLE_DURATION_MS) {
                (beforeUtcMs - candleStart).toInt()
            } else {
                CANDLE_DURATION_MS.toInt()
            }

            val elapsed = step.path.firstTouchInRange(fromElapsed, toElapsed, trigger.thresholdUnits)
            if (elapsed != null) {
                val at = candleStart + elapsed
                if (at >= beforeUtcMs) return null
                return TriggerHit(at, inGap = false, fillBidUnits = step.path.priceAt(elapsed))
            }
        }
        return null
    }

    /**
     * Checks the gap preceding [step] for a fill, or null (Step 5.11).
     *
     * The gap has no path, so the only price available is the one after it. A
     * stop-like trigger uses that price; a limit-like trigger uses its own level,
     * because a resting order at that price was genuinely crossed.
     */
    private fun gapHit(
        step: MarketPathStep,
        trigger: BidTrigger,
        kind: TriggerKind,
        fromUtcMs: Long,
        beforeUtcMs: Long,
    ): TriggerHit? {
        val gap = step.precedingGap ?: return null
        if (fromUtcMs > gap.toUtcMs) return null

        val gapEndBid = window.priceAt(gap.toUtcMs) ?: return null
        if (!trigger.isSatisfiedAt(gapEndBid)) return null

        val at = maxOf(gap.fromUtcMs, fromUtcMs)
        if (at >= beforeUtcMs) return null

        return TriggerHit(
            atSimUtcMs = at,
            inGap = true,
            fillBidUnits = GapExecution.fillBidUnits(
                kind = kind,
                triggerLevelUnits = trigger.thresholdUnits,
                gapEndBidUnits = gapEndBid,
            ),
        )
    }

    // ------------------------------------------------------------------- fills

    /** The quote at [utcMs], or null when there is no price there. */
    private fun quoteAt(utcMs: Long): Quote? {
        val bid = window.priceAt(utcMs) ?: return null
        return Quote(bid, SpreadModel.spreadUnitsFor(utcMs, context.newsReleaseTimesUtcMs))
    }

    /**
     * Opens a position for [order] at [utcMs].
     *
     * The entry price is the executable price for the side — ask for a buy, bid
     * for a sell — adjusted by any slippage. Commission is charged on the opening
     * side only; the closing side is charged when the position closes, so a
     * partial close is apportioned rather than charged twice.
     */
    private fun fillOrder(
        order: TradeOrder,
        quote: Quote,
        utcMs: Long,
        inGap: Boolean,
    ) {
        val base = quote.entryPriceUnits(order.side)
        val slip = SlippageModel.slippageUnitsFor(
            simUtcMs = utcMs,
            orderId = order.orderId,
            type = order.type,
            newsReleaseTimesUtcMs = context.newsReleaseTimesUtcMs,
            inGap = inGap,
        )
        val fillPrice = SlippageModel.applyAdverse(base, slip, order.side)

        val positionId = "pos-${order.orderId}"
        val commission = context.commission.perSideMicros(order.volumeHundredths)

        val execution = Execution(
            executionId = "exec-${order.orderId}-open",
            seasonId = context.seasonId,
            orderId = order.orderId,
            positionId = positionId,
            executedAtSimUtcMs = utcMs,
            priceUnits = fillPrice,
            volumeHundredths = order.volumeHundredths,
            commissionMicros = commission,
            slippageUnits = when (order.side) {
                OrderSide.BUY -> slip
                OrderSide.SELL -> -slip
            },
            bidPriceUnits = quote.bidPriceUnits,
            askPriceUnits = quote.askPriceUnits,
        )

        val position = Position(
            positionId = positionId,
            seasonId = context.seasonId,
            openedAtSimUtcMs = utcMs,
            side = order.side,
            volumeHundredths = order.volumeHundredths,
            openPriceUnits = fillPrice,
            stopLossPips = order.stopLossPips,
            takeProfitPips = order.takeProfitPips,
            trailingStop = order.trailingStop,
            breakeven = order.breakeven,
            status = PositionStatus.OPEN,
            closedAtSimUtcMs = null,
            closePriceUnits = null,
            activeStopUnits = order.stopLossPips?.let {
                stopLevelFor(order.side, fillPrice, it, contract)
            },
            targetUnits = order.takeProfitPips?.let {
                targetLevelFor(order.side, fillPrice, it, contract)
            },
            favorableExtremeUnits = fillPrice,
            breakevenApplied = false,
            entryCommissionMicros = commission,
            realizedPnlMicros = 0L,
            realizedVolumeHundredths = 0,
        )

        state = state.copy(
            orders = state.orders.map {
                if (it.orderId == order.orderId) {
                    it.copy(status = OrderStatus.FILLED, resolvedAtSimUtcMs = utcMs)
                } else {
                    it
                }
            },
            positions = state.positions + position,
            executions = state.executions + execution,
            // The opening commission leaves the balance immediately, so a
            // position that never closes still costs something.
            balanceMicros = state.balanceMicros - commission,
        )
    }

    /**
     * Closes [closeVolumeHundredths] of [position] at [utcMs] (Step 5.12).
     *
     * A partial close leaves the position open with a smaller remaining size and
     * records a [ClosedTrade] for the part that closed. The entry commission is
     * apportioned by volume, so the two parts together charge exactly the original
     * entry commission — no more, and no less.
     */
    private fun closePartially(
        position: Position,
        closeVolumeHundredths: Int,
        quote: Quote,
        atSimUtcMs: Long,
        reason: CloseReason,
        inGap: Boolean,
    ) {
        val base = quote.exitPriceUnits(position.side)
        val slip = SlippageModel.slippageUnitsFor(
            simUtcMs = atSimUtcMs,
            orderId = position.positionId,
            // An exit forced by a stop slips; a resting target does not.
            type = if (reason == CloseReason.TAKE_PROFIT) OrderType.LIMIT else OrderType.STOP,
            newsReleaseTimesUtcMs = context.newsReleaseTimesUtcMs,
            inGap = inGap,
        )
        val fillPrice = SlippageModel.applyAdverse(base, slip, position.side)

        val gross = MoneyScale.pnlMicrosForPriceDelta(
            deltaUnits = when (position.side) {
                OrderSide.BUY -> fillPrice - position.openPriceUnits
                OrderSide.SELL -> position.openPriceUnits - fillPrice
            },
            lotUnits = closeVolumeHundredths,
            ouncesPerLot = contract.ouncesPerLot,
        )

        val remainingBefore = position.remainingVolumeHundredths
        // The entry commission for the part being closed, apportioned by volume.
        // This is for *reporting* the round-trip cost of the trade. It is not
        // charged to the balance here, because the entry side was already taken at
        // entry time; charging it again would bill the user twice for the same fill
        // (Step 6.4).
        val entryShare = if (remainingBefore == 0) {
            0L
        } else {
            position.entryCommissionMicros * closeVolumeHundredths / remainingBefore
        }
        val exitCommission = context.commission.perSideMicros(closeVolumeHundredths)
        val reportedCommission = entryShare + exitCommission
        val net = gross - reportedCommission

        val isFullClose = closeVolumeHundredths == remainingBefore
        val newRealizedVolume = position.realizedVolumeHundredths + closeVolumeHundredths

        val closed = ClosedTrade(
            tradeId = "trade-${position.positionId}-${position.realizedVolumeHundredths}",
            seasonId = context.seasonId,
            positionId = position.positionId,
            side = position.side,
            volumeHundredths = closeVolumeHundredths,
            openPriceUnits = position.openPriceUnits,
            closePriceUnits = fillPrice,
            openTimeSimUtcMs = position.openedAtSimUtcMs,
            closeTimeSimUtcMs = atSimUtcMs,
            grossPnlMicros = gross,
            commissionMicros = reportedCommission,
            swapMicros = 0L,
            netPnlMicros = net,
            closeReason = reason,
            realizedRMicros = realizedRMicros(position, fillPrice),
        )

        val execution = Execution(
            executionId = "exec-${position.positionId}-close-$newRealizedVolume",
            seasonId = context.seasonId,
            orderId = position.positionId,
            positionId = position.positionId,
            executedAtSimUtcMs = atSimUtcMs,
            priceUnits = fillPrice,
            volumeHundredths = closeVolumeHundredths,
            commissionMicros = exitCommission,
            slippageUnits = when (position.side) {
                OrderSide.BUY -> slip
                OrderSide.SELL -> -slip
            },
            bidPriceUnits = quote.bidPriceUnits,
            askPriceUnits = quote.askPriceUnits,
        )

        val updatedPosition = position.copy(
            status = if (isFullClose) PositionStatus.CLOSED else PositionStatus.OPEN,
            closedAtSimUtcMs = if (isFullClose) atSimUtcMs else null,
            closePriceUnits = if (isFullClose) fillPrice else null,
            realizedVolumeHundredths = newRealizedVolume,
            realizedPnlMicros = position.realizedPnlMicros + net,
            // The entry commission for the closed part has already left the balance,
            // so what remains attributed to the position is only the still-open part.
            entryCommissionMicros = position.entryCommissionMicros - entryShare,
        )

        state = state.copy(
            positions = state.positions.map {
                if (it.positionId == position.positionId) updatedPosition else it
            },
            executions = state.executions + execution,
            closedTrades = state.closedTrades + closed,
            // Only the exit-side commission is still owed. The entry side was
            // charged when the position opened, so adding `net` here would charge
            // it a second time.
            balanceMicros = state.balanceMicros + gross - exitCommission,
        )
    }

    /**
     * Realised R in millionths, or 0 when there is no stop to define R.
     *
     * R is profit divided by the risk the stop accepted, measured on the exit
     * side so the spread is already accounted for. With no stop there is no
     * defined risk, and reporting a number would be inventing one.
     */
    private fun realizedRMicros(position: Position, exitPriceUnits: Int): Long {
        val stopPips = position.stopLossPips ?: return 0L
        val riskUnits = contract.pipsToUnits(stopPips)
        if (riskUnits == 0) return 0L
        val rewardUnits = when (position.side) {
            OrderSide.BUY -> exitPriceUnits - position.openPriceUnits
            OrderSide.SELL -> position.openPriceUnits - exitPriceUnits
        }
        return rewardUnits.toLong() * 1_000_000L / riskUnits
    }

    // ---------------------------------------------------- protective rules

    /**
     * Applies trailing-stop and breakeven rules over `[fromUtcMs, toUtcMs]`,
     * returning any [TradingEvent.StopMoved] events (Steps 5.3, 5.13).
     *
     * # Why this is a separate pass
     *
     * These rules do not *cause* a fill; they move a level that may cause one
     * later. Evaluating them against the segment just walked, rather than against
     * the whole interval up front, is what stops a trailing stop from being
     * ratcheted by a price the position has not yet reached.
     *
     * # One consistent rule
     *
     * The favourable extreme is measured on the **exit side** — the highest bid
     * for a long, the lowest ask for a short — because that is the price a close
     * would actually get. Using the bid for both sides would overstate a short's
     * progress by the spread.
     */
    fun applyProtectiveRules(fromUtcMs: Long, toUtcMs: Long): List<TradingEvent> {
        if (toUtcMs <= fromUtcMs) return emptyList()
        val events = mutableListOf<TradingEvent>()

        for (position in state.openPositions) {
            val start = maxOf(fromUtcMs, position.openedAtSimUtcMs)
            if (toUtcMs <= start) continue

            val bidRange = window.bidRangeIn(start, toUtcMs) ?: continue
            val spread = SpreadModel.spreadUnitsFor(start, context.newsReleaseTimesUtcMs)

            val bestExitSide = when (position.side) {
                // A long exits at the bid, so the highest bid is its best exit.
                OrderSide.BUY -> bidRange.last
                // A short exits at the ask, and ask = bid + spread, so the lowest
                // ask sits at the lowest bid shifted *up* by the spread.
                OrderSide.SELL -> bidRange.first + spread
            }

            var updated = position.copy(
                favorableExtremeUnits = when (position.side) {
                    OrderSide.BUY -> maxOf(position.favorableExtremeUnits, bestExitSide)
                    OrderSide.SELL -> minOf(position.favorableExtremeUnits, bestExitSide)
                },
            )

            updated = applyBreakeven(updated, events)
            updated = applyTrailing(updated, events)

            if (updated != position) {
                state = state.copy(
                    positions = state.positions.map {
                        if (it.positionId == position.positionId) updated else it
                    },
                )
            }
        }
        return events
    }

    private fun applyBreakeven(position: Position, events: MutableList<TradingEvent>): Position {
        val spec = position.breakeven ?: return position
        if (position.breakevenApplied) return position
        if (favorableMoveUnits(position) < contract.pipsToUnits(spec.triggerPips)) return position

        val lock = contract.pipsToUnits(spec.lockPips)
        val candidate = when (position.side) {
            OrderSide.BUY -> position.openPriceUnits + lock
            OrderSide.SELL -> position.openPriceUnits - lock
        }
        return tightenStop(position, candidate, StopMoveCause.BREAKEVEN, events)
            .copy(breakevenApplied = true)
    }

    private fun applyTrailing(position: Position, events: MutableList<TradingEvent>): Position {
        val spec = position.trailingStop ?: return position
        if (favorableMoveUnits(position) < contract.pipsToUnits(spec.activationPips)) return position

        val distance = contract.pipsToUnits(spec.distancePips)
        val candidate = when (position.side) {
            OrderSide.BUY -> position.favorableExtremeUnits - distance
            OrderSide.SELL -> position.favorableExtremeUnits + distance
        }
        return tightenStop(position, candidate, StopMoveCause.TRAILING, events)
    }

    /** Distance price has moved in the position's favour from entry, on the exit side. */
    private fun favorableMoveUnits(position: Position): Int = when (position.side) {
        OrderSide.BUY -> position.favorableExtremeUnits - position.openPriceUnits
        OrderSide.SELL -> position.openPriceUnits - position.favorableExtremeUnits
    }

    /**
     * Moves the effective stop to [candidateUnits] if that tightens it, and only
     * then. A stop that could loosen would be a way to avoid being stopped out.
     */
    private fun tightenStop(
        position: Position,
        candidateUnits: Int,
        cause: StopMoveCause,
        events: MutableList<TradingEvent>,
    ): Position {
        val current = position.activeStopUnits
        val tightens = when (position.side) {
            OrderSide.BUY -> current == null || candidateUnits > current
            OrderSide.SELL -> current == null || candidateUnits < current
        }
        if (!tightens) return position

        events += TradingEvent.StopMoved(
            atSimUtcMs = state.processedToSimUtcMs,
            positionId = position.positionId,
            fromUnits = current,
            toUnits = candidateUnits,
            cause = cause,
        )
        return position.copy(activeStopUnits = candidateUnits)
    }

    companion object {
        /**
         * A hard stop on the per-segment fill loops.
         *
         * Each iteration must fill or close something, so the loop is bounded by
         * the number of orders and positions. This guard exists so that a future
         * change which fails to consume a trigger produces a loud error instead of
         * a hung replay.
         */
        const val MAX_SEGMENT_STEPS: Int = 10_000
    }
}

/** Applies a [Modification] to a nullable field. */
internal fun <T> applyModification(current: T?, change: Modification<T>): T? = when (change) {
    is Modification.Keep -> current
    is Modification.Set -> change.value
    Modification.Clear -> null
}
