/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.jtt.reflect;

import org.junit.*;

/*
 */
public class Field_getType01 {

    public static final byte byteField = 11;
    public static final short shortField = 12;
    public static final char charField = 13;
    public static final int intField = 14;
    public static final long longField = 15;
    public static final float floatField = 16;
    public static final double doubleField = 17;
    public static final boolean booleanField = true;

    public static boolean test(int arg) throws NoSuchFieldException {
        if (arg == 0) {
            return Field_getType01.class.getField("byteField").getType() == byte.class;
        } else if (arg == 1) {
            return Field_getType01.class.getField("shortField").getType() == short.class;
        } else if (arg == 2) {
            return Field_getType01.class.getField("charField").getType() == char.class;
        } else if (arg == 3) {
            return Field_getType01.class.getField("intField").getType() == int.class;
        } else if (arg == 4) {
            return Field_getType01.class.getField("longField").getType() == long.class;
        } else if (arg == 5) {
            return Field_getType01.class.getField("floatField").getType() == float.class;
        } else if (arg == 6) {
            return Field_getType01.class.getField("doubleField").getType() == double.class;
        } else if (arg == 7) {
            return Field_getType01.class.getField("booleanField").getType() == boolean.class;
        }
        return false;
    }

    @Test
    public void run0() throws Throwable {
        Assert.assertEquals(true, test(0));
    }

    @Test
    public void run1() throws Throwable {
        Assert.assertEquals(true, test(1));
    }

    @Test
    public void run2() throws Throwable {
        Assert.assertEquals(true, test(2));
    }

    @Test
    public void run3() throws Throwable {
        Assert.assertEquals(true, test(3));
    }

    @Test
    public void run4() throws Throwable {
        Assert.assertEquals(true, test(4));
    }

    @Test
    public void run5() throws Throwable {
        Assert.assertEquals(true, test(5));
    }

    @Test
    public void run6() throws Throwable {
        Assert.assertEquals(true, test(6));
    }

    @Test
    public void run7() throws Throwable {
        Assert.assertEquals(true, test(7));
    }

    @Test
    public void run8() throws Throwable {
        Assert.assertEquals(false, test(8));
    }

}
