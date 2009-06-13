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
package test.com.sun.max.vm.compiler.c1x;

import java.util.*;

import com.sun.c1x.*;
import com.sun.c1x.util.*;
import com.sun.max.collect.*;
import com.sun.max.lang.*;
import com.sun.max.program.*;
import com.sun.max.program.option.*;
import com.sun.max.test.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.compiler.c1x.*;
import com.sun.max.vm.prototype.*;
import com.sun.max.vm.type.*;

/**
 * A simple harness to run the C1X compiler and test it in various modes, without
 * needing JUnit.
 *
 * @author Ben L. Titzer
 */
public class C1XTest {

    private static final OptionSet _options = new OptionSet(true);

    private static final Option<Integer> _trace = _options.newIntegerOption("trace", 0,
        "Set the tracing level of the Maxine VM and runtime.");
    private static final Option<Integer> _verbose = _options.newIntegerOption("verbose", 1,
        "Set the verbosity level of the testing framework.");
    private static final Option<Boolean> _print = _options.newBooleanOption("print-bailout", false,
        "Print bailout exceptions.");
    private static final Option<Boolean> _clinit = _options.newBooleanOption("clinit", true,
        "Compile class initializer (<clinit>) methods");
    private static final Option<Boolean> _failFast = _options.newBooleanOption("fail-fast", true,
        "Stop compilation upon the first bailout.");
    private static final Option<Boolean> _timing = _options.newBooleanOption("timing", false,
        "Report compilation time for each successful compile.");
    private static final Option<Boolean> _average = _options.newBooleanOption("average", true,
        "Report only the average compilation speed.");
    private static final Option<Integer> _warmup = _options.newIntegerOption("warmup", 0,
        "Set the number of warmup runs to execute before initiating the timed run.");
    private static final Option<Boolean> _help = _options.newBooleanOption("help", false,
        "Show help message and exit.");

    private static final List<Timing> _timings = new ArrayList<Timing>();

    public static void main(String[] args) {
        _options.parseArguments(args);
        final String[] arguments = _options.getArguments();

        if (_help.getValue()) {
            _options.printHelp(System.out, 80);
            return;
        }

        Trace.on(_trace.getValue());

        // create the prototype
        new PrototypeGenerator(_options).createJavaPrototype(false);

        // create MaxineRuntime
        final MaxCiRuntime runtime = new MaxCiRuntime();
        final List<MethodActor> methods = findMethodsToCompile(arguments);
        final ProgressPrinter progress = new ProgressPrinter(System.out, methods.size(), _verbose.getValue(), false);

        for (int i = 0; i < _warmup.getValue(); i++) {
            for (MethodActor actor : methods) {
                compile(runtime, actor, false, true);
            }
        }

        for (MethodActor actor : methods) {
            progress.begin(actor.toString());
            final C1XCompilation compilation = compile(runtime, actor, _print.getValue(), false);
            if (compilation == null || compilation.startBlock() != null) {
                progress.pass();

                if (compilation != null && _verbose.getValue() >= 3) {
                    final InstructionPrinter ip = new InstructionPrinter(new C1XPrintStream(Trace.stream()), true);
                    final BlockPrinter bp = new BlockPrinter(ip, false, false);
                    compilation.startBlock().iteratePreOrder(bp);
                }

            } else {
                progress.fail("failed");
                if (_failFast.getValue()) {
                    System.out.println("");
                    break;
                }
            }
        }

        progress.report();
        reportTiming();
    }

    private static C1XCompilation compile(MaxCiRuntime runtime, MethodActor method, boolean printBailout, boolean warmup) {
        if (isCompilable(method)) {
            final long startNs = System.nanoTime();
            final C1XCompilation compilation = new C1XCompilation(runtime, runtime.getCiMethod(method));
            if (compilation.startBlock() == null) {
                if (printBailout) {
                    compilation.bailout().printStackTrace(System.out);
                }
            }
            if (!warmup) {
                // record the time for successful compilations
                recordTime(method, compilation.totalInstructions(), System.nanoTime() - startNs);
            }
            return compilation;
        }
        return null;
    }

    private static boolean isCompilable(MethodActor method) {
        return method instanceof ClassMethodActor && !method.isAbstract() && !method.isNative() && !method.isBuiltin() && !method.isUnsafeCast();
    }

