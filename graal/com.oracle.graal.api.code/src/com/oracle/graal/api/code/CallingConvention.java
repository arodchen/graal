/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.api.code;

import static com.oracle.graal.api.code.ValueUtil.*;

import com.oracle.graal.api.meta.*;


/**
 * A calling convention describes the locations in which the arguments for a call are placed,
 * the location in which the return value is placed if the call is not void and any
 * temporary locations used (and killed) by the call.
 */
public class CallingConvention {

    /**
     * Constants denoting the type of a call for which a calling convention is requested.
     */
    public enum Type {
        /**
         * A request for the outgoing argument locations at a call site to Java code.
         */
        JavaCall(true),

        /**
         * A request for the incoming argument locations.
         */
        JavaCallee(false),

        /**
         * A request for the outgoing argument locations at a call site to the runtime (which may be Java or native code).
         */
        RuntimeCall(true),

        /**
         * A request for the outgoing argument locations at a call site to
         * external native code that complies with the platform ABI.
         */
        NativeCall(true);

        /**
         * Determines if this is a request for the outgoing argument locations at a call site.
         */
        public final boolean out;

        public static final Type[] VALUES = values();

        private Type(boolean out) {
            this.out = out;
        }
    }

    /**
     * The amount of stack space (in bytes) required for the stack-based arguments of the call.
     */
    private final int stackSize;

    private final Value returnLocation;

    /**
     * The locations in which the arguments are placed. This array ordered by argument index.
     */
    private final Value[] argumentLocations;

    /**
     * The locations used by the call in addition to the arguments are placed.
     * From the perspective of register allocation, these locations are killed by the call.
     */
    private final Value[] temporaryLocations;

    public CallingConvention(int stackSize, Value returnLocation, Value... argumentLocations) {
        this(Value.NONE, stackSize, returnLocation, argumentLocations);
    }

    public CallingConvention(Value[] temporaryLocations, int stackSize, Value returnLocation, Value... argumentLocations) {
        assert argumentLocations != null;
        assert temporaryLocations != null;
        assert returnLocation != null;
        this.argumentLocations = argumentLocations;
        this.stackSize = stackSize;
        this.returnLocation = returnLocation;
        this.temporaryLocations = temporaryLocations;
        assert verify();
    }

    /**
     * @return the location for the return value or {@link Value#IllegalValue} if a void call
     */
    public Value getReturn() {
        return returnLocation;
    }

    /**
     * @return the location for the {@code index}'th argument
     */
    public Value getArgument(int index) {
        return argumentLocations[index];
    }

    /**
     * @return the amount of stack space (in bytes) required for the stack-based arguments of the call.
     */
    public int getStackSize() {
        return stackSize;
    }

    /**
     * @return the number of locations required for the arguments
     */
    public int getArgumentCount() {
        return argumentLocations.length;
    }

    /**
     * Gets a location used by the call in addition to the arguments are placed.
     * From the perspective of register allocation, these locations are killed by the call.
     *
     * @return the {@code index}'th temporary location used by the call
     */
    public Value getTemporary(int index) {
        return temporaryLocations[index];
    }

    /**
     * @return the number of temporary locations used by the call
     */
    public int getTemporaryCount() {
        return temporaryLocations.length;
    }

    /**
     * Gets the temporary locations used (and killed) by the call.
     */
    public Value[] getTemporaries() {
        if (temporaryLocations.length == 0) {
            return temporaryLocations;
        }
        return temporaryLocations.clone();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CallingConvention[");
        String sep = "";
        for (Value op : argumentLocations) {
            sb.append(sep).append(op);
            sep = ", ";
        }
        if (returnLocation != Value.IllegalValue) {
            sb.append(" -> ").append(returnLocation);
        }
        if (temporaryLocations.length != 0) {
            sb.append("; temps=");
            sep = "";
            for (Value op : temporaryLocations) {
                sb.append(sep).append(op);
                sep = ", ";
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private boolean verify() {
        for (int i = 0; i < argumentLocations.length; i++) {
            Value location = argumentLocations[i];
            assert isStackSlot(location) || isRegister(location);
        }
        return true;
    }
}
