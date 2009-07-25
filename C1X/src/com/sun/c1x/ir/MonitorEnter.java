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
package com.sun.c1x.ir;

import com.sun.c1x.value.ValueStack;

/**
 * The <code>MonitorEnter</code> instruction represents the acquisition of a monitor.
 *
 * @author Ben L. Titzer
 */
public class MonitorEnter extends AccessMonitor {

    final ValueStack lockStackBefore;

    /**
     * Creates a new MonitorEnter instruction.
     * @param object the instruction producing the object
     * @param lockNumber the number of the lock
     * @param lockStackBefore the state before
     */
    public MonitorEnter(Instruction object, int lockNumber, ValueStack lockStackBefore) {
        super(object, lockNumber);
        this.lockStackBefore = lockStackBefore;
        setNeedsNullCheck(!object.isNonNull());
    }

    /**
     * Gets the lock stack before this instruction.
     * @return the lock stack
     */
    public ValueStack lockStackBefore() {
        return lockStackBefore;
    }

    /**
     * Checks whether this instruction can trap.
     * @return <code>true</code>, conservatively assuming the instruction may cause an exception
     */
    @Override
    public boolean canTrap() {
        return true;
    }

    /**
     * Iterates over all the state values in this instruction.
     * @param closure the closure to apply
     */
    @Override
    public void stateValuesDo(InstructionClosure closure) {
        super.stateValuesDo(closure);
        lockStackBefore.valuesDo(closure);
    }

    /**
     * Implements this instruction's half of the visitor pattern.
     * @param v the visitor to accept
     */
    @Override
    public void accept(InstructionVisitor v) {
        v.visitMonitorEnter(this);
    }
}
