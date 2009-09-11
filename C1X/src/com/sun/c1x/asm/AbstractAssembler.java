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
package com.sun.c1x.asm;

import java.util.*;

import com.sun.c1x.*;
import com.sun.c1x.ci.*;
import com.sun.c1x.debug.*;
import com.sun.c1x.ir.*;
import com.sun.c1x.ri.*;
import com.sun.c1x.util.*;

/**
 * @author Marcelo Cintra
 * @author Thomas Wuerthinger
 */
public abstract class AbstractAssembler {

    public final Buffer codeBuffer;
    public final CiTarget target;
    protected final CiTargetMethod targetMethod;

    public AbstractAssembler(CiTarget target, int frameSize) {
        this.target = target;
        this.targetMethod = new CiTargetMethod(target.registerReferenceMapTemplate.length);
        this.codeBuffer = new Buffer(target.arch.bitOrdering);
        targetMethod.setFrameSize(frameSize);
    }

    public void bind(Label l) {
        if (l.isBound()) {
            // Assembler can bind a label more than once to the same place.
            Util.guarantee(l.loc() == codeBuffer.position(), "attempt to redefine label");
            return;
        }
        l.bindLoc(codeBuffer.position());
        l.patchInstructions(this);
    }

    public void setFrameSize(int frameSize) {
        assert targetMethod.frameSize() == -1 : "frame size already set!";
        targetMethod.setFrameSize(frameSize);
    }

    public CiTargetMethod finishTargetMethod(RiRuntime runtime, int framesize, List<ExceptionInfo> exceptionInfoList, int registerRestoreEpilogueOffset) {

        C1XMetrics.TargetMethods++;

        // Install code, data and frame size
        targetMethod.setTargetCode(codeBuffer.finished());
        targetMethod.setRegisterRestoreEpilogueOffset(registerRestoreEpilogueOffset);

        // Record exception handlers if existant
        if (exceptionInfoList != null) {
            for (ExceptionInfo ei : exceptionInfoList) {
                int codeOffset = ei.codeOffset;
                for (ExceptionHandler handler : ei.exceptionHandlers) {
                    int entryOffset = handler.entryCodeOffset();
                    RiType catchedType = handler.handler.catchKlass();
                    targetMethod.recordExceptionHandler(codeOffset, entryOffset, catchedType);
                }
            }
        }

        if (C1XOptions.PrintMetrics) {
            C1XMetrics.CodeBytesEmitted += targetMethod.targetCode().length;
            C1XMetrics.SafepointsEmitted += targetMethod.safepoints.size();
            C1XMetrics.DirectCallSitesEmitted += targetMethod.directCalls.size();
            C1XMetrics.IndirectCallSitesEmitted += targetMethod.indirectCalls.size();
            C1XMetrics.DataPatches += targetMethod.dataReferences.size();
            C1XMetrics.ExceptionHandlersEmitted += targetMethod.exceptionHandlers.size();
        }

        if (C1XOptions.PrintAssembly) {

            Util.printSection("Target Method", Util.SECTION_CHARACTER);
            TTY.println("Frame size: %d", framesize);
            TTY.println("Register size: %d", targetMethod.referenceRegisterCount());

            Util.printSection("Code", Util.SUB_SECTION_CHARACTER);
            Util.printBytes("Code", targetMethod.targetCode(), targetMethod.targetCode().length, C1XOptions.PrintAssemblyBytesPerLine);

            Util.printSection("Disassembly", Util.SUB_SECTION_CHARACTER);
            TTY.println(runtime.disassemble(Arrays.copyOf(targetMethod.targetCode(), targetMethod.targetCode().length)));

            Util.printSection("Safepoints", Util.SUB_SECTION_CHARACTER);
            for (CiTargetMethod.Safepoint x : targetMethod.safepoints) {
                TTY.println(x.toString());
            }

            Util.printSection("Direct Call Sites", Util.SUB_SECTION_CHARACTER);
            for (CiTargetMethod.Call x : targetMethod.directCalls) {
                TTY.println(x.toString());
            }

            Util.printSection("Indirect Call Sites", Util.SUB_SECTION_CHARACTER);
            for (CiTargetMethod.Call x : targetMethod.indirectCalls) {
                TTY.println(x.toString());
            }

            Util.printSection("Data Patches", Util.SUB_SECTION_CHARACTER);
            for (CiTargetMethod.DataPatch x : targetMethod.dataReferences) {
                TTY.println(x.toString());
            }

            Util.printSection("Exception Handlers", Util.SUB_SECTION_CHARACTER);
            for (CiTargetMethod.ExceptionHandler x : targetMethod.exceptionHandlers) {
                TTY.println(x.toString());
            }

        }

        return targetMethod;
    }

