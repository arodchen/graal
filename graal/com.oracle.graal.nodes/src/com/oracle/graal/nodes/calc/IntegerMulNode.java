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

import com.oracle.max.cri.ci.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;

@NodeInfo(shortName = "*")
public final class IntegerMulNode extends IntegerArithmeticNode implements Canonicalizable, LIRLowerable {

    public IntegerMulNode(RiKind kind, ValueNode x, ValueNode y) {
        super(kind, x, y);
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        if (x().isConstant() && !y().isConstant()) {
            return graph().unique(new IntegerMulNode(kind(), y(), x()));
        }
        if (x().isConstant()) {
            if (kind() == RiKind.Int) {
                return ConstantNode.forInt(x().asConstant().asInt() * y().asConstant().asInt(), graph());
            } else {
                assert kind() == RiKind.Long;
                return ConstantNode.forLong(x().asConstant().asLong() * y().asConstant().asLong(), graph());
            }
        } else if (y().isConstant()) {
            long c = y().asConstant().asLong();
            if (c == 1) {
                return x();
            }
            if (c == 0) {
                return ConstantNode.defaultForKind(kind(), graph());
            }
            if (c > 0 && CiUtil.isPowerOf2(c)) {
                return graph().unique(new LeftShiftNode(kind(), x(), ConstantNode.forInt(CiUtil.log2(c), graph())));
            }
            // canonicalize expressions like "(a * 1) * 2"
            if (x() instanceof IntegerMulNode) {
                IntegerMulNode other = (IntegerMulNode) x();
                if (other.y().isConstant()) {
                    ConstantNode sum;
                    if (kind() == RiKind.Int) {
                        sum = ConstantNode.forInt(y().asConstant().asInt() * other.y().asConstant().asInt(), graph());
                    } else {
                        assert kind() == RiKind.Long;
                        sum = ConstantNode.forLong(y().asConstant().asLong() * other.y().asConstant().asLong(), graph());
                    }
                    return graph().unique(new IntegerMulNode(kind(), other.x(), sum));
                }
            }
        }
        return this;
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
        RiValue op1 = gen.operand(x());
        RiValue op2 = gen.operand(y());
        if (!y().isConstant() && !FloatAddNode.livesLonger(this, y(), gen)) {
            RiValue op = op1;
            op1 = op2;
            op2 = op;
        }
        gen.setResult(this, gen.emitMul(op1, op2));
    }
}
