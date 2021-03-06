/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.amd64;

import com.oracle.graal.amd64.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.bridge.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.amd64.*;
import com.oracle.graal.lir.amd64.AMD64Call.DirectCallOp;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind;

/**
 * A direct call that complies with the conventions for such calls in HotSpot. In particular, for
 * calls using an inline cache, a MOVE instruction is emitted just prior to the aligned direct call.
 */
@Opcode("CALL_DIRECT")
final class AMD64HotspotDirectStaticCallOp extends DirectCallOp {

    private final Constant metaspaceMethod;
    private final InvokeKind invokeKind;

    AMD64HotspotDirectStaticCallOp(ResolvedJavaMethod target, Value result, Value[] parameters, Value[] temps, LIRFrameState state, InvokeKind invokeKind, Constant metaspaceMethod) {
        super(target, result, parameters, temps, state);
        assert invokeKind == InvokeKind.Static || invokeKind == InvokeKind.Special;
        this.metaspaceMethod = metaspaceMethod;
        this.invokeKind = invokeKind;
    }

    @Override
    public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
        // The mark for an invocation that uses an inline cache must be placed at the
        // instruction that loads the Klass from the inline cache.
        AMD64Move.move(tasm, masm, AMD64.rbx.asValue(Kind.Long), metaspaceMethod);
        tasm.recordMark(invokeKind == InvokeKind.Static ? Marks.MARK_INVOKESTATIC : Marks.MARK_INVOKESPECIAL);
        AMD64Move.move(tasm, masm, AMD64.rax.asValue(Kind.Long), Constant.forLong(HotSpotGraalRuntime.graalRuntime().getConfig().nonOopBits));
        super.emitCode(tasm, masm);
    }
}
