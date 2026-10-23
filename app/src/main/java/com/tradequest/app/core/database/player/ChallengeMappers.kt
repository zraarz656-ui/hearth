package com.tradequest.app.core.database.player

import com.tradequest.app.core.challenge.ChallengeHistoryEntry
import com.tradequest.app.core.challenge.ChallengeOutcome
import com.tradequest.app.core.challenge.ChallengeScore
import com.tradequest.app.core.challenge.ChallengeTitle
import com.tradequest.app.core.challenge.SeasonMode
import com.tradequest.app.core.challenge.SeasonStatus

/**
 * Mapping between the challenge domain and its Room rows
 * (`docs/architecture.md` rule 5: domain models must not be Room entities).
 *
 * # Why the enums are stored as strings and mapped by name
 *
 * A stored season is a record a user may keep for years, and an ordinal would
 * silently re-interpret itself if a value were ever inserted into the middle of an
 * enum. `SeasonStatus` and `ChallengeOutcome` are both ordered by severity, which
 * is exactly the shape that invites an insertion. Storing `name` makes a stored
 * value self-describing, so a schema dump is readable, and a value that no longer
 * exists fails loudly at the mapping boundary rather than becoming a neighbouring
 * status.
 *
 * A missing value is therefore a real error, not a "default to the first entry".
 * That is why these throw rather than falling back: a season whose status cannot be
 * read is a corrupted row, and treating it as ACTIVE would let a failed challenge
 * be traded again.
 */
internal object ChallengeMappers {

    fun toEntity(entry: ChallengeHistoryEntry): ChallengeRecordEntity = ChallengeRecordEntity(
        recordId = entry.recordId,
        seasonId = entry.seasonId,
        datasetId = entry.datasetId,
        datasetDisplayName = entry.datasetDisplayName,
        mode = entry.mode.name,
        status = entry.status.name,
        outcome = entry.outcome.name,
        startingBalanceMicros = entry.startingBalanceMicros,
        finalBalanceMicros = entry.finalBalanceMicros,
        finalEquityMicros = entry.finalEquityMicros,
        peakEquityMicros = entry.peakEquityMicros,
        tradingDayCount = entry.tradingDayCount,
        dailyStrikeCount = entry.dailyStrikeCount,
        scorePoints = entry.score.total,
        profitPoints = entry.score.profitPoints,
        riskControlPoints = entry.score.riskControlPoints,
        consistencyPoints = entry.score.consistencyPoints,
        disciplinePoints = entry.score.disciplinePoints,
        title = entry.title.name,
        attemptOrdinal = entry.attemptOrdinal,
        closedTradeCount = entry.closedTradeCount,
        startedAtSimUtcMs = entry.startedAtSimUtcMs,
        completedAtSimUtcMs = entry.completedAtSimUtcMs,
        completedAtRealUtcMs = entry.completedAtRealUtcMs,
    )

    fun toDomain(row: ChallengeRecordEntity): ChallengeHistoryEntry = ChallengeHistoryEntry(
        recordId = row.recordId,
        seasonId = row.seasonId,
        datasetId = row.datasetId,
        datasetDisplayName = row.datasetDisplayName,
        mode = parseMode(row.mode),
        status = parseStatus(row.status),
        outcome = parseOutcome(row.outcome),
        startingBalanceMicros = row.startingBalanceMicros,
        finalBalanceMicros = row.finalBalanceMicros,
        finalEquityMicros = row.finalEquityMicros,
        peakEquityMicros = row.peakEquityMicros,
        tradingDayCount = row.tradingDayCount,
        dailyStrikeCount = row.dailyStrikeCount,
        score = ChallengeScore(
            profitPoints = row.profitPoints,
            riskControlPoints = row.riskControlPoints,
            consistencyPoints = row.consistencyPoints,
            disciplinePoints = row.disciplinePoints,
        ),
        title = parseTitle(row.title),
        attemptOrdinal = row.attemptOrdinal,
        closedTradeCount = row.closedTradeCount,
        startedAtSimUtcMs = row.startedAtSimUtcMs,
        completedAtSimUtcMs = row.completedAtSimUtcMs,
        completedAtRealUtcMs = row.completedAtRealUtcMs,
    )

    private fun parseMode(value: String): SeasonMode =
        SeasonMode.entries.firstOrNull { it.name == value }
            ?: error("unknown season mode in storage: $value")

    private fun parseStatus(value: String): SeasonStatus =
        SeasonStatus.entries.firstOrNull { it.name == value }
            ?: error("unknown season status in storage: $value")

    private fun parseOutcome(value: String): ChallengeOutcome =
        ChallengeOutcome.entries.firstOrNull { it.name == value }
            ?: error("unknown challenge outcome in storage: $value")

    private fun parseTitle(value: String): ChallengeTitle =
        ChallengeTitle.entries.firstOrNull { it.name == value }
            ?: error("unknown challenge title in storage: $value")
}
