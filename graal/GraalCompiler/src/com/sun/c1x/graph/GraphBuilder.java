/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.c1x.graph;

import static com.sun.cri.bytecode.Bytecodes.*;
import static java.lang.reflect.Modifier.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.graph.*;
import com.sun.c1x.*;
import com.sun.c1x.debug.*;
import com.sun.c1x.ir.*;
import com.sun.c1x.opt.*;
import com.sun.c1x.util.*;
import com.sun.c1x.value.*;
import com.sun.cri.bytecode.*;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;
import com.sun.cri.ri.RiType.Representation;

/**
 * The {@code GraphBuilder} class parses the bytecode of a method and builds the IR graph.
 * A number of optimizations may be performed during parsing of the bytecode, including value
 * numbering, inlining, constant folding, strength reduction, etc.
 *
 * @author Ben L. Titzer
 * @author Doug Simon
 */
public final class GraphBuilder {

    /**
     * The minimum value to which {@link C1XOptions#TraceBytecodeParserLevel} must be set to trace
     * the bytecode instructions as they are parsed.
     */
    public static final int TRACELEVEL_INSTRUCTIONS = 1;

    /**
     * The minimum value to which {@link C1XOptions#TraceBytecodeParserLevel} must be set to trace
     * the frame state before each bytecode instruction as it is parsed.
     */
    public static final int TRACELEVEL_STATE = 2;

    /**
     * An enumeration of flags describing scope attributes.
     */
    public enum Flag {
        /**
         * Scope is protected by an exception handler.
         * This attribute is inherited by nested scopes.
         */
        HasHandler,

        /**
         * Code in scope cannot contain safepoints.
         * This attribute is inherited by nested scopes.
         */
        NoSafepoints;

        public final int mask = 1 << ordinal();
    }

    final IR ir;
    final C1XCompilation compilation;
    final CiStatistics stats;

    /**
     * Map used to implement local value numbering for the current block.
     */
    final ValueMap localValueMap;

    /**
     * Map used for local load elimination (i.e. within the current block).
     */
    final MemoryMap memoryMap;

    final BytecodeStream stream;           // the bytecode stream
    // bci-to-block mapping
    BlockMap blockMap;

    // the constant pool
    final RiConstantPool constantPool;

    // the worklist of blocks, managed like a sorted list
    BlockBegin[] workList;

    // the current position in the worklist
    int workListIndex;

    /**
     * Mask of {@link Flag} values.
     */
    int flags;

    // Exception handler list
    List<ExceptionHandler> exceptionHandlers;

    BlockBegin curBlock;                   // the current block
    MutableFrameState curState;            // the current execution state
    Instruction lastInstr;                 // the last instruction added
    final LogStream log;

    boolean skipBlock;                     // skip processing of the rest of this block
    private Value rootMethodSynchronizedObject;



    private Graph graph = new Graph();


    /**
     * Creates a new, initialized, {@code GraphBuilder} instance for a given compilation.
     *
     * @param compilation the compilation
     * @param ir the IR to build the graph into
     */
    public GraphBuilder(C1XCompilation compilation, IR ir) {
        this.compilation = compilation;
        this.ir = ir;
        this.stats = compilation.stats;
        this.memoryMap = C1XOptions.OptLocalLoadElimination ? new MemoryMap() : null;
        this.localValueMap = C1XOptions.OptLocalValueNumbering ? new ValueMap() : null;
        log = C1XOptions.TraceBytecodeParserLevel > 0 ? new LogStream(TTY.out()) : null;
        stream = new BytecodeStream(compilation.method.code());
        constantPool = compilation.runtime.getConstantPool(compilation.method);
    }

    /**
     * Builds the graph for a the specified {@code IRScope}.
     * @param scope the top IRScope
     */
    public void build() {
        RiMethod rootMethod = compilation.method;

        if (log != null) {
            log.println();
            log.println("Compiling " + compilation.method);
        }

        if (rootMethod.noSafepoints()) {
            flags |= Flag.NoSafepoints.mask;
        }

        // 1. create the start block
        ir.startBlock = new BlockBegin(0, ir.nextBlockNumber());
        BlockBegin startBlock = ir.startBlock;

        // 2. compute the block map, setup exception handlers and get the entrypoint(s)
        blockMap = compilation.getBlockMap(rootMethod);
        BlockBegin stdEntry = blockMap.get(0);
        curBlock = startBlock;

        RiExceptionHandler[] handlers = rootMethod.exceptionHandlers();
        if (handlers != null && handlers.length > 0) {
            exceptionHandlers = new ArrayList<ExceptionHandler>(handlers.length);
            for (RiExceptionHandler ch : handlers) {
                ExceptionHandler h = new ExceptionHandler(ch);
                h.setEntryBlock(blockAt(h.handler.handlerBCI()));
                exceptionHandlers.add(h);
            }
            flags |= Flag.HasHandler.mask;
        }

        MutableFrameState initialState = stateAtEntry(rootMethod);
        startBlock.mergeOrClone(initialState, rootMethod);
        BlockBegin syncHandler = null;

        // 3. setup internal state for appending instructions
        curBlock = startBlock;
        lastInstr = startBlock;
        lastInstr.appendNext(null, -1);
        curState = initialState;

        if (isSynchronized(rootMethod.accessFlags())) {
            // 4A.1 add a monitor enter to the start block
            rootMethodSynchronizedObject = synchronizedObject(initialState, compilation.method);
            genMonitorEnter(rootMethodSynchronizedObject, Instruction.SYNCHRONIZATION_ENTRY_BCI);
            // 4A.2 finish the start block
            finishStartBlock(startBlock, stdEntry);

            // 4A.3 setup an exception handler to unlock the root method synchronized object
            syncHandler = new BlockBegin(Instruction.SYNCHRONIZATION_ENTRY_BCI, ir.nextBlockNumber());
            syncHandler.setExceptionEntry();
            syncHandler.setBlockFlag(BlockBegin.BlockFlag.IsOnWorkList);
            syncHandler.setBlockFlag(BlockBegin.BlockFlag.DefaultExceptionHandler);

            ExceptionHandler h = new ExceptionHandler(new CiExceptionHandler(0, rootMethod.code().length, -1, 0, null));
            h.setEntryBlock(syncHandler);
            addExceptionHandler(h);
        } else {
            // 4B.1 simply finish the start block
            finishStartBlock(startBlock, stdEntry);
        }

        // 5. SKIPPED: look for intrinsics

        // 6B.1 do the normal parsing
        addToWorkList(stdEntry);
        iterateAllBlocks();

        if (syncHandler != null && syncHandler.stateBefore() != null) {
            // generate unlocking code if the exception handler is reachable
            fillSyncHandler(rootMethodSynchronizedObject, syncHandler);
        }
    }

    private void finishStartBlock(BlockBegin startBlock, BlockBegin stdEntry) {
        assert curBlock == startBlock;
        Base base = new Base(stdEntry);
        appendWithoutOptimization(base, 0);
        FrameState stateAfter = curState.immutableCopy(bci());
        base.setStateAfter(stateAfter);
        startBlock.setEnd(base);
        assert stdEntry.stateBefore() == null;
        stdEntry.mergeOrClone(stateAfter, method());
    }

    public RiMethod method() {
        return compilation.method;
    }

    public BytecodeStream stream() {
        return stream;
    }

    public int bci() {
        return stream.currentBCI();
    }

    public int nextBCI() {
        return stream.nextBCI();
    }

    private void ipush(Value x) {
        curState.ipush(x);
    }

    private void lpush(Value x) {
        curState.lpush(x);
    }

    private void fpush(Value x) {
        curState.fpush(x);
    }

    private void dpush(Value x) {
        curState.dpush(x);
    }

    private void apush(Value x) {
        curState.apush(x);
    }

    private void wpush(Value x) {
        curState.wpush(x);
    }

    private void push(CiKind kind, Value x) {
        curState.push(kind, x);
    }

    private void pushReturn(CiKind kind, Value x) {
        if (kind != CiKind.Void) {
            curState.push(kind.stackKind(), x);
        }
    }

    private Value ipop() {
        return curState.ipop();
    }

    private Value lpop() {
        return curState.lpop();
    }

    private Value fpop() {
        return curState.fpop();
    }

    private Value dpop() {
        return curState.dpop();
    }

    private Value apop() {
        return curState.apop();
    }

    private Value wpop() {
        return curState.wpop();
    }

