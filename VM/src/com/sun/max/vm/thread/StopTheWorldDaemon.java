/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
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
package com.sun.max.vm.thread;

import static com.sun.max.vm.thread.VmThreadLocal.*;

import com.sun.max.lang.*;
import com.sun.max.sync.*;
import com.sun.max.unsafe.*;
import com.sun.max.util.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.heap.sequential.Beltway.*;
import com.sun.max.vm.reference.*;
import com.sun.max.vm.runtime.*;

/**
 * A daemon thread that hangs around, waiting, then executes a given procedure when requested, then waits again.
 *
 * All other VM threads are forced into a non-mutating state while a request is being serviced. This can be used to
 * implement stop-the-world GC.
 *
 * @author Bernd Mathiske
 * @author Ben L. Titzer
 */
public class StopTheWorldDaemon extends BlockingServerDaemon {

    private static Safepoint.Procedure _suspendProcedure = new Safepoint.Procedure() {
        @Override
        public void run(Pointer trapState) {
            // note that this procedure always runs with safepoints disabled
            final Pointer vmThreadLocals = Safepoint.getLatchRegister();

            if (VmThreadLocal.SAFEPOINT_VENUE.getVariableReference(vmThreadLocals).toJava() == Safepoint.Venue.JAVA) {
                VmThreadLocal.prepareStackReferenceMapFromTrap(vmThreadLocals, trapState);
            } else {
                // GC may already be ongoing
            }

            synchronized (VmThreadMap.ACTIVE) {
                // this is ok even though the GC does not get to scan this frame, because the object involved is in the boot image
            }
            VmThreadLocal.SAFEPOINT_VENUE.setVariableReference(vmThreadLocals, Reference.fromJava(Safepoint.Venue.NATIVE));
        }
    };

    private Runnable _procedure = null;

    public StopTheWorldDaemon(String name, Runnable procedure) {
        super(name);
        _procedure = procedure;

    }

    @Override
    public void start() {
        // If the _waitUntilNonMutating Procedure tries to link nativeSleep in response to a safepoint request
        // from System.gc(), then we have a deadlock. (As the thread calling System.gc() gets a lock on the
        // Heap scheme, which prevents the GC thread from allocating the MethodActor for nativeSleep).
        // So we link it here.
        try {
            sleep(1);
        } catch (InterruptedException interruptedException) {
        }

        super.start();
    }

    private final Pointer.Procedure _triggerSafepoint = new Pointer.Procedure() {

        public void run(Pointer vmThreadLocals) {
            if (vmThreadLocals.isZero()) {
                // Thread is still starting up.
                // Do not need to do anything, because it will try to lock 'VmThreadMap.ACTIVE' and thus block.
            } else {
                Safepoint.runProcedure(VmThread.current(vmThreadLocals), _suspendProcedure);
            }
        }
    };

    private final Pointer.Procedure _resetSafepoint = new Pointer.Procedure() {

        public void run(Pointer vmThreadLocals) {
            Safepoint.cancelProcedure(VmThread.current(vmThreadLocals), _suspendProcedure);
            Safepoint.reset(vmThreadLocals);
        }
    };

    private final Pointer.Procedure _waitUntilNonMutating = new Pointer.Procedure() {

        public void run(Pointer vmThreadLocals) {
            while (VmThreadLocal.inJava(vmThreadLocals)) {
                try {
                    sleep(1);
                } catch (InterruptedException interruptedException) {
                }
            }
            if (VmThreadLocal.SAFEPOINT_VENUE.getVariableReference(vmThreadLocals).toJava() == Safepoint.Venue.NATIVE) {
                // Since this thread is in native code it did not get an opportunity to prepare its stack maps,
                // so we will take care of that for it now:
                VmThreadLocal.prepareStackReferenceMap(vmThreadLocals);
            } else {
                // Threads that hit a safepoint in Java code have prepared *most* of their stack reference map themselves.
                // The part of the stack between suspendCurrentThread() and the JNI stub that enters into the
                // native code for blocking on VmThreadMap.ACTIVE's monitor is not yet prepared. Do it now:
                final Pointer instructionPointer = LAST_JAVA_CALLER_INSTRUCTION_POINTER.getVariableWord(vmThreadLocals).asPointer();
                if (instructionPointer.isZero()) {
                    FatalError.unexpected("A mutator thread in Java at safepoint should be stopped in native monitor code");
                }
                final Pointer stackPointer = LAST_JAVA_CALLER_STACK_POINTER.getVariableWord(vmThreadLocals).asPointer();
                final Pointer framePointer = LAST_JAVA_CALLER_FRAME_POINTER.getVariableWord(vmThreadLocals).asPointer();
                final VmThread vmThread = UnsafeLoophole.cast(VmThread.class, VmThreadLocal.VM_THREAD.getConstantReference(vmThreadLocals));
                vmThread.stackReferenceMapPreparer().completeStackReferenceMap(vmThreadLocals, instructionPointer, stackPointer, framePointer);
            }
        }
    };

