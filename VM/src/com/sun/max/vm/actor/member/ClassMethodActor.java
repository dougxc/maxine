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
package com.sun.max.vm.actor.member;

import static com.sun.max.vm.VMOptions.*;
import static com.sun.max.vm.bytecode.Bytecode.Flags.*;

import java.lang.reflect.*;

import com.sun.max.annotate.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.bytecode.*;
import com.sun.max.vm.bytecode.graft.*;
import com.sun.max.vm.classfile.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.prototype.*;
import com.sun.max.vm.type.*;
import com.sun.max.vm.verifier.*;
import com.sun.org.apache.bcel.internal.generic.*;

/**
 * Non-interface methods.
 *
 * @author Bernd Mathiske
 * @author Hiroshi Yamauchi
 * @author Doug Simon
 */
public abstract class ClassMethodActor extends MethodActor {

    private static boolean traceJNI;

    static {
        register(new VMBooleanXXOption("-XX:-TraceJNI", "Trace JNI calls.") {
            @Override
            public boolean parseValue(Pointer optionValue) {
                traceJNI = getValue();
                return true;
            }
        }, MaxineVM.Phase.STARTING);
    }

    @INSPECTED
    private CodeAttribute codeAttribute;

    @INSPECTED
    public volatile Object targetState;

    private CodeAttribute originalCodeAttribute;

    /**
     * This is the method whose code is actually compiled/executed. In most cases, it will be
     * equal to this object, unless this method has a {@linkplain SUBSTITUTE substitute}.
     *
     * This field is declared volatile so that the double-checking locking idiom used in
     * {@link #compilee()} works as expected. This correctness is guaranteed as long as the
     * compiler follows all the rules of the Java Memory Model as of JDK5 (JSR-133).
     */
    private volatile ClassMethodActor compilee;

    /**
     * The object representing the linkage of this native method actor to a native machine code address.
     * This value is {@code null} if this method is not {@linkplain #isNative() native}
     */
    public final NativeFunction nativeFunction;

    public ClassMethodActor(Utf8Constant name, SignatureDescriptor descriptor, int flags, CodeAttribute codeAttribute) {
        super(name, descriptor, flags);
        this.originalCodeAttribute = codeAttribute;
        this.nativeFunction = isNative() ? new NativeFunction(this) : null;
    }

    /**
     * @return number of locals used by the method parameters (including the receiver if this method isn't static).
     */
    public int numberOfParameterSlots() {
        return descriptor().computeNumberOfSlots() + ((isStatic()) ? 0 : 1);
    }

    /**
     * Determines if JNI activity should be traced at a level useful for debugging.
     * @return {@code true} if JNI should be traced
     */
    @INLINE
    public static boolean traceJNI() {
        return traceJNI;
    }

    public boolean isDeclaredNeverInline() {
        return compilee().isNeverInline();
    }

    public boolean isDeclaredInline(BootstrapCompilerScheme compilerScheme) {
        if (compilee().isInline()) {
            if (MaxineVM.isHosted()) {
                if (compilee().isInlineAfterSnippetsAreCompiled()) {
                    return compilerScheme.areSnippetsCompiled();
                }
            }
            return true;
        }
        return false;
    }

    @INLINE
    public final boolean noSafepoints() {
        return noSafepoints(compilee().flags());
    }

    @INLINE
    public final boolean isDeclaredFoldable() {
        return isDeclaredFoldable(compilee().flags());
    }

    /**
     * Gets the CodeAttribute currently associated with this actor, irrespective of whether or not it will be replaced
     * later as a result of substitution or preprocessing.
     *
     * Note: This method must not be synchronized as it is called by a GC thread during stack walking.
     * @return the raw code attribute
     */
    public final CodeAttribute originalCodeAttribute() {
        return originalCodeAttribute;
    }

    /**
     * Gets the bytecode that is to be compiled and/or executed for this actor.
     * @return the code attribute
     */
    public final CodeAttribute codeAttribute() {
        // Ensure that any prerequisite substitution and/or preprocessing of the code to be
        // compiled/executed is performed first
        compilee();

        return codeAttribute;
    }