    private Value pop(CiKind kind) {
        return curState.pop(kind);
    }

    private CiKind peekKind() {
        Value top = curState.stackAt(curState.stackSize() - 1);
        if (top == null) {
            top = curState.stackAt(curState.stackSize() - 2);
            assert top != null;
            assert top.kind.isDoubleWord();
        }
        return top.kind;
    }

    private void loadLocal(int index, CiKind kind) {
        push(kind, curState.loadLocal(index));
    }

    private void storeLocal(CiKind kind, int index) {
        curState.storeLocal(index, pop(kind));
    }

    List<ExceptionHandler> handleException(Instruction x, int bci) {
        if (!hasHandler()) {
            return Util.uncheckedCast(Collections.EMPTY_LIST);
        }

        ArrayList<ExceptionHandler> exceptionHandlers = new ArrayList<ExceptionHandler>();
        FrameState stateBefore = x.stateBefore();

        assert stateBefore != null : "exception handler state must be available for " + x;
        FrameState state = stateBefore;
        assert bci == Instruction.SYNCHRONIZATION_ENTRY_BCI || bci == bci() : "invalid bci";

        // join with all potential exception handlers
        if (this.exceptionHandlers != null) {
            for (ExceptionHandler handler : this.exceptionHandlers) {
                if (handler.covers(bci)) {
                    // if the handler covers this bytecode index, add it to the list
                    if (addExceptionHandler(exceptionHandlers, handler, state)) {
                        // if the handler was a default handler, we are done
                        return exceptionHandlers;
                    }
                }
            }
        }

        return exceptionHandlers;
    }

    /**
     * Adds an exception handler to the {@linkplain BlockBegin#exceptionHandlerBlocks() list}
     * of exception handlers for the {@link #curBlock current block}.
     *
     * @param exceptionHandlers
     * @param handler
     * @param curScopeData
     * @param curState the current state with empty stack
     * @param scopeCount
     * @return {@code true} if handler catches all exceptions (i.e. {@code handler.isCatchAll() == true})
     */
    private boolean addExceptionHandler(ArrayList<ExceptionHandler> exceptionHandlers, ExceptionHandler handler, FrameState curState) {
        compilation.setHasExceptionHandlers();

        BlockBegin entry = handler.entryBlock();
        FrameState entryState = entry.stateBefore();

        assert entry.bci() == handler.handler.handlerBCI();
        assert entryState == null || curState.locksSize() == entryState.locksSize() : "locks do not match : cur:" + curState.locksSize() + " entry:" + entryState.locksSize();

        // exception handler starts with an empty expression stack
        curState = curState.immutableCopyWithEmptyStack();

        entry.mergeOrClone(curState, method());

        // add current state for correct handling of phi functions
        int phiOperand = entry.addExceptionState(curState);

        // add entry to the list of exception handlers of this block
        curBlock.addExceptionHandler(entry);

        // add back-edge from exception handler entry to this block
        if (!entry.blockPredecessors().contains(curBlock)) {
            entry.addPredecessor(curBlock);
        }

        // clone exception handler
        ExceptionHandler newHandler = new ExceptionHandler(handler);
        newHandler.setPhiOperand(phiOperand);
        exceptionHandlers.add(newHandler);

        // fill in exception handler subgraph lazily
        if (!entry.wasVisited()) {
            addToWorkList(entry);
        } else {
            // This will occur for exception handlers that cover themselves. This code
            // pattern is generated by javac for synchronized blocks. See the following
            // for why this change to javac was made:
            //
            //   http://www.cs.arizona.edu/projects/sumatra/hallofshame/java-async-race.html
        }

        // stop when reaching catch all
        return handler.isCatchAll();
    }

    void genLoadConstant(int cpi) {
        Object con = constantPool().lookupConstant(cpi);

        if (con instanceof RiType) {
            // this is a load of class constant which might be unresolved
            RiType riType = (RiType) con;
            if (!riType.isResolved() || C1XOptions.TestPatching) {
                push(CiKind.Object, append(new ResolveClass(riType, RiType.Representation.JavaClass, null)));
            } else {
                push(CiKind.Object, append(new Constant(riType.getEncoding(Representation.JavaClass), graph)));
            }
        } else if (con instanceof CiConstant) {
            CiConstant constant = (CiConstant) con;
            push(constant.kind.stackKind(), appendConstant(constant));
        } else {
            throw new Error("lookupConstant returned an object of incorrect type");
        }
    }

    void genLoadIndexed(CiKind kind) {
        FrameState stateBefore = curState.immutableCopy(bci());
        Value index = ipop();
        Value array = apop();
        Value length = null;
        if (cseArrayLength(array)) {
            length = append(new ArrayLength(array, stateBefore, graph));
        }
        Value v = append(new LoadIndexed(array, index, length, kind, stateBefore, graph));
        push(kind.stackKind(), v);
    }

    void genStoreIndexed(CiKind kind) {
        FrameState stateBefore = curState.immutableCopy(bci());
        Value value = pop(kind.stackKind());
        Value index = ipop();
        Value array = apop();
        Value length = null;
        if (cseArrayLength(array)) {
            length = append(new ArrayLength(array, stateBefore, graph));
        }
        StoreIndexed result = new StoreIndexed(array, index, length, kind, value, stateBefore, graph);
        append(result);
        if (memoryMap != null) {
            memoryMap.storeValue(value);
        }
    }

    void stackOp(int opcode) {
        switch (opcode) {
            case POP: {
                curState.xpop();
                break;
            }
            case POP2: {
                curState.xpop();
                curState.xpop();
                break;
            }
            case DUP: {
                Value w = curState.xpop();
                curState.xpush(w);
                curState.xpush(w);
                break;
            }
            case DUP_X1: {
                Value w1 = curState.xpop();
                Value w2 = curState.xpop();
                curState.xpush(w1);
                curState.xpush(w2);
                curState.xpush(w1);
                break;
            }
            case DUP_X2: {
                Value w1 = curState.xpop();
                Value w2 = curState.xpop();
                Value w3 = curState.xpop();
                curState.xpush(w1);
                curState.xpush(w3);
                curState.xpush(w2);
                curState.xpush(w1);
                break;
            }
            case DUP2: {
                Value w1 = curState.xpop();
                Value w2 = curState.xpop();
                curState.xpush(w2);
                curState.xpush(w1);
                curState.xpush(w2);
                curState.xpush(w1);
                break;
            }
            case DUP2_X1: {
                Value w1 = curState.xpop();
                Value w2 = curState.xpop();
                Value w3 = curState.xpop();
                curState.xpush(w2);
                curState.xpush(w1);
                curState.xpush(w3);
                curState.xpush(w2);
                curState.xpush(w1);
                break;
            }
            case DUP2_X2: {
                Value w1 = curState.xpop();
                Value w2 = curState.xpop();
                Value w3 = curState.xpop();
                Value w4 = curState.xpop();
                curState.xpush(w2);
                curState.xpush(w1);
                curState.xpush(w4);
                curState.xpush(w3);
                curState.xpush(w2);
                curState.xpush(w1);
                break;
            }
            case SWAP: {
                Value w1 = curState.xpop();
                Value w2 = curState.xpop();
                curState.xpush(w1);
                curState.xpush(w2);
                break;
            }
            default:
                throw Util.shouldNotReachHere();
        }

    }

    void genArithmeticOp(CiKind kind, int opcode) {
        genArithmeticOp(kind, opcode, null);
    }

    void genArithmeticOp(CiKind kind, int opcode, FrameState state) {
        genArithmeticOp(kind, opcode, kind, kind, state);
    }

    void genArithmeticOp(CiKind result, int opcode, CiKind x, CiKind y, FrameState state) {
        Value yValue = pop(y);
        Value xValue = pop(x);
        Value result1 = append(new ArithmeticOp(opcode, result, xValue, yValue, isStrict(method().accessFlags()), state, graph));
        push(result, result1);
    }

    void genNegateOp(CiKind kind) {
        push(kind, append(new NegateOp(pop(kind), graph)));
    }

    void genShiftOp(CiKind kind, int opcode) {
        Value s = ipop();
        Value x = pop(kind);
        // note that strength reduction of e << K >>> K is correctly handled in canonicalizer now
        push(kind, append(new ShiftOp(opcode, x, s, graph)));
    }

