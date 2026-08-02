package io.izzel.arclight.boot.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.Set;

// 双端模组把客户端逻辑写进合成 lambda 时, 服务端整类验证会连带加载客户端类而崩溃
// 三重门(类在名单内 + 方法为 lambda$ 合成 + 方法体确有客户端引用)全中才清空方法体
public class ClientRefSanitizer implements Implementer {

    private static final Set<String> TARGETS = Set.of(
        "com/molox/createimp/network/WorkWarehouseActivateEffectPacket",
        "com/molox/createimp/network/WorkWarehouseMaterialsReadyEffectPacket",
        "com/molox/createimp/network/OpenTemplateMaterialsGuiPacket"
    );

    private static final String CLIENT_PKG = "net/minecraft/client/";
    private static final String BLAZE_PKG = "com/mojang/blaze3d/";

    @Override
    public boolean processClass(ClassNode node) {
        if (!TARGETS.contains(node.name)) {
            return false;
        }
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("lambda$") && referencesClient(method)) {
                sanitize(method);
                changed = true;
                Implementer.LOGGER.debug("Sanitized client-only lambda {}.{}{}", node.name, method.name, method.desc);
            }
        }
        return changed;
    }

    private static boolean referencesClient(MethodNode method) {
        if (method.instructions == null) {
            return false;
        }
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode node = (MethodInsnNode) insn;
                if (isClient(node.owner) || isClient(node.desc)) {
                    return true;
                }
            } else if (insn instanceof FieldInsnNode) {
                FieldInsnNode node = (FieldInsnNode) insn;
                if (isClient(node.owner) || isClient(node.desc)) {
                    return true;
                }
            } else if (insn instanceof TypeInsnNode) {
                if (isClient(((TypeInsnNode) insn).desc)) {
                    return true;
                }
            } else if (insn instanceof InvokeDynamicInsnNode) {
                if (isClient(((InvokeDynamicInsnNode) insn).desc)) {
                    return true;
                }
            } else if (insn instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) insn).cst;
                if (cst instanceof Type && isClient(((Type) cst).getInternalName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isClient(String desc) {
        return desc != null && (desc.contains(CLIENT_PKG) || desc.contains(BLAZE_PKG));
    }

    // 只清空方法体, 删方法会让 BootstrapMethods 的 MethodHandle 解析失败
    private static void sanitize(MethodNode method) {
        method.instructions.clear();
        if (method.tryCatchBlocks != null) {
            method.tryCatchBlocks.clear();
        }
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        if (method.visibleLocalVariableAnnotations != null) {
            method.visibleLocalVariableAnnotations.clear();
        }
        if (method.invisibleLocalVariableAnnotations != null) {
            method.invisibleLocalVariableAnnotations.clear();
        }
        Type ret = Type.getReturnType(method.desc);
        method.instructions.add(buildReturn(ret));
        method.maxStack = Math.max(ret.getSize(), 1);
    }

    private static InsnList buildReturn(Type ret) {
        InsnList list = new InsnList();
        switch (ret.getSort()) {
            case Type.VOID:
                list.add(new InsnNode(Opcodes.RETURN));
                break;
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                list.add(new InsnNode(Opcodes.ICONST_0));
                list.add(new InsnNode(Opcodes.IRETURN));
                break;
            case Type.LONG:
                list.add(new InsnNode(Opcodes.LCONST_0));
                list.add(new InsnNode(Opcodes.LRETURN));
                break;
            case Type.FLOAT:
                list.add(new InsnNode(Opcodes.FCONST_0));
                list.add(new InsnNode(Opcodes.FRETURN));
                break;
            case Type.DOUBLE:
                list.add(new InsnNode(Opcodes.DCONST_0));
                list.add(new InsnNode(Opcodes.DRETURN));
                break;
            default:
                list.add(new InsnNode(Opcodes.ACONST_NULL));
                list.add(new InsnNode(Opcodes.ARETURN));
                break;
        }
        return list;
    }
}
