package com.tradequest.app.core.replay

import com.tradequest.app.core.trading.BreakevenSpec
import com.tradequest.app.core.trading.CloseReason
import com.tradequest.app.core.trading.ClosedTrade
import com.tradequest.app.core.trading.Execution
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.OrderStatus
import com.tradequest.app.core.trading.OrderType
import com.tradequest.app.core.trading.Position
import com.tradequest.app.core.trading.PositionStatus
import com.tradequest.app.core.trading.RiskFailureReason
import com.tradequest.app.core.trading.TradeOrder
import com.tradequest.app.core.trading.TradingState
import com.tradequest.app.core.trading.TrailingStopSpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Thrown when a persisted replay state cannot be read back.
 *
 * A hard failure rather than a fallback: a state that is half-understood would
 * resume a season with the wrong balance or the wrong open positions, and every
 * later decision would inherit the error. Refusing to start is the only safe
 * response, and the caller can offer the user a fresh season instead.
 */
class ReplayStateFormatException(message: String) : IllegalStateException(message)

/**
 * The persisted shape of a replay's resumable state (Steps 7.3-7.4).
 *
 * # Why this is a separate set of types rather than `@Serializable` domain models
 *
 * `docs/architecture.md` rule 5 keeps persistence shapes and domain models
 * apart, and a stored format is the clearest case for it: this is a contract
 * with *past versions of the app*. A domain rename must not silently change what
 * an already-written row means, and a domain field added later must not appear
 * in an old blob. Keeping the shapes separate means the on-disk format changes
 * only when [ReplayStateCodec.SCHEMA_VERSION] changes, which is a deliberate act.
 *
 * Enums are stored as names rather than ordinals. An ordinal would re-map every
 * value the moment someone reorders an enum, which is a silent corruption.
 */
@Serializable
internal data class TrailingStopDto(val distancePips: Int, val activationPips: Int)

@Serializable
internal data class BreakevenDto(val triggerPips: Int, val lockPips: Int)

@Serializable
internal data class OrderDto(
    val orderId: String,
    val seasonId: String,
    val placedAtSimUtcMs: Long,
    val type: String,
    val side: String,
    val volumeHundredths: Int,
    val requestedPriceUnits: Int?,
    val stopLossPips: Int?,
    val takeProfitPips: Int?,
    val trailingStop: TrailingStopDto?,
    val breakeven: BreakevenDto?,
    val status: String,
    val resolvedAtSimUtcMs: Long?,
    val rejectionReason: String?,
)

@Serializable
internal data class PositionDto(
    val positionId: String,
    val seasonId: String,
    val openedAtSimUtcMs: Long,
    val side: String,
    val volumeHundredths: Int,
    val openPriceUnits: Int,
    val stopLossPips: Int?,
    val takeProfitPips: Int?,
    val trailingStop: TrailingStopDto?,
    val breakeven: BreakevenDto?,
    val status: String,
    val closedAtSimUtcMs: Long?,
    val closePriceUnits: Int?,
    val activeStopUnits: Int?,
    val targetUnits: Int?,
    val favorableExtremeUnits: Int,
    val breakevenApplied: Boolean,
    val entryCommissionMicros: Long,
    val realizedPnlMicros: Long,
    val realizedVolumeHundredths: Int,
)

@Serializable
internal data class ExecutionDto(
    val executionId: String,
    val seasonId: String,
    val orderId: String,
    val positionId: String?,
    val executedAtSimUtcMs: Long,
    val priceUnits: Int,
    val volumeHundredths: Int,
    val commissionMicros: Long,
    val slippageUnits: Int,
    val bidPriceUnits: Int,
    val askPriceUnits: Int,
)

@Serializable
internal data class ClosedTradeDto(
    val tradeId: String,
    val seasonId: String,
    val positionId: String,
    val side: String,
    val volumeHundredths: Int,
    val openPriceUnits: Int,
    val closePriceUnits: Int,
    val openTimeSimUtcMs: Long,
    val closeTimeSimUtcMs: Long,
    val grossPnlMicros: Long,
    val commissionMicros: Long,
    val swapMicros: Long,
    val netPnlMicros: Long,
    val closeReason: String,
    val realizedRMicros: Long,
)

/** The whole resumable state of one season's replay (Step 7.3). */
@Serializable
internal data class ReplayStateDto(
    val schemaVersion: Int,
    val seasonId: String,
    val balanceMicros: Long,
    val processedToSimUtcMs: Long,
    val dayStartEquityMicros: Long,
    val dayEpochDay: Long,
    val dailyLossStrikes: Int,
    val dayLocked: Boolean,
    val challengeFailed: Boolean,
    val failureReason: String?,
    val marginWarningActive: Boolean,
    val orders: List<OrderDto>,
    val positions: List<PositionDto>,
    val executions: List<ExecutionDto>,
    val closedTrades: List<ClosedTradeDto>,
)

