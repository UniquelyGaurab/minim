package com.minim.music.data.ai

import com.minim.music.data.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** v5.2.0: one thing shown in the chat. Kept for as long as the app runs, never saved. */
sealed class ChatItem {
    val key: Long = nextKey++

    class User(val text: String) : ChatItem()
    class Assistant : ChatItem() {
        val text = StringBuilder()
        var done = false
    }
    class Songs(val tracks: List<Track>) : ChatItem()
    class Liked(val track: Track) : ChatItem() {
        var undone = false
    }
    class Action(val kind: ActionKind, val title: String, val count: Int) : ChatItem()
    class Playlist(var name: String, tracks: List<Track>) : ChatItem() {
        val tracks: MutableList<Track> = tracks.toMutableList()
        var saved = false
    }
    class Error(val problem: GeminiProblem) : ChatItem()

    private companion object {
        var nextKey = 1L
    }
}

/** What the chat screen hears from the session (always on the main thread). */
interface ChatListener {
    fun onItemAdded(item: ChatItem)
    fun onAssistantText(item: ChatItem.Assistant, delta: String)
    fun onThinking(thinking: Boolean)
    fun onBusy(busy: Boolean)
    fun onOpenArtist(name: String)
    fun onCleared()
}

/**
 * v5.2.0: the Gemini conversation. Lives in MinimApp, so it survives closing the chat sheet
 * and rotating the phone, and is wiped when the app is closed. Nothing is stored or synced.
 */
