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
package com.oracle.graal.api.meta.test;

import static java.lang.Integer.*;
import static java.lang.reflect.Modifier.*;
import static org.junit.Assert.*;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

import org.junit.*;

import sun.reflect.ConstantPool;

import com.oracle.graal.api.meta.*;

/**
 * Tests for {@link ResolvedJavaType}.
 */
public class TestResolvedJavaType extends TypeUniverse {

    public TestResolvedJavaType() {
    }

    @Test
    public void findInstanceFieldWithOffsetTest() {
        for (Class c : classes) {
            ResolvedJavaType type = runtime.lookupJavaType(c);
            Set<Field> reflectionFields = getInstanceFields(c, true);
            for (Field f : reflectionFields) {
                ResolvedJavaField rf = lookupField(type.getInstanceFields(true), f);
                assertNotNull(rf);
                long offset = isStatic(f.getModifiers()) ? unsafe.staticFieldOffset(f) : unsafe.objectFieldOffset(f);
                ResolvedJavaField result = type.findInstanceFieldWithOffset(offset);
                assertNotNull(result);
                assertTrue(fieldsEqual(f, result));
            }
        }
    }

    @Test
    public void isInterfaceTest() {
        for (Class c : classes) {
            ResolvedJavaType type = runtime.lookupJavaType(c);
            boolean expected = c.isInterface();
            boolean actual = type.isInterface();
            assertEquals(expected, actual);
        }
    }

    @Test
    public void isInstanceClassTest() {
        for (Class c : classes) {
            ResolvedJavaType type = runtime.lookupJavaType(c);
            boolean expected = !c.isArray() && !c.isPrimitive() && !c.isInterface();
            boolean actual = type.isInstanceClass();
            assertEquals(expected, actual);
        }
    }

    @Test
    public void isArrayTest() {
        for (Class c : classes) {
            ResolvedJavaType type = runtime.lookupJavaType(c);
            boolean expected = c.isArray();
            boolean actual = type.isArray();
            assertEquals(expected, actual);
        }
    }

    @Test
    public void getModifiersTest() {
        for (Class c : classes) {
            ResolvedJavaType type = runtime.lookupJavaType(c);
            int expected = c.getModifiers();
            int actual = type.getModifiers();
            assertEquals(expected, actual);
        }
    }

    @Test
    public void isAssignableFromTest() {
        Class[] all = classes.toArray(new Class[classes.size()]);
        for (int i = 0; i < all.length; i++) {
            Class<?> c1 = all[i];
            for (int j = i; j < all.length; j++) {
                Class<?> c2 = all[j];
                ResolvedJavaType t1 = runtime.lookupJavaType(c1);
                ResolvedJavaType t2 = runtime.lookupJavaType(c2);
                boolean expected = c1.isAssignableFrom(c2);
                boolean actual = t1.isAssignableFrom(t2);
                assertEquals(expected, actual);
                if (expected && t1 != t2) {
                    assertFalse(t2.isAssignableFrom(t1));
                }
            }
        }
    }

    @Test
    public void isInstanceTest() {
        for (Constant c : constants) {
            if (c.getKind() == Kind.Object && !c.isNull()) {
                Object o = c.asObject();
                Class<? extends Object> cls = o.getClass();
                while (cls != null) {
                    ResolvedJavaType type = runtime.lookupJavaType(cls);
                    boolean expected = cls.isInstance(o);
                    boolean actual = type.isInstance(c);
                    assertEquals(expected, actual);
                    cls = cls.getSuperclass();
                }
            }
        }
    }

    private static Class asExactClass(Class c) {
        if (c.isArray()) {
            if (asExactClass(c.getComponentType()) != null) {
                return c;
            }
        } else {
            if (c.isPrimitive() || Modifier.isFinal(c.getModifiers())) {
                return c;
            }
        }
        return null;
    }

