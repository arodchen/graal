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
package com.oracle.graal.nodes.extended;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.type.*;

/**
 * Accesses a value at an memory address specified by an {@linkplain #object object} and a
 * {@linkplain #nullCheckLocation() location}. The access does not include a null check on the
 * object.
 */
public abstract class AccessNode extends DeoptimizingFixedWithNextNode implements Access, GuardingNode {

    @Input private GuardingNode guard;
    @Input private ValueNode object;
    @Input private ValueNode location;
    private boolean nullCheck;
    private WriteBarrierType barrierType;
    private boolean compress;

    public ValueNode object() {
        return object;
    }

    public LocationNode location() {
        return (LocationNode) location;
    }

    public LocationNode nullCheckLocation() {
        return (LocationNode) location;
    }

    public boolean getNullCheck() {
        return nullCheck;
    }

    public void setNullCheck(boolean check) {
        this.nullCheck = check;
    }

    public AccessNode(ValueNode object, ValueNode location, Stamp stamp) {
        this(object, location, stamp, null, WriteBarrierType.NONE, false);
    }

    public AccessNode(ValueNode object, ValueNode location, Stamp stamp, WriteBarrierType barrierType, boolean compress) {
        this(object, location, stamp, null, barrierType, compress);
    }

    public AccessNode(ValueNode object, ValueNode location, Stamp stamp, GuardingNode guard, WriteBarrierType barrierType, boolean compress) {
        super(stamp);
        this.object = object;
        this.location = location;
        this.guard = guard;
        this.barrierType = barrierType;
        this.compress = compress;
    }

    @Override
    public AccessNode asNode() {
        return this;
    }

    @Override
    public boolean canDeoptimize() {
        return nullCheck;
    }

    @Override
    public DeoptimizationReason getDeoptimizationReason() {
        return DeoptimizationReason.NullCheckException;
    }

    @Override
    public GuardingNode getGuard() {
        return guard;
    }

    @Override
    public void setGuard(GuardingNode guard) {
        updateUsages(this.guard == null ? null : this.guard.asNode(), guard == null ? null : guard.asNode());
        this.guard = guard;
    }

    @Override
    public WriteBarrierType getWriteBarrierType() {
        return barrierType;
    }

    @Override
    public boolean compress() {
        return compress;
    }
}
