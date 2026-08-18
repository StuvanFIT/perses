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
package org.perses.spartree

abstract class AbstractSparTreeEditListener {
  class SparTreeEditEvent(
    val programSizeBefore: Int,
    edit: AbstractSparTreeEdit<*>,
  ) {
    val program = edit.program

    /**
     * Identifies the edit that was applied. Only the id is retained, not the edit itself, because
     * an edit holds a reference to the whole spar-tree.
     */
    val editId = edit.id
  }

  abstract fun onAfterSparTreeEditApplied(event: SparTreeEditEvent)
}
