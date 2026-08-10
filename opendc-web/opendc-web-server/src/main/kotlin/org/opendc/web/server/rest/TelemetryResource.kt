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

package org.opendc.web.server.rest

import jakarta.transaction.Transactional
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.opendc.web.launcher.TelemetryReport
import org.opendc.web.server.model.Execution
import org.opendc.web.server.telemetry.RunKey
import org.opendc.web.server.telemetry.TelemetryStore

private const val BEARER = "Bearer "

/**
 * Where launchers say how far they have got.
 *
 * The only endpoint a runner ever calls, and the only one that resolves no user: the bearer token is
 * the caller, and it can do nothing but write progress into the work it was handed. That is what
 * makes this safe to expose to a cluster the browser API is not.
 *
 * Nothing reported here decides anything. Whether an execution succeeded is the platform's account
 * of the process, which is why a launcher never reports that it finished and why the numbers written
 * here are only ever overwritten, never accumulated.
 */
@Path("telemetry")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class TelemetryResource(private val store: TelemetryStore) {
    @POST
    @Transactional
    fun report(
        @HeaderParam("Authorization") authorization: String?,
        report: TelemetryReport,
    ): Response {
        val execution = Execution.findByToken(bearer(authorization)) ?: throw notAuthenticated()
        if (execution.state.isTerminal) {
            throw conflict("This execution has already finished")
        }
        val experimentId = execution.experiment.publicId
        val carried = execution.units.associateBy { it.scenarioIndex to it.seed }
        for (run in report.runs) {
            // A report naming work this execution is not carrying is dropped rather than refused:
            // one token writes one bag's progress, and a launcher that got that wrong is not a
            // reason to lose the runs it got right.
            val unit = carried[run.scenarioIndex to run.seed] ?: continue
            // Against the count settled at submit, not one the launcher sends: the denominator is
            // the platform's and holds still for the whole of a run.
            unit.completedTasks = run.completedTasks.coerceIn(0, unit.totalTasks)
            store.write(RunKey(experimentId, run.scenarioIndex, run.seed), run.series)
        }
        return Response.status(204).build()
    }

    private fun bearer(authorization: String?): String {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            throw notAuthenticated()
        }
        return authorization.removePrefix(BEARER).trim()
    }
}
