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
package com.sun.max.vm.jdk;

import static com.sun.cri.bytecode.Bytecodes.*;

import java.security.*;
import java.util.*;

import com.sun.cri.bytecode.*;
import com.sun.max.annotate.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.bytecode.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.stack.*;
import com.sun.max.vm.stack.StackFrameWalker.Cursor;
import com.sun.max.vm.thread.*;

/**
 * Method substitutions for the {@link java.security.AccessController} class.
 * We leverage some common code in the substitutions for {@link sun.reflect.Reflection}.
 */
@METHOD_SUBSTITUTIONS(AccessController.class)
final class JDK_java_security_AccessController {

    private JDK_java_security_AccessController() {
    }

    /**
     * Performs a privileged action.
     *
     * @see java.security.AccessController#doPrivileged(PrivilegedAction)
     * @param <T> the type of the result of the privileged action
     * @param action the action to perform
     * @return the result of performing the action
     */
    @SUBSTITUTE
    public static <T> T doPrivileged(PrivilegedAction<T> action) {
        final JDK_sun_reflect_Reflection.Context context =  JDK_sun_reflect_Reflection.getCallerContext(1);
        final VmThread current = VmThread.current();
        current.pushPrivilegedElement(context.methodActorResult.holder(), context.framePointerResult, null);
        try {
            return action.run();
        } finally {
            current.popPrivilegedElement();
        }
    }

    /**
     * Performs a privileged action.
     *
     * @see java.security.AccessController#doPrivileged(PrivilegedAction, AccessControlContext)
     * @param <T> the type of the result of the privileged action
     * @param action the action to perform
     * @param context the access control context
     * @return the result of performing the action
     */
    @SUBSTITUTE
    public static <T> T doPrivileged(PrivilegedAction<T> action, AccessControlContext accessControlContext) {
        final JDK_sun_reflect_Reflection.Context context =  JDK_sun_reflect_Reflection.getCallerContext(1);
        final VmThread current = VmThread.current();
        current.pushPrivilegedElement(context.methodActorResult.holder(), context.framePointerResult, accessControlContext);
        try {
            return action.run();
        } finally {
            current.popPrivilegedElement();
        }
    }

    /**
     * Performs a privileged action that may generate an exception.
     *
     * @see java.security.AccessController#doPrivileged(PrivilegedExceptionAction, AccessControlContext)
     * @param <T> the type of the result of the privileged action
     * @param action the action to perform
     * @return the result of performing the action
     * @throws PrivilegedActionException if the privileged action caused an exception
     */
    @SUBSTITUTE
    public static <T> T doPrivileged(PrivilegedExceptionAction<T> action) throws PrivilegedActionException {
        final JDK_sun_reflect_Reflection.Context context =  JDK_sun_reflect_Reflection.getCallerContext(1);
        final VmThread current = VmThread.current();
        current.pushPrivilegedElement(context.methodActorResult.holder(), context.framePointerResult, null);
        try {
            return action.run();
        } catch (Exception exception) {
            throw new PrivilegedActionException(exception);
        } finally {
            current.popPrivilegedElement();
        }
    }

    /**
     * Performs a privileged action that may generate an exception.
     *
     * @see java.security.AccessController#doPrivileged(PrivilegedExceptionAction, AccessControlContext)
     * @param <T> the type of the result of the privileged action
     * @param action the action to perform
     * @param context the access control context
     * @return the result of performing the action
     * @throws PrivilegedActionException if the privileged action caused an exception
     */
    @SUBSTITUTE
    public static <T> T doPrivileged(PrivilegedExceptionAction<T> action, AccessControlContext accessControlContext) throws PrivilegedActionException {
        final JDK_sun_reflect_Reflection.Context context =  JDK_sun_reflect_Reflection.getCallerContext(1);
        final VmThread current = VmThread.current();
        current.pushPrivilegedElement(context.methodActorResult.holder(), context.framePointerResult, accessControlContext);
        try {
            return action.run();
        } catch (Exception exception) {
            throw new PrivilegedActionException(exception);
        } finally {
            current.popPrivilegedElement();
        }
    }

   /**
     * This class implements a closure that analyzes protection domains on the call stack. It is based on the HotSpot
     * implementation.
     */
    private static class Context extends RawStackFrameVisitor {

