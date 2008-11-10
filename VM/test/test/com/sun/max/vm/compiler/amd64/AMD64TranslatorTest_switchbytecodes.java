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
package test.com.sun.max.vm.compiler.amd64;

import java.io.*;

import junit.framework.*;
import test.com.sun.max.vm.compiler.*;
import test.com.sun.max.vm.compiler.bytecode.*;

import com.sun.max.asm.*;
import com.sun.max.asm.dis.amd64.*;
import com.sun.max.io.*;
import com.sun.max.program.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.type.*;

/**
 * @author David Liu
 */
@org.junit.runner.RunWith(org.junit.runners.AllTests.class)
public class AMD64TranslatorTest_switchbytecodes extends CompilerTestCase<TargetMethod> {

    public static void main(String[] args) {
        junit.textui.TestRunner.run(AMD64TranslatorTest_switchbytecodes.suite());
    }

    public static Test suite() {
        final TestSuite suite = new TestSuite(AMD64TranslatorTest_switchbytecodes.class.getSimpleName());
        // $JUnit-BEGIN$
        suite.addTestSuite(AMD64TranslatorTest_switchbytecodes.class);
        // $JUnit-END$
        return new AMD64TranslatorTestSetup(suite); // This performs the test
    }

    public AMD64TranslatorTest_switchbytecodes(String name) {
        super(name);
    }

    private static int arrayCopyBackward(int length) {
        switch (length) {
            case -1:
                System.out.println("Foobar -1!");
                break;
            case 0:
                System.out.println("Foobar 0!");
                break;
            case 1:
                System.out.println("Foobar 1!");
                break;
            case 2:
                System.out.println("Foobar 2!");
                break;
            case 3:
                System.out.println("Foobar 3!");
                break;
            case 5:
                System.out.println("Foobar 5!");
                break;
            default:
                System.out.println("Default!");
                break;
        }

        switch (length) {
            case -1:
                System.out.println("Foobar -1!");
                break;
            case 0:
                System.out.println("Foobar 0!");
                break;
            case 1:
                System.out.println("Foobar 1!");
                break;
            case 2:
                System.out.println("Foobar 2!");
                break;
            case 3:
                System.out.println("Foobar 3!");
                break;
            case 5:
                System.out.println("Foobar 5!");
                break;
            default:
                System.out.println("Default!");
                break;
        }

        int j = 0;
        switch (length) {
            case 0: {
                for (int i = length - 1; i >= 0; i--) {
                    j = j + 1;
                }
                break;
            }
            case 1: {
                for (int i = length - 1; i >= 0; i--) {
                    j = j + 1;
                }
                break;
            }
            case 2: {
                for (int i = length - 1; i >= 0; i--) {
                    j = j + 1;
                }
                break;
            }
            case 3: {
                for (int i = length - 1; i >= 0; i--) {
                    j = j + 1;
                }
                break;
            }
            case 4: {
                for (int i = length - 1; i >= 0; i--) {
                    j = j + 1;
                }
                break;
            }
            case 5: {
                for (int i = length - 1; i >= 0; i--) {
                    j = j + 1;
                }
                break;
            }
            default: {
                j = 23;
            }
        }
        return j;
    }

    public void test_arrayCopyBackward() throws ClassNotFoundException, IOException, AssemblyException {
        final TargetMethod targetMethod = compilerTestSetup().translate(getClassMethodActor("arrayCopyBackward", SignatureDescriptor.create(int.class, int.class)));
        final InlineDataDecoder inlineDataDecoder = InlineDataDecoder.createFrom(targetMethod.encodedInlineDataDescriptors());
        AMD64Disassembler disassembler = new AMD64Disassembler(0L, inlineDataDecoder);
        targetMethod.traceBundle(IndentWriter.traceStreamWriter());
        disassembler = new AMD64Disassembler(targetMethod.codeStart().toLong(), inlineDataDecoder);
        final BufferedInputStream stream = new BufferedInputStream(new ByteArrayInputStream(targetMethod.code()));
        Trace.line(1, "test_arrayCopyBackward:");
        disassembler.scanAndPrint(stream, Trace.stream());
    }

    private static int perform_tableswitch_1(int a) {
        int b = a;
        switch (b) {
            case 1:
                b = 10;
                break;
            case 2:
                b = 20;
                break;
            case 3:
                b = 30;
                break;
            case 4:
                b = 40;
                break;
            case 5:
                b = 50;
                break;
            case 6:
                b = 60;
                break;
            case 7:
                b = 70;
                break;
            case 8:
                b = 80;
                break;
            case 9:
                b = 90;
                break;
            default:
                b = -1;
                break;
        }
        return b;
    }