    /**
     * @return the actor for the method that will be compiled and/or executed in lieu of this method
     */
    public final ClassMethodActor compilee() {
        if (this.compilee == null) {
            synchronized (this) {
                if (compilee != null) {
                    return compilee;
                }
                ClassMethodActor compilee = this;
                CodeAttribute codeAttribute = this.originalCodeAttribute;

                if (!isHiddenToReflection()) {
                    final ClassMethodActor substitute = METHOD_SUBSTITUTIONS.Static.findSubstituteFor(this);
                    if (substitute != null) {
                        compilee = substitute;
                        codeAttribute = substitute.originalCodeAttribute;
                    }
                    if (MaxineVM.isHosted()) {
                        validateInlineAnnotation(compilee);
                    }
                }

                ClassVerifier verifier = null;

                final CodeAttribute processedCodeAttribute = Preprocessor.apply(compilee, codeAttribute);
                final boolean modified = processedCodeAttribute != codeAttribute;
                codeAttribute = processedCodeAttribute;

                final ClassActor holder = compilee.holder();
                if (MaxineVM.isHosted()) {
                    if (holder.kind != Kind.WORD) {
                        // We simply verify all methods during boot image build time as the overhead should be acceptable.
                        verifier = modified ? new TypeInferencingVerifier(holder) : Verifier.verifierFor(holder);
                    }
                } else {
                    if (holder().majorVersion < 50) {
                        // The compiler/JIT/interpreter cannot handle JSR or RET instructions. However, these instructions
                        // can legally appear in class files whose version number is less than 50.0. So, we inline them
                        // with the type inferencing verifier if they appear in the bytecode of a pre-version-50.0 class file.
                        if (codeAttribute != null && containsSubroutines(codeAttribute.code())) {
                            verifier = new TypeInferencingVerifier(holder);
                        }
                    } else {
                        if (modified) {
                            // The methods in class files whose version is greater than or equal to 50.0 are required to
                            // have stack maps. If the bytecode of such a method has been preprocessed, then its
                            // pre-existing stack maps will have been invalidated and must be regenerated with the
                            // type inferencing verifier
                            verifier = new TypeInferencingVerifier(holder);
                        }
                    }
                }

                if (verifier != null && codeAttribute != null && !compilee.holder().isGenerated()) {
                    if (MaxineVM.isHosted()) {
                        try {
                            codeAttribute = verifier.verify(compilee, codeAttribute);
                        } catch (OmittedClassError e) {
                            // Ignore: assume all classes being loaded during boot imaging are verifiable.
                        }
                    } else {
                        codeAttribute = verifier.verify(compilee, codeAttribute);
                    }
                }
                this.codeAttribute = codeAttribute;
                this.compilee = compilee;
            }
        }
        return compilee;
    }

    /**
     * Gets a {@link StackTraceElement} object describing the source code location corresponding to a given bytecode
     * position in this method.
     *
     * @param bytecodePosition a bytecode position in this method's {@linkplain #codeAttribute() code}
     * @return the stack trace element
     */
    public StackTraceElement toStackTraceElement(int bytecodePosition) {
        final ClassActor holder = holder();
        return new StackTraceElement(holder.name.string, name.string, holder.sourceFileName, sourceLineNumber(bytecodePosition));
    }

    /**
     * Gets the source line number corresponding to a given bytecode position in this method.
     * @param bytecodePosition the byte code position
     * @return -1 if a source line number is not available
     */
    public int sourceLineNumber(int bytecodePosition) {
        return codeAttribute().lineNumberTable().findLineNumber(bytecodePosition);
    }

    /**
     * Determines if a given bytecode sequence contains either of the instructions ({@link JSR} or {@link RET}) used
     * to implement bytecode subroutines.
     * @param code the byte array of code
     * @return {@link true} if the code contains subroutines
     */
    private static boolean containsSubroutines(byte[] code) {
        final BytecodeVisitor visitor = new BytecodeAdapter() {
            @Override
            protected void opcodeDecoded() {
                final Bytecode currentOpcode = currentOpcode();
                if (currentOpcode.is(JSR_OR_RET)) {
                    bytecodeScanner().stop();
                }
            }
        };
        final BytecodeScanner scanner = new BytecodeScanner(visitor);
        scanner.scan(new BytecodeBlock(code));
        return scanner.wasStopped();
    }

    /**
     * @see InliningAnnotationsValidator#apply(ClassMethodActor)
     */
    @HOSTED_ONLY
    private void validateInlineAnnotation(ClassMethodActor compilee) {
        if (!compilee.holder().isGenerated()) {
            try {
                InliningAnnotationsValidator.apply(compilee);
            } catch (LinkageError linkageError) {
                ProgramWarning.message("Error while validating INLINE annotation for " + compilee + ": " + linkageError);
            }
        }
    }

    public MethodActor original() {
        final MethodActor original = METHOD_SUBSTITUTIONS.Static.findOriginal(this);
        if (original != null) {
            return original;
        }
        return this;
    }

    public synchronized void verify(ClassVerifier classVerifier) {
        if (codeAttribute() != null) {
            codeAttribute = classVerifier.verify(this, codeAttribute);
        }
    }

    public static ClassMethodActor fromJava(Method javaMethod) {
        return (ClassMethodActor) MethodActor.fromJava(javaMethod);
    }

    public TargetMethod currentTargetMethod() {
        return TargetState.currentTargetMethod(targetState);
    }

    public TargetMethod[] targetMethodHistory() {
        return TargetState.targetMethodHistory(targetState);
    }

    public int targetMethodCount() {
        return TargetState.targetMethodCount(targetState);
    }
}
