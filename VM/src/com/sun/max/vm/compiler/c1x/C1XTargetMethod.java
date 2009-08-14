/*
 * Copyright (c) 2009 Sun Microsystems, Inc.  All rights reserved.
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
package com.sun.max.vm.compiler.c1x;

import com.sun.max.vm.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.ClassMethodActor;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.stack.JavaStackFrameLayout;
import com.sun.max.vm.stack.OptoStackFrameLayout;
import com.sun.max.asm.InstructionSet;
import com.sun.max.io.*;
import com.sun.max.unsafe.*;

/**
 * This class implements a {@link com.sun.max.vm.compiler.target.TargetMethod target method} for
 * the Maxine VM that represents a compiled method generated by C1X.
 *
 * @author Ben L. Titzer
 * @author Thomas Wuerthinger
 */
public class C1XTargetMethod extends TargetMethod {

    private int registerReferenceMapSize = 2; // TODO: Set this appropriately
    private int[] exceptionPositionsToCatchPositions;
    private ClassActor[] exceptionClassActors;

    public C1XTargetMethod(ClassMethodActor classMethodActor, DynamicCompilerScheme compilerScheme) {
        super(classMethodActor, compilerScheme);
    }

    public C1XTargetMethod(String stubName, DynamicCompilerScheme compilerScheme) {
        super(stubName, compilerScheme);
    }

    @Override
    public JavaStackFrameLayout stackFrameLayout() {
        return new OptoStackFrameLayout(frameSize());
    }

    @Override
    public boolean areReferenceMapsFinalized() {
        return true;
    }

    @Override
    public InstructionSet instructionSet() {
        return InstructionSet.AMD64;
    }

    @Override
    public final int registerReferenceMapSize() {
        return registerReferenceMapSize;
    }

    @Override
    public final void patchCallSite(int callOffset, Word callEntryPoint) {
        final long target = callEntryPoint.asAddress().toLong();
        final int displacement = (int) (target - codeStart().plus(callOffset).toLong());
        X86InstructionDecoder.patchRelativeInstruction(code(), callOffset, displacement);
    }

    @Override
    public void forwardTo(TargetMethod newTargetMethod) {
        forwardTo(this, newTargetMethod);
    }

    // TODO: (tw) Get rid of these!!!!!!!
    private static final int RJMP = 0xe9;

    public static void forwardTo(TargetMethod oldTargetMethod, TargetMethod newTargetMethod) {
        assert oldTargetMethod != newTargetMethod;
        assert !oldTargetMethod.isNative();
        assert oldTargetMethod.abi().callEntryPoint() != CallEntryPoint.C_ENTRY_POINT;

        final long newOptEntry = CallEntryPoint.OPTIMIZED_ENTRY_POINT.in(newTargetMethod).asAddress().toLong();
        final long newJitEntry = CallEntryPoint.JIT_ENTRY_POINT.in(newTargetMethod).asAddress().toLong();

        patchCode(oldTargetMethod, CallEntryPoint.OPTIMIZED_ENTRY_POINT.offsetFromCodeStart(), newOptEntry, RJMP);
        patchCode(oldTargetMethod, CallEntryPoint.JIT_ENTRY_POINT.offsetFromCodeStart(), newJitEntry, RJMP);
    }

    private static void patchCode(TargetMethod targetMethod, int offset, long target, int controlTransferOpcode) {
        final Pointer callSite = targetMethod.codeStart().plus(offset);
        final long displacement = (target - (callSite.toLong() + 5L)) & 0xFFFFFFFFL;
        if (MaxineVM.isPrototyping()) {
            final byte[] code = targetMethod.code();
            code[offset] = (byte) controlTransferOpcode;
            code[offset + 1] = (byte) displacement;
            code[offset + 2] = (byte) (displacement >> 8);
            code[offset + 3] = (byte) (displacement >> 16);
            code[offset + 4] = (byte) (displacement >> 24);
        } else {
            // TODO: Patching code is probably not thread safe!
            //       Patch location must not straddle a cache-line (32-byte) boundary.
            FatalError.check(true | callSite.isWordAligned(), "Method " + targetMethod.classMethodActor().format("%H.%n(%p)") + " entry point is not word aligned.");
            // The read, modify, write below should be changed to simply a write once we have the method entry point alignment fixed.
            final Word patch = callSite.readWord(0).asAddress().and(0xFFFFFF0000000000L).or((displacement << 8) | controlTransferOpcode);
            callSite.writeWord(0, patch);
        }
    }

    public void setGenerated(int[] exceptionPositionsToCatchPositions, ClassActor[] exceptionClassActors, int[] stopPositions, byte[] compressedJavaFrameDescriptors, Object[] directCallees,
                    int indirectCalls, int safepoints, byte[] refMaps, byte[] data, Object[] refLiterals, byte[] targetCode, byte[] encodedInlineDataDescriptors, int frameSize, int stackRefMapSize,
                    TargetABI abi) {

        this.exceptionPositionsToCatchPositions = exceptionPositionsToCatchPositions;
        this.exceptionClassActors = exceptionClassActors;
        super.setGenerated(stopPositions, compressedJavaFrameDescriptors, directCallees, indirectCalls, safepoints, refMaps, data, refLiterals, targetCode, encodedInlineDataDescriptors, frameSize, stackRefMapSize, abi);
    }

    @Override
    public Address throwAddressToCatchAddress(boolean isTopFrame, Address throwAddress, Class<? extends Throwable> throwableClass) {

        final int throwOffset = throwAddress.minus(codeStart).toInt();
        for (int i = 0; i < getExceptionHandlerCount(); i++) {
            int codePos = getCodePosAt(i);
            int catchPos = getCatchPosAt(i);
            ClassActor catchType = getTypeAt(i);

            if (codePos == throwOffset && checkType(throwableClass, catchType)) {
                return codeStart.plus(catchPos);
            }
        }
        return Address.zero();
    }

    private boolean isCatchAll(ClassActor type) {
        return type == null;
    }

    private boolean checkType(Class<? extends Throwable> throwableClass, ClassActor catchType) {
        return isCatchAll(catchType) || catchType.isAssignableFrom(ClassActor.fromJava(throwableClass));
    }

    private int getCodePosAt(int i) {
        return exceptionPositionsToCatchPositions[i * 2];
    }

    private int getCatchPosAt(int i) {
        return exceptionPositionsToCatchPositions[i * 2 + 1];
    }

    private ClassActor getTypeAt(int i) {
        return exceptionClassActors[i];
    }

    private int getExceptionHandlerCount() {
        return exceptionClassActors == null ? 0 : exceptionClassActors.length;
    }

    @Override
    public void traceExceptionHandlers(IndentWriter writer) {
        writer.println(getExceptionHandlerCount() + " exception handlers:");
        for (int i = 0; i < getExceptionHandlerCount(); i++) {
            int codePos = getCodePosAt(i);
            int catchPos = getCatchPosAt(i);
            ClassActor type = getTypeAt(i);

            writer.println("[codePos=" + codePos + ", catchPos=" + catchPos + ", type=" + type + "]");
        }
    }
}