    private static int perform_lookupswitch_1(int a) {
        int b = a;
        switch (b) {
            case 0x1000000:
                b = 0x10;
                break;
            case 0x2000000:
                b = 0x20;
                break;
            case 0x3000000:
                b = 0x30;
                break;
            case 0x4000000:
                b = 0x40;
                break;
            case 0x5000000:
                b = 0x50;
                break;
            case 0x6000000:
                b = 0x60;
                break;
            case 0x7000000:
                b = 0x70;
                break;
            case 0x8000000:
                b = 0x80;
                break;
            case 0x9000000:
                b = 0x90;
                break;
            case 0xA000000:
                b = 0xA0;
                break;
            case 0xB000000:
                b = 0xB0;
                break;
            case 0xC000000:
                b = 0xC0;
                break;
            case 0xD000000:
                b = 0xD0;
                break;
            case 0xE000000:
                b = 0xE0;
                break;
            case 0xF000000:
                b = 0xF0;
                break;
            default:
                b = -1;
                break;
        }
        return b;
    }

    public void test_tableswitch_1() throws IOException, AssemblyException {
        final TargetMethod targetMethod = compilerTestSetup().translate(getClassMethodActor("perform_tableswitch_1", SignatureDescriptor.create(int.class, int.class)));
        final InlineDataDecoder inlineDataDecoder = InlineDataDecoder.createFrom(targetMethod.encodedInlineDataDescriptors());
        AMD64Disassembler disassembler = new AMD64Disassembler(0L, inlineDataDecoder);
        targetMethod.traceBundle(IndentWriter.traceStreamWriter());
        disassembler = new AMD64Disassembler(targetMethod.codeStart().toLong(), inlineDataDecoder);
        final BufferedInputStream stream = new BufferedInputStream(new ByteArrayInputStream(targetMethod.code()));
        Trace.line(1, "test_tableswitch_1:");
        disassembler.scanAndPrint(stream, Trace.stream());
    }

    public void test_lookupswitch_1() throws IOException, AssemblyException {
        final TargetMethod targetMethod = compilerTestSetup().translate(getClassMethodActor("perform_lookupswitch_1", SignatureDescriptor.create(int.class, int.class)));
        final InlineDataDecoder inlineDataDecoder = InlineDataDecoder.createFrom(targetMethod.encodedInlineDataDescriptors());
        AMD64Disassembler disassembler = new AMD64Disassembler(0L, inlineDataDecoder);
        targetMethod.traceBundle(IndentWriter.traceStreamWriter());
        disassembler = new AMD64Disassembler(targetMethod.codeStart().toLong(), inlineDataDecoder);
        final BufferedInputStream stream = new BufferedInputStream(new ByteArrayInputStream(targetMethod.code()));
        Trace.line(1, "test_lookupswitch_1:");
        disassembler.scanAndPrint(stream, Trace.stream());
    }

    private static int perform_lookupswitch_3(int a) {
        int b = a;
        switch (b) {
            case 'X':
                b = 10;
                break;
            case -1:
                b = 20;
                break;
            default:
                b = 30;
        }
        return b;
    }

    public void test_lookupswitch_3() throws IOException, AssemblyException {
        final TargetMethod targetMethod = compileMethod("perform_lookupswitch_3");
        final InlineDataDecoder inlineDataDecoder = InlineDataDecoder.createFrom(targetMethod.encodedInlineDataDescriptors());
        AMD64Disassembler disassembler = new AMD64Disassembler(0L, inlineDataDecoder);
        new BytecodeConfirmation(targetMethod.classMethodActor()) {

            @Override
            public void lookupswitch(int defaultOffset, int numberOfCases) {
                getBytecodeScanner().skipBytes(numberOfCases * 8);
                confirmPresence();
            }
        };
        targetMethod.traceBundle(IndentWriter.traceStreamWriter());
        disassembler = new AMD64Disassembler(targetMethod.codeStart().toLong(), inlineDataDecoder);
        final BufferedInputStream stream = new BufferedInputStream(new ByteArrayInputStream(targetMethod.code()));
        Trace.line(1, "perform_lookupswitch_3:");
        disassembler.scanAndPrint(stream, Trace.stream());
    }

}
