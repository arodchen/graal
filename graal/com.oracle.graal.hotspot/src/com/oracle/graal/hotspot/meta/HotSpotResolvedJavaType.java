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
package com.oracle.graal.hotspot.meta;

import static com.oracle.graal.graph.FieldIntrospection.*;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.hotspot.*;

/**
 * Implementation of {@link JavaType} for resolved non-primitive HotSpot classes.
 */
public final class HotSpotResolvedJavaType extends HotSpotJavaType implements ResolvedJavaType {

    private static final long serialVersionUID = 3481514353553840471L;

    /**
     * Value for the {@code sizeOrSpecies} parameter in {@link HotSpotResolvedJavaType#HotSpotResolvedJavaType}
     * denoting that the new type represents an interface class.
     */
    public static final int INTERFACE_SPECIES_VALUE = Integer.MIN_VALUE;

    /**
     * Value for the {@code sizeOrSpecies} parameter in {@link HotSpotResolvedJavaType#HotSpotResolvedJavaType}
     * denoting that the new type represents an array class.
     */
    public static final int ARRAY_SPECIES_VALUE = Integer.MAX_VALUE;

    /**
     * Reference to the metaspace Klass object.
     */
    private final long metaspaceKlass;

    private final Class<?> javaMirror; // this could be read directly from 'metaspaceKlass'...
    private final String simpleName;
    private final boolean hasFinalizableSubclass;

    /**
     * The instance size for an instance type, {@link HotSpotResolvedJavaType#INTERFACE_SPECIES_VALUE} denoting
     * an interface type or {@link HotSpotResolvedJavaType#ARRAY_SPECIES_VALUE} denoting an array type.
     */
    private final int sizeOrSpecies;

    private HashMap<Long, ResolvedJavaField> fieldCache;
    private HashMap<Long, HotSpotResolvedJavaMethod> methodCache;
    private HotSpotResolvedJavaField[] instanceFields;
    private ResolvedJavaType[] interfaces;
    private ConstantPool constantPool;
    private boolean isInitialized;
    private ResolvedJavaType arrayOfType;

    /**
     * Gets the Graal mirror from a HotSpot metaspace Klass native object.
     *
     * @param metaspaceKlass a metaspace Klass object boxed in a {@link Constant}
     * @return the {@link ResolvedJavaType} corresponding to {@code klassConstant}
     */
    public static ResolvedJavaType fromMetaspaceKlass(Constant metaspaceKlass) {
        assert metaspaceKlass.getKind().isLong();
        return fromMetaspaceKlass(metaspaceKlass.asLong());
    }

    /**
     * Gets the Graal mirror from a HotSpot metaspace Klass native object.
     *
     * @param metaspaceKlass a metaspace Klass object
     * @return the {@link ResolvedJavaType} corresponding to {@code metaspaceKlass}
     */
    public static ResolvedJavaType fromMetaspaceKlass(long metaspaceKlass) {
        assert metaspaceKlass != 0;
        Class javaClass = (Class) unsafe.getObject(null, metaspaceKlass + HotSpotGraalRuntime.getInstance().getConfig().classMirrorOffset);
        assert javaClass != null;
        return fromClass(javaClass);
    }

    /**
     * Gets the Graal mirror from a {@link Class} object.
     *
     * @return the {@link HotSpotResolvedJavaType} corresponding to {@code javaClass}
     */
    public static ResolvedJavaType fromClass(Class javaClass) {
        ResolvedJavaType type = (ResolvedJavaType) unsafe.getObject(javaClass, (long) HotSpotGraalRuntime.getInstance().getConfig().graalMirrorInClassOffset);
        if (type == null) {
            type = HotSpotGraalRuntime.getInstance().getCompilerToVM().getResolvedType(javaClass);
            assert type != null;
        }
        return type;
    }

    /**
     * @param hasFinalizableSubclass
     * @param sizeOrSpecies the size of an instance of the type, or {@link HotSpotResolvedJavaType#INTERFACE_SPECIES_VALUE} or {@link HotSpotResolvedJavaType#ARRAY_SPECIES_VALUE}
     */
    public HotSpotResolvedJavaType(long metaspaceKlass,
                    String name,
                    String simpleName,
                    Class javaMirror,
                    boolean hasFinalizableSubclass,
                    int sizeOrSpecies) {
        super(name);
        this.metaspaceKlass = metaspaceKlass;
        this.javaMirror = javaMirror;
        this.simpleName = simpleName;
        this.hasFinalizableSubclass = hasFinalizableSubclass;
        this.sizeOrSpecies = sizeOrSpecies;
        assert name.charAt(0) != '[' || sizeOrSpecies == ARRAY_SPECIES_VALUE : name + " " + Long.toHexString(sizeOrSpecies);
        assert javaMirror.isArray() == isArrayClass();
        assert javaMirror.isInterface() == isInterface();
        //System.out.println("0x" + Long.toHexString(metaspaceKlass) + ": " + name);
    }

