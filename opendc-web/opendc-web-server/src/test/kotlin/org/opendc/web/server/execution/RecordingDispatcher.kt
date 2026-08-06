/*
 * Copyright (c) 2026 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.web.server.execution

import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import org.opendc.web.dispatcher.Dispatcher
import org.opendc.web.dispatcher.ExecutionSlot
import org.opendc.web.dispatcher.ExitOutcome
import org.opendc.web.dispatcher.LaunchRequest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * A platform the test suite drives itself.
 *
 * It stands in for every dispatcher across these tests, so nothing here starts a real process: what
 * a launcher does with a manifest belongs to the launcher's own tests, and what the server does
 * about an outcome is decided by the outcome, not by how long a subprocess took to produce it.
 */
@Mock
@ApplicationScoped
class RecordingDispatcher : Dispatcher {
    val launched = CopyOnWriteArrayList<LaunchRequest>()
    private val listener = AtomicReference<(UUID, ExitOutcome) -> Unit> { _, _ -> }
    private val capacity = AtomicReference(ExecutionSlot(cores = 8, memoryMb = 8192.0))

    override val name: String get() = "recording"

    override fun slot(): ExecutionSlot = capacity.get()

    override fun admits(
        cores: Int,
        memoryMb: Double,
    ): Boolean = cores <= capacity.get().cores && memoryMb <= capacity.get().memoryMb

    override fun observe(onFinished: (UUID, ExitOutcome) -> Unit) {
        listener.set(onFinished)
    }

    override fun launch(request: LaunchRequest) {
        launched += request
    }

    override fun cancel(executionId: UUID) {}

    override fun reconcile(executionIds: List<UUID>): Set<UUID> = emptySet()

    /** Reports an execution ending, the way a platform would. */
    fun finish(
        executionId: UUID,
        exit: ExitOutcome,
    ) {
        listener.get().invoke(executionId, exit)
    }

    fun forget() {
        launched.clear()
    }
}
