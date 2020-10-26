/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.ballerinalang.compiler.bir.codegen;

import org.ballerinalang.compiler.BLangCompilerException;
import org.ballerinalang.model.elements.PackageID;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.wso2.ballerinalang.compiler.bir.codegen.internal.AsyncDataCollector;
import org.wso2.ballerinalang.compiler.bir.codegen.internal.BIRVarToJVMIndexMap;
import org.wso2.ballerinalang.compiler.bir.codegen.internal.FunctionParamComparator;
import org.wso2.ballerinalang.compiler.bir.codegen.internal.JavaClass;
import org.wso2.ballerinalang.compiler.bir.codegen.internal.LabelGenerator;
import org.wso2.ballerinalang.compiler.bir.codegen.interop.ExternalMethodGen;
import org.wso2.ballerinalang.compiler.bir.codegen.interop.InteropMethodGen;
import org.wso2.ballerinalang.compiler.bir.codegen.interop.JType;
import org.wso2.ballerinalang.compiler.bir.codegen.interop.JTypeTags;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRBasicBlock;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRFunction;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRPackage;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRTypeDefinition;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRVariableDcl;
import org.wso2.ballerinalang.compiler.bir.model.BIRNonTerminator.TypeTest;
import org.wso2.ballerinalang.compiler.bir.model.BIROperand;
import org.wso2.ballerinalang.compiler.bir.model.BIRTerminator;
import org.wso2.ballerinalang.compiler.bir.model.BIRTerminator.Branch;
import org.wso2.ballerinalang.compiler.bir.model.BIRTerminator.Call;
import org.wso2.ballerinalang.compiler.bir.model.BIRTerminator.GOTO;
import org.wso2.ballerinalang.compiler.bir.model.BIRTerminator.Return;
import org.wso2.ballerinalang.compiler.bir.model.BirScope;
import org.wso2.ballerinalang.compiler.bir.model.InstructionKind;
import org.wso2.ballerinalang.compiler.bir.model.VarKind;
import org.wso2.ballerinalang.compiler.bir.model.VarScope;
import org.wso2.ballerinalang.compiler.semantics.model.SymbolTable;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.Symbols;
import org.wso2.ballerinalang.compiler.semantics.model.types.BInvokableType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BNilType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BServiceType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BUnionType;
import org.wso2.ballerinalang.compiler.util.Name;
import org.wso2.ballerinalang.compiler.util.TypeTags;
import org.wso2.ballerinalang.util.Flags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.ballerinalang.model.symbols.SymbolOrigin.VIRTUAL;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.Opcodes.AALOAD;
import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ANEWARRAY;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DCONST_0;
import static org.objectweb.asm.Opcodes.DLOAD;
import static org.objectweb.asm.Opcodes.DSTORE;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.DUP_X1;
import static org.objectweb.asm.Opcodes.FCONST_0;
import static org.objectweb.asm.Opcodes.FLOAD;
import static org.objectweb.asm.Opcodes.FSTORE;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IADD;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IFGT;
import static org.objectweb.asm.Opcodes.IFNULL;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.ISUB;
import static org.objectweb.asm.Opcodes.LCONST_0;
import static org.objectweb.asm.Opcodes.LLOAD;
import static org.objectweb.asm.Opcodes.LSTORE;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.SIPUSH;
import static org.objectweb.asm.Opcodes.V1_8;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.ANNOTATION_MAP_NAME;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.ANNOTATION_UTILS;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.ARRAY_VALUE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.BALLERINA;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.BUILT_IN_PACKAGE_NAME;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.B_OBJECT;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.CHANNEL_DETAILS;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.CREATE_TYPES_METHOD;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.CURRENT_MODULE_INIT;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.DECIMAL_VALUE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.ERROR_UTILS;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.ERROR_VALUE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.FUNCTION_POINTER;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.FUTURE_VALUE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.HANDLE_STOP_PANIC_METHOD;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.HANDLE_VALUE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.JVM_INIT_METHOD;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MAP_VALUE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_INIT;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_INIT_CLASS_NAME;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_START;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_STARTED;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_START_ATTEMPTED;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_STOP;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.OBJECT;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.PANIC_FIELD;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.RUNTIME_UTILS;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.SCHEDULER;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.SCHEDULER_START_METHOD;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.STRAND;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.STRAND_CLASS;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.STREAM_VALUE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.STRING_VALUE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.TABLE_VALUE_IMPL;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.THROWABLE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.TYPE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.TYPEDESC_VALUE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.VALUE_CREATOR;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.XML_VALUE;

/**
 * BIR function to JVM byte code generation class.
 *
 * @since 1.2.0
 */
public class JvmMethodGen {

    private static final String START_FUNCTION_SUFFIX = "<start>";
    private static final String STOP_FUNCTION_SUFFIX = "<stop>";

    public static final String STATE = "state";
    public static final String RESUME_INDEX = "resumeIndex";
    private int nextId = -1;
    private int nextVarId = -1;
    private final JvmPackageGen jvmPackageGen;
    private final SymbolTable symbolTable;
    private final BUnionType errorOrNilType;

    JvmMethodGen(JvmPackageGen jvmPackageGen) {
        this.jvmPackageGen = jvmPackageGen;
        this.symbolTable = jvmPackageGen.symbolTable;
        this.errorOrNilType = BUnionType.create(null, symbolTable.errorType, symbolTable.nilType);
    }