    private static List<MethodActor> findMethodsToCompile(String[] arguments) {
        final Classpath classpath = Classpath.fromSystem();
        final List<MethodActor> methods = new ArrayList<MethodActor>();

        for (int i = 0; i != arguments.length; ++i) {
            final String argument = arguments[i];
            final int colonIndex = argument.indexOf(':');
            final String classNamePattern = colonIndex == -1 ? argument : argument.substring(0, colonIndex);

            // search for matching classes on the class path
            final AppendableSequence<String> matchingClasses = new ArrayListSequence<String>();
            new ClassSearch() {
                @Override
                protected boolean visitClass(String className) {
                    if (!className.endsWith("package-info")) {
                        if (className.contains(classNamePattern)) {
                            matchingClasses.append(className);
                        }
                    }
                    return true;
                }
            }.run(classpath);

            // for all found classes, search for matching methods
            for (String className : matchingClasses) {
                try {
                    final Class<?> javaClass = Class.forName(className, false, C1XTest.class.getClassLoader());
                    ClassActor classActor = getClassActorNonfatal(javaClass);
                    if (classActor == null) {
                        continue;
                    }

                    if (colonIndex == -1) {
                        // Class only: compile all methods in class
                        for (MethodActor actor : classActor.localStaticMethodActors()) {
                            if (_clinit.getValue() || actor != classActor.classInitializer()) {
                                methods.add(actor);
                            }
                        }
                        for (MethodActor actor : classActor.localVirtualMethodActors()) {
                            methods.add(actor);
                        }
                    } else {
                        // a method pattern was specified, find matching methods
                        final int parenIndex = argument.indexOf('(', colonIndex + 1);
                        final String methodNamePattern;
                        final SignatureDescriptor signature;
                        if (parenIndex == -1) {
                            methodNamePattern = argument.substring(colonIndex + 1);
                            signature = null;
                        } else {
                            methodNamePattern = argument.substring(colonIndex + 1, parenIndex);
                            signature = SignatureDescriptor.create(argument.substring(parenIndex));
                        }
                        addMatchingMethods(methods, classActor, methodNamePattern, signature, classActor.localStaticMethodActors());
                        addMatchingMethods(methods, classActor, methodNamePattern, signature, classActor.localVirtualMethodActors());
                    }
                } catch (ClassNotFoundException classNotFoundException) {
                    ProgramWarning.message(classNotFoundException.toString());
                }
            }
        }
        return methods;
    }

    private static ClassActor getClassActorNonfatal(Class<?> javaClass) {
        ClassActor classActor = null;
        try {
            classActor = ClassActor.fromJava(javaClass);
        } catch (Throwable t) {
            // do nothing.
        }
        return classActor;
    }

    private static void addMatchingMethods(final List<MethodActor> methods, final ClassActor classActor, final String methodNamePattern, final SignatureDescriptor signature, MethodActor[] methodActors) {
        for (final MethodActor method : methodActors) {
            if (method.name().toString().contains(methodNamePattern)) {
                final SignatureDescriptor methodSignature = method.descriptor();
                if (signature == null || signature.equals(methodSignature)) {
                    methods.add(method);
                }
            }
        }
    }

    private static void recordTime(MethodActor method, int instructions, long ns) {
        if (_timing.getValue()) {
            _timings.add(new Timing((ClassMethodActor) method, instructions, ns));
        }
    }

    private static void reportTiming() {
        if (_timing.getValue()) {
            double totalBcps = 0d;
            double totalIps = 0d;
            int count = 0;
            for (Timing timing : _timings) {
                final MethodActor method = timing._classMethodActor;
                final long ns = timing._nanoSeconds;
                final double bcps = timing.bytecodesPerSecond();
                final double ips = timing.instructionsPerSecond();
                if (!_average.getValue()) {
                    System.out.print(Strings.padLengthWithSpaces("#" + timing._number, 6));
                    System.out.print(Strings.padLengthWithSpaces(method.toString(), 80) + ": ");
                    System.out.print(Strings.padLengthWithSpaces(13, ns + " ns "));
                    System.out.print(Strings.padLengthWithSpaces(18, Strings.fixedDouble(bcps, 2) + " bytes/s"));
                    System.out.print(Strings.padLengthWithSpaces(18, Strings.fixedDouble(ips, 2) + " insts/s"));
                    System.out.println();
                }
                totalBcps += bcps;
                totalIps += ips;
                count++;
            }
            System.out.print("Average: ");
            System.out.print(Strings.fixedDouble(totalBcps / count, 2) + " bytes/s   ");
            System.out.print(Strings.fixedDouble(totalIps / count, 2) + " insts/s");
            System.out.println();
        }
    }

    private static class Timing {
        private final int _number;
        private final ClassMethodActor _classMethodActor;
        private final int _instructions;
        private final long _nanoSeconds;

        Timing(ClassMethodActor classMethodActor, int instructions, long ns) {
            _number = _timings.size();
            _classMethodActor = classMethodActor;
            _instructions = instructions;
            _nanoSeconds = ns;
        }

        public double bytecodesPerSecond() {
            return 1000000000 * (_classMethodActor.rawCodeAttribute().code().length / (double) _nanoSeconds);
        }

        public double instructionsPerSecond() {
            return 1000000000 * (_instructions / (double) _nanoSeconds);
        }
    }
}
