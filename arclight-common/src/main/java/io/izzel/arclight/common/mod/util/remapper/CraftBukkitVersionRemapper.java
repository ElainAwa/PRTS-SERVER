package io.izzel.arclight.common.mod.util.remapper;

import io.izzel.arclight.common.mod.ArclightMod;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.objectweb.asm.tree.*;

import java.util.regex.Pattern;

public class CraftBukkitVersionRemapper implements PluginTransformer {

    public static final CraftBukkitVersionRemapper INSTANCE = new CraftBukkitVersionRemapper();
    private static final Marker MARKER = MarkerManager.getMarker("CBREMAPPER");

    private static final Pattern VERSION_PATTERN = Pattern.compile("v\\d+_\\d+_R\\d+");
    private static final String CRAFTBUKKIT_PREFIX = "org/bukkit/craftbukkit/";
    private static final String GENERIC_VERSION = "v";

    @Override
    public void handleClass(ClassNode node, ClassLoaderRemapper remapper, ArclightRemapConfig config) {
        if (node.name.startsWith(CRAFTBUKKIT_PREFIX)) {
            String remapped = remapInternalName(node.name);
            if (!remapped.equals(node.name)) {
                ArclightMod.LOGGER.debug(MARKER, "Remapping class {} to {}", node.name, remapped);
                node.name = remapped;
            }
        }

        if (node.superName != null) {
            node.superName = remapInternalName(node.superName);
        }

        if (node.interfaces != null) {
            for (int i = 0; i < node.interfaces.size(); i++) {
                node.interfaces.set(i, remapInternalName(node.interfaces.get(i)));
            }
        }

        for (FieldNode field : node.fields) {
            field.desc = remapDescriptor(field.desc);
            if (field.signature != null) {
                field.signature = remapSignature(field.signature);
            }
        }

        for (MethodNode method : node.methods) {
            method.desc = remapDescriptor(method.desc);
            if (method.signature != null) {
                method.signature = remapSignature(method.signature);
            }

            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof TypeInsnNode typeInsn) {
                    typeInsn.desc = remapInternalName(typeInsn.desc);
                } else if (insn instanceof FieldInsnNode fieldInsn) {
                    fieldInsn.owner = remapInternalName(fieldInsn.owner);
                    fieldInsn.desc = remapDescriptor(fieldInsn.desc);
                } else if (insn instanceof MethodInsnNode methodInsn) {
                    methodInsn.owner = remapInternalName(methodInsn.owner);
                    methodInsn.desc = remapDescriptor(methodInsn.desc);
                } else if (insn instanceof InvokeDynamicInsnNode invokeDynamicInsn) {
                    invokeDynamicInsn.bsm = new org.objectweb.asm.Handle(
                            invokeDynamicInsn.bsm.getTag(),
                            remapInternalName(invokeDynamicInsn.bsm.getOwner()),
                            invokeDynamicInsn.bsm.getName(),
                            remapDescriptor(invokeDynamicInsn.bsm.getDesc()),
                            invokeDynamicInsn.bsm.isInterface()
                    );
                    invokeDynamicInsn.desc = remapDescriptor(invokeDynamicInsn.desc);
                    Object[] bsmArgs = invokeDynamicInsn.bsmArgs;
                    for (int i = 0; i < bsmArgs.length; i++) {
                        if (bsmArgs[i] instanceof org.objectweb.asm.Type) {
                            bsmArgs[i] = org.objectweb.asm.Type.getType(remapDescriptor(((org.objectweb.asm.Type) bsmArgs[i]).getDescriptor()));
                        } else if (bsmArgs[i] instanceof org.objectweb.asm.Handle handle) {
                            bsmArgs[i] = new org.objectweb.asm.Handle(
                                    handle.getTag(),
                                    remapInternalName(handle.getOwner()),
                                    handle.getName(),
                                    remapDescriptor(handle.getDesc()),
                                    handle.isInterface()
                            );
                        }
                    }
                } else if (insn instanceof MultiANewArrayInsnNode multiArrayInsn) {
                    multiArrayInsn.desc = remapDescriptor(multiArrayInsn.desc);
                }
            }

            if (method.localVariables != null) {
                for (LocalVariableNode localVar : method.localVariables) {
                    localVar.desc = remapDescriptor(localVar.desc);
                    if (localVar.signature != null) {
                        localVar.signature = remapSignature(localVar.signature);
                    }
                }
            }

            if (method.tryCatchBlocks != null) {
                for (TryCatchBlockNode tryCatch : method.tryCatchBlocks) {
                    if (tryCatch.type != null) {
                        tryCatch.type = remapInternalName(tryCatch.type);
                    }
                }
            }
        }

        remapAnnotations(node.visibleAnnotations);
        remapAnnotations(node.invisibleAnnotations);

        for (FieldNode field : node.fields) {
            remapAnnotations(field.visibleAnnotations);
            remapAnnotations(field.invisibleAnnotations);
        }

        for (MethodNode method : node.methods) {
            remapAnnotations(method.visibleAnnotations);
            remapAnnotations(method.invisibleAnnotations);
        }
    }

    private void remapAnnotations(java.util.List<AnnotationNode> annotations) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                annotation.desc = remapDescriptor(annotation.desc);
            }
        }
    }

    private String remapInternalName(String internalName) {
        if (internalName == null || !internalName.startsWith(CRAFTBUKKIT_PREFIX)) {
            return internalName;
        }

        String afterPrefix = internalName.substring(CRAFTBUKKIT_PREFIX.length());
        int slashIndex = afterPrefix.indexOf('/');

        if (slashIndex == -1) {
            // No slash after prefix, entire string is version
            if (VERSION_PATTERN.matcher(afterPrefix).matches()) {
                return CRAFTBUKKIT_PREFIX + GENERIC_VERSION;
            }
            return internalName;
        }

        String versionPart = afterPrefix.substring(0, slashIndex);
        if (VERSION_PATTERN.matcher(versionPart).matches()) {
            return CRAFTBUKKIT_PREFIX + GENERIC_VERSION + afterPrefix.substring(slashIndex);
        }

        return internalName;
    }

    private String remapDescriptor(String descriptor) {
        if (descriptor == null) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < descriptor.length()) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                // Object type descriptor
                int semicolon = descriptor.indexOf(';', i);
                if (semicolon == -1) {
                    result.append(descriptor.substring(i));
                    break;
                }
                String className = descriptor.substring(i + 1, semicolon);
                result.append('L').append(remapInternalName(className)).append(';');
                i = semicolon + 1;
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }

    private String remapSignature(String signature) {
        if (signature == null) {
            return null;
        }
        // Simple implementation - just remap class references in signatures
        return remapDescriptor(signature);
    }
}
