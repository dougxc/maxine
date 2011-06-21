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
package com.sun.max.vm.compiler.target;

import static com.sun.max.vm.MaxineVM.*;
import static com.sun.max.vm.VMConfiguration.*;
import static com.sun.max.vm.VMOptions.*;

import java.io.*;
import java.util.*;

import com.oracle.max.asm.*;
import com.sun.cri.ci.*;
import com.sun.max.annotate.*;
import com.sun.max.lang.*;
import com.sun.max.platform.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.stack.*;
import com.sun.max.vm.stack.StackFrameWalker.Cursor;
import com.sun.max.vm.type.*;

/**
 * Generator for {@linkplain Adapter adapters}, code stubs interposing a call from a caller that has a different
 * parameter passing convention from the callee.
 */
public abstract class AdapterGenerator {

    @HOSTED_ONLY
    public static void init() {
        ISA isa = Platform.platform().isa;
        String className = Classes.getPackageName(AdapterGenerator.class) + "." + isa.toString().toLowerCase() + "." + isa + AdapterGenerator.class.getSimpleName();
        Classes.forName(className);
    }

    /**
     * Local alias to {@link VMFrameLayout#STACK_SLOT_SIZE}.
     */
    public static final int OPT_SLOT_SIZE = VMFrameLayout.STACK_SLOT_SIZE;

    /**
     * Local alias to {@link JVMSFrameLayout#JVMS_SLOT_SIZE}.
     */
    public static final int BASELINE_SLOT_SIZE = JVMSFrameLayout.JVMS_SLOT_SIZE;