    @Test
    public void asExactTypeTest() {
        for (Class c : classes) {
            ResolvedJavaType type = runtime.lookupJavaType(c);
            ResolvedJavaType exactType = type.asExactType();
            Class expected = asExactClass(c);
            if (expected == null) {
                assertTrue("exact(" + c.getName() + ") != null", exactType == null);
            } else {
                assertNotNull(exactType);
                assertTrue(exactType.equals(runtime.lookupJavaType(expected)));
            }
        }
    }

    @Test
    public void getSuperclassTest() {
        for (Class c : classes) {
            ResolvedJavaType type = runtime.lookupJavaType(c);
            Class expected = c.getSuperclass();
            ResolvedJavaType actual = type.getSuperclass();
            if (expected == null) {
                assertTrue(actual == null);
            } else {
                assertNotNull(actual);
                assertTrue(actual.equals(runtime.lookupJavaType(expected)));
            }
        }
    }

    @Test
    public void getInterfacesTest() {
        for (Class c : classes) {
            ResolvedJavaType type = runtime.lookupJavaType(c);
            Class[] expected = c.getInterfaces();
            ResolvedJavaType[] actual = type.getInterfaces();
            assertEquals(expected.length, actual.length);
            for (int i = 0; i < expected.length; i++) {
                assertTrue(actual[i].equals(runtime.lookupJavaType(expected[i])));
            }
        }
    }

    public Class getSupertype(Class c) {
        assert !c.isPrimitive();
        if (c.isArray()) {
            Class componentType = c.getComponentType();
            if (componentType.isPrimitive() || componentType == Object.class) {
                return Object.class;
            }
            return getArrayClass(getSupertype(componentType));
        }
        if (c.isInterface()) {
            return Object.class;
        }
        return c.getSuperclass();
    }

    public Class findLeastCommonAncestor(Class<?> c1Initial, Class<?> c2Initial) {
        if (c1Initial.isPrimitive() || c2Initial.isPrimitive()) {
            return null;
        } else {
            Class<?> c1 = c1Initial;
            Class<?> c2 = c2Initial;
            while (true) {
                if (c1.isAssignableFrom(c2)) {
                    return c1;
                }
                if (c2.isAssignableFrom(c1)) {
                    return c2;
                }
                c1 = getSupertype(c1);
                c2 = getSupertype(c2);
            }
        }
    }

    @Test
    public void findLeastCommonAncestorTest() {
        Class[] all = classes.toArray(new Class[classes.size()]);
        for (int i = 0; i < all.length; i++) {
            Class<?> c1 = all[i];
            for (int j = i; j < all.length; j++) {
                Class<?> c2 = all[j];
                ResolvedJavaType t1 = runtime.lookupJavaType(c1);
                ResolvedJavaType t2 = runtime.lookupJavaType(c2);
                Class expected = findLeastCommonAncestor(c1, c2);
                ResolvedJavaType actual = t1.findLeastCommonAncestor(t2);
                if (expected == null) {
                    assertTrue(actual == null);
                } else {
                    assertNotNull(actual);
                    assertTrue(actual.equals(runtime.lookupJavaType(expected)));
                }
            }
        }
    }

    private static class Base {
    }

    abstract static class Abstract1 extends Base {
    }

    interface Interface1 {
    }

    static class Concrete1 extends Abstract1 {
    }

    static class Concrete2 extends Abstract1 implements Interface1 {
    }

    static class Concrete3 extends Concrete2 {
    }

    abstract static class Abstract4 extends Concrete3 {
    }

    void checkConcreteSubtype(ResolvedJavaType type, Class expected) {
        ResolvedJavaType subtype = type.findUniqueConcreteSubtype();
        if (subtype == null) {
            // findUniqueConcreteSubtype() is conservative
        } else {
            if (expected == null) {
                assertNull(subtype);
            } else {
                assertTrue(subtype.equals(runtime.lookupJavaType(expected)));
            }
        }

        if (!type.isArray()) {
            ResolvedJavaType arrayType = type.getArrayClass();
            ResolvedJavaType arraySubtype = arrayType.findUniqueConcreteSubtype();
            if (arraySubtype != null) {
                assertEquals(arraySubtype, arrayType);
            } else {
                // findUniqueConcreteSubtype() method is conservative
            }
        }
    }

