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
package com.sun.max.tele.debug.solaris;

import java.io.*;
import java.nio.*;

import com.sun.max.collect.*;
import com.sun.max.memory.*;
import com.sun.max.platform.*;
import com.sun.max.tele.*;
import com.sun.max.tele.debug.*;
import com.sun.max.tele.page.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.prototype.*;

/**
 * @author Bernd Mathiske
 * @author Aritra Bandyopadhyay
 * @author Doug Simon
 */
public final class SolarisTeleProcess extends TeleProcess {

    private final DataAccess dataAccess;

    @Override
    public DataAccess dataAccess() {
        return dataAccess;
    }

    private static native long nativeCreateChild(long argv, int vmAgentPort);

    private long processHandle;

    long processHandle() {
        return processHandle;
    }

    /**
     * Creates a handle to a native Solaris process by launching a new process with a given set of command line arguments.
     *
     * @param teleVM
     * @param platform
     * @param programFile
     * @param commandLineArguments
     * @param agent TODO
     * @throws BootImageException
     */
    SolarisTeleProcess(TeleVM teleVM, Platform platform, File programFile, String[] commandLineArguments, TeleVMAgent agent) throws BootImageException {
        super(teleVM, platform, ProcessState.STOPPED);
        final Pointer commandLineArgumentsBuffer = TeleProcess.createCommandLineArgumentsBuffer(programFile, commandLineArguments);
        processHandle = nativeCreateChild(commandLineArgumentsBuffer.toLong(), agent.port());
        dataAccess = new PageDataAccess(this, platform.processorKind().dataModel());
        try {
            resume();
        } catch (OSExecutionRequestException e) {
            throw new BootImageException("Error resuming VM after starting it", e);
        }
    }

    private static native void nativeKill(long processHandle);

    @Override
    public void kill() throws OSExecutionRequestException {
        nativeKill(processHandle);
    }

    private static native boolean nativeSuspend(long processHandle);

    @Override
    public void suspend() throws OSExecutionRequestException {
        if (!nativeSuspend(processHandle)) {
            throw new OSExecutionRequestException("Could not suspend process");
        }
    }

    private static native boolean nativeResume(long processHandle);

    @Override
    protected void resume() throws OSExecutionRequestException {
        if (!nativeResume(processHandle)) {
            throw new OSExecutionRequestException("The VM could not be resumed");
        }
    }

    /**
     * Waits until this process is stopped.
     */
    private static native boolean nativeWait(long processHandle);

    @Override
    protected boolean waitUntilStopped() {
        final boolean result = nativeWait(processHandle);
        return result;
    }

    private native void nativeGatherThreads(long processHandle, AppendableSequence<TeleNativeThread> threads, long threadSpecificsList);

    @Override
    protected void gatherThreads(AppendableSequence<TeleNativeThread> threads) {
        final Word threadSpecificsList = dataAccess().readWord(teleVM().bootImageStart().plus(teleVM().bootImage().header().threadSpecificsListOffset));
        assert !threadSpecificsList.isZero();
        nativeGatherThreads(processHandle, threads, threadSpecificsList.asAddress().toLong());
    }

    @Override
    protected TeleNativeThread createTeleNativeThread(int id, long lwpId, long stackBase, long stackSize) {
        return new SolarisTeleNativeThread(this, id, lwpId, stackBase, stackSize);
    }

    /**
     * Copies bytes from the tele process into a given {@linkplain ByteBuffer#isDirect() direct ByteBuffer} or byte
     * array.
     *
     * @param src the address in the tele process to copy from
     * @param dst the destination of the copy operation. This is a direct {@link ByteBuffer} or {@code byte[]}
     *            depending on the value of {@code isDirectByteBuffer}
     * @param isDirectByteBuffer
     * @param dstOffset the offset in {@code dst} at which to start writing
     * @param length the number of bytes to copy
     * @return the number of bytes copied or -1 if there was an error
     */
    private static native int nativeReadBytes(long processHandle, long src, Object dst, boolean isDirectByteBuffer, int offset, int length);

    @Override
    protected int read0(Address src, ByteBuffer dst, int offset, int length) {
        assert dst.limit() - offset >= length;
        if (dst.isDirect()) {
            return nativeReadBytes(processHandle, src.toLong(), dst, true, offset, length);
        }
        assert dst.array() != null;
        return nativeReadBytes(processHandle, src.toLong(), dst.array(), false, dst.arrayOffset() + offset, length);
    }

    /**
     * Copies bytes from a given {@linkplain ByteBuffer#isDirect() direct ByteBuffer} or byte array into the tele process.
     *
     * @param dst the address in the tele process to copy to
     * @param src the source of the copy operation. This is a direct {@link ByteBuffer} or {@code byte[]}
     *            depending on the value of {@code isDirectByteBuffer}
     * @param isDirectByteBuffer
     * @param srcOffset the offset in {@code src} at which to start reading
     * @param length the number of bytes to copy
     * @return the number of bytes copied or -1 if there was an error
     */
    private static native int nativeWriteBytes(long processHandle, long dst, Object src, boolean isDirectByteBuffer, int offset, int length);

    @Override
    protected int write0(ByteBuffer src, int offset, int length, Address dst) {
        assert src.limit() - offset >= length;
        if (src.isDirect()) {
            return nativeWriteBytes(processHandle, dst.toLong(), src, true, offset, length);
        }
        assert src.array() != null;
        return nativeWriteBytes(processHandle, dst.toLong(), src.array(), false, src.arrayOffset() + offset, length);
    }

    private native boolean nativeActivateWatchpoint(long processHandle, long start, long size);
    private native boolean nativeDeactivateWatchpoint(long processHandle, long start, long size);

    @Override
    public int maximumWatchpointCount() {
        // not sure how many are supported; we'll try this
        return Integer.MAX_VALUE;
    }

    @Override
    protected boolean activateWatchpoint(MemoryRegion memoryRegion) {
        return nativeActivateWatchpoint(processHandle, memoryRegion.start().toLong(), memoryRegion.size().toLong());
    }

    @Override
    protected boolean deactivateWatchpoint(MemoryRegion memoryRegion) {
        return nativeDeactivateWatchpoint(processHandle, memoryRegion.start().toLong(), memoryRegion.size().toLong());
    }
}