    @Override
    public int getModifiers() {
        return javaMirror.getModifiers();
    }

    public int getAccessFlags() {
        HotSpotVMConfig config = HotSpotGraalRuntime.getInstance().getConfig();
        return unsafe.getInt(null, metaspaceKlass + config.klassAccessFlagsOffset);
    }

    @Override
    public ResolvedJavaType getArrayClass() {
        if (arrayOfType == null) {
            arrayOfType = fromClass(Array.newInstance(javaMirror, 0).getClass());
        }
        return arrayOfType;
    }

    @Override
    public ResolvedJavaType getComponentType() {
        Class javaComponentType = javaMirror.getComponentType();
        return javaComponentType == null ? null : fromClass(javaComponentType);
    }

    @Override
    public ResolvedJavaType findUniqueConcreteSubtype() {
        if (isArrayClass()) {
            return getComponentType().findUniqueConcreteSubtype() != null ? this : null;
        } else {
            ResolvedJavaType subtype = (ResolvedJavaType) HotSpotGraalRuntime.getInstance().getCompilerToVM().getUniqueConcreteSubtype(this);
            assert subtype == null || !subtype.isInterface();
            return subtype;
        }
    }

    @Override
    public ResolvedJavaType getSuperclass() {
        Class javaSuperclass = javaMirror.getSuperclass();
        return javaSuperclass == null ? null : fromClass(javaSuperclass);
    }

    @Override
    public ResolvedJavaType[] getInterfaces() {
        if (interfaces == null) {
            Class[] javaInterfaces = javaMirror.getInterfaces();
            ResolvedJavaType[] result = new ResolvedJavaType[javaInterfaces.length];
            for (int i = 0; i < javaInterfaces.length; i++) {
                result[i] = fromClass(javaInterfaces[i]);
            }
            interfaces = result;
        }
        return interfaces;
    }

    @Override
    public ResolvedJavaType findLeastCommonAncestor(ResolvedJavaType otherType) {
        if (otherType instanceof HotSpotTypePrimitive) {
            return null;
        } else {
            return (ResolvedJavaType) HotSpotGraalRuntime.getInstance().getCompilerToVM().getLeastCommonAncestor(this, (HotSpotResolvedJavaType) otherType);
        }
    }

    @Override
    public ResolvedJavaType asExactType() {
        if (isArrayClass()) {
            return getComponentType().asExactType() != null ? this : null;
        }
        return Modifier.isFinal(getModifiers()) ? this : null;
    }

    @Override
    public Constant getEncoding(Representation r) {
        switch (r) {
            case JavaClass:
                return Constant.forObject(javaMirror);
            case ObjectHub:
                return klass();
            case StaticPrimitiveFields:
            case StaticObjectFields:
                return Constant.forObject(javaMirror);
            default:
                assert false : "Should not reach here.";
                return null;
        }
    }

    @Override
    public boolean hasFinalizableSubclass() {
        return hasFinalizableSubclass;
    }

    @Override
    public boolean hasFinalizer() {
        HotSpotVMConfig config = HotSpotGraalRuntime.getInstance().getConfig();
        return (getAccessFlags() & config.klassHasFinalizerFlag) != 0;
    }

    @Override
    public boolean isArrayClass() {
        return sizeOrSpecies == ARRAY_SPECIES_VALUE;
    }

    @Override
    public boolean isInitialized() {
        if (!isInitialized) {
            isInitialized = HotSpotGraalRuntime.getInstance().getCompilerToVM().isTypeInitialized(this);
        }
        return isInitialized;
    }

    @Override
    public void initialize() {
        if (!isInitialized) {
            HotSpotGraalRuntime.getInstance().getCompilerToVM().initializeType(this);
        }
        isInitialized = true;
    }

    @Override
    public boolean isInstance(Constant obj) {
        return javaMirror.isInstance(obj);
    }

    @Override
    public boolean isInstanceClass() {
        return !isArrayClass() && !isInterface();
    }

    @Override
    public boolean isInterface() {
        return sizeOrSpecies == INTERFACE_SPECIES_VALUE;
    }

    @Override
    public boolean isSubtypeOf(ResolvedJavaType other) {
        if (other instanceof HotSpotResolvedJavaType) {
            return HotSpotGraalRuntime.getInstance().getCompilerToVM().isSubtypeOf(this, other);
        }
        // No resolved type is a subtype of an unresolved type.
        return false;
    }

    @Override
    public Kind getKind() {
        return Kind.Object;
    }

    @Override
    public ResolvedJavaMethod resolveMethod(ResolvedJavaMethod method) {
        assert method instanceof HotSpotMethod;
        return (ResolvedJavaMethod) HotSpotGraalRuntime.getInstance().getCompilerToVM().resolveMethod(this, method.getName(), ((HotSpotSignature) method.getSignature()).asString());
    }