    void genLogicOp(CiKind kind, int opcode) {
        Value y = pop(kind);
        Value x = pop(kind);
        push(kind, append(new LogicOp(opcode, x, y, graph)));
    }

    void genCompareOp(CiKind kind, int opcode, CiKind resultKind) {
        Value y = pop(kind);
        Value x = pop(kind);
        Value value = append(new CompareOp(opcode, resultKind, x, y, graph));
        if (!resultKind.isVoid()) {
            ipush(value);
        }
    }

    void genConvert(int opcode, CiKind from, CiKind to) {
        CiKind tt = to.stackKind();
        push(tt, append(new Convert(opcode, pop(from.stackKind()), tt, graph)));
    }

    void genIncrement() {
        int index = stream().readLocalIndex();
        int delta = stream().readIncrement();
        Value x = curState.localAt(index);
        Value y = append(Constant.forInt(delta, graph));
        curState.storeLocal(index, append(new ArithmeticOp(IADD, CiKind.Int, x, y, isStrict(method().accessFlags()), null, graph)));
    }

    void genGoto(int fromBCI, int toBCI) {
        boolean isSafepoint = !noSafepoints() && toBCI <= fromBCI;
        append(new Goto(blockAt(toBCI), null, isSafepoint));
    }

    void ifNode(Value x, Condition cond, Value y, FrameState stateBefore) {
        BlockBegin tsucc = blockAt(stream().readBranchDest());
        BlockBegin fsucc = blockAt(stream().nextBCI());
        int bci = stream().currentBCI();
        boolean isSafepoint = !noSafepoints() && tsucc.bci() <= bci || fsucc.bci() <= bci;
        append(new If(x, cond, y, tsucc, fsucc, isSafepoint ? stateBefore : null, isSafepoint));
    }

    void genIfZero(Condition cond) {
        Value y = appendConstant(CiConstant.INT_0);
        FrameState stateBefore = curState.immutableCopy(bci());
        Value x = ipop();
        ifNode(x, cond, y, stateBefore);
    }

    void genIfNull(Condition cond) {
        FrameState stateBefore = curState.immutableCopy(bci());
        Value y = appendConstant(CiConstant.NULL_OBJECT);
        Value x = apop();
        ifNode(x, cond, y, stateBefore);
    }

    void genIfSame(CiKind kind, Condition cond) {
        FrameState stateBefore = curState.immutableCopy(bci());
        Value y = pop(kind);
        Value x = pop(kind);
        ifNode(x, cond, y, stateBefore);
    }

    void genThrow(int bci) {
        FrameState stateBefore = curState.immutableCopy(bci());
        Throw t = new Throw(apop(), stateBefore, !noSafepoints());
        appendWithoutOptimization(t, bci);
    }

    void genCheckCast() {
        int cpi = stream().readCPI();
        RiType type = constantPool().lookupType(cpi, CHECKCAST);
        boolean isInitialized = !C1XOptions.TestPatching && type.isResolved() && type.isInitialized();
        Value typeInstruction = genResolveClass(RiType.Representation.ObjectHub, type, isInitialized, cpi);
        CheckCast c = new CheckCast(type, typeInstruction, apop(), null);
        apush(append(c));
        checkForDirectCompare(c);
    }

    void genInstanceOf() {
        int cpi = stream().readCPI();
        RiType type = constantPool().lookupType(cpi, INSTANCEOF);
        boolean isInitialized = !C1XOptions.TestPatching && type.isResolved() && type.isInitialized();
        Value typeInstruction = genResolveClass(RiType.Representation.ObjectHub, type, isInitialized, cpi);
        InstanceOf i = new InstanceOf(type, typeInstruction, apop(), null);
        ipush(append(i));
        checkForDirectCompare(i);
    }

    private void checkForDirectCompare(TypeCheck check) {
        RiType type = check.targetClass();
        if (!type.isResolved() || type.isArrayClass()) {
            return;
        }
    }

    void genNewInstance(int cpi) {
        FrameState stateBefore = curState.immutableCopy(bci());
        RiType type = constantPool().lookupType(cpi, NEW);
        NewInstance n = new NewInstance(type, cpi, constantPool(), stateBefore);
        if (memoryMap != null) {
            memoryMap.newInstance(n);
        }
        apush(append(n));
    }

    void genNewTypeArray(int typeCode) {
        FrameState stateBefore = curState.immutableCopy(bci());
        CiKind kind = CiKind.fromArrayTypeCode(typeCode);
        RiType elementType = compilation.runtime.asRiType(kind);
        apush(append(new NewTypeArray(ipop(), elementType, stateBefore)));
    }

    void genNewObjectArray(int cpi) {
        RiType type = constantPool().lookupType(cpi, ANEWARRAY);
        FrameState stateBefore = curState.immutableCopy(bci());
        NewArray n = new NewObjectArray(type, ipop(), stateBefore);
        apush(append(n));
    }

    void genNewMultiArray(int cpi) {
        RiType type = constantPool().lookupType(cpi, MULTIANEWARRAY);
        FrameState stateBefore = curState.immutableCopy(bci());
        int rank = stream().readUByte(bci() + 3);
        Value[] dims = new Value[rank];
        for (int i = rank - 1; i >= 0; i--) {
            dims[i] = ipop();
        }
        NewArray n = new NewMultiArray(type, dims, stateBefore, cpi, constantPool());
        apush(append(n));
    }

    void genGetField(int cpi, RiField field) {
        // Must copy the state here, because the field holder must still be on the stack.
        FrameState stateBefore = curState.immutableCopy(bci());
        LoadField load = new LoadField(apop(), field, stateBefore);
        appendOptimizedLoadField(field.kind(), load);
    }

    void genPutField(int cpi, RiField field) {
        // Must copy the state here, because the field holder must still be on the stack.
        FrameState stateBefore = curState.immutableCopy(bci());
        Value value = pop(field.kind().stackKind());
        appendOptimizedStoreField(new StoreField(apop(), field, value, stateBefore));
    }

    void genGetStatic(int cpi, RiField field) {
        RiType holder = field.holder();
        boolean isInitialized = !C1XOptions.TestPatching && field.isResolved();
        CiConstant constantValue = null;
        if (isInitialized) {
            constantValue = field.constantValue(null);
        }
        if (constantValue != null) {
            push(constantValue.kind.stackKind(), appendConstant(constantValue));
        } else {
            Value container = genResolveClass(RiType.Representation.StaticFields, holder, field.isResolved(), cpi);
            LoadField load = new LoadField(container, field, null);
            appendOptimizedLoadField(field.kind(), load);
        }
    }

    void genPutStatic(int cpi, RiField field) {
        RiType holder = field.holder();
        Value container = genResolveClass(RiType.Representation.StaticFields, holder, field.isResolved(), cpi);
        Value value = pop(field.kind().stackKind());
        StoreField store = new StoreField(container, field, value, null);
        appendOptimizedStoreField(store);
    }

    private Value genResolveClass(RiType.Representation representation, RiType holder, boolean initialized, int cpi) {
        Value holderInstr;
        if (initialized) {
            holderInstr = appendConstant(holder.getEncoding(representation));
        } else {
            holderInstr = append(new ResolveClass(holder, representation, null));
        }
        return holderInstr;
    }

    private void appendOptimizedStoreField(StoreField store) {
        if (memoryMap != null) {
            StoreField previous = memoryMap.store(store);
            if (previous == null) {
                // the store is redundant, do not append
                return;
            }
        }
        append(store);
    }

    private void appendOptimizedLoadField(CiKind kind, LoadField load) {
        if (memoryMap != null) {
            Value replacement = memoryMap.load(load);
            if (replacement != load) {
                // the memory buffer found a replacement for this load (no need to append)
                push(kind.stackKind(), replacement);
                return;
            }
        }
        // append the load to the instruction
        Value optimized = append(load);
        if (memoryMap != null && optimized != load) {
            // local optimization happened, replace its value in the memory map
            memoryMap.setResult(load, optimized);
        }
        push(kind.stackKind(), optimized);
    }

    void genInvokeStatic(RiMethod target, int cpi, RiConstantPool constantPool) {
        RiType holder = target.holder();
        boolean isInitialized = !C1XOptions.TestPatching && target.isResolved() && holder.isInitialized();
        if (!isInitialized && C1XOptions.ResolveClassBeforeStaticInvoke) {
            // Re-use the same resolution code as for accessing a static field. Even though
            // the result of resolution is not used by the invocation (only the side effect
            // of initialization is required), it can be commoned with static field accesses.
            genResolveClass(RiType.Representation.StaticFields, holder, isInitialized, cpi);
        }

        Value[] args = curState.popArguments(target.signature().argumentSlots(false));
        appendInvoke(INVOKESTATIC, target, args, cpi, constantPool);
    }