    public void genJMethodForBFunc(BIRFunction func, ClassWriter cw, BIRPackage module, String moduleClassName,
                                   BType attachedType, AsyncDataCollector asyncDataCollector) {

        BIRVarToJVMIndexMap indexMap = new BIRVarToJVMIndexMap();
        BIRVariableDcl strandVar = new BIRVariableDcl(symbolTable.stringType, new Name(STRAND), VarScope.FUNCTION,
                                                      VarKind.ARG);
        indexMap.addToMapIfNotFoundAndGetIndex(strandVar);

        // generate method desc
        int access = Opcodes.ACC_PUBLIC;
        int localVarOffset;
        if (attachedType != null) {
            localVarOffset = 1;
            // add the self as the first local var
            BIRVariableDcl selfVar = new BIRVariableDcl(symbolTable.anyType, new Name("self"), VarScope.FUNCTION,
                                                        VarKind.ARG);
            indexMap.addToMapIfNotFoundAndGetIndex(selfVar);
        } else {
            localVarOffset = 0;
            access += ACC_STATIC;
        }

        String funcName = JvmCodeGenUtil.cleanupFunctionName(func.name.value);
        BType retType = getReturnType(func);
        String desc = JvmCodeGenUtil.getMethodDesc(func.type.paramTypes, retType);
        MethodVisitor mv = cw.visitMethod(access, funcName, desc, null, null);
        mv.visitCode();

        visitModuleStartFunction(module, funcName, mv);

        Label methodStartLabel = new Label();
        mv.visitLabel(methodStartLabel);

        // set channel details to strand.
        setChannelDetailsToStrand(func, localVarOffset, mv);

        // panic if this strand is cancelled
        checkStrandCancelled(mv, localVarOffset);

        genLocalVars(indexMap, mv, func.localVars);

        int returnVarRefIndex = getReturnVarRefIndex(func, indexMap, retType, mv);
        int stateVarIndex = getStateVarIndex(indexMap, mv);

        mv.visitVarInsn(ALOAD, localVarOffset);
        mv.visitFieldInsn(GETFIELD, STRAND_CLASS, RESUME_INDEX, "I");

        LabelGenerator labelGen = new LabelGenerator();
        Label resumeLabel = labelGen.getLabel(funcName + "resume");
        mv.visitJumpInsn(IFGT, resumeLabel);

        Label varinitLabel = labelGen.getLabel(funcName + "varinit");
        mv.visitLabel(varinitLabel);

        // process basic blocks
        List<Label> labels = new ArrayList<>();
        List<Integer> states = new ArrayList<>();

        addCasesForBasicBlocks(func, funcName, labelGen, labels, states);

        JvmInstructionGen instGen = new JvmInstructionGen(mv, indexMap, module, jvmPackageGen);
        JvmErrorGen errorGen = new JvmErrorGen(mv, indexMap, instGen);
        JvmTerminatorGen termGen = new JvmTerminatorGen(mv, indexMap, labelGen, errorGen, module, instGen,
                                                        jvmPackageGen);

        mv.visitVarInsn(ILOAD, stateVarIndex);
        Label yieldLable = labelGen.getLabel(funcName + "yield");
        mv.visitLookupSwitchInsn(yieldLable, toIntArray(states), labels.toArray(new Label[0]));

        generateBasicBlocks(mv, labelGen, errorGen, instGen, termGen, func, returnVarRefIndex,
                            stateVarIndex, localVarOffset, module, attachedType,
                            moduleClassName, asyncDataCollector);
        mv.visitLabel(resumeLabel);
        String frameName = getFrameClassName(JvmCodeGenUtil.getPackageName(module), funcName, attachedType);
        genGetFrameOnResumeIndex(localVarOffset, mv, frameName);

        generateFrameClassFieldLoad(func.localVars, mv, indexMap, frameName);
        mv.visitFieldInsn(GETFIELD, frameName, STATE, "I");
        mv.visitVarInsn(ISTORE, stateVarIndex);
        mv.visitJumpInsn(GOTO, varinitLabel);

        mv.visitLabel(yieldLable);
        mv.visitTypeInsn(NEW, frameName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, frameName, JVM_INIT_METHOD, "()V", false);

        generateFrameClassFieldUpdate(func.localVars, mv, indexMap, frameName);

        mv.visitInsn(DUP);
        mv.visitVarInsn(ILOAD, stateVarIndex);
        mv.visitFieldInsn(PUTFIELD, frameName, STATE, "I");

        generateGetFrame(indexMap, localVarOffset, mv);

        Label methodEndLabel = new Label();
        mv.visitLabel(methodEndLabel);
        termGen.genReturnTerm(returnVarRefIndex, func);

        // Create Local Variable Table
        createLocalVariableTable(func, indexMap, localVarOffset, mv, methodStartLabel, labelGen, methodEndLabel);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void generateGetFrame(BIRVarToJVMIndexMap indexMap, int localVarOffset, MethodVisitor mv) {
        BIRVariableDcl frameVar = new BIRVariableDcl(symbolTable.stringType, new Name("frame"), null, VarKind.TEMP);
        int frameVarIndex = indexMap.addToMapIfNotFoundAndGetIndex(frameVar);
        mv.visitVarInsn(ASTORE, frameVarIndex);
        mv.visitVarInsn(ALOAD, localVarOffset);
        mv.visitFieldInsn(GETFIELD, STRAND_CLASS, JvmMethodGenUtils.FRAMES, "[Ljava/lang/Object;");
        mv.visitVarInsn(ALOAD, localVarOffset);
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETFIELD, STRAND_CLASS, RESUME_INDEX, "I");
        mv.visitInsn(DUP_X1);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitFieldInsn(PUTFIELD, STRAND_CLASS, RESUME_INDEX, "I");
        mv.visitVarInsn(ALOAD, frameVarIndex);
        mv.visitInsn(AASTORE);
    }

    private void createLocalVariableTable(BIRFunction func, BIRVarToJVMIndexMap indexMap, int localVarOffset,
                                          MethodVisitor mv, Label methodStartLabel, LabelGenerator labelGen,
                                          Label methodEndLabel) {
        String funcName = JvmCodeGenUtil.cleanupFunctionName(func.name.value);
        // Add strand variable to LVT
        mv.visitLocalVariable("__strand", String.format("L%s;", STRAND_CLASS), null, methodStartLabel, methodEndLabel,
                              localVarOffset);
        BIRBasicBlock endBB = func.basicBlocks.get(func.basicBlocks.size() - 1);
        for (int i = localVarOffset; i < func.localVars.size(); i++) {
            BIRVariableDcl localVar = func.localVars.get(i);
            Label startLabel = methodStartLabel;
            Label endLabel = methodEndLabel;
            if (!isLocalOrArg(localVar)) {
                continue;
            }
            // local vars have visible range information
            if (localVar.kind == VarKind.LOCAL) {
                if (localVar.startBB != null) {
                    startLabel = labelGen.getLabel(funcName + JvmCodeGenUtil.SCOPE_PREFIX + localVar.insScope.id);
                }
                if (localVar.endBB != null) {
                    endLabel = labelGen.getLabel(funcName + endBB.id.value + "beforeTerm");
                }
            }
            String metaVarName = localVar.metaVarName;
            if (isCompilerAddedVars(metaVarName)) {
                mv.visitLocalVariable(metaVarName, getJVMTypeSign(localVar.type), null,
                                      startLabel, endLabel, indexMap.addToMapIfNotFoundAndGetIndex(localVar));
            }
        }
    }

    private boolean isLocalOrArg(BIRVariableDcl localVar) {
        boolean synArg = localVar.type.tag == TypeTags.BOOLEAN && localVar.name.value.startsWith("%syn");
        return !synArg && (localVar.kind == VarKind.LOCAL || localVar.kind == VarKind.ARG);
    }

    private boolean isCompilerAddedVars(String metaVarName) {
        return metaVarName != null && !"".equals(metaVarName) &&
                // filter out compiler added vars
                !((metaVarName.startsWith("$") && metaVarName.endsWith("$"))
                        || (metaVarName.startsWith("$$") && metaVarName.endsWith("$$"))
                        || metaVarName.startsWith("_$$_"));
    }


