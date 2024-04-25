package com.kr8ne.mensMorris

import com.kr8ne.mensMorris.cache.Cache
import com.kr8ne.mensMorris.move.Movement
import com.kr8ne.mensMorris.move.moveProvider
import com.kr8ne.mensMorris.move.removeChecker
import com.kr8ne.mensMorris.move.triplesMap

/**
 * used for storing position data
 * @param positions all pieces
 * @param freePieces pieces we can still place: first - green, second - blue
 * both should be <= 8
 * @see longHashCode
 * @param greenPiecesAmount used for fast evaluation & game state checker (stores green pieces)
 * @param bluePiecesAmount used for fast evaluation & game state checker (stores blue pieces)
 * @param pieceToMove piece going to move next
 * @param removalCount number of pieces to remove
 * always <= 2
 * @see longHashCode
 */
@Suppress("EqualsOrHashCode")
class Position(
    override var positions: Array<Boolean?>,
    override var freePieces: Pair<UByte, UByte> = Pair(0U, 0U),
    var greenPiecesAmount: UByte = ((positions.count { it == true }.toUByte() + freePieces.first).toUByte()),
    var bluePiecesAmount: UByte = (positions.count { it == false }.toUByte() + freePieces.second).toUByte(),
    override var pieceToMove: Boolean,
    override var removalCount: Byte = 0
) : PositionI {
    /**
     * evaluates position
     * @return pair, where first is eval. for green and the second one for blue
     */
    override fun evaluate(depth: UByte): Pair<Int, Int> {
        if (greenPiecesAmount < PIECES_TO_FLY) {
            val depthCost = depth.toInt() * DEPTH_COST
            return Pair(LOST_GAME_COST + depthCost, Int.MAX_VALUE - depthCost)
        }
        if (bluePiecesAmount < PIECES_TO_FLY) {
            val depthCost = depth.toInt() * DEPTH_COST
            return Pair(Int.MAX_VALUE - depthCost, LOST_GAME_COST + depthCost)
        }
        var greenEvaluation = 0
        var blueEvaluation = 0

        val greenPieces = (greenPiecesAmount.toInt() + if (pieceToMove) removalCount.toInt() else 0)
        val bluePieces = (bluePiecesAmount.toInt() + if (!pieceToMove) removalCount.toInt() else 0)

        greenEvaluation += (greenPieces - bluePieces) * PIECE_COST
        blueEvaluation += (bluePieces - greenPieces) * PIECE_COST

        val (unfinishedTriples, findBlockedTriples) = triplesEvaluation()

        val greenUnfinishedTriplesDelta =
            (unfinishedTriples.first - unfinishedTriples.second * ENEMY_UNFINISHED_TRIPLES_COST)
        greenEvaluation += greenUnfinishedTriplesDelta * UNFINISHED_TRIPLES_COST


        val blueUnfinishedTriplesDelta =
            (unfinishedTriples.second - unfinishedTriples.first * ENEMY_UNFINISHED_TRIPLES_COST)
        blueEvaluation += blueUnfinishedTriplesDelta * UNFINISHED_TRIPLES_COST

        greenEvaluation +=
            (findBlockedTriples.first - findBlockedTriples.second) * POSSIBLE_TRIPLE_COST
        blueEvaluation +=
            (findBlockedTriples.second - findBlockedTriples.first) * POSSIBLE_TRIPLE_COST

        return Pair(greenEvaluation, blueEvaluation)
    }

    /**
     * @return pair of unfinished triples and blocked triples
     */
    fun triplesEvaluation(): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        var greenUnfinishedTriples = 0
        var blueUnfinishedTriples = 0
        var greenBlockedTriples = 0
        var blueBlockedTriples = 0
        for (triples in triplesMap) {
            var greenPieces = 0
            var bluePieces = 0
            triples.forEach {
                when (positions[it]) {
                    true -> {
                        greenPieces++
                    }

                    false -> {
                        bluePieces++
                    }

                    null -> {}
                }
            }
            if (greenPieces == 2 && bluePieces == 0) {
                greenUnfinishedTriples++
            }
            if (greenPieces == 0 && bluePieces == 2) {
                blueUnfinishedTriples++
            }
            if (greenPieces == 2 && bluePieces == 1) {
                greenBlockedTriples++
            }
            if (greenPieces == 1 && bluePieces == 2) {
                blueBlockedTriples++
            }
        }
        return Pair(
            Pair(greenUnfinishedTriples, blueUnfinishedTriples),
            Pair(greenBlockedTriples, blueBlockedTriples)
        )
    }

    /**
     * @return true if the game has ended
     */
    private fun gameEnded(): Boolean {
        return greenPiecesAmount < PIECES_TO_FLY || bluePiecesAmount < PIECES_TO_FLY
    }

    /**
     * @param depth current depth
     * @color color of the piece we are finding a move for
     * @return possible positions and there evaluation
     */
    override fun solve(
        depth: UByte
    ): Pair<Pair<Int, Int>, MutableList<Movement>> {
        if (depth == 0.toUByte() || gameEnded()) {
            return Pair(evaluate(depth), mutableListOf())
        }
        // for all possible positions, we try to solve them
        val positions: MutableList<Pair<Pair<Int, Int>, MutableList<Movement>>> = generatePositionsWithEval(depth)
        if (positions.isEmpty()) {
            // if we can't make a move, we lose
            return Pair(
                if (!pieceToMove) {
                    Pair(LOST_GAME_COST, Int.MAX_VALUE)
                } else {
                    Pair(Int.MAX_VALUE, LOST_GAME_COST)
                }, mutableListOf()
            )
        }
        return positions.maxBy {
            if (pieceToMove) it.first.first else it.first.second
        }
    }

    /**
     * generates all positions with corresponding evaluation
     * @param depth our current depth
     */
    private fun generatePositionsWithEval(
        depth: UByte
    ): MutableList<Pair<Pair<Int, Int>, MutableList<Movement>>> {
        val positions: MutableList<Pair<Pair<Int, Int>, MutableList<Movement>>> = mutableListOf()
        val movesAfterRemoval = mutableListOf<Pair<Movement, Movement>>()
        generateMoves(depth).forEach {
            val pos = it.producePosition(this)
            if (pos.removalCount > 0 && !gameEnded()) {
                movesAfterRemoval.addAll(
                    pos.generateRemovalMoves().map { newMove -> Pair(newMove, it) })
                return@forEach
            }
            val result = pos.solve((depth - 1u).toUByte())
            if (depth != 1.toUByte() && result.second.isEmpty() && !pos.gameEnded()) {
                return@forEach
            }
            result.second.add(it)
            positions.add(result)
        }
        movesAfterRemoval.forEach { (newMove, oldMove) ->
            val pos = newMove.producePosition(this)
            val result = pos.solve((depth - 1u).toUByte())
            if (depth != 1.toUByte() && result.second.isEmpty() && !pos.gameEnded()) {
                return@forEach
            }
            result.second.addAll(listOf(newMove, oldMove))
            positions.add(result)
        }
        return positions
    }

    /**
     * @return a copy of the current position
     */
    override fun copy(): Position {
        return Position(
            positions.clone(),
            freePieces,
            greenPiecesAmount,
            bluePiecesAmount,
            pieceToMove,
            removalCount
        )
    }

    /**
     * @param move the last move we have performed
     * @return the amount of removes we need to perform
     */
    fun removalAmount(move: Movement): Byte {
        if (move.endIndex == null) return 0

        return removeChecker[move.endIndex]!!.count { list ->
            list.all { positions[it] == pieceToMove }
        }.toByte()
    }

    /**
     * @return possible movements
     */
    override fun generateMoves(currentDepth: UByte, ignoreCache: Boolean): List<Movement> {
        if (!ignoreCache) {
            // check if we can abort calculation / use our previous result
            val cache = Cache.getCache(this, currentDepth)
            if (cache != null)
                return cache
        }
        val generatedList = when (gameState()) {
            GameState.Placement -> {
                generatePlacementMovements()
            }

            GameState.End -> {
                listOf()
            }

            GameState.Flying -> {
                generateFlyingMovements()
            }

            GameState.Normal -> {
                generateNormalMovements()
            }

            GameState.Removing -> {
                generateRemovalMoves()
            }
        }
        // store our work into our hashMap
        Cache.addCache(this, generatedList, currentDepth)
        return generatedList
    }

    private fun generateRemovalMoves(): List<Movement> {
        val possibleMove: MutableList<Movement> = mutableListOf()
        positions.forEachIndexed { index, piece ->
            if (piece == !pieceToMove) {
                possibleMove.add(Movement(index, null))
            }
        }
        return possibleMove
    }

    /**
     * @return all possible normal movements
     */
    private fun generateNormalMovements(): List<Movement> {
        val possibleMove: MutableList<Movement> = mutableListOf()
        positions.forEachIndexed { startIndex, piece ->
            if (piece == pieceToMove) {
                moveProvider[startIndex]!!.forEach { endIndex ->
                    if (positions[endIndex] == null) {
                        possibleMove.add(Movement(startIndex, endIndex))
                    }
                }
            }
        }
        return possibleMove
    }

    /**
     * @return all possible flying movements
     */
    private fun generateFlyingMovements(): List<Movement> {
        val possibleMove: MutableList<Movement> = mutableListOf()
        positions.forEachIndexed { startIndex, piece ->
            if (piece == pieceToMove) {
                positions.forEachIndexed { endIndex, endPiece ->
                    if (endPiece == null) {
                        possibleMove.add(Movement(startIndex, endIndex))
                    }
                }
            }
        }
        return possibleMove
    }

    /**
     * @return possible piece placements
     */
    private fun generatePlacementMovements(): List<Movement> {
        val possibleMove: MutableList<Movement> = mutableListOf()
        positions.forEachIndexed { endIndex, piece ->
            if (piece == null) {
                possibleMove.add(Movement(null, endIndex))
            }
        }
        return possibleMove
    }

    /**
     * @return state of the game
     */
    override fun gameState(): GameState {
        return when {
            (gameEnded()) -> {
                GameState.End
            }

            (removalCount > 0) -> {
                GameState.Removing
            }

            ((if (pieceToMove) freePieces.first else freePieces.second) > 0U) -> {
                GameState.Placement
            }

            ((pieceToMove && greenPiecesAmount == PIECES_TO_FLY) ||
                    (!pieceToMove && bluePiecesAmount == PIECES_TO_FLY)) -> {
                GameState.Flying
            }

            else -> GameState.Normal
        }
    }

    /**
     * used for easier writing of auto tests
     */
    @Suppress("unused")
    fun displayAsCode(): String {
        var str = (
                """
        Position(
            mutableListOf(
                _____                   _____                   _____
                        _____           _____           _____
                                _____   _____   _____
                _____   _____   _____           _____   _____   _____
                                _____   _____   _____
                        _____           _____           _____
                _____                   _____                   _____
            ),
            freePieces = Pair(${freePieces.first}u, ${freePieces.second}u),
            pieceToMove = ${pieceToMove},
            removalCount = $removalCount
        )
        """.trimIndent()
                )
        repeat(24) {
            val newString = when (positions[it]) {
                null -> "EMPTY"
                false -> "BLUE_"
                true -> "GREEN"
            }
            str = str.replaceFirst("_____", newString)
        }
        return str
    }

    /**
     * this function is needed for unit tests,
     * especially needed is comparison with other positions
     */
    override fun equals(other: Any?): Boolean {
        if (other !is Position) {
            return super.equals(other)
        }
        for (i in positions.indices) {
            if (positions[i] != other.positions[i]) {
                return false
            }
        }
        return freePieces == other.freePieces && pieceToMove == other.pieceToMove
    }

    /**
     * prints position in human-readable form
     */
    override fun toString(): String {
        var str = ("""
            _____                   _____                   _____
                    _____           _____           _____
                            _____   _____   _____
            _____   _____   _____           _____   _____   _____
                            _____   _____   _____
                    _____           _____           _____
            _____                   _____                   _____
        """.trimIndent())
        repeat(24) {
            val newString = when (positions[it]) {
                null -> "empty"
                false -> "blue"
                true -> "green"
            }
            str = str.replaceFirst("_____", newString)
        }
        return str
    }

    /**
     * used for caching, replaces hashcode
     * this "hash" function has no collisions
     * each result is 31 symbols long
     * TODO: try to compress it
     * (1){pieceToMove}(1){removalCount}(24){positions}(3){freePieces.first}(3){freePieces.second}
     */
    fun longHashCode(): Long {
        var result = 0L
        // 3^30 = 205891132094649
        result += removalCount * 205891132094649
        //3^29 = 68630377364883
        var pow329 = 68630377364883
        positions.forEach {
            result += when (it) {
                null -> 2
                true -> 1
                false -> 0
            } * pow329
            pow329 /= 3
        }
        result += freePieces.first.toString(3).toInt() * 9
        result += freePieces.second.toString(3).toInt() * 1
        if (pieceToMove) {
            result *= -1
        }
        return result
    }
}