    void genInvokeInterface(RiMethod target, int cpi, RiConstantPool constantPool) {
        Value[] args = curState.popArguments(target.signature().argumentSlots(true));

        genInvokeIndirect(INVOKEINTERFACE, target, args, cpi, constantPool);

    }

    void genInvokeVirtual(RiMethod target, int cpi, RiConstantPool constantPool) {
        Value[] args = curState.popArguments(target.signature().argumentSlots(true));
        genInvokeIndirect(INVOKEVIRTUAL, target, args, cpi, constantPool);

    }

    void genInvokeSpecial(RiMethod target, RiType knownHolder, int cpi, RiConstantPool constantPool) {
        Value[] args = curState.popArguments(target.signature().argumentSlots(true));
        invokeDirect(target, args, knownHolder, cpi, constantPool);

    }

    /**
     * Temporary work-around to support the @ACCESSOR Maxine annotation.
     */
    private static final Class<?> Accessor;
    static {
        Class<?> c = null;
        try {
            c = Class.forName("com.sun.max.unsafe.Accessor");
        } catch (ClassNotFoundException e) {
        }
        Accessor = c;
    }

    /**
     * Temporary work-around to support the @ACCESSOR Maxine annotation.
     */
    private static ThreadLocal<RiType> boundAccessor = new ThreadLocal<RiType>();

    /**
     * Temporary work-around to support the @ACCESSOR Maxine annotation.
     */
    private static RiMethod bindAccessorMethod(RiMethod target) {
        if (Accessor != null && target.isResolved() && target.holder().javaClass() == Accessor) {
            RiType accessor = boundAccessor.get();
            assert accessor != null : "Cannot compile call to method in " + target.holder() + " without enclosing @ACCESSOR annotated method";
            RiMethod newTarget = accessor.resolveMethodImpl(target);
            assert target != newTarget : "Could not bind " + target + " to a method in " + accessor;
            target = newTarget;
        }
        return target;
    }

    private void genInvokeIndirect(int opcode, RiMethod target, Value[] args, int cpi, RiConstantPool constantPool) {
        Value receiver = args[0];
        // attempt to devirtualize the call
        if (target.isResolved()) {
            RiType klass = target.holder();

            // 0. check for trivial cases
            if (target.canBeStaticallyBound() && !isAbstract(target.accessFlags())) {
                // check for trivial cases (e.g. final methods, nonvirtual methods)
                invokeDirect(target, args, target.holder(), cpi, constantPool);
                return;
            }
            // 1. check if the exact type of the receiver can be determined
            RiType exact = getExactType(klass, receiver);
            if (exact != null && exact.isResolved()) {
                // either the holder class is exact, or the receiver object has an exact type
                invokeDirect(exact.resolveMethodImpl(target), args, exact, cpi, constantPool);
                return;
            }
            // 2. check if an assumed leaf method can be found
            RiMethod leaf = getAssumedLeafMethod(target, receiver);
            if (leaf != null && leaf.isResolved() && !isAbstract(leaf.accessFlags()) && leaf.holder().isResolved()) {
                if (C1XOptions.PrintAssumptions) {
                    TTY.println("Optimistic invoke direct because of leaf method to " + leaf);
                }
                invokeDirect(leaf, args, null, cpi, constantPool);
                return;
            } else if (C1XOptions.PrintAssumptions) {
                TTY.println("Could not make leaf method assumption for target=" + target + " leaf=" + leaf + " receiver.declaredType=" + receiver.declaredType());
            }
            // 3. check if the either of the holder or declared type of receiver can be assumed to be a leaf
            exact = getAssumedLeafType(klass, receiver);
            if (exact != null && exact.isResolved()) {
                RiMethod targetMethod = exact.resolveMethodImpl(target);
                if (C1XOptions.PrintAssumptions) {
                    TTY.println("Optimistic invoke direct because of leaf type to " + targetMethod);
                }
                // either the holder class is exact, or the receiver object has an exact type
                invokeDirect(targetMethod, args, exact, cpi, constantPool);
                return;
            } else if (C1XOptions.PrintAssumptions) {
                TTY.println("Could not make leaf type assumption for type " + klass);
            }
        }
        // devirtualization failed, produce an actual invokevirtual
        appendInvoke(opcode, target, args, cpi, constantPool);
    }

    private CiKind returnKind(RiMethod target) {
        return target.signature().returnKind();
    }

    private void invokeDirect(RiMethod target, Value[] args, RiType knownHolder, int cpi, RiConstantPool constantPool) {
        appendInvoke(INVOKESPECIAL, target, args, cpi, constantPool);
    }

    private void appendInvoke(int opcode, RiMethod target, Value[] args, int cpi, RiConstantPool constantPool) {
        CiKind resultType = returnKind(target);
        Value result = append(new Invoke(opcode, resultType.stackKind(), args, target, target.signature().returnType(compilation.method.holder()), null));
        pushReturn(resultType, result);
    }

    private RiType getExactType(RiType staticType, Value receiver) {
        RiType exact = staticType.exactType();
        if (exact == null) {
            exact = receiver.exactType();
            if (exact == null) {
                if (receiver.isConstant()) {
                    exact = compilation.runtime.getTypeOf(receiver.asConstant());
                }
                if (exact == null) {
                    RiType declared = receiver.declaredType();
                    exact = declared == null || !declared.isResolved() ? null : declared.exactType();
                }
            }
        }
        return exact;
    }

    private RiType getAssumedLeafType(RiType type) {
        if (isFinal(type.accessFlags())) {
            return type;
        }
        RiType assumed = null;
        if (C1XOptions.UseAssumptions) {
            assumed = type.uniqueConcreteSubtype();
            if (assumed != null) {
                if (C1XOptions.PrintAssumptions) {
                    TTY.println("Recording concrete subtype assumption in context of " + type.name() + ": " + assumed.name());
                }
                compilation.assumptions.recordConcreteSubtype(type, assumed);
            }
        }
        return assumed;
    }

    private RiType getAssumedLeafType(RiType staticType, Value receiver) {
        RiType assumed = getAssumedLeafType(staticType);
        if (assumed != null) {
            return assumed;
        }
        RiType declared = receiver.declaredType();
        if (declared != null && declared.isResolved()) {
            assumed = getAssumedLeafType(declared);
            return assumed;
        }
        return null;
    }

    private RiMethod getAssumedLeafMethod(RiMethod target, Value receiver) {
        RiMethod assumed = getAssumedLeafMethod(target);
        if (assumed != null) {
            return assumed;
        }
        RiType declared = receiver.declaredType();
        if (declared != null && declared.isResolved() && !declared.isInterface()) {
            RiMethod impl = declared.resolveMethodImpl(target);
            if (impl != null) {
                assumed = getAssumedLeafMethod(impl);
            }
        }
        return assumed;
    }

    void callRegisterFinalizer() {
        Value receiver = curState.loadLocal(0);
        RiType declaredType = receiver.declaredType();
        RiType receiverType = declaredType;
        RiType exactType = receiver.exactType();
        if (exactType == null && declaredType != null) {
            exactType = declaredType.exactType();
        }
        if (exactType == null && receiver instanceof Local && ((Local) receiver).javaIndex() == 0) {
            // the exact type isn't known, but the receiver is parameter 0 => use holder
            receiverType = compilation.method.holder();
            exactType = receiverType.exactType();
        }
        boolean needsCheck = true;
        if (exactType != null) {
            // we have an exact type
            needsCheck = exactType.hasFinalizer();
        } else {
            // if either the declared type of receiver or the holder can be assumed to have no finalizers
            if (declaredType != null && !declaredType.hasFinalizableSubclass()) {
                if (compilation.recordNoFinalizableSubclassAssumption(declaredType)) {
                    needsCheck = false;
                }
            }

            if (receiverType != null && !receiverType.hasFinalizableSubclass()) {
                if (compilation.recordNoFinalizableSubclassAssumption(receiverType)) {
                    needsCheck = false;
                }
            }
        }

        if (needsCheck) {
            // append a call to the finalizer registration
            append(new RegisterFinalizer(curState.loadLocal(0), curState.immutableCopy(bci())));
            C1XMetrics.InlinedFinalizerChecks++;
        }
    }