        final List<ProtectionDomain> protectionDomains = new LinkedList<ProtectionDomain>();
        final VmThread.PrivilegedElement privilegedElement = VmThread.current().getTopPrivilegedElement();
        AccessControlContext privilegedContext;
        ProtectionDomain prevProtectionDomain;
        boolean isPrivileged;

        Context() {
        }

        @Override
        public boolean visitFrame(Cursor current, Cursor callee) {
            if (current.isTopFrame()) {
                // skip caller, i.e., 'getAccessControlContext()'
                return true;
            }
            TargetMethod targetMethod = current.targetMethod();
            if (targetMethod == null || targetMethod instanceof Adapter) {
                // native or adapter frame
                return true;
            }

            isPrivileged = false;
            ProtectionDomain protectionDomain = null;
            // check the privileged frames for a match
            if (privilegedElement != null && privilegedElement.frameId == current.fp()) {
                isPrivileged = true;
                privilegedContext = privilegedElement.context;
                protectionDomain = privilegedElement.classActor.protectionDomain();
            } else {
                BytecodeLocation bytecodeLocation = targetMethod.getBytecodeLocationFor(current.ip(), false);
                if (bytecodeLocation == null) {
                    protectionDomain = targetMethod.classMethodActor().holder().protectionDomain();
                } else {
                    while (bytecodeLocation != null) {
                        final MethodActor classMethodActor = bytecodeLocation.classMethodActor;
                        if (classMethodActor.isApplicationVisible()) {
                            protectionDomain = bytecodeLocation.classMethodActor.holder().protectionDomain();
                        }
                        bytecodeLocation = bytecodeLocation.parent();
                    }
                }
            }

            if (prevProtectionDomain != protectionDomain && protectionDomain != null) {
                protectionDomains.add(protectionDomain);
                prevProtectionDomain = protectionDomain;
            }

            // terminate if we found a privileged domain
            return !isPrivileged;
        }
    }

    /**
     * Gets the sequence of protection domains for all the frames on the stack.
     *
     * @return a sequence of protection domains for the current stack
     */
    @NEVER_INLINE
    private static Context getContext() {
        final Context context = new Context();
        new VmStackFrameWalker(VmThread.current().tla()).inspect(VMRegister.getInstructionPointer(),
                                                       VMRegister.getCpuStackPointer(),
                                                       VMRegister.getCpuFramePointer(),
                                                       context);
        return context;
    }

    @ALIAS(declaringClass = AccessControlContext.class, name = "<init>")
    private native void init(ProtectionDomain[] context, boolean isPrivileged);

    @INTRINSIC(UNSAFE_CAST)
    static native JDK_java_security_AccessController asThis(AccessControlContext acc);

    @ALIAS(declaringClass = AccessControlContext.class)
    AccessControlContext privilegedContext;

    /**
     * Gets the access control context for the current stack.
     *
     * @see java.security.AccessController#getStackAccessControlContext()
     * @return the access control context for the current stack
     */
    @SUBSTITUTE
    public static AccessControlContext getStackAccessControlContext() {
        final Context context = getContext();
        ProtectionDomain[] protectionDomains = null;
        if (context.protectionDomains.isEmpty()) {
            if (context.isPrivileged && context.privilegedContext == null) {
                return null;
            }
        } else {
            protectionDomains = context.protectionDomains.toArray(new ProtectionDomain[context.protectionDomains.size()]);
        }

        AccessControlContext acc = (AccessControlContext) Heap.createTuple(ClassActor.fromJava(AccessControlContext.class).dynamicHub());
        JDK_java_security_AccessController thisAccessControlContext = asThis(acc);
        thisAccessControlContext.init(protectionDomains, context.isPrivileged);
        if (context.isPrivileged) {
            // need to manually set privilegedContext as no constructor for that
            thisAccessControlContext.privilegedContext = context.privilegedContext;
        }
        return acc;
    }

    /**
     * Gets the inherited access control context.
     *
     * @see java.security.AccessController#getInheritedAccessControlContext()
     * @return the inherited access control context
     */
    @SUBSTITUTE
    public static AccessControlContext getInheritedAccessControlContext() {
        JDK_java_lang_Thread thread = JDK_java_lang_Thread.asThis(Thread.currentThread());
        return thread.inheritedAccessControlContext;
    }
}
