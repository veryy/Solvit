package dev.solvit.http

import kotlinx.coroutines.*
import okhttp3.*
import okio.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Replays a callback delivering a socket resource before its caller resumes on Main. */
class HttpResponseOwnershipTest {
    private class QueueDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }
    private class Fixture {
        val mainThread = Thread.currentThread()
        val main = QueueDispatcher()
        val io = QueueDispatcher()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + main + CoroutineExceptionHandler { _, e -> errors.add(e) })
        val call = PendingCall()
        val closes = AtomicInteger()
        val closeThreads = ConcurrentLinkedQueue<Thread>()
        var consumed = false
        var value: String? = null
        val response = Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(object : ResponseBody() {
                val content = object : ForwardingSource(Buffer().writeUtf8("actual bytes")) {
                    override fun close() {
                        closes.incrementAndGet(); closeThreads.add(Thread.currentThread())
                        check(Thread.currentThread() !== mainThread) { "TLS response closed on Main" }
                        super.close()
                    }
                }.buffer()
                override fun contentType(): MediaType? = null
                override fun contentLength() = 12L
                override fun source() = content
            }).build()
        fun worker(block: () -> Unit) {
            val thrown = ConcurrentLinkedQueue<Throwable>()
            val thread = Thread({ try { block() } catch (e: Throwable) { thrown.add(e) } }, "http-resource-worker")
            thread.start(); thread.join(5_000)
            assertFalse(thread.isAlive, "Worker did not finish")
            assertTrue(thrown.isEmpty(), "Worker failed: ${thrown.peek()}")
        }
        fun start(): Job {
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                value = call.readResponse(io) { consumed = true; it.body!!.string() }
            }
            worker { io.drain() }
            assertTrue(call.enqueued.await(1, TimeUnit.SECONDS), "Request did not enqueue")
            return job
        }
        fun finish(job: Job) {
            repeat(8) { worker { io.drain() }; main.drain() }
            assertTrue(job.isCompleted, "Request did not settle")
            assertTrue(errors.isEmpty(), "Cancellation threw: ${errors.peek()}")
        }
        fun assertClosedOffMain() {
            assertEquals(1, closes.get(), "Every delivered response must close exactly once")
            assertTrue(closeThreads.all { it !== mainThread })
            assertTrue(call.cancelThreads.all { it !== mainThread }, "Call cancellation must run off Main")
        }
        fun dispose() { scope.cancel(); worker { io.drain() }; main.drain() }
    }
    private class PendingCall : Call {
        val enqueued = CountDownLatch(1)
        val cancelThreads = ConcurrentLinkedQueue<Thread>()
        lateinit var callback: Callback
        private var executed = false
        @Volatile private var cancelled = false
        override fun request() = Request.Builder().url("https://example.invalid/fixture").build()
        override fun enqueue(responseCallback: Callback) { executed = true; callback = responseCallback; enqueued.countDown() }
        override fun execute(): Response = error("The real adapter must enqueue asynchronously")
        override fun cancel() { cancelled = true; cancelThreads.add(Thread.currentThread()) }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = PendingCall()
    }

    @Test fun cancellationAfterCallbackBeforeResumeClosesTheOwnedResponseOffMain() {
        val f = Fixture()
        try {
            val job = f.start()
            f.worker { f.call.callback.onResponse(f.call, f.response) }
            job.cancel() // Main owns cancellation while the response's continuation is still queued.
            f.finish(job)
            assertFalse(f.consumed)
            f.assertClosedOffMain()
        } finally { f.dispose() }
    }

    @Test fun responseArrivingAfterCancellationIsClosedAndNeverConsumed() {
        val f = Fixture()
        try {
            val job = f.start(); job.cancel(); f.finish(job)
            f.worker { f.call.callback.onResponse(f.call, f.response) }
            assertFalse(f.consumed)
            f.assertClosedOffMain()
        } finally { f.dispose() }
    }

    @Test fun normalResponseKeepsItsBytesAndClosesOffMain() {
        val f = Fixture()
        try {
            val job = f.start(); f.worker { f.call.callback.onResponse(f.call, f.response) }
            f.finish(job)
            assertEquals("actual bytes", f.value)
            f.assertClosedOffMain()
        } finally { f.dispose() }
    }
}