    void genReturn(Value x) {
        if (method().isConstructor() && method().holder().superType() == null) {
            callRegisterFinalizer();
        }

        curState.truncateStack(0);
        if (Modifier.isSynchronized(method().accessFlags())) {
            FrameState stateBefore = curState.immutableCopy(bci());
            // unlock before exiting the method
            int lockNumber = curState.locksSize() - 1;
            MonitorAddress lockAddress = null;
            if (compilation.runtime.sizeOfBasicObjectLock() != 0) {
                lockAddress = new MonitorAddress(lockNumber);
                append(lockAddress);
            }
            append(new MonitorExit(rootMethodSynchronizedObject, lockAddress, lockNumber, stateBefore));
            curState.unlock();
        }
        append(new Return(x, !noSafepoints()));
    }

    /**
     * Gets the number of locks held.
     */
    private int locksSize() {
        return curState.locksSize();
    }

    void genMonitorEnter(Value x, int bci) {
        int lockNumber = locksSize();
        MonitorAddress lockAddress = null;
        if (compilation.runtime.sizeOfBasicObjectLock() != 0) {
            lockAddress = new MonitorAddress(lockNumber);
            append(lockAddress);
        }
        MonitorEnter monitorEnter = new MonitorEnter(x, lockAddress, lockNumber, null);
        appendWithoutOptimization(monitorEnter, bci);
        curState.lock(ir, x, lockNumber + 1);
        monitorEnter.setStateAfter(curState.immutableCopy(bci));
        killMemoryMap(); // prevent any optimizations across synchronization
    }

    void genMonitorExit(Value x, int bci) {
        int lockNumber = curState.locksSize() - 1;
        if (lockNumber < 0) {
            throw new CiBailout("monitor stack underflow");
        }
        MonitorAddress lockAddress = null;
        if (compilation.runtime.sizeOfBasicObjectLock() != 0) {
            lockAddress = new MonitorAddress(lockNumber);
            append(lockAddress);
        }
        appendWithoutOptimization(new MonitorExit(x, lockAddress, lockNumber, null), bci);
        curState.unlock();
        killMemoryMap(); // prevent any optimizations across synchronization
    }

    void genJsr(int dest) {
        throw new CiBailout("jsr/ret not supported");
    }

    void genRet(int localIndex) {
        throw new CiBailout("jsr/ret not supported");
    }

    void genTableswitch() {
        int bci = bci();
        BytecodeTableSwitch ts = new BytecodeTableSwitch(stream(), bci);
        int max = ts.numberOfCases();
        List<BlockBegin> list = new ArrayList<BlockBegin>(max + 1);
        boolean isBackwards = false;
        for (int i = 0; i < max; i++) {
            // add all successors to the successor list
            int offset = ts.offsetAt(i);
            list.add(blockAt(bci + offset));
            isBackwards |= offset < 0; // track if any of the successors are backwards
        }
        int offset = ts.defaultOffset();
        isBackwards |= offset < 0; // if the default successor is backwards
        list.add(blockAt(bci + offset));
        boolean isSafepoint = isBackwards && !noSafepoints();
        FrameState stateBefore = isSafepoint ? curState.immutableCopy(bci()) : null;
        append(new TableSwitch(ipop(), list, ts.lowKey(), stateBefore, isSafepoint));
    }

    void genLookupswitch() {
        int bci = bci();
        BytecodeLookupSwitch ls = new BytecodeLookupSwitch(stream(), bci);
        int max = ls.numberOfCases();
        List<BlockBegin> list = new ArrayList<BlockBegin>(max + 1);
        int[] keys = new int[max];
        boolean isBackwards = false;
        for (int i = 0; i < max; i++) {
            // add all successors to the successor list
            int offset = ls.offsetAt(i);
            list.add(blockAt(bci + offset));
            keys[i] = ls.keyAt(i);
            isBackwards |= offset < 0; // track if any of the successors are backwards
        }
        int offset = ls.defaultOffset();
        isBackwards |= offset < 0; // if the default successor is backwards
        list.add(blockAt(bci + offset));
        boolean isSafepoint = isBackwards && !noSafepoints();
        FrameState stateBefore = isSafepoint ? curState.immutableCopy(bci()) : null;
        append(new LookupSwitch(ipop(), list, keys, stateBefore, isSafepoint));
    }

    /**
     * Determines whether the length of an array should be extracted out as a separate instruction
     * before an array indexing instruction. This exposes it to CSE.
     * @param array
     * @return
     */
    private boolean cseArrayLength(Value array) {
        // checks whether an array length access should be generated for CSE
        if (C1XOptions.OptCSEArrayLength) {
            // always access the length for CSE
            return true;
        } else if (array.isConstant()) {
            // the array itself is a constant
            return true;
        } else if (array instanceof LoadField && ((LoadField) array).constantValue() != null) {
            // the length is derived from a constant array
            return true;
        } else if (array instanceof NewArray) {
            // the array is derived from an allocation
            final Value length = ((NewArray) array).length();
            return length != null && length.isConstant();
        }
        return false;
    }

    private Value appendConstant(CiConstant type) {
        return appendWithBCI(new Constant(type, graph), bci());
    }

    private Value append(Instruction x) {
        return appendWithBCI(x, bci());
    }

    private Value appendWithoutOptimization(Instruction x, int bci) {
        return appendWithBCI(x, bci);
    }

    private Value appendWithBCI(Instruction x, int bci) {
        if (x.isAppended()) {
            // the instruction has already been added
            return x;
        }
        if (localValueMap != null) {
            // look in the local value map
            Value r = localValueMap.findInsert(x);
            if (r != x) {
                C1XMetrics.LocalValueNumberHits++;
                if (r instanceof Instruction) {
                    assert ((Instruction) r).isAppended() : "instruction " + r + "is not appended";
                }
                return r;
            }
        }

        assert x.next() == null : "instruction should not have been appended yet";
        assert lastInstr.next() == null : "cannot append instruction to instruction which isn't end (" + lastInstr + "->" + lastInstr.next() + ")";
        if (lastInstr instanceof Base) {
            assert false : "may only happen when inlining intrinsics";
        } else {
            lastInstr = lastInstr.appendNext(x, bci);
        }
        if (++stats.nodeCount >= C1XOptions.MaximumInstructionCount) {
            // bailout if we've exceeded the maximum inlining size
            throw new CiBailout("Method and/or inlining is too large");
        }

        if (memoryMap != null && hasUncontrollableSideEffects(x)) {
            // conservatively kill all memory if there are unknown side effects
            memoryMap.kill();
        }

        if (x instanceof StateSplit) {
            StateSplit stateSplit = (StateSplit) x;
            if (stateSplit.stateBefore() == null) {
                stateSplit.setStateBefore(curState.immutableCopy(bci));
            }
        }

        if (x.canTrap()) {
            // connect the instruction to any exception handlers
            x.setExceptionHandlers(handleException(x, bci));
        }

        return x;
    }

    private boolean hasUncontrollableSideEffects(Value x) {
        return x instanceof Invoke || x instanceof ResolveClass;
    }

    private BlockBegin blockAtOrNull(int bci) {
        return blockMap.get(bci);
    }

    private BlockBegin blockAt(int bci) {
        BlockBegin result = blockAtOrNull(bci);
        assert result != null : "Expected a block to begin at " + bci;
        return result;
    }

    MutableFrameState stateAtEntry(RiMethod method) {
        MutableFrameState state = new MutableFrameState(-1, method.maxLocals(), method.maxStackSize());
        int index = 0;
        if (!isStatic(method.accessFlags())) {
            // add the receiver and assume it is non null
            Local local = new Local(method.holder().kind(), index);
            local.setFlag(Value.Flag.NonNull, true);
            local.setDeclaredType(method.holder());
            state.storeLocal(index, local);
            index = 1;
        }
        RiSignature sig = method.signature();
        int max = sig.argumentCount(false);
        RiType accessingClass = method.holder();
        for (int i = 0; i < max; i++) {
            RiType type = sig.argumentTypeAt(i, accessingClass);
            CiKind kind = type.kind().stackKind();
            Local local = new Local(kind, index);
            if (type.isResolved()) {
                local.setDeclaredType(type);
            }
            state.storeLocal(index, local);
            index += kind.sizeInSlots();
        }
        return state;
    }

