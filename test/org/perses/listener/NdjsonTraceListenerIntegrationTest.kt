/*
 * Copyright (C) 2018-2025 University of Waterloo.
 *
 * This file is part of Perses.
 *
 * Perses is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3, or (at your option) any later version.
 *
 * Perses is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Perses; see the file LICENSE.  If not see <http://www.gnu.org/licenses/>.
 */
package org.perses.listener

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.perses.reduction.ReducerFunctionalTestUtility
import org.perses.reduction.reducer.PersesNodePrioritizedDfsReducer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * Runs a real reduction over a small C benchmark with `--trace-ndjson-file` enabled and checks the
 * properties the trace has to satisfy for an external tool to be able to join it against a log
 * written by the property test script.
 */
@RunWith(JUnit4::class)
class NdjsonTraceListenerIntegrationTest {
  /**
   * Deliberately outside the reduction working directory, which the test utility deletes when it
   * closes.
   */
  private val traceDir: Path = Files.createTempDirectory(this::class.simpleName!!)
  private val traceFile: Path = traceDir.resolve("trace.ndjson")

  @OptIn(ExperimentalPathApi::class)
  @After
  fun teardown() {
    traceDir.deleteRecursively()
  }

  @Test
  fun testTraceIsJoinableAndConsistentWithTestScriptStatistics() {
    var scriptExecutionNumber = -1
    var externalCacheHitNumber = -1
    ReducerFunctionalTestUtility(
      reductionFolder = "test_data/ndjson_trace",
      testScript = "r.sh",
      sourceFile = "t.c",
      reducerAnnotation = PersesNodePrioritizedDfsReducer.META,
      cmdCustomizer = { it.profilingFlags.traceNdjsonFile = traceFile },
    ).use { utility ->
      utility.reductionDriver.reduce()
      val statistics = utility.reducerContext.executorService.statistics
      scriptExecutionNumber = statistics.scriptExecutionNumber
      externalCacheHitNumber = statistics.externalCacheHitNumber
    }
    // The utility is closed here, so the listener manager has drained its dispatch queue, the
    // listener has been closed and the stream pool has flushed and closed the file.

    val records = readRecords()
    assertThat(records).isNotEmpty()

    val executions = records.filter { it.kind == "TEST_EXECUTION" }
    assertThat(executions).isNotEmpty()

    // Every candidate that actually reached the test script must be joinable, and the join key must
    // identify exactly one candidate.
    val workDirIds = executions.map { it.workDirId }
    assertThat(workDirIds).doesNotContain(null)
    assertThat(workDirIds).containsNoDuplicates()

    // A cache hit never runs the script, so it has nothing to join against.
    records.filter { it.kind == "CACHE_HIT" }.forEach {
      assertThat(it.workDirId).isNull()
    }

    // The global execution cache is disabled by the test utility, so every hit counted by the
    // executor service would be invisible in the trace. Assert it stayed off, otherwise the bound
    // below is not meaningful.
    assertThat(externalCacheHitNumber).isEqualTo(0)

    // Each TEST_EXECUTION record corresponds to one script execution. The reverse does not hold:
    // a task can be cancelled after its script already ran, and the state-based reducers do not
    // report cancellations at all, so the script execution counter is an upper bound.
    assertThat(executions.size).isAtMost(scriptExecutionNumber)
    assertThat(scriptExecutionNumber).isGreaterThan(0)

    // Every record must name the reducer that produced it, and the pass counter must be positive
    // and non-decreasing over the file.
    var previousPass = 0
    records.forEach { record ->
      assertThat(record.reducerShortName).isNotNull()
      assertThat(record.reducerPass).isAtLeast(previousPass)
      previousPass = record.reducerPass
    }
    assertThat(previousPass).isAtLeast(1)

    // seq is dense and monotonic, which is what lets a consumer detect a truncated file.
    assertThat(records.map { it.seq }).isEqualTo(records.indices.toList())
  }

  private fun readRecords(): List<Record> =
    Files
      .readAllLines(traceFile, StandardCharsets.UTF_8)
      .filter { it.isNotBlank() }
      .map { line ->
        Record(
          seq = requireNotNull(intField(line, "seq")) { "seq must be present in $line" },
          workDirId = stringField(line, "workDirId"),
          reducerPass =
            requireNotNull(intField(line, "reducerPass")) { "reducerPass missing in $line" },
          reducerShortName = stringField(line, "reducerShortName"),
          kind = requireNotNull(stringField(line, "eventKind")) { "eventKind missing in $line" },
        )
      }

  private data class Record(
    val seq: Int,
    val workDirId: String?,
    val reducerPass: Int,
    val reducerShortName: String?,
    val kind: String,
  )

  companion object {
    /**
     * A deliberately small reader rather than a JSON library dependency. It only has to cope with
     * the flat scalar fields this test inspects, none of which can contain an escape.
     */
    private fun stringField(
      line: String,
      name: String,
    ): String? {
      val nullMatch = Regex(""""${Regex.escape(name)}":null""").find(line)
      if (nullMatch != null) {
        return null
      }
      val match = Regex(""""${Regex.escape(name)}":"([^"\\]*)"""").find(line)
      return requireNotNull(match) { "Field $name not found in $line" }.groupValues[1]
    }

    private fun intField(
      line: String,
      name: String,
    ): Int? {
      val match = Regex(""""${Regex.escape(name)}":(-?\d+|null)""").find(line)
      val value = requireNotNull(match) { "Field $name not found in $line" }.groupValues[1]
      return if (value == "null") null else value.toInt()
    }
  }
}
