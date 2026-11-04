package com.swordfish.lemuroid.app.shared.online.world

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.game.BaseGameScreenViewModel
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

/** Talking to another player in the game, step by step. */
sealed interface Talk {
    /** We walked up to [name] and pressed A: trade or battle? */
    data class Ask(val playerId: Int, val name: String) : Talk

    /** Waiting for [name] to answer. */
    data class Waiting(val playerId: Int, val name: String, val kind: String) : Talk

    /** [name] walked up to us and asks to [kind]. */
    data class Invited(val playerId: Int, val name: String, val kind: String) : Talk

    /** How it went, and what to do next. [answered]: false when no answer came. */
    data class Result(val name: String, val kind: String, val accepted: Boolean, val answered: Boolean = true) : Talk

    /** Both said yes: the games are linking up for the trade or battle right here. */
    data class Connecting(val name: String, val kind: String) : Talk

    /** The link didn't come up, why as far as we can tell, and [details] for finding out more. */
    data class LinkFailed(
        val name: String,
        val kind: String,
        val why: LinkFailure = LinkFailure.DROPPED,
        val details: String? = null,
    ) : Talk

    companion object {
        const val TRADE = "trade"
        const val BATTLE = "battle"
    }
}

/** Why a trade or battle didn't start. */
enum class LinkFailure {
    /** Our game stayed busy (a menu, a text box) and never got to it. */
    BUSY,

    /** Our game never used the cable (it isn't plugged into the room's link). */
    OUR_GAME,

    /** Their game never answered: most often a different app version on their phone. */
    NO_ANSWER,

    /** Both games started linking, but it didn't hold. */
    DROPPED,
}

/**
 * The conversation as a text box at the bottom of the game picture, in the spirit of the games'
 * own boxes: a light box with a dark frame, game-like lettering, and choices with an arrow.
 * Only the game's own buttons work it ([keys]), like the game's boxes: left and right move the
 * arrow one choice per press, A picks, B backs out. It takes no taps.
 */
