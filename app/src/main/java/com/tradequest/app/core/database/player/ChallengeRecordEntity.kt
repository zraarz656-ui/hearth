package com.tradequest.app.core.database.player

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A finished season's summary, retained after the season itself may be deleted
 * (Steps 11.9, 11.11).
 *
 * # Why this is a separate table from `Season`
 *
 * The plan requires two things that pull in opposite directions: history must
 * survive (Step 3.4: "previous attempts must remain saved"), and a dataset's
 * challenge history must be listable without loading every trade. `Season` holds
 * the *live* progress and is the parent of every season-scoped row, so deleting a
 * season cascades its trades away — correct for a user who deletes a season, wrong
 * for a history list that would then forget the attempt ever happened.
 *
 * This table is written once, when a season reaches a terminal state, and has no
 * foreign key to `Season`: it is a *record*, not a child. That is what lets
 * "personal best per dataset" (Step 11.11) and "challenge history" (Step 11.9)
 * outlive the seasons they describe, and it is why the outcome fields are
 * denormalised here rather than read through a join the season may no longer
 * support.
 *
 * # Why `attemptOrdinal` is stored
 *
 * Step 11.11 requires first-attempt results to stay distinguishable from repeats.
 * That could be derived by counting earlier rows for the same dataset, but the
 * count would change if a user deleted an earlier season — the second attempt
 * would silently become "first". Storing the ordinal at completion time makes the
 * distinction a fact about the attempt, not a query result.
 */
@Entity(
    tableName = "ChallengeRecord",
    indices = [
        Index(value = ["datasetId", "completedAtRealUtcMs"]),
        Index(value = ["seasonId"], unique = true),
    ],
)
data class ChallengeRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "recordId")
    val recordId: String,

    /** The season this records. A logical reference: the season may be deleted. */
    @ColumnInfo(name = "seasonId")
    val seasonId: String,

    @ColumnInfo(name = "datasetId")
    val datasetId: String,

    /** Display name of the dataset at completion time, so history reads standalone. */
    @ColumnInfo(name = "datasetDisplayName")
    val datasetDisplayName: String,

    @ColumnInfo(name = "mode")
    val mode: String,

    /** PASSED, FAILED or ABANDONED. A record is never written for an ACTIVE season. */
    @ColumnInfo(name = "status")
    val status: String,

    /** Why it ended. See [com.tradequest.app.core.challenge.ChallengeOutcome]. */
    @ColumnInfo(name = "outcome")
    val outcome: String,

    @ColumnInfo(name = "startingBalanceMicros")
    val startingBalanceMicros: Long,

    @ColumnInfo(name = "finalBalanceMicros")
    val finalBalanceMicros: Long,

    @ColumnInfo(name = "finalEquityMicros")
    val finalEquityMicros: Long,

    @ColumnInfo(name = "peakEquityMicros")
    val peakEquityMicros: Long,

    @ColumnInfo(name = "tradingDayCount")
    val tradingDayCount: Int,

    @ColumnInfo(name = "dailyStrikeCount")
    val dailyStrikeCount: Int,

    @ColumnInfo(name = "scorePoints")
    val scorePoints: Int,

    /** The score's four components, so history can show the breakdown (Step 11.6). */
    @ColumnInfo(name = "profitPoints")
    val profitPoints: Int,

    @ColumnInfo(name = "riskControlPoints")
    val riskControlPoints: Int,

    @ColumnInfo(name = "consistencyPoints")
    val consistencyPoints: Int,

    @ColumnInfo(name = "disciplinePoints")
    val disciplinePoints: Int,

    @ColumnInfo(name = "title")
    val title: String,

    /** 1 for a dataset's first attempt, 2 for the next, and so on (Step 11.11). */
    @ColumnInfo(name = "attemptOrdinal")
    val attemptOrdinal: Int,

    @ColumnInfo(name = "closedTradeCount")
    val closedTradeCount: Int,

    @ColumnInfo(name = "startedAtSimUtcMs")
    val startedAtSimUtcMs: Long,

    @ColumnInfo(name = "completedAtSimUtcMs")
    val completedAtSimUtcMs: Long,

    @ColumnInfo(name = "completedAtRealUtcMs")
    val completedAtRealUtcMs: Long,
)
