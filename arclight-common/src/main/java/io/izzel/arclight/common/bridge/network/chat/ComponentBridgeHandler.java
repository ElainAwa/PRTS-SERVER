package io.izzel.arclight.common.bridge.network.chat;

import com.google.common.collect.Streams;
import io.izzel.arclight.common.bridge.core.entity.player.ServerPlayerEntityBridge;
import io.izzel.arclight.common.bridge.core.util.text.ITextComponentBridge;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.v.entity.CraftPlayer;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Runtime bridge handler for Component functionality
 * This replaces the problematic ComponentMixin to avoid early class loading issues
 */
public class ComponentBridgeHandler {
    
    private static final ConcurrentMap<Class<?>, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;
    
    /**
     * Initialize the bridge handler after all classes are loaded
     */
    public static void initialize() {
        if (initialized) return;
        
        try {
            // Cache commonly used methods to avoid reflection overhead
            Class<?> componentClass = Component.class;
            
            // Cache getSiblings method
            Method getSiblingsMethod = componentClass.getDeclaredMethod("getSiblings");
            METHOD_CACHE.put(componentClass, getSiblingsMethod);
            
            initialized = true;
        } catch (Exception e) {
            System.err.println("[Luminara] Failed to initialize ComponentBridgeHandler: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Get siblings from a Component using cached reflection
     */
    @SuppressWarnings("unchecked")
    public static List<Component> getSiblings(Component component) {
        if (!initialized) {
            initialize();
        }
        
        try {
            Method method = METHOD_CACHE.get(Component.class);
            if (method != null) {
                return (List<Component>) method.invoke(component);
            }
        } catch (Exception e) {
            System.err.println("[Luminara] Failed to get siblings from Component: " + e.getMessage());
        }
        
        // Fallback to empty list
        return List.of();
    }
    
    /**
     * Create a stream of components (replaces ComponentMixin.stream())
     */
    public static Stream<Component> createStream(Component component) {
        if (!initialized) {
            initialize();
        }

        try {
            class Func implements Function<Component, Stream<? extends Component>> {
                @Override
                public Stream<? extends Component> apply(Component comp) {
                    return createStream(comp);
                }
            }
            List<Component> siblings = getSiblings(component);
            return Streams.concat(Stream.of(component), siblings.stream().flatMap(new Func()));
        } catch (Exception e) {
            System.err.println("[Luminara] Failed to create Component stream: " + e.getMessage());
            return Stream.of(component);
        }
    }

    /**
     * Create an iterator for components (replaces ComponentMixin.iterator())
     */
    public static Iterator<Component> createIterator(Component component) {
        if (!initialized) {
            initialize();
        }

        try {
            return createStream(component).iterator();
        } catch (Exception e) {
            System.err.println("[Luminara] Failed to create Component iterator: " + e.getMessage());
            return List.of(component).iterator();
        }
    }

    /**
     * Bridge method to handle Component iteration
     */
    public static Iterable<Component> asIterable(Component component) {
        return () -> createIterator(component);
    }
    
    /**
     * Bridge method for text component functionality
     */
    public static void handleTextComponent(Component component, ServerPlayer player) {
        if (!initialized) {
            initialize();
        }

        try {
            // Handle text component bridge functionality
            if (player instanceof ServerPlayerEntityBridge bridge) {
                CraftPlayer craftPlayer = bridge.bridge$getBukkitEntity();
                if (craftPlayer != null) {
                    // Process component for Bukkit compatibility
                    processComponentForBukkit(component, craftPlayer);
                }
            }
        } catch (Exception e) {
            System.err.println("[Luminara] Failed to handle text component: " + e.getMessage());
        }
    }
    
    /**
     * Implementation of ITextComponentBridge functionality
     */
    public static class ComponentBridge implements ITextComponentBridge {
        private final Component component;

        public ComponentBridge(Component component) {
            this.component = component;
        }

        @Override
        public Stream<Component> bridge$stream() {
            return createStream(component);
        }

        @Override
        public Iterator<Component> bridge$iterator() {
            return createIterator(component);
        }
    }

    /**
     * Create a bridge instance for a component
     */
    public static ITextComponentBridge createBridge(Component component) {
        return new ComponentBridge(component);
    }

    private static void processComponentForBukkit(Component component, CraftPlayer player) {
        // Implementation for Bukkit component processing
        // This would contain the logic that was previously in ComponentMixin
        // For now, this is a placeholder for future implementation
    }
}