    private Value synchronizedObject(FrameState curState2, RiMethod target) {
        if (isStatic(target.accessFlags())) {
            Constant classConstant = new Constant(target.holder().getEncoding(Representation.JavaClass), graph);
            return appendWithoutOptimization(classConstant, Instruction.SYNCHRONIZATION_ENTRY_BCI);
        } else {
            return curState2.localAt(0);
        }
    }

    private void fillSyncHandler(Value lock, BlockBegin syncHandler) {
        BlockBegin origBlock = curBlock;
        MutableFrameState origState = curState;
        Instruction origLast = lastInstr;

        lastInstr = curBlock = syncHandler;
        while (lastInstr.next() != null) {
            // go forward to the end of the block
            lastInstr = lastInstr.next();
        }
        curState = syncHandler.stateBefore().copy();

        int bci = Instruction.SYNCHRONIZATION_ENTRY_BCI;
        Value exception = appendWithoutOptimization(new ExceptionObject(curState.immutableCopy(bci)), bci);

        assert lock != null;
        assert curState.locksSize() > 0 && curState.lockAt(locksSize() - 1) == lock;
        if (lock instanceof Instruction) {
            Instruction l = (Instruction) lock;
            if (!l.isAppended()) {
                lock = appendWithoutOptimization(l, Instruction.SYNCHRONIZATION_ENTRY_BCI);
            }
        }
        // exit the monitor
        genMonitorExit(lock, Instruction.SYNCHRONIZATION_ENTRY_BCI);

        apush(exception);
        genThrow(bci);
        BlockEnd end = (BlockEnd) lastInstr;
        curBlock.setEnd(end);
        end.setStateAfter(curState.immutableCopy(bci()));

        curBlock = origBlock;
        curState = origState;
        lastInstr = origLast;
    }

    private void iterateAllBlocks() {
        BlockBegin b;
        while ((b = removeFromWorkList()) != null) {
            if (!b.wasVisited()) {
                b.setWasVisited(true);
                // now parse the block
                killMemoryMap();
                curBlock = b;
                curState = b.stateBefore().copy();
                lastInstr = b;
                b.appendNext(null, -1);

                iterateBytecodesForBlock(b.bci(), false);
            }
        }
    }

    private BlockEnd iterateBytecodesForBlock(int bci, boolean inliningIntoCurrentBlock) {
        skipBlock = false;
        assert curState != null;
        stream.setBCI(bci);

        BlockBegin block = curBlock;
        BlockEnd end = null;
        boolean pushException = block.isExceptionEntry() && block.next() == null;
        int prevBCI = bci;
        int endBCI = stream.endBCI();
        boolean blockStart = true;

        while (bci < endBCI) {
            BlockBegin nextBlock = blockAtOrNull(bci);
            if (bci == 0 && inliningIntoCurrentBlock) {
                if (!nextBlock.isParserLoopHeader()) {
                    // Ignore the block boundary of the entry block of a method
                    // being inlined unless the block is a loop header.
                    nextBlock = null;
                    blockStart = false;
                }
            }
            if (nextBlock != null && nextBlock != block) {
                // we fell through to the next block, add a goto and break
                end = new Goto(nextBlock, null, false);
                lastInstr = lastInstr.appendNext(end, prevBCI);
                break;
            }
            // read the opcode
            int opcode = stream.currentBC();

            // push an exception object onto the stack if we are parsing an exception handler
            if (pushException) {
                FrameState stateBefore = curState.immutableCopy(bci());
                apush(append(new ExceptionObject(stateBefore)));
                pushException = false;
            }

            traceState();
            traceInstruction(bci, stream, opcode, blockStart);
            processBytecode(bci, stream, opcode);

            prevBCI = bci;

            if (lastInstr instanceof BlockEnd) {
                end = (BlockEnd) lastInstr;
                break;
            }
            stream.next();
            bci = stream.currentBCI();
            blockStart = false;
        }

        // stop processing of this block
        if (skipBlock) {
            skipBlock = false;
            return (BlockEnd) lastInstr;
        }

        // if the method terminates, we don't need the stack anymore
        if (end instanceof Return || end instanceof Throw) {
            curState.clearStack();
        }

        // connect to begin and set state
        // NOTE that inlining may have changed the block we are parsing
        assert end != null : "end should exist after iterating over bytecodes";
        end.setStateAfter(curState.immutableCopy(bci()));
        curBlock.setEnd(end);
        // propagate the state
        for (BlockBegin succ : end.blockSuccessors()) {
            assert succ.blockPredecessors().contains(curBlock);
            succ.mergeOrClone(end.stateAfter(), method());
            addToWorkList(succ);
        }
        return end;
    }

    private void traceState() {
        if (C1XOptions.TraceBytecodeParserLevel >= TRACELEVEL_STATE && !TTY.isSuppressed()) {
            log.println(String.format("|   state [nr locals = %d, stack depth = %d, method = %s]", curState.localsSize(), curState.stackSize(), method()));
            for (int i = 0; i < curState.localsSize(); ++i) {
                Value value = curState.localAt(i);
                log.println(String.format("|   local[%d] = %-8s : %s", i, value == null ? "bogus" : value.kind.javaName, value));
            }
            for (int i = 0; i < curState.stackSize(); ++i) {
                Value value = curState.stackAt(i);
                log.println(String.format("|   stack[%d] = %-8s : %s", i, value == null ? "bogus" : value.kind.javaName, value));
            }
            for (int i = 0; i < curState.locksSize(); ++i) {
                Value value = curState.lockAt(i);
                log.println(String.format("|   lock[%d] = %-8s : %s", i, value == null ? "bogus" : value.kind.javaName, value));
            }
        }
    }