    @Test
    public void findUniqueConcreteSubtypeTest() {
        ResolvedJavaType base = runtime.lookupJavaType(Base.class);
        checkConcreteSubtype(base, Base.class);

        ResolvedJavaType a1 = runtime.lookupJavaType(Abstract1.class);
        ResolvedJavaType c1 = runtime.lookupJavaType(Concrete1.class);

        checkConcreteSubtype(base, null);
        checkConcreteSubtype(a1, Concrete1.class);
        checkConcreteSubtype(c1, Concrete1.class);

        ResolvedJavaType i1 = runtime.lookupJavaType(Interface1.class);
        ResolvedJavaType c2 = runtime.lookupJavaType(Concrete2.class);

        checkConcreteSubtype(base, null);
        checkConcreteSubtype(a1, null);
        checkConcreteSubtype(c1, Concrete1.class);
        checkConcreteSubtype(i1, Concrete2.class);
        checkConcreteSubtype(c2, Concrete2.class);

        ResolvedJavaType c3 = runtime.lookupJavaType(Concrete3.class);
        checkConcreteSubtype(c2, null);
        checkConcreteSubtype(c3, Concrete3.class);

        ResolvedJavaType a4 = runtime.lookupJavaType(Abstract4.class);
        checkConcreteSubtype(c3, null);
        checkConcreteSubtype(a4, null);
    }

    @Test
    public void getComponentTypeTest() {
        for (Class c : classes) {
            ResolvedJavaType type = runtime.lookupJavaType(c);
            Class expected = c.getComponentType();
            ResolvedJavaType actual = type.getComponentType();
            if (expected == null) {
                assertNull(actual);
            } else {
                assertTrue(actual.equals(runtime.lookupJavaType(expected)));
            }
        }
    }

    @Test
    public void getArrayClassTest() {
        for (Class c : classes) {
            if (c != void.class) {
                ResolvedJavaType type = runtime.lookupJavaType(c);
                Class expected = getArrayClass(c);
                ResolvedJavaType actual = type.getArrayClass();
                assertTrue(actual.equals(runtime.lookupJavaType(expected)));
            }
        }
    }

    static class Declarations {

        final Method implementation;
        final Set<Method> declarations;

        public Declarations(Method impl) {
            this.implementation = impl;
            declarations = new HashSet<>();
        }
    }

    /**
     * See <a href="http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-5.html#jvms-5.4.5">Method
     * overriding</a>.
     */
    static boolean isOverriderOf(Method impl, Method m) {
        if (!isPrivate(m.getModifiers()) && !isFinal(m.getModifiers())) {
            if (m.getName().equals(impl.getName())) {
                if (m.getReturnType() == impl.getReturnType()) {
                    if (Arrays.equals(m.getParameterTypes(), impl.getParameterTypes())) {
                        if (isPublic(m.getModifiers()) || isProtected(m.getModifiers())) {
                            // m is public or protected
                            return isPublic(impl.getModifiers()) || isProtected(impl.getModifiers());
                        } else {
                            // m is package-private
                            return impl.getDeclaringClass().getPackage() == m.getDeclaringClass().getPackage();
                        }
                    }
                }
            }
        }
        return false;
    }

    static final Map<Class, VTable> vtables = new HashMap<>();

    static class VTable {

        final Map<NameAndSignature, Method> methods = new HashMap<>();
    }