/**
 * Reads and writes [TradingState] as text (Steps 7.3-7.4).
 *
 * # What is persisted, and why this is enough to resume
 *
 * The engine's state is the whole of what a replay owes the future: the balance,
 * every order and position in every state, the fills and closed trades that back
 * history, the processed-time cursor, and the daily-loss bookkeeping. Persisting
 * exactly that means resuming needs no recomputation and no second source of
 * truth, which is what makes a resumed replay identical to an uninterrupted one.
 *
 * # Why the version is checked rather than guessed
 *
 * [SCHEMA_VERSION] is written into every blob. A blob from a version this build
 * does not understand is refused, because the alternative — reading what it can
 * and defaulting the rest — would produce a plausible state that is subtly wrong.
 */
object ReplayStateCodec {

    /**
     * Bump when the stored shape changes in any way that alters meaning.
     *
     * Adding an optional field with a default does not require a bump; changing
     * what an existing field means does.
     */
    const val SCHEMA_VERSION: Int = 1

    private val json = Json {
        // Explicit, so a field that happens to equal its default is still
        // written. A blob whose content depends on Kotlin defaults would change
        // meaning if a default were ever edited.
        encodeDefaults = true
        // An unrecognised key means the blob and this build disagree about the
        // format, which is exactly the case that must not pass silently.
        ignoreUnknownKeys = false
    }

    fun encode(state: TradingState): String =
        json.encodeToString(ReplayStateDto.serializer(), state.toDto())

    fun decode(text: String): TradingState {
        val dto = try {
            json.decodeFromString(ReplayStateDto.serializer(), text)
        } catch (e: SerializationException) {
            throw ReplayStateFormatException("replay state is not readable: ${e.message}")
        }
        if (dto.schemaVersion != SCHEMA_VERSION) {
            throw ReplayStateFormatException(
                "replay state schema ${dto.schemaVersion} is not supported; " +
                    "this build writes and reads $SCHEMA_VERSION",
            )
        }
        return dto.toDomain()
    }

    private fun TradingState.toDto() = ReplayStateDto(
        schemaVersion = SCHEMA_VERSION,
        seasonId = seasonId,
        balanceMicros = balanceMicros,
        processedToSimUtcMs = processedToSimUtcMs,
        dayStartEquityMicros = dayStartEquityMicros,
        dayEpochDay = dayEpochDay,
        dailyLossStrikes = dailyLossStrikes,
        dayLocked = dayLocked,
        challengeFailed = challengeFailed,
        failureReason = failureReason?.name,
        marginWarningActive = marginWarningActive,
        orders = orders.map { it.toDto() },
        positions = positions.map { it.toDto() },
        executions = executions.map { it.toDto() },
        closedTrades = closedTrades.map { it.toDto() },
    )

    private fun ReplayStateDto.toDomain() = TradingState(
        seasonId = seasonId,
        balanceMicros = balanceMicros,
        orders = orders.map { it.toDomain() },
        positions = positions.map { it.toDomain() },
        executions = executions.map { it.toDomain() },
        closedTrades = closedTrades.map { it.toDomain() },
        processedToSimUtcMs = processedToSimUtcMs,
        dayStartEquityMicros = dayStartEquityMicros,
        dayEpochDay = dayEpochDay,
        dailyLossStrikes = dailyLossStrikes,
        dayLocked = dayLocked,
        challengeFailed = challengeFailed,
        failureReason = failureReason?.let {
            parseEnum<RiskFailureReason>(it, "RiskFailureReason")
        },
        marginWarningActive = marginWarningActive,
    )

    private fun TradeOrder.toDto() = OrderDto(
        orderId = orderId,
        seasonId = seasonId,
        placedAtSimUtcMs = placedAtSimUtcMs,
        type = type.name,
        side = side.name,
        volumeHundredths = volumeHundredths,
        requestedPriceUnits = requestedPriceUnits,
        stopLossPips = stopLossPips,
        takeProfitPips = takeProfitPips,
        trailingStop = trailingStop?.toDto(),
        breakeven = breakeven?.toDto(),
        status = status.name,
        resolvedAtSimUtcMs = resolvedAtSimUtcMs,
        rejectionReason = rejectionReason,
    )

    private fun OrderDto.toDomain() = TradeOrder(
        orderId = orderId,
        seasonId = seasonId,
        placedAtSimUtcMs = placedAtSimUtcMs,
        type = parseEnum<OrderType>(type, "OrderType"),
        side = parseEnum<OrderSide>(side, "OrderSide"),
        volumeHundredths = volumeHundredths,
        requestedPriceUnits = requestedPriceUnits,
        stopLossPips = stopLossPips,
        takeProfitPips = takeProfitPips,
        trailingStop = trailingStop?.toDomain(),
        breakeven = breakeven?.toDomain(),
        status = parseEnum<OrderStatus>(status, "OrderStatus"),
        resolvedAtSimUtcMs = resolvedAtSimUtcMs,
        rejectionReason = rejectionReason,
    )

