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

/** 实验性网络优化（默认关闭）： */
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
            return new EpollEventLoopGroup(threads, tfb.setNameFormat("PRTS Epoll IO #%d").build());
        }
        if (grp instanceof NioEventLoopGroup) {
            return new NioEventLoopGroup(threads, tfb.setNameFormat("PRTS Netty IO #%d").build());
        }
        return grp;
    }
}
