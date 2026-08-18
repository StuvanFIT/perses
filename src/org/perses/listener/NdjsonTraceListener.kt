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

import org.perses.reduction.AbstractReductionListener
import org.perses.reduction.PropertyTestResult
import org.perses.reduction.event.AbstractTestScriptExecutionEvent
import org.perses.reduction.event.AbstractTestScriptExecutionEvent.TestResultCacheHitEvent
import org.perses.reduction.event.AbstractTestScriptExecutionEvent.TestScriptExecutionCanceledEvent
import org.perses.reduction.event.AbstractTestScriptExecutionEvent.TestScriptExecutionEvent
import org.perses.reduction.event.BestProgramUpdateEvent
import org.perses.reduction.event.FixpointIterationStartEvent
import org.perses.reduction.event.ReductionEndEvent
import org.perses.spartree.AbstractTreeEditAction
import org.perses.spartree.NodeReplacementAction
import org.perses.spartree.ParserRuleSparTreeNode
import org.perses.util.FileStreamPool

/**
 * Emits one NDJSON record per property test candidate, so that an external tool can merge the
 * records with a log produced by the property test script itself and build a visualisation trace.
 *
 * The join key is [AbstractTestScriptExecutionEvent.workingDirectory], which is the current working
 * directory of the test script process. A wrapper script can read it from its own `$PWD`.
 *
 * ## Threading
 *
 * The mutable state in this class is deliberately unsynchronised. Every callback this class
 * overrides is dispatched by `AsyncReductionListenerManager.submitEvent`, which runs on a
 * single-threaded executor, so the callbacks are serialised.
 *
 * `onSlicingTokensStart` and `onSlicingTokensEnd` are the two callbacks that bypass that executor
 * and run on the calling reducer thread instead. This class must therefore never override them.
 *
 * ## What is read from the event
 *
 * Only immutable data is read here. Node identity ([org.perses.spartree.AbstractTreeNode.nodeId],
 * [org.perses.spartree.AbstractSparTreeNode.ruleName] and [ParserRuleSparTreeNode.ruleType]) is
 * fixed at node construction and is safe to read asynchronously. Anything that the reducer thread
 * keeps mutating -- `leafTokenCount`, `edit.tree.tokenCount` -- is not, which is why the pre-edit
 * token count is captured at event construction and carried on the event instead.
 *
 * This class also never touches `event.textualProgram`, which would re-render the whole candidate
 * program on the dispatch thread.
 *
 * ## What is deliberately not done here
 *
 * No transformation taxonomy is computed. The raw evidence -- the concrete edit class, the verbatim
 * action set description and the reducer short name -- is emitted instead, and classification is
 * left to the consumer. The description strings are unversioned free-form text with no test pinning
 * them, so matching on them here would break silently against upstream rewording.
 *
 * No node-level state is cached across events either. `onNodeReductionStart` is only fired by node
 * reducers and LLM reducers; for token slicers, line slicers, HDD, list-minimiser passes, Latra and
 * TREC it never fires, so a cache would go stale without any signal. All node identity is instead
 * read per candidate from the edit's action set, which is always populated.
 */
