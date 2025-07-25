package io.izzel.arclight.common.bridge.network.chat;

import com.google.common.collect.Streams;
import io.izzel.arclight.common.bridge.core.util.text.ITextComponentBridge;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Stream;


public class ComponentBridgeHandler {
    
    private static final ConcurrentMap<Class<?>, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;
    
    // Initialize the bridge handler after all classes are loaded
    public static void initialize() {
        if (initialized) return;
        
        try {
            Class<?> componentClass = Component.class;

            // Try to find getSiblings method with different possible names
            Method getSiblingsMethod = null;
            String[] possibleNames = {"getSiblings", "m_7220_", "m_130940_", "siblings"};

            for (String methodName : possibleNames) {
                try {
                    getSiblingsMethod = componentClass.getDeclaredMethod(methodName);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // Try next name
                }
            }

            // If still not found, try to find method by return type
            if (getSiblingsMethod == null) {
                for (Method method : componentClass.getDeclaredMethods()) {
                    if (method.getReturnType().equals(List.class) && method.getParameterCount() == 0) {
                        getSiblingsMethod = method;
                        break;
                    }
                }
            }

            if (getSiblingsMethod != null) {
                METHOD_CACHE.put(componentClass, getSiblingsMethod);
                initialized = true;
            } else {
                System.err.println("[Luminara] Could not find getSiblings method in Component class");
            }
        } catch (Exception e) {
            System.err.println("[Luminara] Failed to initialize ComponentBridgeHandler: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // Get siblings from a Component using cached reflection
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
    
    //  Create a stream of components (replaces ComponentMixin.stream())
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

    // reate an iterator for components (replaces ComponentMixin.iterator())
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

    // Bridge method to handle Component iteration
    public static Iterable<Component> asIterable(Component component) {
        return () -> createIterator(component);
    }
    

    
    // Implementation of ITextComponentBridge functionality
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

    // Create a bridge instance for a component
    public static ITextComponentBridge createBridge(Component component) {
        return new ComponentBridge(component);
    }


}
