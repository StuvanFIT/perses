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

import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.perses.TestUtility
import org.perses.program.TokenizedProgram
import org.perses.reduction.AbstractReducerNameAndDesc
import org.perses.reduction.PropertyTestResult
import org.perses.reduction.event.AbstractTestScriptExecutionEvent.TestResultCacheHitEvent
import org.perses.reduction.event.AbstractTestScriptExecutionEvent.TestScriptExecutionCanceledEvent
import org.perses.reduction.event.AbstractTestScriptExecutionEvent.TestScriptExecutionEvent
import org.perses.reduction.event.FixpointIterationStartEvent
import org.perses.reduction.event.ReductionEndEvent
import org.perses.reduction.event.ReductionStartEvent
import org.perses.reduction.event.TestScriptExecutorServiceStatisticsSnapshot
import org.perses.spartree.AbstractSparTreeEdit
import org.perses.spartree.AbstractSparTreeNode
import org.perses.spartree.LexerRuleSparTreeNode
import org.perses.spartree.NodeDeletionActionSet
import org.perses.spartree.NodeReplacementActionSet
import org.perses.spartree.ParserRuleSparTreeNode
import org.perses.util.FileNameContentPair
import org.perses.util.FileStreamPool
import org.perses.util.shell.ExitCode
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

@RunWith(JUnit4::class)
class NdjsonTraceListenerTest {
  private val tempDir: Path = Files.createTempDirectory(this::class.simpleName!!)
  private val resultFile: Path = tempDir.resolve("trace.ndjson")
  private val streamPool = FileStreamPool()
  private val listener =
    NdjsonTraceListener(
      streamPool.rentStream(resultFile, this::class.qualifiedName!!),
    )

  private val tree = TestUtility.createSparTreeFromFile(Paths.get("test_data/parentheses/t.c"))

  /** Typed as the base class so that the lexer-node assertion below is a real runtime check. */
  private val printfToken: AbstractSparTreeNode =
    tree.getTokenNodeForText("printf").single()

  private val reductionStartEvent =
    ReductionStartEvent(
      currentTimeMillis = 100,
      tree = WeakReference(null),
      programSize = 42,
      commandLineOptions = "<cmd>",
    )

  @OptIn(ExperimentalPathApi::class)
  @After
  fun teardown() {
    listener.close()
    streamPool.close()
    tempDir.deleteRecursively()
  }

  @Test
  fun testSingleTestExecutionRecordHasExactContent() {
    val parserRuleNode = findParserRuleAncestorOfPrintf()
    val actionSet =
      NodeDeletionActionSet
        .Builder("token slicer@1")
        .deleteNode(parserRuleNode)
        .build()
    val edit = tree.createNodeDeletionEdit(actionSet)
    val workDir = tempDir.resolve("000042")

    startFixpointIteration()
    listener.onTestScriptExecution(
      TestScriptExecutionEvent(
        currentTimeMillis = 200,
        result = PropertyTestResult(exitCode = ExitCode(1), elapsedMillis = 37),
        program = edit.program,
        edit = edit,
        outputCreator = FAILING_OUTPUT_CREATOR,
        workingDirectory = workDir,
        programSizeBefore = 120,
      ),
    )
    flush()

    assertThat(readRecords()).containsExactly(
      "{" +
        """"seq":0,""" +
        """"workDirId":"000042",""" +
        """"workDirPath":"${workDir.toAbsolutePath()}",""" +
        """"reducerPass":1,""" +
        """"reducerShortName":"fake_reducer",""" +
        """"editId":${edit.id},""" +
        """"editClass":"NodeDeletionTreeEdit",""" +
        """"actionsDescription":"token slicer@1",""" +
        """"targets":[{"nodeId":${parserRuleNode.nodeId},""" +
        """"ruleName":${expectedRuleNameJson(parserRuleNode)},""" +
        """"ruleType":"${parserRuleNode.ruleType.name}",""" +
        """"replacingNodeId":null}],""" +
        """"tokensBefore":120,""" +
        """"tokensAfter":${edit.program.tokenCount},""" +
        """"isInteresting":false,""" +
        """"exitCode":1,""" +
        """"elapsedMillis":37,""" +
        """"eventKind":"TEST_EXECUTION"}""",
    )
  }

