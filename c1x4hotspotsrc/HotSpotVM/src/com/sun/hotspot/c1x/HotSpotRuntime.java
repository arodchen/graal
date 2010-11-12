/*
 * Copyright (c) 2010 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.hotspot.c1x;

import java.io.*;
import java.lang.reflect.*;

import com.sun.cri.ci.*;
import com.sun.cri.ci.CiTargetMethod.Call;
import com.sun.cri.ci.CiTargetMethod.DataPatch;
import com.sun.cri.ci.CiTargetMethod.Safepoint;
import com.sun.cri.ri.*;
import com.sun.max.asm.*;
import com.sun.max.asm.dis.*;
import com.sun.max.lang.*;

/**
 * CRI runtime implementation for the HotSpot VM.
 *
 * @author Thomas Wuerthinger, Lukas Stadler
 */
public class HotSpotRuntime implements RiRuntime {

    final HotSpotVMConfig config;
    final HotSpotRegisterConfig regConfig;


    public HotSpotRuntime(HotSpotVMConfig config) {
        this.config = config;
        regConfig = new HotSpotRegisterConfig(config);
    }

    @Override
    public int codeOffset() {
        return 0;
    }

    @Override
    public String disassemble(byte[] code) {
        return disassemble(code, new DisassemblyPrinter(false));
    }

    private String disassemble(byte[] code, DisassemblyPrinter disassemblyPrinter) {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        final ISA instructionSet = ISA.AMD64;
        Disassembler.disassemble(byteArrayOutputStream, code, instructionSet, WordWidth.BITS_64, 0, null, disassemblyPrinter);
        return byteArrayOutputStream.toString();
    }

    @Override
    public String disassemble(final CiTargetMethod targetMethod) {

        final DisassemblyPrinter disassemblyPrinter = new DisassemblyPrinter(false) {

            private String toString(Call call) {
                if (call.runtimeCall != null) {
                    return "{" + call.runtimeCall.name() + "}";
                } else if (call.symbol != null) {
                    return "{" + call.symbol + "}";
                } else if (call.globalStubID != null) {
                    return "{" + call.globalStubID + "}";
                } else {
                    return "{" + call.method + "}";
                }
            }

            private String siteInfo(int pcOffset) {
                for (Call call : targetMethod.directCalls) {
                    if (call.pcOffset == pcOffset) {
                        return toString(call);
                    }
                }
                for (Call call : targetMethod.indirectCalls) {
                    if (call.pcOffset == pcOffset) {
                        return toString(call);
                    }
                }
                for (Safepoint site : targetMethod.safepoints) {
                    if (site.pcOffset == pcOffset) {
                        return "{safepoint}";
                    }
                }
                for (DataPatch site : targetMethod.dataReferences) {
                    if (site.pcOffset == pcOffset) {
                        return "{" + site.constant + "}";
                    }
                }
                return null;
            }

            @Override
            protected String disassembledObjectString(Disassembler disassembler, DisassembledObject disassembledObject) {
                final String string = super.disassembledObjectString(disassembler, disassembledObject);

                String site = siteInfo(disassembledObject.startPosition());
                if (site != null) {
                    return string + " " + site;
                }
                return string;
            }
        };
        return disassemble(targetMethod.targetCode(), disassemblyPrinter);
    }

    @Override
    public String disassemble(RiMethod method) {
        return "No disassembler available";
    }

    @Override
    public RiConstantPool getConstantPool(RiMethod method) {
        return ((HotSpotTypeResolved) method.holder()).constantPool();
    }

    @Override
    public RiOsrFrame getOsrFrame(RiMethod method, int bci) {
        return null;
    }

    @Override
    public RiType getRiType(Class<?> javaClass) {
        if (javaClass == Object[].class || javaClass == Long.class || javaClass == Integer.class || javaClass == Throwable.class) {
            return Compiler.getVMEntries().getType(javaClass);
        }
        throw new UnsupportedOperationException("unexpected class in getRiType: " + javaClass);
    }

    @Override
    public RiSnippets getSnippets() {
        throw new UnsupportedOperationException("getSnippets");
    }

    @Override
    public boolean mustInline(RiMethod method) {
        return false;
    }

    @Override
    public boolean mustNotCompile(RiMethod method) {
        return false;
    }

    @Override
    public boolean mustNotInline(RiMethod method) {
        return Modifier.isNative(method.accessFlags());
    }

    @Override
    public Object registerGlobalStub(CiTargetMethod targetMethod, String name) {
        return HotSpotTargetMethod.installStub(targetMethod, name);
    }

    @Override
    public int sizeofBasicObjectLock() {
        // TODO shouldn't be hard coded
        return 2 * 8;
    }

    @Override
    public int basicObjectLockOffsetInBytes() {
        return 8;
    }

    @Override
    public RiField getRiField(Field javaField) {
        throw new UnsupportedOperationException("getRiField");
    }

    @Override
    public RiMethod getRiMethod(Method javaMethod) {
        throw new UnsupportedOperationException("getRiMethod");
    }

    @Override
    public RiMethod getRiMethod(Constructor<?> javaConstructor) {
        throw new UnsupportedOperationException("getRiMethod");
    }

    @Override
    public CiConstant invoke(RiMethod method, CiMethodInvokeArguments args) {
        return null;
    }

    @Override
    public CiConstant foldWordOperation(int opcode, CiMethodInvokeArguments args) {
        throw new UnsupportedOperationException("foldWordOperation");
    }

    @Override
    public boolean compareConstantObjects(Object x, Object y) {
        if (x == null && y == null) {
            return true;
        } else if (x == null || y == null) {
            return false;
        } else if (x instanceof Long && y instanceof Long) {
            return (Long) x == (long) (Long) y;
        }
        throw new UnsupportedOperationException("compareConstantObjects: " + x + ", " + y);
    }

    @Override
    public boolean recordLeafMethodAssumption(RiMethod method) {
        return false;
    }

    @Override
    public RiRegisterConfig getRegisterConfig(RiMethod method) {
        return regConfig;
    }

    public boolean needsDebugInfo() {
        return false;
    }

}