    static synchronized VTable getVTable(Class c) {
        VTable vtable = vtables.get(c);
        if (vtable == null) {
            vtable = new VTable();
            if (c != Object.class) {
                VTable superVtable = getVTable(c.getSuperclass());
                vtable.methods.putAll(superVtable.methods);
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!isStatic(m.getModifiers()) && !isPrivate(m.getModifiers())) {
                    Method overridden = vtable.methods.put(new NameAndSignature(m), m);
                    if (overridden != null) {
                        // println(m + " overrides " + overridden);
                    }
                }
            }
            vtables.put(c, vtable);
        }
        return vtable;
    }

    static Set<Method> findDeclarations(Method impl, Class c) {
        Set<Method> declarations = new HashSet<>();
        NameAndSignature implSig = new NameAndSignature(impl);
        if (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (new NameAndSignature(m).equals(implSig)) {
                    declarations.add(m);
                    break;
                }
            }
            if (!c.isInterface()) {
                declarations.addAll(findDeclarations(impl, c.getSuperclass()));
            }
            for (Class i : c.getInterfaces()) {
                declarations.addAll(findDeclarations(impl, i));
            }
        }
        return declarations;
    }

    private static void checkResolveMethod(ResolvedJavaType type, ResolvedJavaMethod decl, ResolvedJavaMethod expected) {
        ResolvedJavaMethod impl = type.resolveMethod(decl);
        assertEquals(expected, impl);
    }

    @Test
    public void resolveMethodTest() {
        for (Class c : classes) {
            if (!c.isPrimitive() && !c.isInterface()) {
                ResolvedJavaType type = runtime.lookupJavaType(c);
                VTable vtable = getVTable(c);
                for (Method impl : vtable.methods.values()) {
                    Set<Method> decls = findDeclarations(impl, c);
                    for (Method decl : decls) {
                        ResolvedJavaMethod m = runtime.lookupJavaMethod(decl);
                        ResolvedJavaMethod i = runtime.lookupJavaMethod(impl);
                        checkResolveMethod(type, m, i);
                    }
                }
            }
        }
    }

    @Test
    public void findUniqueConcreteMethodTest() throws NoSuchMethodException {
        ResolvedJavaMethod thisMethod = runtime.lookupJavaMethod(getClass().getDeclaredMethod("findUniqueConcreteMethodTest"));
        ResolvedJavaMethod ucm = runtime.lookupJavaType(getClass()).findUniqueConcreteMethod(thisMethod);
        assertEquals(thisMethod, ucm);
    }

    public static Set<Field> getInstanceFields(Class c, boolean includeSuperclasses) {
        if (c.isArray() || c.isPrimitive() || c.isInterface()) {
            return Collections.emptySet();
        }
        Set<Field> result = new HashSet<>();
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                result.add(f);
            }
        }
        if (includeSuperclasses && c != Object.class) {
            result.addAll(getInstanceFields(c.getSuperclass(), true));
        }
        return result;
    }

    public boolean fieldsEqual(Field f, ResolvedJavaField rjf) {
        return rjf.getDeclaringClass().equals(runtime.lookupJavaType(f.getDeclaringClass())) && rjf.getName().equals(f.getName()) &&
                        rjf.getType().resolve(rjf.getDeclaringClass()).equals(runtime.lookupJavaType(f.getType()));
    }

    public ResolvedJavaField lookupField(ResolvedJavaField[] fields, Field key) {
        for (ResolvedJavaField rf : fields) {
            if (fieldsEqual(key, rf)) {
                assert (fieldModifiers() & key.getModifiers()) == rf.getModifiers() : key + ": " + toHexString(key.getModifiers()) + " != " + toHexString(rf.getModifiers());
                return rf;
            }
        }
        return null;
    }

    public Field lookupField(Set<Field> fields, ResolvedJavaField key) {
        for (Field f : fields) {
            if (fieldsEqual(f, key)) {
                assert key.getModifiers() == (fieldModifiers() & f.getModifiers()) : key + ": " + toHexString(key.getModifiers()) + " != " + toHexString(f.getModifiers());
                return f;
            }
        }
        return null;
    }

    private boolean isHiddenFromReflection(ResolvedJavaField f) {
        if (f.getDeclaringClass().equals(runtime.lookupJavaType(Throwable.class)) && f.getName().equals("backtrace")) {
            return true;
        }
        if (f.getDeclaringClass().equals(runtime.lookupJavaType(ConstantPool.class)) && f.getName().equals("constantPoolOop")) {
            return true;
        }
        return false;
    }

    @Test
    public void getInstanceFieldsTest() {
        for (Class c : classes) {
            ResolvedJavaType type = runtime.lookupJavaType(c);
            for (boolean includeSuperclasses : new boolean[]{true, false}) {
                Set<Field> expected = getInstanceFields(c, includeSuperclasses);
                ResolvedJavaField[] actual = type.getInstanceFields(includeSuperclasses);
                for (Field f : expected) {
                    assertNotNull(lookupField(actual, f));
                }
                for (ResolvedJavaField rf : actual) {
                    if (!isHiddenFromReflection(rf)) {
                        assertEquals(lookupField(expected, rf) != null, !rf.isInternal());
                    }
                }

                // Test stability of getInstanceFields
                ResolvedJavaField[] actual2 = type.getInstanceFields(includeSuperclasses);
                assertArrayEquals(actual, actual2);
            }
        }
    }

    @Test
    public void getAnnotationTest() {
        for (Class c : classes) {
            ResolvedJavaType type = runtime.lookupJavaType(c);
            for (Annotation a : c.getAnnotations()) {
                assertEquals(a, type.getAnnotation(a.annotationType()));
            }
        }
    }

    @Test
    public void memberClassesTest() {
        for (Class c : classes) {
            ResolvedJavaType type = runtime.lookupJavaType(c);
            assertEquals(c.isLocalClass(), type.isLocal());
            assertEquals(c.isMemberClass(), type.isMember());
            Class enclc = c.getEnclosingClass();
            ResolvedJavaType enclt = type.getEnclosingType();
            assertFalse(enclc == null ^ enclt == null);
            if (enclc != null) {
                assertEquals(enclt, runtime.lookupJavaType(enclc));
            }
        }
    }

    @Test
    public void classFilePathTest() {
        for (Class c : classes) {
            ResolvedJavaType type = runtime.lookupJavaType(c);
            URL path = type.getClassFilePath();
            if (type.isPrimitive() || type.isArray()) {
                assertEquals(null, path);
            } else {
                assertNotNull(path);
                String pathString = path.getPath();
                if (type.isLocal() || type.isMember()) {
                    assertTrue(pathString.indexOf('$') > 0);
                }
            }
        }
    }

    private Method findTestMethod(Method apiMethod) {
        String testName = apiMethod.getName() + "Test";
        for (Method m : getClass().getDeclaredMethods()) {
            if (m.getName().equals(testName) && m.getAnnotation(Test.class) != null) {
                return m;
            }
        }
        return null;
    }

    // @formatter:off
    private static final String[] untestedApiMethods = {
        "initialize",
        "isPrimitive",
        "newArray",
        "getDeclaredMethods",
        "getDeclaredConstructors",
        "isInitialized",
        "getEncoding",
        "hasFinalizableSubclass",
        "hasFinalizer",
        "getSourceFileName",
        "getClassFilePath",
        "isLocal",
        "isMember",
        "getEnclosingType"
    };
    // @formatter:on

    /**
     * Ensures that any new methods added to {@link ResolvedJavaMethod} either have a test written
     * for them or are added to {@link #untestedApiMethods}.
     */
    @Test
    public void testCoverage() {
        Set<String> known = new HashSet<>(Arrays.asList(untestedApiMethods));
        for (Method m : ResolvedJavaType.class.getDeclaredMethods()) {
            if (findTestMethod(m) == null) {
                assertTrue("test missing for " + m, known.contains(m.getName()));
            } else {
                assertFalse("test should be removed from untestedApiMethods" + m, known.contains(m.getName()));
            }
        }
    }

}