class NdjsonTraceListener(
  private val stream: FileStreamPool.ManagedPrintStream,
) : AbstractReductionListener() {
  /** Confined to the listener dispatch thread. See the class doc. */
  private var seq: Long = 0

  /** Confined to the listener dispatch thread. See the class doc. */
  private var reducerPass: Int = 0

  /** Confined to the listener dispatch thread. See the class doc. */
  private var reducerShortName: String? = null

  override fun onFixpointIterationStart(event: FixpointIterationStartEvent) {
    ++reducerPass
    reducerShortName = event.reducerClass.shortName
  }

  override fun onTestScriptExecution(event: TestScriptExecutionEvent) {
    writeRecord(event, EVENT_KIND_TEST_EXECUTION, event.result)
  }

  override fun onTestResultCacheHit(event: TestResultCacheHitEvent) {
    writeRecord(event, EVENT_KIND_CACHE_HIT, result = null)
  }

  override fun onTestScriptExecutionCancelled(event: TestScriptExecutionCanceledEvent) {
    writeRecord(event, EVENT_KIND_CANCELLED, result = null)
  }

  /**
   * Emitted as its own record rather than as a flag on the candidate record, because a candidate is
   * reported *before* its edit is applied: at the time the candidate record is written, whether it
   * will be accepted is not yet known. The consumer joins this record back to the candidate on
   * [editId], which is ground truth -- unlike inferring acceptance from a token-count decrease,
   * which misclassifies replacement and Latra edits that leave the token count unchanged.
   */
  override fun onBestProgramUpdated(event: BestProgramUpdateEvent) {
    val builder = StringBuilder(RECORD_BUILDER_INITIAL_CAPACITY)
    builder.append('{')
    builder.appendNumberField("seq", seq++)
    builder.append(',')
    builder.appendNumberField("reducerPass", reducerPass.toLong())
    builder.append(',')
    builder.appendStringField("reducerShortName", reducerShortName)
    builder.append(',')
    builder.appendNullableNumberField(
      "editId",
      event.editId.takeIf { it != BestProgramUpdateEvent.UNKNOWN_EDIT_ID },
    )
    builder.append(',')
    builder.appendNumberField("tokensBefore", event.programSizeBefore.toLong())
    builder.append(',')
    builder.appendNumberField("tokensAfter", event.programSize.toLong())
    builder.append(',')
    builder.appendStringField("eventKind", EVENT_KIND_BEST_UPDATE)
    builder.append('}')
    stream.println(builder.toString())
  }

  override fun onReductionEnd(event: ReductionEndEvent) {
    val builder = StringBuilder(RECORD_BUILDER_INITIAL_CAPACITY)
    builder.append('{')
    builder.appendNumberField("seq", seq++)
    builder.append(',')
    builder.appendNumberField("originalTokens", event.startEvent.initialProgramSize().toLong())
    builder.append(',')
    builder.appendNumberField("finalTokens", event.programSize.toLong())
    builder.append(',')
    builder.appendNumberField(
      "wallTimeMillis",
      event.currentTimeMillis - event.startEvent.currentTimeMillis,
    )
    builder.append(',')
    builder.appendNumberField(
      "scriptExecutionNumber",
      event.testScriptExecutorServiceStatistics.scriptExecutionNumber.toLong(),
    )
    builder.append(',')
    builder.appendNumberField(
      "externalCacheHitNumber",
      event.testScriptExecutorServiceStatistics.externalCacheHitNumber.toLong(),
    )
    builder.append(',')
    builder.appendNumberField("reducerPasses", reducerPass.toLong())
    builder.append(',')
    builder.appendStringField("eventKind", EVENT_KIND_REDUCTION_END)
    builder.append('}')
    stream.println(builder.toString())
    // ManagedPrintStream.close() only returns the rental; it does not flush.
    stream.flush()
  }

  override fun close() {
    stream.flush()
    stream.close()
  }

  private fun writeRecord(
    event: AbstractTestScriptExecutionEvent,
    eventKind: String,
    result: PropertyTestResult?,
  ) {
    val edit = event.edit
    val workingDirectory = event.workingDirectory
    val builder = StringBuilder(RECORD_BUILDER_INITIAL_CAPACITY)
    builder.append('{')
    builder.appendNumberField("seq", seq++)
    builder.append(',')
    builder.appendStringField("workDirId", workingDirectory?.fileName?.toString())
    builder.append(',')
    builder.appendStringField("workDirPath", workingDirectory?.toAbsolutePath()?.toString())
    builder.append(',')
    builder.appendNumberField("reducerPass", reducerPass.toLong())
    builder.append(',')
    builder.appendStringField("reducerShortName", reducerShortName)
    builder.append(',')
    builder.appendNumberField("editId", edit.id.toLong())
    builder.append(',')
    builder.appendStringField("editClass", edit.javaClass.simpleName)
    builder.append(',')
    builder.appendStringField("actionsDescription", edit.actionSet.actionsDescription)
    builder.append(',')
    builder.appendTargets(edit.actionSet.actions)
    builder.append(',')
    builder.appendNullableNumberField("tokensBefore", event.programSizeBefore.takeIf { it >= 0 })
    builder.append(',')
    builder.appendNumberField("tokensAfter", event.program.tokenCount.toLong())
    builder.append(',')
    builder.appendBooleanField("isInteresting", result?.isInteresting)
    builder.append(',')
    builder.appendNullableNumberField("exitCode", result?.exitCode?.intValue)
    builder.append(',')
    builder.appendNullableNumberField("elapsedMillis", result?.elapsedMillis)
    builder.append(',')
    builder.appendStringField("eventKind", eventKind)
    builder.append('}')
    stream.println(builder.toString())
  }

  companion object {
    const val EVENT_KIND_TEST_EXECUTION = "TEST_EXECUTION"
    const val EVENT_KIND_CACHE_HIT = "CACHE_HIT"
    const val EVENT_KIND_CANCELLED = "CANCELLED"
    const val EVENT_KIND_BEST_UPDATE = "BEST_UPDATE"
    const val EVENT_KIND_REDUCTION_END = "REDUCTION_END"

    private const val RECORD_BUILDER_INITIAL_CAPACITY = 512

    private fun StringBuilder.appendTargets(
      actions: Iterable<AbstractTreeEditAction>,
    ) {
      appendJsonString("targets")
      append(":[")
      var isFirst = true
      actions.forEach { action ->
        if (isFirst) {
          isFirst = false
        } else {
          append(',')
        }
        val targetNode = action.targetNode
        append('{')
        appendNumberField("nodeId", targetNode.nodeId.toLong())
        append(',')
        appendStringField("ruleName", targetNode.ruleName)
        append(',')
        // Only a parser rule node carries a rule type. Lexer rule nodes and placeholder nodes do
        // not, and token slicers operate exclusively on lexer rule nodes, so this must not be a
        // hard cast.
        appendStringField("ruleType", (targetNode as? ParserRuleSparTreeNode)?.ruleType?.name)
        append(',')
        appendNullableNumberField(
          "replacingNodeId",
          (action as? NodeReplacementAction)?.replacingNode?.nodeId,
        )
        append('}')
      }
      append(']')
    }

    private fun StringBuilder.appendStringField(
      name: String,
      value: String?,
    ) {
      appendJsonString(name)
      append(':')
      if (value == null) {
        append("null")
      } else {
        appendJsonString(value)
      }
    }

    private fun StringBuilder.appendNumberField(
      name: String,
      value: Long,
    ) {
      appendJsonString(name)
      append(':')
      append(value)
    }

    private fun StringBuilder.appendNullableNumberField(
      name: String,
      value: Int?,
    ) {
      appendJsonString(name)
      append(':')
      if (value == null) {
        append("null")
      } else {
        append(value)
      }
    }

    private fun StringBuilder.appendBooleanField(
      name: String,
      value: Boolean?,
    ) {
      appendJsonString(name)
      append(':')
      append(if (value == null) "null" else value.toString())
    }

    /**
     * Appends [value] as a quoted JSON string. Action set descriptions are free-form text that can
     * contain quotes and backslashes, so this must escape properly rather than just wrap in quotes.
     */
    private fun StringBuilder.appendJsonString(value: String) {
      append('"')
      for (element in value) {
        when (element) {
          '"' -> append("\\\"")
          '\\' -> append("\\\\")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          '\b' -> append("\\b")
          '\u000C' -> append("\\f")
          else ->
            if (element < ' ') {
              append("\\u")
              append(String.format("%04x", element.code))
            } else {
              append(element)
            }
        }
      }
      append('"')
    }
  }
}
