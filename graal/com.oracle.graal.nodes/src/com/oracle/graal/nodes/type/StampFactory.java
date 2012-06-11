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
package com.oracle.graal.nodes.type;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.type.GenericStamp.GenericStampType;


public class StampFactory {

    private static final Stamp[] stampCache = new Stamp[Kind.values().length];
    private static final Stamp objectStamp = new ObjectStamp(null, false, false);
    private static final Stamp objectNonNullStamp = new ObjectStamp(null, false, true);
    private static final Stamp dependencyStamp = new GenericStamp(GenericStampType.Dependency);
    private static final Stamp extensionStamp = new GenericStamp(GenericStampType.Extension);
    private static final Stamp virtualStamp = new GenericStamp(GenericStampType.Virtual);
    private static final Stamp conditionStamp = new GenericStamp(GenericStampType.Condition);
    private static final Stamp voidStamp = new GenericStamp(GenericStampType.Void);

    private static final Stamp positiveInt = forInt(0, Integer.MAX_VALUE);

    private static void setCache(Kind kind, Stamp stamp) {
        stampCache[kind.ordinal()] = stamp;
    }

    static {
        setCache(Kind.Boolean, new IntegerStamp(Kind.Boolean));
        setCache(Kind.Byte, new IntegerStamp(Kind.Byte));
        setCache(Kind.Short, new IntegerStamp(Kind.Short));
        setCache(Kind.Char, new IntegerStamp(Kind.Char));
        setCache(Kind.Int, new IntegerStamp(Kind.Int));
        setCache(Kind.Long, new IntegerStamp(Kind.Long));

        setCache(Kind.Float, new FloatStamp(Kind.Float));
        setCache(Kind.Double, new FloatStamp(Kind.Double));

        setCache(Kind.Jsr, new IntegerStamp(Kind.Jsr));

        setCache(Kind.Object, objectStamp);
        setCache(Kind.Void, voidStamp);
    }

    public static Stamp forKind(Kind kind) {
        assert stampCache[kind.stackKind().ordinal()] != null : "unexpected forKind(" + kind + ")";
        return stampCache[kind.stackKind().ordinal()];
    }

    public static Stamp forVoid() {
        return voidStamp;
    }

    public static Stamp intValue() {
        return forKind(Kind.Int);
    }

    public static Stamp dependency() {
        return dependencyStamp;
    }

    public static Stamp extension() {
        return extensionStamp;
    }

    public static Stamp virtual() {
        return virtualStamp;
    }

    public static Stamp condition() {
        return conditionStamp;
    }

    public static Stamp positiveInt() {
        return positiveInt;
    }

    public static Stamp forInt(int lowerBound, int upperBound) {
        return new IntegerStamp(Kind.Int, lowerBound, upperBound);
    }

    public static Stamp forLong(long lowerBound, long upperBound) {
        return new IntegerStamp(Kind.Long, lowerBound, upperBound);
    }

    public static Stamp forConstant(Constant value) {
        assert value.kind != Kind.Object;
        if (value.kind == Kind.Object) {
            throw new GraalInternalError("unexpected kind: %s", value.kind);
        } else {
            if (value.kind == Kind.Int) {
                return forInt(value.asInt(), value.asInt());
            } else if (value.kind == Kind.Long) {
                return forLong(value.asLong(), value.asLong());
            }
            return forKind(value.kind.stackKind());
        }
    }

    public static Stamp forConstant(Constant value, CodeCacheProvider runtime) {
        assert value.kind == Kind.Object;
        if (value.kind == Kind.Object) {
            ResolvedJavaType type = value.isNull() ? null : runtime.getTypeOf(value);
            return new ObjectStamp(type, value.isNonNull(), value.isNonNull());
        } else {
            throw new GraalInternalError("CiKind.Object expected, actual kind: %s", value.kind);
        }
    }

    public static Stamp object() {
        return objectStamp;
    }

    public static Stamp objectNonNull() {
        return objectNonNullStamp;
    }

    public static Stamp declared(ResolvedJavaType type) {
        return declared(type, false);
    }

    public static Stamp declaredNonNull(ResolvedJavaType type) {
        return declared(type, true);
    }

    public static Stamp declared(ResolvedJavaType type, boolean nonNull) {
        assert type != null;
        assert type.kind() == Kind.Object;
        ResolvedJavaType exact = type.exactType();
        if (exact != null) {
            return new ObjectStamp(exact, true, nonNull);
        } else {
            return new ObjectStamp(type, false, nonNull);
        }
    }

    public static Stamp exactNonNull(ResolvedJavaType type) {
        return new ObjectStamp(type, true, true);
    }

    public static Stamp or(Collection<? extends StampProvider> values) {
        return meet(values);
    }

    public static Stamp meet(Collection<? extends StampProvider> values) {
        if (values.size() == 0) {
            return forVoid();
        } else {
            Iterator< ? extends StampProvider> iterator = values.iterator();
            Stamp stamp = iterator.next().stamp();

            while (iterator.hasNext()) {
                stamp = stamp.meet(iterator.next().stamp());
            }
            return stamp;
        }
    }
}