    private void genGetFrameOnResumeIndex(int localVarOffset, MethodVisitor mv, String frameName) {
        mv.visitVarInsn(ALOAD, localVarOffset);
        mv.visitFieldInsn(GETFIELD, STRAND_CLASS, JvmMethodGenUtils.FRAMES, "[Ljava/lang/Object;");
        mv.visitVarInsn(ALOAD, localVarOffset);
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETFIELD, STRAND_CLASS, RESUME_INDEX, "I");
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitInsn(DUP_X1);
        mv.visitFieldInsn(PUTFIELD, STRAND_CLASS, RESUME_INDEX, "I");
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, frameName);
    }

    private void addCasesForBasicBlocks(BIRFunction func, String funcName, LabelGenerator labelGen, List<Label> lables,
                                        List<Integer> states) {
        int caseIndex = 0;
        for (int i = 0; i < func.basicBlocks.size(); i++) {
            BIRBasicBlock bb = func.basicBlocks.get(i);
            if (i == 0) {
                lables.add(caseIndex, labelGen.getLabel(funcName + bb.id.value));
                states.add(caseIndex, caseIndex);
                caseIndex += 1;
            }
            lables.add(caseIndex, labelGen.getLabel(funcName + bb.id.value + "beforeTerm"));
            states.add(caseIndex, caseIndex);
            caseIndex += 1;
        }
    }

    private int getStateVarIndex(BIRVarToJVMIndexMap indexMap, MethodVisitor mv) {
        BIRVariableDcl stateVar = new BIRVariableDcl(symbolTable.stringType, //should  be javaInt
                                                     new Name(STATE), null, VarKind.TEMP);
        int stateVarIndex = indexMap.addToMapIfNotFoundAndGetIndex(stateVar);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, stateVarIndex);
        return stateVarIndex;
    }

    private int getReturnVarRefIndex(BIRFunction func, BIRVarToJVMIndexMap indexMap, BType retType, MethodVisitor mv) {
        BIRVariableDcl varDcl = func.localVars.get(0);
        int returnVarRefIndex = indexMap.addToMapIfNotFoundAndGetIndex(varDcl);
        genDefaultValue(mv, retType, returnVarRefIndex);
        return returnVarRefIndex;
    }

    private void genLocalVars(BIRVarToJVMIndexMap indexMap, MethodVisitor mv, List<BIRVariableDcl> localVars) {
        localVars.sort(new FunctionParamComparator());
        for (int i = 1; i < localVars.size(); i++) {
            BIRVariableDcl localVar = localVars.get(i);
            int index = indexMap.addToMapIfNotFoundAndGetIndex(localVar);
            if (localVar.kind != VarKind.ARG) {
                BType bType = localVar.type;
                genDefaultValue(mv, bType, index);
            }
        }
    }

    private void setChannelDetailsToStrand(BIRFunction func, int localVarOffset, MethodVisitor mv) {
        // these channel info is required to notify datachannels, when there is a panic
        // we cannot set this during strand creation, because function call do not have this info.
        if (func.workerChannels.length <= 0) {
            return;
        }
        mv.visitVarInsn(ALOAD, localVarOffset);
        JvmCodeGenUtil.loadChannelDetails(mv, Arrays.asList(func.workerChannels));
        mv.visitMethodInsn(INVOKEVIRTUAL, STRAND_CLASS, "updateChannelDetails",
                           String.format("([L%s;)V", CHANNEL_DETAILS), false);
    }

    private void visitModuleStartFunction(BIRPackage module, String funcName, MethodVisitor mv) {
        if (!isModuleStartFunction(module, funcName)) {
            return;
        }
        mv.visitInsn(ICONST_1);
        mv.visitFieldInsn(PUTSTATIC, JvmCodeGenUtil.getModuleLevelClassName(module, MODULE_INIT_CLASS_NAME),
                          MODULE_START_ATTEMPTED, "Z");
    }

    private BType getReturnType(BIRFunction func) {
        BType retType = func.type.retType;
        if (JvmCodeGenUtil.isExternFunc(func) && Symbols.isFlagOn(retType.flags, Flags.PARAMETERIZED)) {
            retType = JvmCodeGenUtil.TYPE_BUILDER.build(func.type.retType);
        }
        return retType;
    }

    private void generateFrameClassFieldLoad(List<BIRVariableDcl> localVars, MethodVisitor mv,
                                             BIRVarToJVMIndexMap indexMap, String frameName) {
        for (BIRVariableDcl localVar : localVars) {
            int index = indexMap.addToMapIfNotFoundAndGetIndex(localVar);
            BType bType = localVar.type;
            mv.visitInsn(DUP);

            if (TypeTags.isIntegerTypeTag(bType.tag)) {
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"), "J");
                mv.visitVarInsn(LSTORE, index);
            } else if (TypeTags.isStringTypeTag(bType.tag)) {
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", JvmConstants.B_STRING_VALUE));
                mv.visitVarInsn(ASTORE, index);
            } else if (TypeTags.isXMLTypeTag(bType.tag)) {
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", XML_VALUE));
                mv.visitVarInsn(ASTORE, index);
            } else {
                generateFrameClassFieldLoadByTypeTag(mv, frameName, localVar, index, bType);
            }
        }

    }

    private void generateFrameClassFieldLoadByTypeTag(MethodVisitor mv, String frameName, BIRVariableDcl localVar,
                                                      int index, BType bType) {
        switch (bType.tag) {
            case TypeTags.BYTE:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"), "I");
                mv.visitVarInsn(ISTORE, index);
                break;
            case TypeTags.FLOAT:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"), "D");
                mv.visitVarInsn(DSTORE, index);
                break;
            case TypeTags.DECIMAL:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", DECIMAL_VALUE));
                mv.visitVarInsn(ASTORE, index);
                break;
            case TypeTags.BOOLEAN:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"), "Z");
                mv.visitVarInsn(ISTORE, index);
                break;
            case TypeTags.MAP:
            case TypeTags.RECORD:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", MAP_VALUE));
                mv.visitVarInsn(ASTORE, index);
                break;
            case TypeTags.STREAM:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", STREAM_VALUE));
                mv.visitVarInsn(ASTORE, index);
                break;
            case TypeTags.TABLE:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", TABLE_VALUE_IMPL));
                mv.visitVarInsn(ASTORE, index);
                break;
            case TypeTags.ARRAY:
            case TypeTags.TUPLE:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", ARRAY_VALUE));
                mv.visitVarInsn(ASTORE, index);
                break;
            case TypeTags.OBJECT:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", B_OBJECT));
                mv.visitVarInsn(ASTORE, index);
                break;
            case TypeTags.ERROR:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", ERROR_VALUE));
                mv.visitVarInsn(ASTORE, index);
                break;
            case TypeTags.FUTURE:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", FUTURE_VALUE));
                mv.visitVarInsn(ASTORE, index);
                break;
            case TypeTags.INVOKABLE:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", FUNCTION_POINTER));
                mv.visitVarInsn(ASTORE, index);
                break;
            case TypeTags.TYPEDESC:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", TYPEDESC_VALUE));
                mv.visitVarInsn(ASTORE, index);
                break;
            case TypeTags.NIL:
            case TypeTags.NEVER:
            case TypeTags.ANY:
            case TypeTags.ANYDATA:
            case TypeTags.UNION:
            case TypeTags.INTERSECTION:
            case TypeTags.JSON:
            case TypeTags.FINITE:
            case TypeTags.READONLY:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", OBJECT));
                mv.visitVarInsn(ASTORE, index);
                break;
            case TypeTags.HANDLE:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", HANDLE_VALUE));
                mv.visitVarInsn(ASTORE, index);
                break;
            case JTypeTags.JTYPE:
                generateFrameClassJFieldLoad(localVar, mv, index, frameName);
                break;
            default:
                throw new BLangCompilerException(JvmConstants.TYPE_NOT_SUPPORTED_MESSAGE + bType);
        }
    }

    private void generateFrameClassJFieldLoad(BIRVariableDcl localVar, MethodVisitor mv,
                                              int index, String frameName) {

        JType jType = (JType) localVar.type;

        switch (jType.jTag) {
            case JTypeTags.JBYTE:
            case JTypeTags.JCHAR:
            case JTypeTags.JSHORT:
            case JTypeTags.JINT:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"), "I");
                mv.visitVarInsn(ISTORE, index);
                break;
            case JTypeTags.JLONG:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"), "J");
                mv.visitVarInsn(LSTORE, index);
                break;
            case JTypeTags.JFLOAT:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"), "F");
                mv.visitVarInsn(FSTORE, index);
                break;
            case JTypeTags.JDOUBLE:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"), "D");
                mv.visitVarInsn(DSTORE, index);
                break;
            case JTypeTags.JBOOLEAN:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"), "Z");
                mv.visitVarInsn(ISTORE, index);
                break;
            case JTypeTags.JARRAY:
            case JTypeTags.JREF:
                mv.visitFieldInsn(GETFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  InteropMethodGen.getJTypeSignature(jType));
                mv.visitVarInsn(ASTORE, index);
                break;
            default:
                throw new BLangCompilerException(JvmConstants.TYPE_NOT_SUPPORTED_MESSAGE +
                                                         String.format("%s", jType));
        }
    }

    private void generateFrameClassFieldUpdate(List<BIRVariableDcl> localVars, MethodVisitor mv,
                                                      BIRVarToJVMIndexMap indexMap, String frameName) {
        for (BIRVariableDcl localVar : localVars) {
            int index = indexMap.addToMapIfNotFoundAndGetIndex(localVar);
            mv.visitInsn(DUP);

            BType bType = localVar.type;
            if (TypeTags.isIntegerTypeTag(bType.tag)) {
                mv.visitVarInsn(LLOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"), "J");
            } else if (TypeTags.isStringTypeTag(bType.tag)) {
                mv.visitVarInsn(ALOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", JvmConstants.B_STRING_VALUE));
            } else if (TypeTags.isXMLTypeTag(bType.tag)) {
                mv.visitVarInsn(ALOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", XML_VALUE));
            } else {
                generateFrameClassFieldUpdateByTypeTag(mv, frameName, localVar, index, bType);
            }
        }
    }

    private void generateFrameClassFieldUpdateByTypeTag(MethodVisitor mv, String frameName, BIRVariableDcl localVar,
                                                        int index, BType bType) {
        switch (bType.tag) {
            case TypeTags.BYTE:
                mv.visitVarInsn(ILOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"), "I");
                break;
            case TypeTags.FLOAT:
                mv.visitVarInsn(DLOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"), "D");
                break;
            case TypeTags.DECIMAL:
                mv.visitVarInsn(ALOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", DECIMAL_VALUE));
                break;
            case TypeTags.BOOLEAN:
                mv.visitVarInsn(ILOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"), "Z");
                break;
            case TypeTags.MAP:
            case TypeTags.RECORD:
                mv.visitVarInsn(ALOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", MAP_VALUE));
                break;
            case TypeTags.STREAM:
                mv.visitVarInsn(ALOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", STREAM_VALUE));
                break;
            case TypeTags.TABLE:
                mv.visitVarInsn(ALOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", TABLE_VALUE_IMPL));
                break;
            case TypeTags.ARRAY:
            case TypeTags.TUPLE:
                mv.visitVarInsn(ALOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", ARRAY_VALUE));
                break;
            case TypeTags.ERROR:
                mv.visitVarInsn(ALOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", ERROR_VALUE));
                break;
            case TypeTags.FUTURE:
                mv.visitVarInsn(ALOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", FUTURE_VALUE));
                break;
            case TypeTags.TYPEDESC:
                mv.visitVarInsn(ALOAD, index);
                mv.visitTypeInsn(CHECKCAST, TYPEDESC_VALUE);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", TYPEDESC_VALUE));
                break;
            case TypeTags.OBJECT:
                mv.visitVarInsn(ALOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", B_OBJECT));
                break;
            case TypeTags.INVOKABLE:
                mv.visitVarInsn(ALOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", FUNCTION_POINTER));
                break;
            case TypeTags.NIL:
            case TypeTags.NEVER:
            case TypeTags.ANY:
            case TypeTags.ANYDATA:
            case TypeTags.UNION:
            case TypeTags.INTERSECTION:
            case TypeTags.JSON:
            case TypeTags.FINITE:
            case TypeTags.READONLY:
                mv.visitVarInsn(ALOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", OBJECT));
                break;
            case TypeTags.HANDLE:
                mv.visitVarInsn(ALOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"),
                                  String.format("L%s;", HANDLE_VALUE));
                break;
            case JTypeTags.JTYPE:
                generateFrameClassJFieldUpdate(localVar, mv, index, frameName);
                break;
            default:
                throw new BLangCompilerException(JvmConstants.TYPE_NOT_SUPPORTED_MESSAGE +
                                                         String.format("%s", bType));
        }
    }

    private void generateFrameClassJFieldUpdate(BIRVariableDcl localVar, MethodVisitor mv,
                                                       int index, String frameName) {
        JType jType = (JType) localVar.type;
        switch (jType.jTag) {
            case JTypeTags.JBYTE:
                mv.visitVarInsn(ILOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"), "B");
                break;
            case JTypeTags.JCHAR:
                mv.visitVarInsn(ILOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"), "C");
                break;
            case JTypeTags.JSHORT:
                mv.visitVarInsn(ILOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"), "S");
                break;
            case JTypeTags.JINT:
                mv.visitVarInsn(ILOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"), "I");
                break;
            case JTypeTags.JLONG:
                mv.visitVarInsn(LLOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"), "J");
                break;
            case JTypeTags.JFLOAT:
                mv.visitVarInsn(FLOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"), "F");
                break;
            case JTypeTags.JDOUBLE:
                mv.visitVarInsn(DLOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"), "D");
                break;
            case JTypeTags.JBOOLEAN:
                mv.visitVarInsn(ILOAD, index);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"), "Z");
                break;
            case JTypeTags.JARRAY:
            case JTypeTags.JREF:
                String classSig = InteropMethodGen.getJTypeSignature(jType);
                String className = InteropMethodGen.getSignatureForJType(jType);
                mv.visitVarInsn(ALOAD, index);
                mv.visitTypeInsn(CHECKCAST, className);
                mv.visitFieldInsn(PUTFIELD, frameName, localVar.name.value.replace("%", "_"), classSig);
                break;
            default:
                throw new BLangCompilerException(JvmConstants.TYPE_NOT_SUPPORTED_MESSAGE +
                                                         String.format("%s", jType));
        }
    }

    private String getJVMTypeSign(BType bType) {
        if (TypeTags.isIntegerTypeTag(bType.tag)) {
            return "J";
        } else if (TypeTags.isStringTypeTag(bType.tag)) {
            return String.format("L%s;", STRING_VALUE);
        } else if (TypeTags.isXMLTypeTag(bType.tag)) {
            return String.format("L%s;", XML_VALUE);
        }

        String jvmType;
        switch (bType.tag) {
            case TypeTags.BYTE:
                jvmType = "I";
                break;
            case TypeTags.FLOAT:
                jvmType = "D";
                break;
            case TypeTags.BOOLEAN:
                jvmType = "Z";
                break;
            case TypeTags.DECIMAL:
                jvmType = String.format("L%s;", DECIMAL_VALUE);
                break;
            case TypeTags.MAP:
            case TypeTags.RECORD:
                jvmType = String.format("L%s;", MAP_VALUE);
                break;
            case TypeTags.STREAM:
                jvmType = String.format("L%s;", STREAM_VALUE);
                break;
            case TypeTags.TABLE:
                jvmType = String.format("L%s;", TABLE_VALUE_IMPL);
                break;
            case TypeTags.ARRAY:
            case TypeTags.TUPLE:
                jvmType = String.format("L%s;", ARRAY_VALUE);
                break;
            case TypeTags.OBJECT:
                jvmType = String.format("L%s;", B_OBJECT);
                break;
            case TypeTags.ERROR:
                jvmType = String.format("L%s;", ERROR_VALUE);
                break;
            case TypeTags.FUTURE:
                jvmType = String.format("L%s;", FUTURE_VALUE);
                break;
            case TypeTags.INVOKABLE:
                jvmType = String.format("L%s;", FUNCTION_POINTER);
                break;
            case TypeTags.HANDLE:
                jvmType = String.format("L%s;", HANDLE_VALUE);
                break;
            case TypeTags.TYPEDESC:
                jvmType = String.format("L%s;", TYPEDESC_VALUE);
                break;
            case TypeTags.NIL:
            case TypeTags.NEVER:
            case TypeTags.ANY:
            case TypeTags.ANYDATA:
            case TypeTags.UNION:
            case TypeTags.INTERSECTION:
            case TypeTags.JSON:
            case TypeTags.FINITE:
            case TypeTags.READONLY:
                jvmType = String.format("L%s;", OBJECT);
                break;
            case JTypeTags.JTYPE:
                jvmType = InteropMethodGen.getJTypeSignature((JType) bType);
                break;
            default:
                throw new BLangCompilerException("JVM code generation is not supported for type " +
                        String.format("%s", bType));
        }

        return jvmType;
    }

    void loadLocalType(MethodVisitor mv, BIRTypeDefinition typeDefinition) {
        JvmTypeGen.loadType(mv, typeDefinition.type);
    }

    private boolean isModuleTestInitFunction(BIRPackage module, BIRFunction func) {
        return func.name.value.equals(
                calculateModuleSpecialFuncName(JvmMethodGenUtils.packageToModuleId(module), "<testinit>"));
    }

    static String calculateModuleSpecialFuncName(PackageID id, String funcSuffix) {
        String orgName = id.orgName.value;
        String moduleName = id.name.value;
        String version = id.version.value;

        String funcName;
        if (moduleName.equals(".")) {
            funcName = ".." + funcSuffix;
        } else if (version.equals("")) {
            funcName = moduleName + "." + funcSuffix;
        } else {
            funcName = moduleName + ":" + version + "." + funcSuffix;
        }

        if (!orgName.equalsIgnoreCase("$anon")) {
            funcName = orgName + "/" + funcName;
        }

        return funcName;
    }

    private void genDefaultValue(MethodVisitor mv, BType bType, int index) {
        if (TypeTags.isIntegerTypeTag(bType.tag)) {
            mv.visitInsn(LCONST_0);
            mv.visitVarInsn(LSTORE, index);
            return;
        } else if (TypeTags.isStringTypeTag(bType.tag) || TypeTags.isXMLTypeTag(bType.tag)) {
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, index);
            return;
        }

        switch (bType.tag) {
            case TypeTags.BYTE:
            case TypeTags.BOOLEAN:
                mv.visitInsn(ICONST_0);
                mv.visitVarInsn(ISTORE, index);
                break;
            case TypeTags.FLOAT:
                mv.visitInsn(DCONST_0);
                mv.visitVarInsn(DSTORE, index);
                break;
            case TypeTags.MAP:
            case TypeTags.ARRAY:
            case TypeTags.STREAM:
            case TypeTags.TABLE:
            case TypeTags.ERROR:
            case TypeTags.NIL:
            case TypeTags.NEVER:
            case TypeTags.ANY:
            case TypeTags.ANYDATA:
            case TypeTags.OBJECT:
            case TypeTags.CHAR_STRING:
            case TypeTags.DECIMAL:
            case TypeTags.UNION:
            case TypeTags.INTERSECTION:
            case TypeTags.RECORD:
            case TypeTags.TUPLE:
            case TypeTags.FUTURE:
            case TypeTags.JSON:
            case TypeTags.INVOKABLE:
            case TypeTags.FINITE:
            case TypeTags.HANDLE:
            case TypeTags.TYPEDESC:
            case TypeTags.READONLY:
                mv.visitInsn(ACONST_NULL);
                mv.visitVarInsn(ASTORE, index);
                break;
            case JTypeTags.JTYPE:
                genJDefaultValue(mv, (JType) bType, index);
                break;
            default:
                throw new BLangCompilerException(JvmConstants.TYPE_NOT_SUPPORTED_MESSAGE +
                        String.format("%s", bType));
        }
    }

    private void genJDefaultValue(MethodVisitor mv, JType jType, int index) {
        switch (jType.jTag) {
            case JTypeTags.JBYTE:
            case JTypeTags.JCHAR:
            case JTypeTags.JSHORT:
            case JTypeTags.JINT:
            case JTypeTags.JBOOLEAN:
                mv.visitInsn(ICONST_0);
                mv.visitVarInsn(ISTORE, index);
                break;
            case JTypeTags.JLONG:
                mv.visitInsn(LCONST_0);
                mv.visitVarInsn(LSTORE, index);
                break;
            case JTypeTags.JFLOAT:
                mv.visitInsn(FCONST_0);
                mv.visitVarInsn(FSTORE, index);
                break;
            case JTypeTags.JDOUBLE:
                mv.visitInsn(DCONST_0);
                mv.visitVarInsn(DSTORE, index);
                break;
            case JTypeTags.JARRAY:
            case JTypeTags.JREF:
                mv.visitInsn(ACONST_NULL);
                mv.visitVarInsn(ASTORE, index);
                break;
            default:
                throw new BLangCompilerException(JvmConstants.TYPE_NOT_SUPPORTED_MESSAGE +
                        String.format("%s", jType));
        }
    }

    private String getFrameClassName(String pkgName, String funcName, BType attachedType) {
        String frameClassName = pkgName;
        if (isValidType(attachedType)) {
            frameClassName += JvmCodeGenUtil.cleanupReadOnlyTypeName(JvmCodeGenUtil.toNameString(attachedType)) + "_";
        }

        return frameClassName + JvmCodeGenUtil.cleanupFunctionName(funcName) + "Frame";
    }

    private boolean isValidType(BType attachedType) {
        return attachedType != null && (attachedType.tag == TypeTags.OBJECT || attachedType instanceof BServiceType ||
                attachedType.tag == TypeTags.RECORD);
    }

    private void checkStrandCancelled(MethodVisitor mv, int localVarOffset) {
        mv.visitVarInsn(ALOAD, localVarOffset);
        mv.visitFieldInsn(GETFIELD, STRAND_CLASS, "cancel", "Z");
        Label notCancelledLabel = new Label();
        mv.visitJumpInsn(IFEQ, notCancelledLabel);
        mv.visitMethodInsn(INVOKESTATIC, ERROR_UTILS, "createCancelledFutureError",
                           String.format("()L%s;", ERROR_VALUE), false);
        mv.visitInsn(ATHROW);

        mv.visitLabel(notCancelledLabel);
    }

    private int[] toIntArray(List<Integer> states) {
        int[] ints = new int[states.size()];
        for (int i = 0; i < states.size(); i++) {
            ints[i] = states.get(i);
        }
        return ints;
    }

    private boolean isModuleStartFunction(BIRPackage module, String functionName) {
        return functionName.equals(JvmCodeGenUtil.cleanupFunctionName(
                calculateModuleSpecialFuncName(JvmMethodGenUtils.packageToModuleId(module), START_FUNCTION_SUFFIX)));
    }

    public void generateBasicBlocks(MethodVisitor mv, LabelGenerator labelGen, JvmErrorGen errorGen,
                                    JvmInstructionGen instGen, JvmTerminatorGen termGen, BIRFunction func,
                                    int returnVarRefIndex, int stateVarIndex, int localVarOffset, BIRPackage module,
                                    BType attachedType, String moduleClassName, AsyncDataCollector asyncDataCollector) {

        String funcName = JvmCodeGenUtil.cleanupFunctionName(func.name.value);
        BirScope lastScope = null;
        Set<BirScope> visitedScopesSet = new HashSet<>();

        int caseIndex = 0;
        for (int i = 0; i < func.basicBlocks.size(); i++) {
            BIRBasicBlock bb = func.basicBlocks.get(i);
            // create jvm label
            Label bbLabel = labelGen.getLabel(funcName + bb.id.value);
            mv.visitLabel(bbLabel);
            if (i == 0) {
                pushShort(mv, stateVarIndex, caseIndex);
                caseIndex += 1;
            }

            // generate instructions
            lastScope = JvmCodeGenUtil
                    .getLastScopeFromBBInsGen(mv, labelGen, instGen, localVarOffset, asyncDataCollector, funcName, bb,
                                              visitedScopesSet, lastScope);

            Label bbEndLabel = labelGen.getLabel(funcName + bb.id.value + "beforeTerm");
            mv.visitLabel(bbEndLabel);

            BIRTerminator terminator = bb.terminator;
            pushShort(mv, stateVarIndex, caseIndex);
            caseIndex += 1;

            processTerminator(mv, func, module, funcName, terminator);
            termGen.genTerminator(terminator, moduleClassName, func, funcName, localVarOffset, returnVarRefIndex,
                                  attachedType, asyncDataCollector);

            errorGen.generateTryCatch(func, funcName, bb, termGen, labelGen);

            BIRBasicBlock thenBB = terminator.thenBB;
            if (thenBB != null) {
                JvmCodeGenUtil.genYieldCheck(mv, termGen.getLabelGenerator(), thenBB, funcName, localVarOffset);
            }
        }
    }

    private void processTerminator(MethodVisitor mv, BIRFunction func, BIRPackage module, String funcName,
                                   BIRTerminator terminator) {
        JvmCodeGenUtil.generateDiagnosticPos(terminator.pos, mv);
        if ((JvmMethodGenUtils.isModuleInitFunction(module, func) || isModuleTestInitFunction(module, func)) &&
                terminator instanceof Return) {
            generateAnnotLoad(mv, module.typeDefs, JvmCodeGenUtil.getPackageName(module));
        }
        //set module start success to true for $_init class
        if (isModuleStartFunction(module, funcName) && terminator.kind == InstructionKind.RETURN) {
            mv.visitInsn(ICONST_1);
            mv.visitFieldInsn(PUTSTATIC,
                              JvmCodeGenUtil.getModuleLevelClassName(module, MODULE_INIT_CLASS_NAME),
                              MODULE_STARTED, "Z");
        }
    }

    private void pushShort(MethodVisitor mv, int stateVarIndex, int caseIndex) {
        // SIPUSH range is (-32768 to 32767) so if the state index goes beyond that, need to use visitLdcInsn
        mv.visitIntInsn(SIPUSH, caseIndex);
        mv.visitVarInsn(ISTORE, stateVarIndex);
    }

    /**
     * Generate a lambda function to invoke ballerina main.
     *
     * @param cw        class visitor
     * @param pkg       bir package
     * @param initClass module init class
     * @param depMods   dependent module list
     */
    void generateLambdaForPackageInits(ClassWriter cw, BIRPackage pkg, String initClass, List<PackageID> depMods) {
        //need to generate lambda for package Init as well, if exist
        if (!JvmMethodGenUtils.hasInitFunction(pkg)) {
            return;
        }
        generateLambdaForModuleFunction(cw, MODULE_INIT, initClass);

        // generate another lambda for start function as well
        generateLambdaForModuleFunction(cw, MODULE_START, initClass);

        PackageID currentModId = JvmMethodGenUtils.packageToModuleId(pkg);
        String fullFuncName = calculateModuleSpecialFuncName(currentModId, STOP_FUNCTION_SUFFIX);

        generateLambdaForDepModStopFunc(cw, JvmCodeGenUtil.cleanupFunctionName(fullFuncName), initClass);

        for (PackageID id : depMods) {
            fullFuncName = calculateModuleSpecialFuncName(id, STOP_FUNCTION_SUFFIX);
            String jvmClass = JvmCodeGenUtil.getPackageName(id) + MODULE_INIT_CLASS_NAME;
            generateLambdaForDepModStopFunc(cw, JvmCodeGenUtil.cleanupFunctionName(fullFuncName), jvmClass);
        }
    }

    private void generateLambdaForDepModStopFunc(ClassWriter cw, String funcName, String initClass) {
        MethodVisitor mv;
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC + ACC_STATIC,
                            String.format("$lambda$%s", funcName),
                            String.format("([L%s;)L%s;", OBJECT, OBJECT), null, null);
        mv.visitCode();

        //load strand as first arg
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, STRAND_CLASS);

        mv.visitMethodInsn(INVOKESTATIC, initClass, funcName, String.format("(L%s;)L%s;", STRAND_CLASS, OBJECT), false);
        JvmMethodGenUtils.visitReturn(mv);
    }

    private void generateLambdaForModuleFunction(ClassWriter cw, String funcName, String initClass) {

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC + ACC_STATIC,
                                          String.format("$lambda$%s$", funcName),
                                          String.format("([L%s;)L%s;", OBJECT, OBJECT), null, null);
        mv.visitCode();

        //load strand as first arg
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, STRAND_CLASS);

        mv.visitMethodInsn(INVOKESTATIC, initClass, funcName, String.format("(L%s;)L%s;", STRAND_CLASS, OBJECT), false);
        JvmCastGen.addBoxInsn(mv, errorOrNilType);
        JvmMethodGenUtils.visitReturn(mv);
    }

    void addInitAndTypeInitInstructions(BIRPackage pkg, BIRFunction func) {

        List<BIRBasicBlock> basicBlocks = new ArrayList<>();
        nextId = -1;
        BIRBasicBlock nextBB = new BIRBasicBlock(getNextBBId());
        basicBlocks.add(nextBB);

        PackageID modID = JvmMethodGenUtils.packageToModuleId(pkg);

        BIRBasicBlock typeOwnerCreateBB = new BIRBasicBlock(getNextBBId());
        basicBlocks.add(typeOwnerCreateBB);

        nextBB.terminator = new Call(null, InstructionKind.CALL, false, modID, new Name(CURRENT_MODULE_INIT),
                new ArrayList<>(), null, typeOwnerCreateBB, Collections.emptyList(), Collections.emptySet());

        if (func.basicBlocks.size() == 0) {
            typeOwnerCreateBB.terminator = new Return(func.pos);
            func.basicBlocks = basicBlocks;
            return;
        }

        typeOwnerCreateBB.terminator = new GOTO(null, func.basicBlocks.get(0));

        basicBlocks.addAll(func.basicBlocks);
        func.basicBlocks = basicBlocks;
    }

    void enrichPkgWithInitializers(Map<String, JavaClass> jvmClassMap, String typeOwnerClass,
                                   BIRPackage pkg, List<PackageID> moduleImports) {

        JavaClass javaClass = jvmClassMap.get(typeOwnerClass);
        BIRFunction initFunc = generateDepModInit(moduleImports, pkg, MODULE_INIT,
                                                  JvmMethodGenUtils.INIT_FUNCTION_SUFFIX);
        javaClass.functions.add(initFunc);
        pkg.functions.add(initFunc);

        BIRFunction startFunc = generateDepModInit(moduleImports, pkg, MODULE_START, START_FUNCTION_SUFFIX);
        javaClass.functions.add(startFunc);
        pkg.functions.add(startFunc);

    }

    private BIRFunction generateDepModInit(List<PackageID> imprtMods, BIRPackage pkg, String funcName,
                                           String initName) {

        nextId = -1;
        nextVarId = -1;

        BIRVariableDcl retVar = new BIRVariableDcl(null, errorOrNilType, new Name("%ret"),
                                                   VarScope.FUNCTION, VarKind.RETURN, "");
        BIROperand retVarRef = new BIROperand(retVar);

        BInvokableType funcType = new BInvokableType(Collections.emptyList(), null, errorOrNilType, null);
        BIRFunction modInitFunc = new BIRFunction(null, new Name(funcName), 0, funcType, null, 0, null, VIRTUAL);
        modInitFunc.localVars.add(retVar);
        addAndGetNextBasicBlock(modInitFunc);

        BIRVariableDcl boolVal = addAndGetNextVar(modInitFunc, symbolTable.booleanType);
        BIROperand boolRef = new BIROperand(boolVal);

        for (PackageID id : imprtMods) {
            String initFuncName = calculateModuleSpecialFuncName(id, initName);
            addCheckedInvocation(modInitFunc, id, initFuncName, retVarRef, boolRef);
        }

        PackageID currentModId = JvmMethodGenUtils.packageToModuleId(pkg);
        String currentInitFuncName = calculateModuleSpecialFuncName(currentModId, initName);
        BIRBasicBlock lastBB = addCheckedInvocation(modInitFunc, currentModId, currentInitFuncName, retVarRef, boolRef);

        lastBB.terminator = new Return(null);

        return modInitFunc;
    }

    private Name getNextBBId() {

        String bbIdPrefix = "genBB";
        nextId += 1;
        return new Name(bbIdPrefix + nextId);
    }

    private Name getNextVarId() {

        String varIdPrefix = "%";
        nextVarId += 1;
        return new Name(varIdPrefix + nextVarId);
    }

    private BIRBasicBlock addCheckedInvocation(BIRFunction func, PackageID modId, String initFuncName,
                                               BIROperand retVar, BIROperand boolRef) {

        BIRBasicBlock lastBB = func.basicBlocks.get(func.basicBlocks.size() - 1);
        BIRBasicBlock nextBB = addAndGetNextBasicBlock(func);
        // TODO remove once lang.annotation is fixed
        if (modId.orgName.value.equals(BALLERINA) && modId.name.value.equals(BUILT_IN_PACKAGE_NAME)) {
            lastBB.terminator = new Call(null, InstructionKind.CALL, false, modId,
                    new Name(initFuncName), Collections.emptyList(), null, nextBB, Collections.emptyList(),
                    Collections.emptySet());
            return nextBB;
        }
        lastBB.terminator = new Call(null, InstructionKind.CALL, false, modId, new Name(initFuncName),
                Collections.emptyList(), retVar, nextBB, Collections.emptyList(), Collections.emptySet());

        TypeTest typeTest = new TypeTest(null, symbolTable.errorType, boolRef, retVar);
        nextBB.instructions.add(typeTest);

        BIRBasicBlock trueBB = addAndGetNextBasicBlock(func);
        BIRBasicBlock retBB = addAndGetNextBasicBlock(func);
        retBB.terminator = new Return(null);
        trueBB.terminator = new GOTO(null, retBB);

        BIRBasicBlock falseBB = addAndGetNextBasicBlock(func);
        nextBB.terminator = new Branch(null, boolRef, trueBB, falseBB);
        return falseBB;
    }

    private BIRBasicBlock addAndGetNextBasicBlock(BIRFunction func) {

        BIRBasicBlock nextbb = new BIRBasicBlock(getNextBBId());
        func.basicBlocks.add(nextbb);
        return nextbb;
    }

    private BIRVariableDcl addAndGetNextVar(BIRFunction func, BType typeVal) {

        BIRVariableDcl nextLocalVar = new BIRVariableDcl(typeVal, getNextVarId(), VarScope.FUNCTION, VarKind.LOCAL);
        func.localVars.add(nextLocalVar);
        return nextLocalVar;
    }

    private void generateAnnotLoad(MethodVisitor mv, List<BIRTypeDefinition> typeDefs, String pkgName) {

        String typePkgName = ".";
        if (!"".equals(pkgName)) {
            typePkgName = pkgName;
        }

        for (BIRTypeDefinition optionalTypeDef : typeDefs) {
            if (optionalTypeDef.isBuiltin) {
                continue;
            }
            BType bType = optionalTypeDef.type;
            if (bType.tag != TypeTags.FINITE && !(bType instanceof BServiceType)) {
                loadAnnots(mv, typePkgName, optionalTypeDef);
            }

        }
    }

    private void loadAnnots(MethodVisitor mv, String pkgName, BIRTypeDefinition typeDef) {

        String pkgClassName = pkgName.equals(".") || pkgName.equals("") ? MODULE_INIT_CLASS_NAME :
                jvmPackageGen.lookupGlobalVarClassName(pkgName, ANNOTATION_MAP_NAME);
        mv.visitFieldInsn(GETSTATIC, pkgClassName, ANNOTATION_MAP_NAME, String.format("L%s;", MAP_VALUE));
        loadLocalType(mv, typeDef);
        mv.visitMethodInsn(INVOKESTATIC, String.format("%s", ANNOTATION_UTILS), "processAnnotations",
                           String.format("(L%s;L%s;)V", MAP_VALUE, TYPE), false);
    }

    void generateFrameClasses(BIRPackage pkg, Map<String, byte[]> pkgEntries) {

        pkg.functions.parallelStream().forEach(func -> generateFrameClassForFunction(pkg, func, pkgEntries, null));

        for (BIRTypeDefinition typeDef : pkg.typeDefs) {
            List<BIRFunction> attachedFuncs = typeDef.attachedFuncs;
            if (attachedFuncs == null || attachedFuncs.size() == 0) {
                continue;
            }

            BType attachedType;
            if (typeDef.type.tag == TypeTags.RECORD) {
                // Only attach function of records is the record init. That should be
                // generated as a static function.
                attachedType = null;
            } else {
                attachedType = typeDef.type;
            }
            attachedFuncs.parallelStream().forEach(func ->
                    generateFrameClassForFunction(pkg, func, pkgEntries, attachedType));
        }
    }

    private void generateFrameClassForFunction(BIRPackage pkg, BIRFunction func, Map<String, byte[]> pkgEntries,
                                               BType attachedType) {
        String frameClassName = getFrameClassName(JvmCodeGenUtil.getPackageName(pkg), func.name.value,
                                                  attachedType);
        ClassWriter cw = new BallerinaClassWriter(COMPUTE_FRAMES);
        if (func.pos != null && func.pos.src != null) {
            cw.visitSource(func.pos.src.cUnitName, null);
        }
        cw.visit(V1_8, Opcodes.ACC_PUBLIC + ACC_SUPER, frameClassName, null, OBJECT, null);
        JvmCodeGenUtil.generateDefaultConstructor(cw, OBJECT);

        int k = 0;
        List<BIRVariableDcl> localVars = func.localVars;
        while (k < localVars.size()) {
            BIRVariableDcl localVar = localVars.get(k);
            BType bType = localVar.type;
            String fieldName = localVar.name.value.replace("%", "_");
            String typeSig = JvmCodeGenUtil.getFieldTypeSignature(bType);
            cw.visitField(Opcodes.ACC_PUBLIC, fieldName, typeSig, null, null).visitEnd();
            k = k + 1;
        }

        FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC, STATE, "I", null, null);
        fv.visitEnd();

        cw.visitEnd();

        // panic if there are errors in the frame class. These cannot be logged, since
        // frame classes are internal implementation details.
        pkgEntries.put(frameClassName + ".class", cw.toByteArray());
    }

    void generateModuleInitializer(ClassWriter cw, BIRPackage module, String typeOwnerClass) {

        String orgName = module.org.value;
        String moduleName = module.name.value;
        String version = module.version.value;

        // Using object return type since this is similar to a ballerina function without a return.
        // A ballerina function with no returns is equivalent to a function with nil-return.
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC + ACC_STATIC, CURRENT_MODULE_INIT,
                                          String.format("(L%s;)L%s;", STRAND_CLASS, OBJECT), null, null);
        mv.visitCode();

        mv.visitMethodInsn(INVOKESTATIC, typeOwnerClass, CREATE_TYPES_METHOD, "()V", false);
        mv.visitTypeInsn(NEW, typeOwnerClass);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, typeOwnerClass, JVM_INIT_METHOD, "()V", false);
        mv.visitVarInsn(ASTORE, 1);
        mv.visitLdcInsn(orgName);
        mv.visitLdcInsn(moduleName);
        mv.visitLdcInsn(version);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, String.format("%s", VALUE_CREATOR), "addValueCreator",
                String.format("(L%s;L%s;L%s;L%s;)V", STRING_VALUE, STRING_VALUE, STRING_VALUE, VALUE_CREATOR),
                false);

        // Add a nil-return
        mv.visitInsn(ACONST_NULL);
        JvmMethodGenUtils.visitReturn(mv);
    }

    void generateExecutionStopMethod(ClassWriter cw, String initClass, BIRPackage module, List<PackageID> imprtMods,
                                     AsyncDataCollector asyncDataCollector) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC + ACC_STATIC, MODULE_STOP, "()V", null, null);
        mv.visitCode();

        BIRVarToJVMIndexMap indexMap = new BIRVarToJVMIndexMap();

        BIRVariableDcl argsVar = new BIRVariableDcl(symbolTable.anyType, new Name("schedulerVar"),
                                                    VarScope.FUNCTION, VarKind.ARG);
        int schedulerIndex = indexMap.addToMapIfNotFoundAndGetIndex(argsVar);
        BIRVariableDcl futureVar = new BIRVariableDcl(symbolTable.anyType, new Name("futureVar"),
                                                      VarScope.FUNCTION, VarKind.ARG);
        int futureIndex = indexMap.addToMapIfNotFoundAndGetIndex(futureVar);

        mv.visitTypeInsn(NEW, SCHEDULER);
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKESPECIAL, SCHEDULER, JVM_INIT_METHOD, "(IZ)V", false);

        mv.visitVarInsn(ASTORE, schedulerIndex);


        PackageID currentModId = JvmMethodGenUtils.packageToModuleId(module);
        String moduleInitClass = getModuleInitClassName(currentModId);
        String fullFuncName = calculateModuleSpecialFuncName(currentModId, STOP_FUNCTION_SUFFIX);

        scheduleStopMethod(mv, initClass, JvmCodeGenUtil.cleanupFunctionName(fullFuncName), schedulerIndex,
                           futureIndex, moduleInitClass, asyncDataCollector);
        int i = imprtMods.size() - 1;
        while (i >= 0) {
            PackageID id = imprtMods.get(i);
            i -= 1;
            fullFuncName = calculateModuleSpecialFuncName(id, STOP_FUNCTION_SUFFIX);
            moduleInitClass = getModuleInitClassName(id);
            scheduleStopMethod(mv, initClass, JvmCodeGenUtil.cleanupFunctionName(fullFuncName), schedulerIndex,
                               futureIndex, moduleInitClass, asyncDataCollector);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void scheduleStopMethod(MethodVisitor mv, String initClass, String stopFuncName,
                                    int schedulerIndex, int futureIndex, String moduleClass,
                                    AsyncDataCollector asyncDataCollector) {
        // Create a scheduler. A new scheduler is used here, to make the stop function to not to
        // depend/wait on whatever is being running on the background. eg: a busy loop in the main.
        mv.visitFieldInsn(GETSTATIC, moduleClass, MODULE_START_ATTEMPTED, "Z");
        Label labelIf = new Label();
        mv.visitJumpInsn(IFEQ, labelIf);
        JvmMethodGenUtils.genArgs(mv, schedulerIndex);

        // create FP value
        String lambdaFuncName = "$lambda$" + stopFuncName;
        JvmCodeGenUtil.createFunctionPointer(mv, initClass, lambdaFuncName);

        // no parent strand
        mv.visitInsn(ACONST_NULL);
        JvmTypeGen.loadType(mv, new BNilType());
        JvmMethodGenUtils.submitToScheduler(mv, initClass, "stop", asyncDataCollector);
        mv.visitVarInsn(ASTORE, futureIndex);

        mv.visitVarInsn(ALOAD, futureIndex);

        mv.visitFieldInsn(GETFIELD, FUTURE_VALUE, STRAND, String.format("L%s;", STRAND_CLASS));
        mv.visitIntInsn(BIPUSH, 100);
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        mv.visitFieldInsn(PUTFIELD, STRAND_CLASS, JvmMethodGenUtils.FRAMES, String.format("[L%s;", OBJECT));

        mv.visitVarInsn(ALOAD, futureIndex);
        mv.visitFieldInsn(GETFIELD, FUTURE_VALUE, STRAND, String.format("L%s;", STRAND_CLASS));
        mv.visitFieldInsn(GETFIELD, STRAND_CLASS, "scheduler", String.format("L%s;", SCHEDULER));
        mv.visitMethodInsn(INVOKEVIRTUAL, SCHEDULER, SCHEDULER_START_METHOD, "()V", false);

        mv.visitVarInsn(ALOAD, futureIndex);
        mv.visitFieldInsn(GETFIELD, FUTURE_VALUE, PANIC_FIELD, String.format("L%s;", THROWABLE));

        // handle any runtime errors
        mv.visitJumpInsn(IFNULL, labelIf);
        mv.visitFieldInsn(GETSTATIC, moduleClass, MODULE_STARTED, "Z");
        mv.visitJumpInsn(IFEQ, labelIf);

        mv.visitVarInsn(ALOAD, futureIndex);
        mv.visitFieldInsn(GETFIELD, FUTURE_VALUE, PANIC_FIELD, String.format("L%s;", THROWABLE));
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME_UTILS, HANDLE_STOP_PANIC_METHOD, String.format("(L%s;)V", THROWABLE),
                           false);
        mv.visitLabel(labelIf);
    }

    private String getModuleInitClassName(PackageID id) {
        return JvmCodeGenUtil.getModuleLevelClassName(id.orgName.value, id.name.value, id.version.value,
                                                      MODULE_INIT_CLASS_NAME);
    }

    public void resetIds() {
        nextId = -1;
        nextVarId = -1;
    }

    int incrementAndGetNextId() {
        return nextId++;
    }

    void generateMethod(BIRFunction birFunc, ClassWriter cw, BIRPackage birModule, BType attachedType,
                        String moduleClassName, AsyncDataCollector asyncDataCollector) {
        if (JvmCodeGenUtil.isExternFunc(birFunc)) {
            ExternalMethodGen.genJMethodForBExternalFunc(birFunc, cw, birModule, attachedType, this, jvmPackageGen,
                                                         moduleClassName, asyncDataCollector);
        } else {
            genJMethodForBFunc(birFunc, cw, birModule, moduleClassName, attachedType,
                               asyncDataCollector);
        }
    }
}