    private void processBytecode(int bci, BytecodeStream s, int opcode) {
        int cpi;

        // Checkstyle: stop
        switch (opcode) {
            case NOP            : /* nothing to do */ break;
            case ACONST_NULL    : apush(appendConstant(CiConstant.NULL_OBJECT)); break;
            case ICONST_M1      : ipush(appendConstant(CiConstant.INT_MINUS_1)); break;
            case ICONST_0       : ipush(appendConstant(CiConstant.INT_0)); break;
            case ICONST_1       : ipush(appendConstant(CiConstant.INT_1)); break;
            case ICONST_2       : ipush(appendConstant(CiConstant.INT_2)); break;
            case ICONST_3       : ipush(appendConstant(CiConstant.INT_3)); break;
            case ICONST_4       : ipush(appendConstant(CiConstant.INT_4)); break;
            case ICONST_5       : ipush(appendConstant(CiConstant.INT_5)); break;
            case LCONST_0       : lpush(appendConstant(CiConstant.LONG_0)); break;
            case LCONST_1       : lpush(appendConstant(CiConstant.LONG_1)); break;
            case FCONST_0       : fpush(appendConstant(CiConstant.FLOAT_0)); break;
            case FCONST_1       : fpush(appendConstant(CiConstant.FLOAT_1)); break;
            case FCONST_2       : fpush(appendConstant(CiConstant.FLOAT_2)); break;
            case DCONST_0       : dpush(appendConstant(CiConstant.DOUBLE_0)); break;
            case DCONST_1       : dpush(appendConstant(CiConstant.DOUBLE_1)); break;
            case BIPUSH         : ipush(appendConstant(CiConstant.forInt(s.readByte()))); break;
            case SIPUSH         : ipush(appendConstant(CiConstant.forInt(s.readShort()))); break;
            case LDC            : // fall through
            case LDC_W          : // fall through
            case LDC2_W         : genLoadConstant(s.readCPI()); break;
            case ILOAD          : loadLocal(s.readLocalIndex(), CiKind.Int); break;
            case LLOAD          : loadLocal(s.readLocalIndex(), CiKind.Long); break;
            case FLOAD          : loadLocal(s.readLocalIndex(), CiKind.Float); break;
            case DLOAD          : loadLocal(s.readLocalIndex(), CiKind.Double); break;
            case ALOAD          : loadLocal(s.readLocalIndex(), CiKind.Object); break;
            case ILOAD_0        : // fall through
            case ILOAD_1        : // fall through
            case ILOAD_2        : // fall through
            case ILOAD_3        : loadLocal(opcode - ILOAD_0, CiKind.Int); break;
            case LLOAD_0        : // fall through
            case LLOAD_1        : // fall through
            case LLOAD_2        : // fall through
            case LLOAD_3        : loadLocal(opcode - LLOAD_0, CiKind.Long); break;
            case FLOAD_0        : // fall through
            case FLOAD_1        : // fall through
            case FLOAD_2        : // fall through
            case FLOAD_3        : loadLocal(opcode - FLOAD_0, CiKind.Float); break;
            case DLOAD_0        : // fall through
            case DLOAD_1        : // fall through
            case DLOAD_2        : // fall through
            case DLOAD_3        : loadLocal(opcode - DLOAD_0, CiKind.Double); break;
            case ALOAD_0        : // fall through
            case ALOAD_1        : // fall through
            case ALOAD_2        : // fall through
            case ALOAD_3        : loadLocal(opcode - ALOAD_0, CiKind.Object); break;
            case IALOAD         : genLoadIndexed(CiKind.Int   ); break;
            case LALOAD         : genLoadIndexed(CiKind.Long  ); break;
            case FALOAD         : genLoadIndexed(CiKind.Float ); break;
            case DALOAD         : genLoadIndexed(CiKind.Double); break;
            case AALOAD         : genLoadIndexed(CiKind.Object); break;
            case BALOAD         : genLoadIndexed(CiKind.Byte  ); break;
            case CALOAD         : genLoadIndexed(CiKind.Char  ); break;
            case SALOAD         : genLoadIndexed(CiKind.Short ); break;
            case ISTORE         : storeLocal(CiKind.Int, s.readLocalIndex()); break;
            case LSTORE         : storeLocal(CiKind.Long, s.readLocalIndex()); break;
            case FSTORE         : storeLocal(CiKind.Float, s.readLocalIndex()); break;
            case DSTORE         : storeLocal(CiKind.Double, s.readLocalIndex()); break;
            case ASTORE         : storeLocal(CiKind.Object, s.readLocalIndex()); break;
            case ISTORE_0       : // fall through
            case ISTORE_1       : // fall through
            case ISTORE_2       : // fall through
            case ISTORE_3       : storeLocal(CiKind.Int, opcode - ISTORE_0); break;
            case LSTORE_0       : // fall through
            case LSTORE_1       : // fall through
            case LSTORE_2       : // fall through
            case LSTORE_3       : storeLocal(CiKind.Long, opcode - LSTORE_0); break;
            case FSTORE_0       : // fall through
            case FSTORE_1       : // fall through
            case FSTORE_2       : // fall through
            case FSTORE_3       : storeLocal(CiKind.Float, opcode - FSTORE_0); break;
            case DSTORE_0       : // fall through
            case DSTORE_1       : // fall through
            case DSTORE_2       : // fall through
            case DSTORE_3       : storeLocal(CiKind.Double, opcode - DSTORE_0); break;
            case ASTORE_0       : // fall through
            case ASTORE_1       : // fall through
            case ASTORE_2       : // fall through
            case ASTORE_3       : storeLocal(CiKind.Object, opcode - ASTORE_0); break;
            case IASTORE        : genStoreIndexed(CiKind.Int   ); break;
            case LASTORE        : genStoreIndexed(CiKind.Long  ); break;
            case FASTORE        : genStoreIndexed(CiKind.Float ); break;
            case DASTORE        : genStoreIndexed(CiKind.Double); break;
            case AASTORE        : genStoreIndexed(CiKind.Object); break;
            case BASTORE        : genStoreIndexed(CiKind.Byte  ); break;
            case CASTORE        : genStoreIndexed(CiKind.Char  ); break;
            case SASTORE        : genStoreIndexed(CiKind.Short ); break;
            case POP            : // fall through
            case POP2           : // fall through
            case DUP            : // fall through
            case DUP_X1         : // fall through
            case DUP_X2         : // fall through
            case DUP2           : // fall through
            case DUP2_X1        : // fall through
            case DUP2_X2        : // fall through
            case SWAP           : stackOp(opcode); break;
            case IADD           : // fall through
            case ISUB           : // fall through
            case IMUL           : genArithmeticOp(CiKind.Int, opcode); break;
            case IDIV           : // fall through
            case IREM           : genArithmeticOp(CiKind.Int, opcode, curState.immutableCopy(bci())); break;
            case LADD           : // fall through
            case LSUB           : // fall through
            case LMUL           : genArithmeticOp(CiKind.Long, opcode); break;
            case LDIV           : // fall through
            case LREM           : genArithmeticOp(CiKind.Long, opcode, curState.immutableCopy(bci())); break;
            case FADD           : // fall through
            case FSUB           : // fall through
            case FMUL           : // fall through
            case FDIV           : // fall through
            case FREM           : genArithmeticOp(CiKind.Float, opcode); break;
            case DADD           : // fall through
            case DSUB           : // fall through
            case DMUL           : // fall through
            case DDIV           : // fall through
            case DREM           : genArithmeticOp(CiKind.Double, opcode); break;
            case INEG           : genNegateOp(CiKind.Int); break;
            case LNEG           : genNegateOp(CiKind.Long); break;
            case FNEG           : genNegateOp(CiKind.Float); break;
            case DNEG           : genNegateOp(CiKind.Double); break;
            case ISHL           : // fall through
            case ISHR           : // fall through
            case IUSHR          : genShiftOp(CiKind.Int, opcode); break;
            case IAND           : // fall through
            case IOR            : // fall through
            case IXOR           : genLogicOp(CiKind.Int, opcode); break;
            case LSHL           : // fall through
            case LSHR           : // fall through
            case LUSHR          : genShiftOp(CiKind.Long, opcode); break;
            case LAND           : // fall through
            case LOR            : // fall through
            case LXOR           : genLogicOp(CiKind.Long, opcode); break;
            case IINC           : genIncrement(); break;
            case I2L            : genConvert(opcode, CiKind.Int   , CiKind.Long  ); break;
            case I2F            : genConvert(opcode, CiKind.Int   , CiKind.Float ); break;
            case I2D            : genConvert(opcode, CiKind.Int   , CiKind.Double); break;
            case L2I            : genConvert(opcode, CiKind.Long  , CiKind.Int   ); break;
            case L2F            : genConvert(opcode, CiKind.Long  , CiKind.Float ); break;
            case L2D            : genConvert(opcode, CiKind.Long  , CiKind.Double); break;
            case F2I            : genConvert(opcode, CiKind.Float , CiKind.Int   ); break;
            case F2L            : genConvert(opcode, CiKind.Float , CiKind.Long  ); break;
            case F2D            : genConvert(opcode, CiKind.Float , CiKind.Double); break;
            case D2I            : genConvert(opcode, CiKind.Double, CiKind.Int   ); break;
            case D2L            : genConvert(opcode, CiKind.Double, CiKind.Long  ); break;
            case D2F            : genConvert(opcode, CiKind.Double, CiKind.Float ); break;
            case I2B            : genConvert(opcode, CiKind.Int   , CiKind.Byte  ); break;
            case I2C            : genConvert(opcode, CiKind.Int   , CiKind.Char  ); break;
            case I2S            : genConvert(opcode, CiKind.Int   , CiKind.Short ); break;
            case LCMP           : genCompareOp(CiKind.Long, opcode, CiKind.Int); break;
            case FCMPL          : genCompareOp(CiKind.Float, opcode, CiKind.Int); break;
            case FCMPG          : genCompareOp(CiKind.Float, opcode, CiKind.Int); break;
            case DCMPL          : genCompareOp(CiKind.Double, opcode, CiKind.Int); break;
            case DCMPG          : genCompareOp(CiKind.Double, opcode, CiKind.Int); break;
            case IFEQ           : genIfZero(Condition.EQ); break;
            case IFNE           : genIfZero(Condition.NE); break;
            case IFLT           : genIfZero(Condition.LT); break;
            case IFGE           : genIfZero(Condition.GE); break;
            case IFGT           : genIfZero(Condition.GT); break;
            case IFLE           : genIfZero(Condition.LE); break;
            case IF_ICMPEQ      : genIfSame(CiKind.Int, Condition.EQ); break;
            case IF_ICMPNE      : genIfSame(CiKind.Int, Condition.NE); break;
            case IF_ICMPLT      : genIfSame(CiKind.Int, Condition.LT); break;
            case IF_ICMPGE      : genIfSame(CiKind.Int, Condition.GE); break;
            case IF_ICMPGT      : genIfSame(CiKind.Int, Condition.GT); break;
            case IF_ICMPLE      : genIfSame(CiKind.Int, Condition.LE); break;
            case IF_ACMPEQ      : genIfSame(peekKind(), Condition.EQ); break;
            case IF_ACMPNE      : genIfSame(peekKind(), Condition.NE); break;
            case GOTO           : genGoto(s.currentBCI(), s.readBranchDest()); break;
            case JSR            : genJsr(s.readBranchDest()); break;
            case RET            : genRet(s.readLocalIndex()); break;
            case TABLESWITCH    : genTableswitch(); break;
            case LOOKUPSWITCH   : genLookupswitch(); break;
            case IRETURN        : genReturn(ipop()); break;
            case LRETURN        : genReturn(lpop()); break;
            case FRETURN        : genReturn(fpop()); break;
            case DRETURN        : genReturn(dpop()); break;
            case ARETURN        : genReturn(apop()); break;
            case RETURN         : genReturn(null  ); break;
            case GETSTATIC      : cpi = s.readCPI(); genGetStatic(cpi, constantPool().lookupField(cpi, opcode)); break;
            case PUTSTATIC      : cpi = s.readCPI(); genPutStatic(cpi, constantPool().lookupField(cpi, opcode)); break;
            case GETFIELD       : cpi = s.readCPI(); genGetField(cpi, constantPool().lookupField(cpi, opcode)); break;
            case PUTFIELD       : cpi = s.readCPI(); genPutField(cpi, constantPool().lookupField(cpi, opcode)); break;
            case INVOKEVIRTUAL  : cpi = s.readCPI(); genInvokeVirtual(constantPool().lookupMethod(cpi, opcode), cpi, constantPool()); break;
            case INVOKESPECIAL  : cpi = s.readCPI(); genInvokeSpecial(constantPool().lookupMethod(cpi, opcode), null, cpi, constantPool()); break;
            case INVOKESTATIC   : cpi = s.readCPI(); genInvokeStatic(constantPool().lookupMethod(cpi, opcode), cpi, constantPool()); break;
            case INVOKEINTERFACE: cpi = s.readCPI(); genInvokeInterface(constantPool().lookupMethod(cpi, opcode), cpi, constantPool()); break;
            case NEW            : genNewInstance(s.readCPI()); break;
            case NEWARRAY       : genNewTypeArray(s.readLocalIndex()); break;
            case ANEWARRAY      : genNewObjectArray(s.readCPI()); break;
            case ARRAYLENGTH    : genArrayLength(); break;
            case ATHROW         : genThrow(s.currentBCI()); break;
            case CHECKCAST      : genCheckCast(); break;
            case INSTANCEOF     : genInstanceOf(); break;
            case MONITORENTER   : genMonitorEnter(apop(), s.currentBCI()); break;
            case MONITOREXIT    : genMonitorExit(apop(), s.currentBCI()); break;
            case MULTIANEWARRAY : genNewMultiArray(s.readCPI()); break;
            case IFNULL         : genIfNull(Condition.EQ); break;
            case IFNONNULL      : genIfNull(Condition.NE); break;
            case GOTO_W         : genGoto(s.currentBCI(), s.readFarBranchDest()); break;
            case JSR_W          : genJsr(s.readFarBranchDest()); break;
            case BREAKPOINT:
                throw new CiBailout("concurrent setting of breakpoint");
            default:
                throw new CiBailout("Unsupported opcode " + opcode + " (" + nameOf(opcode) + ") [bci=" + bci + "]");
        }
        // Checkstyle: resume
    }