    /**
     * A signature denotes the parameter kinds of a call including the receiver kind if applicable.
     * The result kind is omitted from the signature as all calling conventions use the same
     * location for holding this value.
     */
    public static class Sig {
        public final CiKind[] kinds;
        public Sig(SignatureDescriptor signature, int receiver) {
            kinds = CiUtil.signatureToKinds(signature, receiver == 1 ? CiKind.Object : null);
        }
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Sig) {
                Sig sig = (Sig) obj;
                return Arrays.equals(kinds, sig.kinds);
            }
            return false;
        }
        @Override
        public int hashCode() {
            if (kinds.length == 0) {
                return kinds.length;
            }
            return kinds.length ^ kinds[kinds.length - 1].typeChar;
        }
        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(kinds.length + 2);
            buf.append('(');
            for (CiKind k : kinds) {
                buf.append(k.typeChar);
            }
            return buf.append(')').toString();
        }
    }

    /**
     * The adapters that have been generated by this generator.
     */
    final HashMap<Sig, Adapter> adapters = new HashMap<Sig, Adapter>(100);

    /**
     * The type of an adapter generated by this generator.
     */
    public final Adapter.Type adapterType;

    protected final CiRegisterConfig opt;

    /**
     * Creates an adapter generator for a given adapter type.
     *
     * @param prologueSize the size of the prologue in a callee that calls the adapters generated by this generator
     * @param adapterType the type of adapter produced by this generator
     */
    protected AdapterGenerator(Adapter.Type adapterType) {
        opt = vm().registerConfigs.standard;
        this.adapterType = adapterType;
        AdapterGenerator old = generatorsByCallee.put(adapterType.callee, this);
        assert old == null;
    }

    /**
     * Gets the number of bytes occupied in an adapter frame for a given value kind.
     *
     * @param kind a value kind
     * @param slotSize the adapter frame slot size of a {@linkplain Kind#isCategory1() category 1} kind
     */
    public static int frameSizeFor(CiKind kind, int slotSize) {
        return kind.jvmSlots * slotSize;
    }

    /**
     * Gets the number of bytes occupied in an adapter frame for a set of arguments.
     *
     * @param argKinds the kinds of the arguments
     * @param slotSize the adapter frame slot size of a {@linkplain Kind#isCategory1() category 1} kind
     */
    public static int frameSizeFor(CiKind[] argKinds, int slotSize) {
        int size = 0;
        for (CiKind k : argKinds) {
            size += frameSizeFor(k, slotSize);
        }
        return size;
    }

    private static final EnumMap<CallEntryPoint, AdapterGenerator> generatorsByCallee = new EnumMap<CallEntryPoint, AdapterGenerator>(CallEntryPoint.class);

    /**
     * Gets an adapter generator based on a given callee.
     *
     * @param callee the method that must be adapted to
     * @return the generator for an adapter that adapts a call to {@code callee} from a method that is compiled with the
     *         other calling convention. The value {@code null} is returned if {@code callee} is itself an adapter, is
     *         never called by code compiled by the VM (e.g. JNI functions and templates) or if there is only one
     *         compiler configured for the VM.
     */
    public static AdapterGenerator forCallee(TargetMethod callee) {
        if (callee.classMethodActor == null) {
            // Some kind of stub
            return null;
        }
        return forCallee(callee.classMethodActor, callee.callEntryPoint);
    }

    public static int prologueSizeForCallee(TargetMethod callee) {
        if (callee.classMethodActor == null) {
            return 0;
        }
        AdapterGenerator generator = forCallee(callee.classMethodActor, callee.callEntryPoint);
        if (generator == null) {
            return 0;
        }
        return generator.prologueSizeForCallee(callee.classMethodActor);
    }

    /**
     * The code size of a given callee's prologue {@linkplain #emitPrologue(Object, Adapter) emitted} by this generator.
     *
     * @param callee a callee whose prologue was produced by this generator
     * @return the code size of the prologue in callee emitted by this generator
     */
    public abstract int prologueSizeForCallee(ClassMethodActor callee);

    /**
     * Gets an adapter generator based on a given callee.
     *
     * @param callee the method that must be adapted to
     * @param callingConvention the calling convention that {@code callee} is being compiled with
     * @return the generator for an adapter that adapts a call to {@code callee} from a method that is compiled
     *         with the other calling convention. The value {@code null} is returned if {@code callee} is never
     *         called by code compiled by the VM (e.g. JNI functions and templates) or if there is only one
     *         compiler configured for the VM.
     */
    public static AdapterGenerator forCallee(ClassMethodActor callee, CallEntryPoint callingConvention) {
        if (!vmConfig().needsAdapters()) {
            // Only one calling convention; no adapters in use
            return null;
        }

        if (callee != null && (callee.isTemplate() || callee.isVmEntryPoint())) {
            // Templates do not have adapters as they are not complete methods that are called
            return null;
        }
        return generatorsByCallee.get(callingConvention);
    }

    /**
     * Gets an adapter based on a given callee, creating it first if necessary.
     *
     * @param callee the method that must be adapted to
     * @return an adapter for a call to {@code callee} from a method compiled with a different calling convention. This
     *         will be {@code null} if there is no need to adapt a call to {@code callee} (e.g. OPT -> baseline call with 0
     *         arguments)
     */
    public final Adapter make(ClassMethodActor callee) {
        SignatureDescriptor signature = callee.descriptor();
        int flags = callee.flags();
        boolean isStatic = Actor.isStatic(flags);
        if (signature.numberOfParameters() == 0 && isStatic) {
            if (adapterType == Adapter.Type.OPT2BASELINE) {
                return null;
            }
            // BASELINE2OPT parameterless calls still require an adapter to save and restore the frame pointer
        }

        // Access to table of adapters must be synchronized
        synchronized (this) {
            Sig sig = new Sig(signature, isStatic ? 0 : 1);
            Adapter adapter = adapters.get(sig);
            if (adapter == null) {
                if (verboseOption.verboseCompilation) {
                    boolean lockDisabledSafepoints = Log.lock();
                    Log.printCurrentThread(false);
                    Log.print(": Creating adapter ");
                    Log.println(sig);
                    Log.unlock(lockDisabledSafepoints);
                }
                adapter = create(sig);
                if (verboseOption.verboseCompilation) {
                    boolean lockDisabledSafepoints = Log.lock();
                    Log.printCurrentThread(false);
                    Log.print(": Created adapter  ");
                    Log.println(adapter.regionName());
                    Log.unlock(lockDisabledSafepoints);
                }
                adapters.put(sig, adapter);
            }
            return adapter;
        }
    }

    /**
     * Creates code to adapt a call to a given callee. This entails ensuring an adapter exists for the signature of the
     * call as well as emitting code for the prologue of the callee to call the adapter.
     *
     * @param callee the method that must be adapted to
     * @param out where to emit the prologue. This must be either an {@link AbstractAssembler} or {@link OutputStream} instance
     * @return the adapter that will adapt a call to {@code callee}. This will be {@code null} if there is no need to
     *         adapt a call to {@code callee} (e.g. OPT -> baseline call with 0 arguments)
     */
    public final Adapter adapt(ClassMethodActor callee, Object out) {
        Adapter adapter = make(callee);
        int prologueSize = emitPrologue(out, adapter);
        assert adapter == null || prologueSize == prologueSizeForCallee(callee);
        return adapter;
    }

    /**
     * If a given stack frame walker cursor denotes a position in the adapter prologue of a target method,
     * then the cursor is advanced to the next frame.
     *
     * @param current the current stack frame cursor
     * @return true if the cursor was advanced
     */
    public abstract boolean advanceIfInPrologue(Cursor current);

    /**
     * Emits the prologue that makes a calls to a given adapter.
     *
     * @param out where to emit the prologue. This must be either an {@link AbstractAssembler} or {@link OutputStream} instance
     * @param adapter the adapter that the prologue calls
     * @return the size of the prologue emitted to {@link out}
     */
    protected abstract int emitPrologue(Object out, Adapter adapter);

    /**
     * Helper method for {@link #emitPrologue(Object, Adapter)} to copy output from an assembler's {@linkplain Buffer buffer} to an
     * {@link OutputStream} object.
     *
     * @param buffer the code buffer into which a prologue has been assembled
     * @param out if this is an {@link OutputStream} instance, then the output is written to it
     */
    protected void copyIfOutputStream(Buffer buffer, Object out) {
        if (out instanceof OutputStream) {
            try {
                ((OutputStream) out).write(buffer.close(true));
            } catch (Exception e) {
                throw FatalError.unexpected(null, e);
            }
        }
    }

    /**
     * Determines if a given instruction pointer denotes a position in a given method's adapter prologue.
     */
    public boolean inPrologue(Address ip, TargetMethod targetMethod) {
        return targetMethod.classMethodActor != null && ip.minus(targetMethod.codeStart).lessThan(prologueSizeForCallee(targetMethod.classMethodActor));
    }

    /**
     * Creates an adapter based on a given signature.
     */
    protected abstract Adapter create(Sig sig);
}