class GeminiChatSession(
    private val client: GeminiClient,
    private val scope: CoroutineScope,
    /** Runs a block on the main thread (stream text arrives on a background thread). */
    private val post: (() -> Unit) -> Unit,
) {
    val items = ArrayList<ChatItem>()
    private val history = ArrayList<Map<String, Any?>>()
    private val known = LinkedHashMap<String, Track>()
    private var job: Job? = null
    private var currentAssistant: ChatItem.Assistant? = null

    var listener: ChatListener? = null
    var busy = false
        private set
    var thinking = false
        private set

    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * 5.2.1: each send gets a number. A turn that was replaced (New chat, or a new message
     * right after it) can no longer add text, errors or state changes to the chat that
     * replaced it.
     */
    private var turnId = 0

    fun clear() {
        turnId++
        job?.cancel()
        job = null
        items.clear()
        history.clear()
        known.clear()
        currentAssistant = null
        setThinking(false)
        setBusy(false)
        listener?.onCleared()
    }

    fun cancel() {
        job?.cancel()
    }

    private fun setBusy(b: Boolean) {
        if (busy == b) return
        busy = b
        listener?.onBusy(b)
    }

    private fun setThinking(t: Boolean) {
        if (thinking == t) return
        thinking = t
        listener?.onThinking(t)
    }

    private fun add(item: ChatItem) {
        if (item !is ChatItem.Assistant) currentAssistant = null
        items += item
        listener?.onItemAdded(item)
    }

    /**
     * Sends [text]. [focus] is the song from the Now Playing context chip (null for a general
     * chat). [endpoint] picks the route (your key or the built-in one) and may throw
     * [GeminiException]. [systemPrompt] is the instruction for this turn.
     */
    fun send(
        text: String,
        focus: Track?,
        actions: MinimActions,
        systemPrompt: String,
        endpoint: suspend () -> GeminiEndpoint,
    ): Boolean {
        val msg = text.trim()
        if (msg.isEmpty() || busy) return false
        add(ChatItem.User(msg))
        val snapshot = history.size
        val prefix = focus?.let {
            "[Opened from Now Playing. Song in focus: \"${it.title}\" by ${it.artist} (id ${it.id}). " +
                "\"This song\" means this one.]\n"
        }.orEmpty()
        focus?.let { known[it.id] = it }
        history += mapOf("role" to "user", "parts" to listOf(mapOf("text" to prefix + msg)))
        setBusy(true)
        setThinking(true)
        val myTurn = ++turnId
        job = scope.launch {
            try {
                val ep = endpoint()
                runTurns(ep, actions, systemPrompt, myTurn)
            } catch (e: CancellationException) {
                if (turnId == myTurn) rollback(snapshot)
                throw e
            } catch (e: GeminiException) {
                if (turnId == myTurn) {
                    rollback(snapshot)
                    add(ChatItem.Error(e.problem))
                }
            } catch (t: Throwable) {
                if (turnId == myTurn) {
                    rollback(snapshot)
                    add(ChatItem.Error(GeminiProblem.Other(t.javaClass.simpleName)))
                }
            } finally {
                if (turnId == myTurn) {
                    currentAssistant?.done = true
                    currentAssistant = null
                    setThinking(false)
                    setBusy(false)
                    trimHistory()
                }
            }
        }
        return true
    }

    /** A failed turn leaves no half-finished function calls behind. */
    private fun rollback(size: Int) {
        while (history.size > size) history.removeAt(history.size - 1)
    }

    /** Keeps the request small: drop the oldest turns, cutting only before a typed message. */
    internal fun trimHistory(max: Int = MAX_HISTORY) {
        if (history.size <= max) return
        var cut = history.size - max
        while (cut < history.size && !isTypedUserTurn(history[cut])) cut++
        if (cut >= history.size) return
        repeat(cut) { history.removeAt(0) }
    }

    private fun isTypedUserTurn(c: Map<String, Any?>): Boolean =
        c["role"] == "user" && c["parts"].asList().orEmpty().any { it.asObj()?.containsKey("text") == true }

    internal fun historyForTest(): List<Map<String, Any?>> = history

    fun requestBody(systemPrompt: String): String = JsonOut.write(
        linkedMapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to systemPrompt))),
            "contents" to history,
            "tools" to listOf(mapOf("functionDeclarations" to ChatTools.declarations)),
            "toolConfig" to mapOf("functionCallingConfig" to mapOf("mode" to "AUTO")),
            "generationConfig" to mapOf("maxOutputTokens" to 2048),
        )
    )

    private suspend fun runTurns(ep: GeminiEndpoint, actions: MinimActions, systemPrompt: String, myTurn: Int) {
        val toolbox = ChatToolbox(actions, known)
        var round = 0
        while (true) {
            round++
            setThinking(true)
            val turn = client.stream(ep, requestBody(systemPrompt)) { delta ->
                post {
                    if (turnId != myTurn || job?.isActive != true) return@post
                    setThinking(false)
                    val a = currentAssistant ?: ChatItem.Assistant().also {
                        add(it)
                        currentAssistant = it
                    }
                    a.text.append(delta)
                    listener?.onAssistantText(a, delta)
                }
            }
            // let the posted text land before tool results are shown
            awaitMain()
            if (turn.parts.isEmpty()) {
                if (turn.blocked) throw GeminiException(GeminiProblem.Blocked)
                if (turn.calls.isEmpty() && round == 1) throw GeminiException(GeminiProblem.Busy)
                return
            }
            history += mapOf("role" to "model", "parts" to turn.parts)
            if (turn.calls.isEmpty()) {
                if (turn.blocked && turn.text.isBlank()) throw GeminiException(GeminiProblem.Blocked)
                return
            }
            if (round >= MAX_ROUNDS) return
            val responses = ArrayList<Map<String, Any?>>()
            for (call in turn.calls) {
                val result = toolbox.run(call) { ev -> onEvent(ev) }
                val fr = linkedMapOf<String, Any?>("name" to call.name, "response" to result)
                call.id?.let { fr["id"] = it }
                responses += mapOf("functionResponse" to fr)
            }
            history += mapOf("role" to "user", "parts" to responses)
            currentAssistant?.done = true
            currentAssistant = null
        }
    }

    private suspend fun awaitMain() {
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont -> post { if (cont.isActive) cont.resumeWith(Result.success(Unit)) } }
    }

    private fun onEvent(ev: ChatEvent) {
        when (ev) {
            is ChatEvent.Songs -> add(ChatItem.Songs(ev.tracks))
            is ChatEvent.Liked -> add(ChatItem.Liked(ev.track))
            is ChatEvent.Action -> add(ChatItem.Action(ev.kind, ev.title, ev.count))
            is ChatEvent.PlaylistPreview -> add(ChatItem.Playlist(ev.name, ev.tracks))
            is ChatEvent.OpenArtist -> listener?.onOpenArtist(ev.name)
        }
    }

    companion object {
        const val MAX_ROUNDS = 6
        const val MAX_HISTORY = 60
    }
}
