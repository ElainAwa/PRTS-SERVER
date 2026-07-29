package io.izzel.arclight.common.mod.mixins;

import io.izzel.arclight.i18n.ArclightConfig;
import org.objectweb.asm.tree.ClassNode;

/** Gates the ported ServerCore optimizations behind the dedicated */
public class ServerCoreProcessor {

    private static final String PREFIX = "io/izzel/arclight/common/mixin/optimization/general/servercore/";

    static boolean shouldApply(ClassNode node) {
        if (node.name == null || !node.name.startsWith(PREFIX)) {
            return true;
        }
        try {
            return ArclightConfig.spec().getOptimization().isServerCoreEnabled();
        } catch (Throwable t) {
            return true;
        }
    }
}