    private fun Position.toDto() = PositionDto(
        positionId = positionId,
        seasonId = seasonId,
        openedAtSimUtcMs = openedAtSimUtcMs,
        side = side.name,
        volumeHundredths = volumeHundredths,
        openPriceUnits = openPriceUnits,
        stopLossPips = stopLossPips,
        takeProfitPips = takeProfitPips,
        trailingStop = trailingStop?.toDto(),
        breakeven = breakeven?.toDto(),
        status = status.name,
        closedAtSimUtcMs = closedAtSimUtcMs,
        closePriceUnits = closePriceUnits,
        activeStopUnits = activeStopUnits,
        targetUnits = targetUnits,
        favorableExtremeUnits = favorableExtremeUnits,
        breakevenApplied = breakevenApplied,
        entryCommissionMicros = entryCommissionMicros,
        realizedPnlMicros = realizedPnlMicros,
        realizedVolumeHundredths = realizedVolumeHundredths,
    )

    private fun PositionDto.toDomain() = Position(
        positionId = positionId,
        seasonId = seasonId,
        openedAtSimUtcMs = openedAtSimUtcMs,
        side = parseEnum<OrderSide>(side, "OrderSide"),
        volumeHundredths = volumeHundredths,
        openPriceUnits = openPriceUnits,
        stopLossPips = stopLossPips,
        takeProfitPips = takeProfitPips,
        trailingStop = trailingStop?.toDomain(),
        breakeven = breakeven?.toDomain(),
        status = parseEnum<PositionStatus>(status, "PositionStatus"),
        closedAtSimUtcMs = closedAtSimUtcMs,
        closePriceUnits = closePriceUnits,
        activeStopUnits = activeStopUnits,
        targetUnits = targetUnits,
        favorableExtremeUnits = favorableExtremeUnits,
        breakevenApplied = breakevenApplied,
        entryCommissionMicros = entryCommissionMicros,
        realizedPnlMicros = realizedPnlMicros,
        realizedVolumeHundredths = realizedVolumeHundredths,
    )

    private fun Execution.toDto() = ExecutionDto(
        executionId = executionId,
        seasonId = seasonId,
        orderId = orderId,
        positionId = positionId,
        executedAtSimUtcMs = executedAtSimUtcMs,
        priceUnits = priceUnits,
        volumeHundredths = volumeHundredths,
        commissionMicros = commissionMicros,
        slippageUnits = slippageUnits,
        bidPriceUnits = bidPriceUnits,
        askPriceUnits = askPriceUnits,
    )

    private fun ExecutionDto.toDomain() = Execution(
        executionId = executionId,
        seasonId = seasonId,
        orderId = orderId,
        positionId = positionId,
        executedAtSimUtcMs = executedAtSimUtcMs,
        priceUnits = priceUnits,
        volumeHundredths = volumeHundredths,
        commissionMicros = commissionMicros,
        slippageUnits = slippageUnits,
        bidPriceUnits = bidPriceUnits,
        askPriceUnits = askPriceUnits,
    )

    private fun ClosedTrade.toDto() = ClosedTradeDto(
        tradeId = tradeId,
        seasonId = seasonId,
        positionId = positionId,
        side = side.name,
        volumeHundredths = volumeHundredths,
        openPriceUnits = openPriceUnits,
        closePriceUnits = closePriceUnits,
        openTimeSimUtcMs = openTimeSimUtcMs,
        closeTimeSimUtcMs = closeTimeSimUtcMs,
        grossPnlMicros = grossPnlMicros,
        commissionMicros = commissionMicros,
        swapMicros = swapMicros,
        netPnlMicros = netPnlMicros,
        closeReason = closeReason.name,
        realizedRMicros = realizedRMicros,
    )

    private fun ClosedTradeDto.toDomain() = ClosedTrade(
        tradeId = tradeId,
        seasonId = seasonId,
        positionId = positionId,
        side = parseEnum<OrderSide>(side, "OrderSide"),
        volumeHundredths = volumeHundredths,
        openPriceUnits = openPriceUnits,
        closePriceUnits = closePriceUnits,
        openTimeSimUtcMs = openTimeSimUtcMs,
        closeTimeSimUtcMs = closeTimeSimUtcMs,
        grossPnlMicros = grossPnlMicros,
        commissionMicros = commissionMicros,
        swapMicros = swapMicros,
        netPnlMicros = netPnlMicros,
        closeReason = parseEnum<CloseReason>(closeReason, "CloseReason"),
        realizedRMicros = realizedRMicros,
    )

    private fun TrailingStopSpec.toDto() = TrailingStopDto(
        distancePips = distancePips,
        activationPips = activationPips,
    )

    private fun TrailingStopDto.toDomain() = TrailingStopSpec(
        distancePips = distancePips,
        activationPips = activationPips,
    )

    private fun BreakevenSpec.toDto() = BreakevenDto(
        triggerPips = triggerPips,
        lockPips = lockPips,
    )

    private fun BreakevenDto.toDomain() = BreakevenSpec(
        triggerPips = triggerPips,
        lockPips = lockPips,
    )

    private inline fun <reified E : Enum<E>> parseEnum(name: String, what: String): E =
        enumValues<E>().firstOrNull { it.name == name }
            ?: throw ReplayStateFormatException("unknown $what: $name")
}
