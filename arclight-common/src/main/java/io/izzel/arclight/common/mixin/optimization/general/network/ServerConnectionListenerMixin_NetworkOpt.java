package io.izzel.arclight.common.mixin.optimization.general.network;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Supplier;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/**
 * 实验性网络优化（默认关闭）：
 * 将 Netty 服务端事件循环线程数从 Netty 默认的 2*CPU 调整为配置值，
 * 降低高在线时网络线程成为瓶颈的概率。
 * 由 optimization.experimental-optimizations-enabled + network-optimization.enabled + netty-threads>0 三重门控。
 */
@Mixin(ServerConnectionListener.class)
public class ServerConnectionListenerMixin_NetworkOpt {

    @Redirect(method = "startTcpServerListener",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Supplier;get()Ljava/lang/Object;"))
    private Object luminara$sizeNettyEventLoop(Supplier<?> instance) {
        if (!ArclightConfig.spec().getOptimization().isExperimentalOptimizationsEnabled()) {
            return instance.get();
        }
        var net = ArclightConfig.spec().getOptimization().getNetworkOptimization();
        if (!net.isEnabled()) {
            return instance.get();
        }
        int threads = net.getNettyThreads();
        if (threads <= 0) {
            return instance.get();
        }
        Object grp = instance.get();
        ThreadFactoryBuilder tfb = new ThreadFactoryBuilder().setDaemon(true);
        if (grp instanceof EpollEventLoopGroup) {
            return new EpollEventLoopGroup(threads, tfb.setNameFormat("Luminara Epoll IO #%d").build());
        }
        if (grp instanceof NioEventLoopGroup) {
            return new NioEventLoopGroup(threads, tfb.setNameFormat("Luminara Netty IO #%d").build());
        }
        return grp;
    }
}
