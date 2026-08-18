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

class BestProgramUpdateEvent(
  val currentFixpointIteration: FixpointIterationStartEvent,
  currentTimeMillis: Long,
  val programSizeBefore: Int,
  programSizeAfter: Int,
  /**
   * The [org.perses.spartree.AbstractSparTreeEdit.id] of the edit that was applied to produce this
   * new best program. It is ground truth for "this candidate was accepted", which cannot be
   * inferred reliably from the token count alone: a replacement or Latra transformation can be
   * applied while leaving the token count unchanged.
   */
  val editId: Int = UNKNOWN_EDIT_ID,
) : AbstractReductionEventWithProgramSize(currentTimeMillis, programSizeAfter) {
  init {
    // FIXME(cnsun): this also needs to check the num of chars of tokens in the case of ==.
    //   FIXME(cnsun): fix this assertion
    //   check(programSizeBefore >= programSizeAfter)
  }

  override fun initialProgramSize() = currentFixpointIteration.initialProgramSize()

  override val prefixLabelFromRootToHere: String
    get() = currentFixpointIteration.prefixLabelFromRootToHere

  companion object {
    /** The value of [editId] when the notification site cannot supply it. */
    const val UNKNOWN_EDIT_ID = -1
  }
}