  /**
   * Token slicers operate exclusively on lexer rule nodes, which carry no rule type. The listener
   * must emit null rather than blowing up on a hard cast.
   */
  @Test
  fun testLexerRuleNodeCandidateEmitsNullRuleTypeAndDoesNotThrow() {
    // Guards the point of the test: if this stops being a lexer
    // node, the assertion below becomes vacuous.
    assertThat(printfToken).isInstanceOf(LexerRuleSparTreeNode::class.java)
    val actionSet =
      NodeDeletionActionSet
        .Builder("token slicer@1")
        .deleteNode(printfToken)
        .build()
    val edit = tree.createNodeDeletionEdit(actionSet)

    startFixpointIteration()
    listener.onTestScriptExecution(
      TestScriptExecutionEvent(
        currentTimeMillis = 200,
        result = PropertyTestResult(exitCode = ExitCode.ZERO, elapsedMillis = 5),
        program = edit.program,
        edit = edit,
        outputCreator = FAILING_OUTPUT_CREATOR,
        workingDirectory = tempDir.resolve("000001"),
        programSizeBefore = 120,
      ),
    )
    flush()

    val record = readRecords().single()
    assertThat(record).contains(
      """"targets":[{"nodeId":${printfToken.nodeId},""" +
        """"ruleName":${expectedRuleNameJson(printfToken)},""" +
        """"ruleType":null,"replacingNodeId":null}]""",
    )
    assertThat(record).contains(""""isInteresting":true""")
    assertThat(record).contains(""""exitCode":0""")
  }

  @Test
  fun testReplacementEditCarriesReplacingNodeId() {
    val target = printfToken.parent!!
    val actionSet =
      NodeReplacementActionSet.createByReplacingSingleNode(
        targetNode = target,
        replacingNode = printfToken,
        actionsDescription = "replacement",
      )
    val edit = tree.createNodeReplacementEdit(actionSet)

    startFixpointIteration()
    emitExecution(edit)
    flush()

    assertThat(readRecords().single()).contains(
      """"targets":[{"nodeId":${target.nodeId},""" +
        """"ruleName":${expectedRuleNameJson(target)},""" +
        """"ruleType":${expectedRuleTypeJson(target)},""" +
        """"replacingNodeId":${printfToken.nodeId}}]""",
    )
  }

  @Test
  fun testCacheHitRecordHasNoWorkDirAndNoResultFields() {
    val edit = createDeletionEditOnPrintf("list minimizer@3")

    startFixpointIteration()
    listener.onTestResultCacheHit(
      TestResultCacheHitEvent(
        currentTimeMillis = 200,
        program = edit.program,
        edit = edit,
        outputCreator = FAILING_OUTPUT_CREATOR,
      ),
    )
    flush()

    val record = readRecords().single()
    assertThat(record).contains(""""workDirId":null""")
    assertThat(record).contains(""""workDirPath":null""")
    assertThat(record).contains(""""tokensBefore":null""")
    assertThat(record).contains(""""isInteresting":null""")
    assertThat(record).contains(""""exitCode":null""")
    assertThat(record).contains(""""elapsedMillis":null""")
    assertThat(record).contains(""""eventKind":"CACHE_HIT"}""")
  }

  @Test
  fun testCancelledRecordKeepsWorkDirButHasNoResultFields() {
    val edit = createDeletionEditOnPrintf("token slicer@2")
    val workDir = tempDir.resolve("000007")

    startFixpointIteration()
    listener.onTestScriptExecutionCancelled(
      TestScriptExecutionCanceledEvent(
        currentTimeMillis = 200,
        millisToCancelTheTask = 12,
        program = edit.program,
        edit = edit,
        outputCreator = FAILING_OUTPUT_CREATOR,
        workingDirectory = workDir,
        programSizeBefore = 99,
      ),
    )
    flush()

    val record = readRecords().single()
    assertThat(record).contains(""""workDirId":"000007",""")
    assertThat(record).contains(""""tokensBefore":99""")
    assertThat(record).contains(""""isInteresting":null""")
    assertThat(record).contains(""""eventKind":"CANCELLED"}""")
  }

  @Test
  fun testSeqIsMonotonicAndReducerPassTracksFixpointIterations() {
    val edit = createDeletionEditOnPrintf("desc")

    startFixpointIteration()
    emitExecution(edit)
    emitExecution(edit)
    startFixpointIteration()
    emitExecution(edit)
    flush()

    val records = readRecords()
    assertThat(records).hasSize(3)
    assertThat(records[0]).contains(""""seq":0,""")
    assertThat(records[1]).contains(""""seq":1,""")
    assertThat(records[2]).contains(""""seq":2,""")
    assertThat(records[0]).contains(""""reducerPass":1,""")
    assertThat(records[1]).contains(""""reducerPass":1,""")
    assertThat(records[2]).contains(""""reducerPass":2,""")
  }

  /**
   * Action set descriptions are free-form text produced by reducers, so the writer must escape them
   * rather than just wrap them in quotes.
   */
  @Test
  fun testActionsDescriptionIsJsonEscaped() {
    val edit = createDeletionEditOnPrintf("a \"quoted\" \\ back\tslash\nnewline")

    startFixpointIteration()
    emitExecution(edit)
    flush()

    assertThat(readRecords().single()).contains(
      """"actionsDescription":"a \"quoted\" \\ back\tslash\nnewline",""",
    )
  }

