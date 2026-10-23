package com.tradequest.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tradequest.app.R
import com.tradequest.app.core.challenge.ChallengeOutcome
import com.tradequest.app.core.challenge.ChallengeTitle
import com.tradequest.app.core.challenge.SeasonStatus

/**
 * The display names for the challenge enums (Steps 11.7-11.9).
 *
 * Shared rather than duplicated because the dashboard and the history both name a
 * status, a title and an outcome, and two copies would drift: one screen would say
 * "Breached the overall loss limit" and the other "Max loss hit" for the same
 * value. They live in `ui.components` rather than in either feature because neither
 * owns them, and the resource ids stay in one place so a translation cannot be
 * added to one screen and missed on the other.
 */
@Composable
fun seasonStatusLabel(status: SeasonStatus): String = stringResource(
    when (status) {
        SeasonStatus.ACTIVE -> R.string.challenge_status_active
        SeasonStatus.PASSED -> R.string.challenge_status_passed
        SeasonStatus.FAILED -> R.string.challenge_status_failed
        SeasonStatus.ABANDONED -> R.string.challenge_status_abandoned
    },
)

/** The display name for a score band (Step 11.7). */
@Composable
fun challengeTitleLabel(title: ChallengeTitle): String = stringResource(
    when (title) {
        ChallengeTitle.ROOKIE -> R.string.challenge_title_rookie
        ChallengeTitle.TRADER -> R.string.challenge_title_trader
        ChallengeTitle.PRO -> R.string.challenge_title_pro
        ChallengeTitle.GOLD_MASTER -> R.string.challenge_title_gold_master
    },
)

/** The display name for why a season ended. */
@Composable
fun challengeOutcomeLabel(outcome: ChallengeOutcome): String = stringResource(
    when (outcome) {
        ChallengeOutcome.TARGET_REACHED -> R.string.challenge_outcome_target
        ChallengeOutcome.OVERALL_LOSS_BREACHED -> R.string.challenge_outcome_overall_loss
        ChallengeOutcome.DAILY_STRIKES_EXHAUSTED -> R.string.challenge_outcome_strikes
        ChallengeOutcome.SUPERSEDED -> R.string.challenge_outcome_superseded
    },
)