    protected void emitByte(int x) {
        codeBuffer.emitByte(x);
    }

    protected void emitShort(int x) {
        codeBuffer.emitShort(x);
    }

    protected void emitInt(int x) {
        codeBuffer.emitInt(x);
    }

    private void verifyReferenceMap() {

    }

    protected void recordGlobalStubCall(int pos, Object globalStubCall, boolean[] registerMap, boolean[] stackMap) {

        assert pos >= 0 && globalStubCall != null;

        if (C1XOptions.TraceRelocation) {
            TTY.print("Global stub call: pos = %d, name = %s", pos, globalStubCall);

            if (registerMap != null) {
                TTY.print(", registerMap.length=%d", registerMap.length);
            }

            if (stackMap != null) {
                TTY.print(", stackMap.length=%d", stackMap.length);
            }
        }

        verifyReferenceMap();
        targetMethod.recordGlobalStubCall(pos, globalStubCall, registerMap, stackMap);
    }

    protected void recordDirectCall(int pos, RiMethod call, boolean[] stackMap) {

        assert pos >= 0 && call != null && stackMap != null;

        if (C1XOptions.TraceRelocation) {
            TTY.println("Direct call: pos = %d, name = %s, stackMap.length = %d", pos, call.name(), stackMap.length);
        }

        verifyReferenceMap();
        targetMethod.recordCall(pos, call, stackMap, false);
    }

    protected void recordIndirectCall(int pos, RiMethod call, boolean[] stackMap) {

        assert pos >= 0 && call != null && stackMap != null;

        if (C1XOptions.TraceRelocation) {
            TTY.println("Indirect call: pos = %d, name = %s, stackMap.length = %d", pos, call.name(), stackMap.length);
        }

        verifyReferenceMap();
        targetMethod.recordCall(pos, call, stackMap, false);
    }

    protected void recordRuntimeCall(int pos, CiRuntimeCall call, boolean[] stackMap) {

        assert pos >= 0 && call != null && stackMap != null;

        if (C1XOptions.TraceRelocation) {
            TTY.println("Runtime call: pos = %d, name = %s, stackMap.length = %d", pos, call.name(), stackMap.length);
        }

        verifyReferenceMap();
        targetMethod.recordRuntimeCall(pos, call, stackMap);
    }

    protected void recordSafepoint(int pos, boolean[] registerMap, boolean[] stackMap) {
        assert pos >= 0 && registerMap != null && stackMap != null;

        if (C1XOptions.TraceRelocation) {
            TTY.print("Safepoint: pos = %d, registerMap.length = %d, stackMap.length = %d", pos, registerMap.length, stackMap.length);
        }

        verifyReferenceMap();
        targetMethod.recordSafepoint(pos, registerMap, stackMap);
    }

    public Address recordDataReferenceInCode(CiConstant data) {

        assert data != null;

        int pos = codeBuffer.position();

        if (C1XOptions.TraceRelocation) {
            TTY.print("Data reference in code: pos = %d, data = %s", pos, data.toString());
        }

        targetMethod.recordDataReference(pos, data);
        return Address.InternalRelocation;
    }

    protected void emitLong(long x) {
        codeBuffer.emitLong(x);
    }

    protected int target(Label l) {
        int branchPc = codeBuffer.position();
        if (l.isBound()) {
            return l.loc();
        } else {
            l.addPatchAt(branchPc);
            // Need to return a pc, doesn't matter what it is since it will be
            // replaced during resolution later.
            // Don't return null or badAddress, since branches shouldn't overflow.
            // Don't return base either because that could overflow displacements
            // for shorter branches. It will get checked when bound.
            return branchPc;
        }
    }

    public abstract void nop();

    public void blockComment(String st) {
        Util.nonFatalUnimplemented();
    }

    public abstract void nullCheck(CiRegister r);

    public void verifiedEntry() {
        Util.nonFatalUnimplemented();
    }

    public abstract void buildFrame(int initialFrameSizeInBytes);

    public abstract void align(int codeEntryAlignment);

    public abstract void makeOffset(int offset);

    public abstract void patchJumpTarget(int branch, int target);
}
