/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.phases;

import java.util.*;

import com.oracle.graal.nodes.*;

public class PhaseSuite<C> extends BasePhase<C> {

    private final List<BasePhase<? super C>> phases;

    public PhaseSuite() {
        this.phases = new ArrayList<>();
    }

    /**
     * Add a new phase at the beginning of this suite.
     */
    public final void prependPhase(BasePhase<? super C> phase) {
        phases.add(0, phase);
    }

    /**
     * Add a new phase at the end of this suite.
     */
    public final void appendPhase(BasePhase<? super C> phase) {
        phases.add(phase);
    }

    public final ListIterator<BasePhase<? super C>> findPhase(Class<? extends BasePhase<? super C>> phaseClass) {
        ListIterator<BasePhase<? super C>> it = phases.listIterator();
        findNextPhase(it, phaseClass);
        return it;
    }

    public static <C> void findNextPhase(ListIterator<BasePhase<? super C>> it, Class<? extends BasePhase<? super C>> phaseClass) {
        while (it.hasNext()) {
            BasePhase<? super C> phase = it.next();
            if (phaseClass.isInstance(phase)) {
                break;
            }
        }
    }

    @Override
    protected void run(StructuredGraph graph, C context) {
        for (BasePhase<? super C> phase : phases) {
            phase.apply(graph, context);
        }
    }
}