  @Test
  fun testRecordIsEmittedEvenWithoutAnyFixpointIteration() {
    val edit = createDeletionEditOnPrintf("desc")

    emitExecution(edit)
    flush()

    val record = readRecords().single()
    assertThat(record).contains(""""reducerPass":0,""")
    assertThat(record).contains(""""reducerShortName":null""")
  }

  /**
   * The summary record emitted by [NdjsonTraceListener.onReductionEnd]. The consumer relies on it
   * for authoritative totals rather than inferring them from the candidate records.
   */
  @Test
  fun testReductionEndRecordCarriesTotals() {
    startFixpointIteration()
    emitExecution(createDeletionEditOnPrintf("desc"))
    flush()

    val endRecords = readAllRecords().filter { it.contains(""""eventKind":"REDUCTION_END"""") }
    assertThat(endRecords).containsExactly(
      "{" +
        """"seq":1,""" +
        """"originalTokens":42,""" +
        """"finalTokens":10,""" +
        """"wallTimeMillis":900,""" +
        """"scriptExecutionNumber":0,""" +
        """"externalCacheHitNumber":0,""" +
        """"reducerPasses":1,""" +
        """"eventKind":"REDUCTION_END"}""",
    )
  }

  private fun createDeletionEditOnPrintf(description: String): AbstractSparTreeEdit<*> =
    tree.createNodeDeletionEdit(
      NodeDeletionActionSet
        .Builder(description)
        .deleteNode(printfToken)
        .build(),
    )

  private fun emitExecution(edit: AbstractSparTreeEdit<*>) {
    listener.onTestScriptExecution(
      TestScriptExecutionEvent(
        currentTimeMillis = 200,
        result = PropertyTestResult(exitCode = ExitCode(1), elapsedMillis = 1),
        program = edit.program,
        edit = edit,
        outputCreator = FAILING_OUTPUT_CREATOR,
        workingDirectory = tempDir.resolve("000000"),
        programSizeBefore = 10,
      ),
    )
  }

  private fun startFixpointIteration() {
    listener.onFixpointIterationStart(
      FixpointIterationStartEvent(
        reductionStartEvent,
        100,
        42,
        1,
        AbstractReducerNameAndDesc(shortName = "fake_reducer", description = "fake"),
        treeStructureDumper = { "" },
        testScriptStatistics =
          TestScriptExecutorServiceStatisticsSnapshot(
            scriptExecutionNumber = 0,
            externalCacheHitNumber = 0,
          ),
      ),
    )
  }

  /** Drives the flush through the same path production uses. */
  private fun flush() {
    listener.onReductionEnd(
      ReductionEndEvent(
        reductionStartEvent,
        currentTimeMillis = 1000,
        programSize = 10,
        TestScriptExecutorServiceStatisticsSnapshot(
          scriptExecutionNumber = 0,
          externalCacheHitNumber = 0,
        ),
      ),
    )
  }

  /**
   * The candidate records only. [flush] drives the flush through [NdjsonTraceListener.onReductionEnd],
   * which also emits a REDUCTION_END summary record; that record is asserted separately by
   * [testReductionEndRecordCarriesTotals] rather than in every candidate assertion.
   */
  private fun readRecords(): List<String> =
    readAllRecords().filterNot { it.contains(""""eventKind":"REDUCTION_END"""") }

  private fun readAllRecords(): List<String> =
    Files.readAllLines(resultFile, StandardCharsets.UTF_8).filter { it.isNotBlank() }

  /**
   * Walks up from a token rather than down from the root, so the result is an interior node of the
   * kind a node reducer actually targets, and never the root itself.
   */
  private fun findParserRuleAncestorOfPrintf(): ParserRuleSparTreeNode {
    var current: AbstractSparTreeNode? = printfToken.parent
    while (current != null) {
      if (current is ParserRuleSparTreeNode) {
        return current
      }
      current = current.parent
    }
    error("The printf token has no parser rule ancestor.")
  }

  private fun expectedRuleTypeJson(node: AbstractSparTreeNode): String {
    val ruleType = (node as? ParserRuleSparTreeNode)?.ruleType ?: return "null"
    return "\"${ruleType.name}\""
  }

  private fun expectedRuleNameJson(node: AbstractSparTreeNode): String {
    val ruleName = node.ruleName ?: return "null"
    return "\"$ruleName\""
  }

  companion object {
    /**
     * The listener must never render the candidate program, so any call into the output creator is
     * a test failure.
     */
    private val FAILING_OUTPUT_CREATOR:
      (TokenizedProgram) -> ImmutableList<FileNameContentPair<String>> =
      { error("The listener must not render the textual program.") }
  }
}
