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
package org.perses.reduction.event

import com.google.common.collect.ImmutableList
import org.perses.program.TokenizedProgram
import org.perses.reduction.PropertyTestResult
import org.perses.spartree.AbstractSparTreeEdit
import org.perses.util.FileNameContentPair
import java.nio.file.Path

sealed class AbstractTestScriptExecutionEvent(
  currentTimeMillis: Long,
  val program: TokenizedProgram,
  val edit: AbstractSparTreeEdit<*>,
  outputCreator: (TokenizedProgram) -> ImmutableList<FileNameContentPair<String>>,
  /**
   * The per-candidate working directory that the property test script is executed in, i.e., the
   * script's current working directory. External tooling can use it to join a record emitted by a
   * listener with a line logged by the test script itself.
   *
   * This is null when the notification site cannot supply it. In particular, it is always null for
   * [TestResultCacheHitEvent]: the query cache is consulted on a different thread from the one that
   * creates the working directory, and on a cache hit no script is executed anyway, so there is
   * nothing to join with.
   */
  val workingDirectory: Path? = null,
  /**
   * The token count of the program this candidate was derived from, or [UNKNOWN_PROGRAM_SIZE] when
   * the notification site cannot supply it.
   *
   * This is captured when the event is created, on the reducer thread, because the spar-tree it is
   * read from is mutable and cannot be read safely once the event has been handed to the
   * asynchronous listener dispatcher.
   */
  val programSizeBefore: Int = -1,
) : AbstractReductionEvent(currentTimeMillis) {
  val textualProgram =
    LazyProgramOutputer(
      program,
      outputCreator,
    )

  class TestScriptExecutionEvent(
    currentTimeMillis: Long,
    val result: PropertyTestResult,
    program: TokenizedProgram,
    edit: AbstractSparTreeEdit<*>,
    outputCreator: (TokenizedProgram) -> ImmutableList<FileNameContentPair<String>>,
    workingDirectory: Path? = null,
    programSizeBefore: Int = -1,
  ) : AbstractTestScriptExecutionEvent(
      currentTimeMillis,
      program,
      edit,
      outputCreator,
      workingDirectory,
      programSizeBefore,
    )

  class TestResultCacheHitEvent(
    currentTimeMillis: Long,
    program: TokenizedProgram,
    edit: AbstractSparTreeEdit<*>,
    outputCreator: (TokenizedProgram) -> ImmutableList<FileNameContentPair<String>>,
  ) : AbstractTestScriptExecutionEvent(currentTimeMillis, program, edit, outputCreator)

  class TestScriptExecutionCanceledEvent(
    currentTimeMillis: Long,
    val millisToCancelTheTask: Int,
    program: TokenizedProgram,
    edit: AbstractSparTreeEdit<*>,
    outputCreator: (TokenizedProgram) -> ImmutableList<FileNameContentPair<String>>,
    workingDirectory: Path? = null,
    programSizeBefore: Int = -1,
  ) : AbstractTestScriptExecutionEvent(
      currentTimeMillis,
      program,
      edit,
      outputCreator,
      workingDirectory,
      programSizeBefore,
    )

  companion object {
    /** The value of [programSizeBefore] when the notification site cannot supply it. */
    const val UNKNOWN_PROGRAM_SIZE = -1
  }
}