    @Override
    public String toString() {
        return "HotSpotType<" + simpleName + ", resolved>";
    }

    public ConstantPool constantPool() {
        if (constantPool == null) {
            constantPool = new HotSpotConstantPool(this);
        }
        return constantPool;
    }

    /**
     * Gets the instance size of this type. If an instance of this type cannot
     * be fast path allocated, then the returned value is negative (its absolute
     * value gives the size). Must not be called if this is an array or interface type.
     */
    public int instanceSize() {
        assert !isArrayClass();
        assert !isInterface();
        return sizeOrSpecies;
    }

    public synchronized HotSpotResolvedJavaMethod createMethod(long metaspaceMethod) {
        HotSpotResolvedJavaMethod method = null;
        if (methodCache == null) {
            methodCache = new HashMap<>(8);
        } else {
            method = methodCache.get(metaspaceMethod);
        }
        if (method == null) {
            method = new HotSpotResolvedJavaMethod(this, metaspaceMethod);
            methodCache.put(metaspaceMethod, method);
        }
        return method;
    }

    public synchronized ResolvedJavaField createField(String fieldName, JavaType type, int offset, int flags, boolean internal) {
        ResolvedJavaField result = null;

        long id = offset + ((long) flags << 32);

        // (thomaswue) Must cache the fields, because the local load elimination only works if the objects from two field lookups are identical.
        if (fieldCache == null) {
            fieldCache = new HashMap<>(8);
        } else {
            result = fieldCache.get(id);
        }

        if (result == null) {
            result = new HotSpotResolvedJavaField(this, fieldName, type, offset, flags, internal);
            fieldCache.put(id, result);
        } else {
            assert result.getName().equals(fieldName);
            assert result.getModifiers() == (Modifier.fieldModifiers() & flags);
        }

        return result;
    }

    @Override
    public ResolvedJavaMethod findUniqueConcreteMethod(ResolvedJavaMethod method) {
        return ((HotSpotResolvedJavaMethod) method).uniqueConcreteMethod();
    }

    @Override
    public ResolvedJavaField[] getInstanceFields(boolean includeSuperclasses) {
        if (instanceFields == null) {
            if (isArrayClass() && isInterface()) {
                instanceFields = new HotSpotResolvedJavaField[0];
            } else {
                HotSpotResolvedJavaField[] myFields = HotSpotGraalRuntime.getInstance().getCompilerToVM().getInstanceFields(this);
                if (javaMirror != Object.class) {
                    HotSpotResolvedJavaField[] superFields = (HotSpotResolvedJavaField[]) getSuperclass().getInstanceFields(true);
                    HotSpotResolvedJavaField[] fields = Arrays.copyOf(superFields, superFields.length + myFields.length);
                    System.arraycopy(myFields, 0, fields, superFields.length, myFields.length);
                    instanceFields = fields;
                } else {
                    assert myFields.length == 0 : "java.lang.Object has fields!";
                    instanceFields = myFields;
                }
            }
        }
        if (!includeSuperclasses) {
            int myFieldsStart = 0;
            while (myFieldsStart < instanceFields.length && instanceFields[myFieldsStart].getDeclaringClass() != this) {
                myFieldsStart++;
            }
            if (myFieldsStart == 0) {
                return instanceFields;
            }
            if (myFieldsStart == instanceFields.length) {
                return new HotSpotResolvedJavaField[0];
            }
            return Arrays.copyOfRange(instanceFields, myFieldsStart, instanceFields.length);
        }
        return instanceFields;
    }

    @Override
    public Class< ? > toJava() {
        return javaMirror;
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return javaMirror.getAnnotation(annotationClass);
    }

    @Override
    public ResolvedJavaType resolve(ResolvedJavaType accessingClass) {
        return this;
    }

    @Override
    public Constant klass() {
        Kind wordKind = HotSpotGraalRuntime.getInstance().getTarget().wordKind;
        return wordKind.isLong() ? Constant.forLong(metaspaceKlass) : Constant.forInt((int) metaspaceKlass);
    }

    public boolean isPrimaryType() {
        return HotSpotGraalRuntime.getInstance().getConfig().secondarySuperCacheOffset != superCheckOffset();
    }

    public int superCheckOffset() {
        HotSpotVMConfig config = HotSpotGraalRuntime.getInstance().getConfig();
        return unsafe.getInt(null, metaspaceKlass + config.superCheckOffsetOffset);
    }

    public long prototypeMarkWord() {
        return HotSpotGraalRuntime.getInstance().getCompilerToVM().getPrototypeMarkWord(this);
    }

    public long address() {
        return metaspaceKlass;
    }

    public String symbol() {
        return javaMirror.getName();
    }
}
