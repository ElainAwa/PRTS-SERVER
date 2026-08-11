package io.izzel.arclight.common.mixin.optimization.general.network;

import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 网络优化：可配置 Netty 事件循环线程数（默认关闭）。 */
@Mixin(ServerConnectionListener.class)
public class ServerConnectionListenerMixin_NetworkOpt {

    @Redirect(method = "startTcpServerListener",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Supplier;get()Ljava/lang/Object;"))
    private Object prts$sizeNettyEventLoop(Supplier<?> instance) {
        var opt = ArclightConfig.spec().getOptimization();
        if (!opt.isNetworkOptimizationEnabled()) {
            return instance.get();
        }
        int threads = opt.getNetworkOptimizationNettyThreads();
        if (threads <= 0) {
            return instance.get();
        }
        Object grp = instance.get();
        if (grp instanceof EpollEventLoopGroup) {
            return new EpollEventLoopGroup(threads, daemonFactory("PRTS Epoll IO"));
        }
        if (grp instanceof NioEventLoopGroup) {
            return new NioEventLoopGroup(threads, daemonFactory("PRTS Netty IO"));
        }
        return grp;
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + " #" + count.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }
}