    private final Pointer.Procedure _fillLastTlabs = new Pointer.Procedure() {

        public void run(Pointer localSpace) {
            if (!localSpace.isZero()) {
                final VmThread thread = VmThread.current(localSpace);
                if (thread != null) {
                    final TLAB tlab = thread.getTLAB();
                    if (!tlab.isFull()) {
                        tlab.fillTLAB();
                    }
                }
            }
        }
    };


    private static class TLABScavengerReset implements Procedure<VmThread> {
        @Override
        public void run(VmThread thread) {
            thread.getTLAB().unSet();
        }
    }

    private TLABScavengerReset _tlabScavengerReset = new TLABScavengerReset();

    private static final Pointer.Procedure _prepareGCThreadStackMap = new Pointer.Procedure() {
        @Override
        public void run(Pointer vmThreadLocals) {
            VmThreadLocal.prepareStackReferenceMap(vmThreadLocals);
        }
    };

    private final Runnable _gcRequest = new Runnable() {

        public void run() {
            synchronized (VmThreadMap.ACTIVE) {
                BeltwayHeapScheme._inGC = true;
                VmThreadMap.ACTIVE.forAllVmThreadLocals(_isNotGCThreadLocalsOrCurrent, _triggerSafepoint);
                VmThreadMap.ACTIVE.forAllVmThreadLocals(_isNotGCThreadLocalsOrCurrent, _waitUntilNonMutating);
                if (BeltwayConfiguration._useTLABS) {
                    VmThreadMap.ACTIVE.forAllVmThreadLocals(_isNotGCThreadLocalsOrCurrent, _fillLastTlabs);
                }
                if (BeltwayConfiguration._useGCTlabs) {
                    VmThreadMap.ACTIVE.forAllVmThreads(_isGCOrStopTheWorldDaemonThread, _tlabScavengerReset);
                }
                VmThreadMap.ACTIVE.forAllVmThreadLocals(_isGCThread, _prepareGCThreadStackMap);
                VmThreadLocal.prepareCurrentStackReferenceMap();
                _procedure.run();
                BeltwayHeapScheme._inGC = false;
                VmThreadMap.ACTIVE.forAllVmThreadLocals(_isNotGCThreadLocalsOrCurrent, _resetSafepoint);
            }
        }


    };

    private static final Predicate<VmThread> _isGCOrStopTheWorldDaemonThread = new Predicate<VmThread>() {
        @Override
        public boolean evaluate(VmThread vmThread) {
            final Thread javaThread = vmThread.javaThread();
            return javaThread instanceof StopTheWorldDaemon || javaThread instanceof BeltwayCollectorThread;
        }
    };

    private static final Pointer.Predicate _isNotGCThreadLocalsOrCurrent = new Pointer.Predicate() {
        @Override
        public boolean evaluate(Pointer vmThreadLocals) {
            if (vmThreadLocals != VmThread.current().vmThreadLocals()) {
                final Thread javaThread = VmThread.current(vmThreadLocals).javaThread();
                return !(javaThread instanceof StopTheWorldDaemon) && !(javaThread instanceof BeltwayCollectorThread);
            }
            return false;
        }
    };

    private static final Pointer.Predicate _isGCThread = new Pointer.Predicate() {
        @Override
        public boolean evaluate(Pointer vmThreadLocals) {
            final Thread javaThread = VmThread.current(vmThreadLocals).javaThread();
            return javaThread instanceof BeltwayCollectorThread;
        }
    };

    public void execute() {
        execute(_gcRequest);
    }

    public TLAB getScavengeTLAB() {
        return _currentTLAB;
    }

    private TLAB _currentTLAB;

}