    private void traceInstruction(int bci, BytecodeStream s, int opcode, boolean blockStart) {
        if (C1XOptions.TraceBytecodeParserLevel >= TRACELEVEL_INSTRUCTIONS && !TTY.isSuppressed()) {
            StringBuilder sb = new StringBuilder(40);
            sb.append(blockStart ? '+' : '|');
            if (bci < 10) {
                sb.append("  ");
            } else if (bci < 100) {
                sb.append(' ');
            }
            sb.append(bci).append(": ").append(Bytecodes.nameOf(opcode));
            for (int i = bci + 1; i < s.nextBCI(); ++i) {
                sb.append(' ').append(s.readUByte(i));
            }
            log.println(sb.toString());
        }
    }

    private void genArrayLength() {
        FrameState stateBefore = curState.immutableCopy(bci());
        ipush(append(new ArrayLength(apop(), stateBefore, graph)));
    }

    void killMemoryMap() {
        if (localValueMap != null) {
            localValueMap.killAll();
        }
        if (memoryMap != null) {
            memoryMap.kill();
        }
    }

    boolean assumeLeafClass(RiType type) {
        if (type.isResolved()) {
            if (isFinal(type.accessFlags())) {
                return true;
            }

            if (C1XOptions.UseAssumptions) {
                RiType assumed = type.uniqueConcreteSubtype();
                if (assumed != null && assumed == type) {
                    if (C1XOptions.PrintAssumptions) {
                        TTY.println("Recording leaf class assumption for " + type.name());
                    }
                    compilation.assumptions.recordConcreteSubtype(type, assumed);
                    return true;
                }
            }
        }
        return false;
    }

    RiMethod getAssumedLeafMethod(RiMethod method) {
        if (method.isResolved()) {
            if (method.isLeafMethod()) {
                return method;
            }

            if (C1XOptions.UseAssumptions) {
                RiMethod assumed = method.uniqueConcreteMethod();
                if (assumed != null) {
                    if (C1XOptions.PrintAssumptions) {
                        TTY.println("Recording concrete method assumption in context of " + method.holder().name() + ": " + assumed.name());
                    }
                    compilation.assumptions.recordConcreteMethod(method, assumed);
                    return assumed;
                } else {
                    if (C1XOptions.PrintAssumptions) {
                        TTY.println("Did not find unique concrete method for " + method);
                    }
                }
            }
        }
        return null;
    }

    private RiConstantPool constantPool() {
        return constantPool;
    }

    /**
     * Adds an exception handler.
     * @param handler the handler to add
     */
    private void addExceptionHandler(ExceptionHandler handler) {
        if (exceptionHandlers == null) {
            exceptionHandlers = new ArrayList<ExceptionHandler>();
        }
        exceptionHandlers.add(handler);
        flags |= Flag.HasHandler.mask;
    }

    /**
     * Adds a block to the worklist, if it is not already in the worklist.
     * This method will keep the worklist topologically stored (i.e. the lower
     * DFNs are earlier in the list).
     * @param block the block to add to the work list
     */
    private void addToWorkList(BlockBegin block) {
        if (!block.isOnWorkList()) {
            block.setOnWorkList(true);
            sortIntoWorkList(block);
        }
    }

    private void sortIntoWorkList(BlockBegin top) {
        // XXX: this is O(n), since the whole list is sorted; a heap could achieve O(nlogn), but
        //      would only pay off for large worklists
        if (workList == null) {
            // need to allocate the worklist
            workList = new BlockBegin[5];
        } else if (workListIndex == workList.length) {
            // need to grow the worklist
            BlockBegin[] nworkList = new BlockBegin[workList.length * 3];
            System.arraycopy(workList, 0, nworkList, 0, workList.length);
            workList = nworkList;
        }
        // put the block at the end of the array
        workList[workListIndex++] = top;
        int dfn = top.depthFirstNumber();
        assert dfn >= 0 : top + " does not have a depth first number";
        int i = workListIndex - 2;
        // push top towards the beginning of the array
        for (; i >= 0; i--) {
            BlockBegin b = workList[i];
            if (b.depthFirstNumber() >= dfn) {
                break; // already in the right position
            }
            workList[i + 1] = b; // bubble b down by one
            workList[i] = top;   // and overwrite it with top
        }
    }

    /**
     * Removes the next block from the worklist. The list is sorted topologically, so the
     * block with the lowest depth first number in the list will be removed and returned.
     * @return the next block from the worklist; {@code null} if there are no blocks
     * in the worklist
     */
    private BlockBegin removeFromWorkList() {
        if (workListIndex == 0) {
            return null;
        }
        // pop the last item off the end
        return workList[--workListIndex];
    }

    /**
     * Checks whether this graph has any handlers.
     * @return {@code true} if there are any exception handlers
     */
    private boolean hasHandler() {
        return (flags & Flag.HasHandler.mask) != 0;
    }

    /**
     * Checks whether this graph can contain safepoints.
     */
    private boolean noSafepoints() {
        return (flags & Flag.NoSafepoints.mask) != 0;
    }
}
