package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

data class PostTurnWorkerState(val activeJobId: String? = null, val failure: PostTurnFailure? = null)
private class PostTurnSettlementException : IllegalStateException("Post-turn status could not be saved")

/** One consumer, durable claims, bounded inference, and no foreground-chat await. */
class PostTurnWorker(
    private val store: ChatStore,
    private val hooks: PostTurnHookRegistry,
    private val provider: ModelProvider,
    private val resolveConfig: (CompletedChatTurn) -> ModelConfig?,
    private val currentRole: suspend (String) -> AgentRole?,
    private val scope: CoroutineScope,
    private val deadlineMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) : PostTurnTaskService {
    init { require(deadlineMs in 1..60_000) }
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(PostTurnWorkerState())
    val state = mutable.asStateFlow()
    private data class ActiveWork(val id: String, val pluginId: String, val job: Job)
    @Volatile private var active: ActiveWork? = null
    private var loop: Job? = null

    /** Start only after installed plugins have been restored, so lookup is authoritative. */
    @Synchronized fun start() {
        if (loop?.isActive == true || !store.supportsCompletedTurns) return
        loop = scope.launch {
            var recovered = false
            while (isActive) {
                var progressed = false
                try {
                    if (!recovered) { store.recoverInterruptedPostTurnJobs(now()); recovered = true }
                    progressed = runNext()
                } catch (e: CancellationException) { currentCoroutineContext().ensureActive() }
                catch (_: Exception) { mutable.value = PostTurnWorkerState(failure = PostTurnFailure.STORAGE) }
                delay(if (progressed) 1_000 else 15_000)
            }
        }
    }
    fun cancelCurrent() { active?.job?.cancel() }
    override suspend fun list(pluginId: String, before: PostTurnJobCursor?, limit: Int,
        authorize: suspend () -> Unit): PostTurnTaskPage {
        authorize()
        val jobs = store.ownPostTurnJobs(pluginId, before, limit, authorize)
        authorize()
        return PostTurnTaskPage(jobs, active?.takeIf { it.pluginId == pluginId }?.id)
    }
    override suspend fun control(pluginId: String, action: PostTurnTaskControl,
        authorize: suspend () -> Unit): Int {
        authorize()
        if (action is PostTurnTaskControl.Stop) {
            require(action.id.isNotBlank() && action.id.length <= 256)
            val selected = active?.takeIf { it.pluginId == pluginId && it.id == action.id } ?: return 0
            authorize()
            // A newer active task cannot replace this captured Job between check and cancel.
            selected.job.cancel()
            return 1
        }
        return store.controlPostTurnJobs(pluginId, action, now(), authorize)
    }
    override suspend fun retired(pluginId: String, turnId: String, authorize: suspend () -> Unit): Boolean {
        authorize()
        val retired = sourceRetired(pluginId, turnId)
        authorize()
        return retired
    }
    suspend fun jobs(before: PostTurnJobCursor? = null, limit: Int = 20): List<PostTurnJob> = store.recentPostTurnJobs(before, limit)
    suspend fun retry(id: String, attempt: Int): Boolean = store.retryPostTurnJob(id, attempt, now())
    suspend fun delete(job: PostTurnJob): Boolean = store.deletePostTurnJob(job.id, job.attempt, job.status)
    suspend fun cleanupFinished(pluginId: String): Int = store.deleteFinishedPostTurnJobs(pluginId, 100)
    /** An absent durable job cannot be claimed or explicitly retried later. */
    suspend fun sourceRetired(pluginId: String, turnId: String): Boolean {
        check(store.supportsCompletedTurns)
        return store.postTurnJob("$turnId:$pluginId") == null
    }

    suspend fun runNext(): Boolean {
        if (!store.supportsCompletedTurns || !mutex.tryLock()) return false
        var claimed: PostTurnJob? = null
        try {
            val waiting = store.postTurnJobs(setOf(PostTurnJobStatus.WAITING), 1).firstOrNull() ?: return false
            if (!store.claimPostTurnJob(waiting.id, waiting.attempt, now())) return false
            val job = waiting.copy(status = PostTurnJobStatus.RUNNING, attempt = waiting.attempt + 1)
            claimed = job
            mutable.value = PostTurnWorkerState(job.id)
            coroutineScope {
                active = ActiveWork(job.id, job.target.pluginId, currentCoroutineContext().job)
                withTimeout(deadlineMs) { execute(job) }
            }
            finish(job, PostTurnJobStatus.SUCCEEDED, null)
        } catch (_: PostTurnRevokedException) {
            claimed?.let { finish(it, PostTurnJobStatus.SKIPPED, null) }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            claimed?.let { finish(it, PostTurnJobStatus.FAILED, PostTurnFailure.DEADLINE) }
        } catch (e: CancellationException) {
            claimed?.let { withContext(NonCancellable) { finish(it, PostTurnJobStatus.FAILED, PostTurnFailure.INTERRUPTED) } }
            throw e
        } catch (e: PostTurnSettlementException) { throw e
        } catch (e: Exception) {
            val code = when (e) {
                is PostTurnWorkException -> e.failure
                is ModelException -> PostTurnFailure.MODEL
                else -> PostTurnFailure.INVALID_OUTPUT
            }
            if (claimed == null) throw e
            finish(claimed, PostTurnJobStatus.FAILED, code)
        } finally {
            active = null
            mutable.value = mutable.value.copy(activeJobId = null)
            mutex.unlock()
        }
        return claimed != null
    }

    private suspend fun finish(job: PostTurnJob, status: PostTurnJobStatus, code: PostTurnFailure?) {
        try {
            withContext(NonCancellable) { store.finishPostTurnJob(job.id, job.attempt, status, code, now()) }
            mutable.value = PostTurnWorkerState(failure = code)
        } catch (e: Exception) {
            mutable.value = PostTurnWorkerState(failure = PostTurnFailure.STORAGE)
            throw PostTurnSettlementException()
        }
    }

    private suspend fun execute(job: PostTurnJob) {
        val hook = hooks.resolve(job.target) ?: throw PostTurnRevokedException()
        suspend fun authority() {
            currentCoroutineContext().ensureActive()
            val live = store.postTurnJob(job.id) ?: throw PostTurnRevokedException()
            if (live.status != PostTurnJobStatus.RUNNING || live.attempt != job.attempt || live.target != job.target || live.turn != job.turn)
                throw PostTurnRevokedException()
            if (hooks.resolve(job.target) !== hook || !hook.enabled()) throw PostTurnRevokedException()
            val role = currentRole(job.turn.sessionId)
            if (role?.id != job.turn.roleId || role != null && !role.allows(job.target.pluginId) ||
                !job.turn.allows(job.target.pluginId) || job.target !in job.turn.targets) throw PostTurnRevokedException()
            configuration(job.turn)
        }
        authority()
        val messages = store.postTurnMessages(job.turn)
        val user = messages.firstOrNull { it.id == job.turn.userMessageId }
        val answer = messages.firstOrNull { it.id == job.turn.assistantMessageId }
        if (user == null || answer == null || user.sessionId != job.turn.sessionId || answer.sessionId != job.turn.sessionId ||
            user.role != Role.USER || answer.role != Role.ASSISTANT || user.status != MessageStatus.COMPLETE ||
            answer.status != MessageStatus.COMPLETE || answer.toolCalls.isNotEmpty()) throw PostTurnRevokedException()
        if (user.content.toByteArray().size.toLong() + answer.content.toByteArray().size > 256 * 1024)
            throw PostTurnWorkException(PostTurnFailure.INPUT_TOO_LARGE)
        val calls = AtomicInteger()
        val model = PostTurnModel { instructions, input ->
            authority()
            if (calls.incrementAndGet() != 1) throw PostTurnWorkException(PostTurnFailure.INVALID_OUTPUT)
            if (instructions.isBlank() || instructions.toByteArray().size > 16 * 1024 || input.toByteArray().size > 256 * 1024)
                throw PostTurnWorkException(PostTurnFailure.INPUT_TOO_LARGE)
            infer(configuration(job.turn), instructions, input)
        }
        val execution = PostTurnExecutionContext(job.target, model) { authority() }
        try {
            withContext(execution) { hook.process(PostTurnInput(job.turn, user.content, answer.content), model) }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (execution.revoked) throw PostTurnRevokedException()
            execution.nativeFailure?.let { throw PostTurnWorkException(it) }
            throw e
        }
    }

    private fun configuration(turn: CompletedChatTurn): ModelConfig {
        val config = resolveConfig(turn) ?: throw PostTurnWorkException(PostTurnFailure.CONFIGURATION)
        if (config.providerId != turn.requestedProviderId || config.model != turn.requestedModel || config.validate() != null)
            throw PostTurnWorkException(PostTurnFailure.CONFIGURATION)
        return config
    }
    private suspend fun infer(config: ModelConfig, instructions: String, input: String): String {
        val output = minOf(1_024, config.contextBudget.outputTokens)
        val options = config.providerOptions + ("max_tokens" to output.toString()) +
            (if (config.providerId == "anthropic") mapOf("thinking_mode" to "disabled") else emptyMap())
        val bounded = ModelConfig(baseUrl = config.baseUrl, model = config.model, apiKey = config.apiKey,
            systemPrompt = instructions, providerId = config.providerId, providerOptions = options,
            contextBudget = config.contextBudget.copy(outputTokens = output))
        var request = ModelRequest(bounded, listOf(ModelMessage(Role.SYSTEM, instructions), ModelMessage(Role.USER, input)))
        val policy = bounded.contextBudget.copy(outputTokens = maxOf(output, provider.reservedOutputTokens(request)))
        val measurement = Utf8ContextTokenMeter().measure(request, policy)
        if (!measurement.fits) throw PostTurnWorkException(PostTurnFailure.INPUT_TOO_LARGE)
        request = request.copy(contextMeasurement = measurement)
        val result = StringBuilder()
        var done = false
        provider.stream(request).collect { chunk ->
            if (done) throw PostTurnWorkException(PostTurnFailure.INVALID_OUTPUT)
            when (chunk) {
                is ModelChunk.Text -> {
                    if (result.length.toLong() + chunk.value.length > 32_768) throw PostTurnWorkException(PostTurnFailure.INVALID_OUTPUT)
                    result.append(chunk.value)
                }
                is ModelChunk.Reasoning, is ModelChunk.NativeTranscript -> Unit
                ModelChunk.Done -> done = true
                else -> throw PostTurnWorkException(PostTurnFailure.INVALID_OUTPUT)
            }
        }
        if (!done || result.isBlank() || result.toString().toByteArray().size > 32 * 1024)
            throw PostTurnWorkException(PostTurnFailure.INVALID_OUTPUT)
        return result.toString().let { if (config.apiKey.isEmpty()) it else it.replace(config.apiKey, "<redacted>") }
    }
}

val PostTurnWorkerServiceKey = ServiceKey("agent.post-turn-worker", PostTurnWorker::class)
class PostTurnWorkerPlugin : SolvitPlugin {
    override val id = "builtin.post-turn-worker"
    override val requires = setOf(Services.Storage, Services.Model, ModelProfilesServiceKey, RoleServiceKey, PostTurnHookServiceKey)
    override val provides = setOf(PostTurnWorkerServiceKey, PostTurnTaskServiceKey)
    override suspend fun enable(context: PluginContext) {
        val profiles = context.require(ModelProfilesServiceKey)
        val roles = context.require(RoleServiceKey)
        val worker = PostTurnWorker(context.require(Services.Storage), context.require(PostTurnHookServiceKey),
            context.require(Services.Model), { turn -> profiles.state.value.takeIf { it.configured }?.profiles?.firstOrNull { it.id == turn.requestedProfileId }?.config },
            { session -> roles.selected(session) }, context.scope)
        context.register(PostTurnWorkerServiceKey, worker)
        context.register(PostTurnTaskServiceKey, worker)
    }
}
