/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.calc;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

@NodeInfo(shortName = ">>")
public final class RightShiftNode extends ShiftNode implements Canonicalizable {

    public RightShiftNode(Kind kind, ValueNode x, ValueNode y) {
        super(kind, x, y);
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        if (x().stamp() instanceof IntegerStamp && ((IntegerStamp) x().stamp()).isPositive()) {
            return graph().unique(new UnsignedRightShiftNode(kind(), x(), y()));
        }
        if (y().isConstant()) {
            int amount = y().asConstant().asInt();
            int originalAmout = amount;
            int mask;
            if (kind() == Kind.Int) {
                mask = 0x1f;
            } else {
                assert kind() == Kind.Long;
                mask = 0x3f;
            }
            amount &= mask;
            if (x().isConstant()) {
                if (kind() == Kind.Int) {
                    return ConstantNode.forInt(x().asConstant().asInt() >> amount, graph());
                } else {
                    assert kind() == Kind.Long;
                    return ConstantNode.forLong(x().asConstant().asLong() >> amount, graph());
                }
            }
            if (amount == 0) {
                return x();
            }
            if (x() instanceof ShiftNode) {
                ShiftNode other = (ShiftNode) x();
                if (other.y().isConstant()) {
                    int otherAmount = other.y().asConstant().asInt() & mask;
                    if (other instanceof RightShiftNode) {
                        int total = amount + otherAmount;
                        if (total != (total & mask)) {
                            assert other.x().stamp() instanceof IntegerStamp;
                            IntegerStamp istamp = (IntegerStamp) other.x().stamp();

                            if (istamp.isPositive()) {
                                return ConstantNode.forIntegerKind(kind(), 0, graph());
                            }
                            if (istamp.isStrictlyNegative()) {
                                return ConstantNode.forIntegerKind(kind(), -1L, graph());
                            }

                            /*
                             * if we cannot replace both shifts with a constant, replace them by a
                             * full shift for this kind
                             */
                            assert total >= mask;
                            return graph().unique(new RightShiftNode(kind(), other.x(), ConstantNode.forInt(mask, graph())));
                        }
                        return graph().unique(new RightShiftNode(kind(), other.x(), ConstantNode.forInt(total, graph())));
                    }
                }
            }
            if (originalAmout != amount) {
                return graph().unique(new RightShiftNode(kind(), x(), ConstantNode.forInt(amount, graph())));
            }
        }
        return this;
    }

    @Override
    public void generate(ArithmeticLIRGenerator gen) {
        gen.setResult(this, gen.emitShr(gen.operand(x()), gen.operand(y())));
    }
}