@Composable
fun TalkBox(
    talk: Talk,
    isPokemon: Boolean,
    onAsk: (playerId: Int, kind: String) -> Unit,
    onAnswer: (playerId: Int, accept: Boolean) -> Unit,
    onClose: () -> Unit,
    keys: Flow<BaseGameScreenViewModel.MenuKey>? = null,
) {
    val viewport by GameViewport.rect.collectAsState()
    val area = viewport ?: return
    val density = LocalDensity.current
    // Letters about as big as the game's own (8 game pixels), so it reads like part of the game.
    val letter = with(density) { (area.height / 144f * 8f).coerceIn(9f * this.density, 20f * this.density).toSp() }
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = letter, color = INK, lineHeight = letter * 1.35f)

    val name = talk.nameOf().uppercase()
    val tradeWord = stringResource(R.string.talk_kind_trade)
    val battleWord = stringResource(R.string.talk_kind_battle)
    val kindWord = { kind: String -> if (kind == Talk.BATTLE) battleWord else tradeWord }

    val message: String
    val choices: List<Pair<String, () -> Unit>>
    // What B does: back out, or say no.
    val back: () -> Unit
    when (talk) {
        is Talk.Ask -> {
            message = stringResource(R.string.talk_ask, name)
            choices =
                listOf(
                    stringResource(R.string.talk_trade) to { onAsk(talk.playerId, Talk.TRADE) },
                    stringResource(R.string.talk_battle) to { onAsk(talk.playerId, Talk.BATTLE) },
                    stringResource(R.string.talk_cancel) to onClose,
                )
            back = onClose
        }
        is Talk.Waiting -> {
            message = stringResource(R.string.talk_waiting, name, kindWord(talk.kind))
            choices = listOf(stringResource(R.string.talk_cancel) to onClose)
            back = onClose
        }
        is Talk.Invited -> {
            message = stringResource(R.string.talk_invited, name, kindWord(talk.kind))
            choices =
                listOf(
                    stringResource(R.string.talk_yes) to { onAnswer(talk.playerId, true) },
                    stringResource(R.string.talk_no) to { onAnswer(talk.playerId, false) },
                )
            back = { onAnswer(talk.playerId, false) }
        }
        is Talk.Connecting -> {
            message = stringResource(R.string.talk_connecting, name, kindWord(talk.kind))
            choices = emptyList()
            back = {}
        }
        is Talk.LinkFailed -> {
            message =
                when (talk.why) {
                    LinkFailure.BUSY -> stringResource(R.string.talk_link_failed_busy, name, kindWord(talk.kind))
                    LinkFailure.OUR_GAME -> stringResource(R.string.talk_link_failed_our_game, name)
                    LinkFailure.NO_ANSWER -> stringResource(R.string.talk_link_failed_no_answer, name)
                    LinkFailure.DROPPED -> stringResource(R.string.talk_link_failed, name, kindWord(talk.kind))
                } + talk.details?.let { "\n($it)" }.orEmpty()
            choices = listOf(stringResource(R.string.talk_ok) to onClose)
            back = onClose
        }
        is Talk.Result -> {
            message =
                when {
                    !talk.answered -> stringResource(R.string.talk_no_answer, name)
                    !talk.accepted -> stringResource(R.string.talk_declined, name)
                    isPokemon && talk.kind == Talk.BATTLE -> stringResource(R.string.talk_go_battle, name)
                    isPokemon -> stringResource(R.string.talk_go_trade, name)
                    else -> stringResource(R.string.talk_go_other, name)
                }
            choices = listOf(stringResource(R.string.talk_ok) to onClose)
            back = onClose
        }
    }

    var selected by remember(talk) { mutableIntStateOf(0) }
    val currentChoices by rememberUpdatedState(choices)
    val currentBack by rememberUpdatedState(back)
    LaunchedEffect(talk, keys) {
        keys?.collect { key ->
            val count = currentChoices.size
            when (key) {
                // The choices sit side by side: only left and right move between them.
                BaseGameScreenViewModel.MenuKey.LEFT -> selected = (selected - 1).coerceAtLeast(0)
                BaseGameScreenViewModel.MenuKey.RIGHT -> selected = (selected + 1).coerceIn(0, (count - 1).coerceAtLeast(0))
                BaseGameScreenViewModel.MenuKey.A -> currentChoices.getOrNull(selected)?.second?.invoke()
                BaseGameScreenViewModel.MenuKey.B -> currentBack()
                else -> Unit
            }
        }
    }

    with(density) {
        val arrow = letter.toDp() * 0.7f
        // Over the game picture, at its bottom like the game's own text boxes.
        Box(
            modifier =
                Modifier
                    .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
                    .size(area.width.toDp(), area.height.toDp()),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .background(FRAME, RoundedCornerShape(6.dp))
                        .padding(3.dp)
                        .background(PAPER, RoundedCornerShape(4.dp))
                        .border(1.dp, EDGE, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(message, style = style)
                    if (choices.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        choices.forEachIndexed { index, (label, _) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 2.dp),
                            ) {
                                // The arrow has its own fixed space, so every choice lines up.
                                Canvas(Modifier.size(arrow)) {
                                    if (index == selected) {
                                        val path =
                                            Path().apply {
                                                moveTo(size.width * 0.15f, 0f)
                                                lineTo(size.width, size.height / 2)
                                                lineTo(size.width * 0.15f, size.height)
                                                close()
                                            }
                                        drawPath(path, INK)
                                    }
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(label, style = style)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Talk.nameOf(): String =
    when (this) {
        is Talk.Ask -> name
        is Talk.Waiting -> name
        is Talk.Invited -> name
        is Talk.Result -> name
        is Talk.Connecting -> name
        is Talk.LinkFailed -> name
    }

private val INK = Color(0xFF202028)
private val PAPER = Color(0xFFF8F8F0)
private val FRAME = Color(0xFF304060)
private val EDGE = Color(0xFF90A0C0)
