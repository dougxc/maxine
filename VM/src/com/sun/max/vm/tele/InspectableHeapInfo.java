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
package com.sun.max.vm.tele;

import com.sun.max.annotate.*;
import com.sun.max.lang.*;
import com.sun.max.memory.*;
import com.sun.max.unsafe.*;

/**
 * Makes critical state information about the object heap
 * remotely inspectable.
 * Active only when VM is being inspected.
 *
 * @author Bernd Mathiske
 * @author Michael Van De Vanter
 */
public final class InspectableHeapInfo {

    private InspectableHeapInfo() {
    }

    @INSPECTED
    private static MemoryRegion[] memoryRegions;

    public static final int MAX_NUMBER_OF_ROOTS = Ints.M / 8;

    @INSPECTED
    public static MemoryRegion rootsRegion = null;

    @INSPECTED
    public static Pointer rootsPointer = Pointer.zero();

    @INSPECTED
    private static long rootEpoch;

    @INSPECTED
    private static long collectionEpoch;

    /**
     * How many words of the heap refer to one Word in the card table.
     */
    @INSPECTED
    private static int cardTableRatio = 100;

    @INSPECTED
    private static int totalCardTableEntries = 0;

    @INSPECTED
    private static Pointer cardTablePointer = Pointer.zero();

    @INSPECTED
    public static Address oldAddress;

    @INSPECTED
    public static Address newAddress;

    /**
     * Stores memory allocated by the heap in an location that can
     * be inspected easily.
     * <br>
     * No-op when VM is not being inspected.
     *
     * @param memoryRegions regions allocated by the heap implementation
     */
    public static void init(MemoryRegion... memoryRegions) {
        if (MaxineMessenger.isVmInspected()) {
            InspectableHeapInfo.memoryRegions = memoryRegions;
            initRootsRegion();
            initCardTable(memoryRegions);
        }
    }


    private static void initRootsRegion() {
        if (rootsRegion == null) {
            final Size size = Size.fromInt(Pointer.size() * MAX_NUMBER_OF_ROOTS);
            rootsPointer = Memory.allocate(size);
            rootsRegion = new RootsMemoryRegion(rootsPointer, size); // TODO:no new calls in this class
        }
    }

    private static void initCardTable(MemoryRegion[] memoryRegions) {
        if (cardTablePointer.equals(Pointer.zero())) {
            for (MemoryRegion memoryRegion : memoryRegions) {
                totalCardTableEntries += calculateNumberOfCardTableEntries(memoryRegion);
            }
            cardTablePointer = Memory.allocate(Size.fromInt(totalCardTableEntries * Word.size()));
        }
    }

    private static int calculateNumberOfCardTableEntries(MemoryRegion memoryRegion) {
        int nrOfWords = memoryRegion.size().toInt() / Word.size();
        int cardTableEntries = nrOfWords / cardTableRatio;
        if (nrOfWords % cardTableRatio != 0) {
            cardTableEntries++;
        }
        return cardTableEntries;
    }

    public static Word touchCardTableField(Address address) {
        return touchCardTableField(address, memoryRegions);
    }

    public static Word touchCardTableField(Address address, MemoryRegion... memoryRegions) {
        int index;
        index = getCardTableIndex(address, memoryRegions);

        if (index != -1) {
            return cardTablePointer.getWord(index);
        }
        return Word.zero();
    }

    public static int getCardTableIndex(Address address, MemoryRegion... memoryRegions) {
        int cardTableEntry = 0;
        for (MemoryRegion memoryRegion : memoryRegions) {
            if (memoryRegion.contains(address)) {
                final int offset = address.minus(memoryRegion.start()).toInt() / Word.size();
                return cardTableEntry + calculateIndexInMemoryRegion(offset);
            }
            cardTableEntry += calculateNumberOfCardTableEntries(memoryRegion);
        }
        return -1;
    }

    private static int calculateIndexInMemoryRegion(int offset) {
        int index = offset / cardTableRatio;
        if (offset % cardTableRatio != 0) {
            index++;
        }
        return index;
    }

    private static class RootsMemoryRegion extends RuntimeMemoryRegion {
        public RootsMemoryRegion(Address address, Size size) {
            super(address, size);
            setDescription("TeleRoots");
            mark.set(end());
        }
    }

    /**
     * Access to the special region containing remote root pointers.
     * This is equivalent to the start address of {@link #rootsRegion}, but it must be
     * inspectable at a lower level before object-valued fields can be inspected.
     *
     * @return base of the specially allocated memory region containing inspectable root pointers
     */
    public static Pointer rootsPointer() {
        return rootsPointer;
    }

    /**
     * For remote inspection:  records that a GC has begun.
     */
    public static void beforeGarbageCollection() {
        collectionEpoch++;
    }

    /**
     * For remote inspection:  records that a GC has concluded.
     */
    public static void afterGarbageCollection() {
        rootEpoch = collectionEpoch;
    }
}
