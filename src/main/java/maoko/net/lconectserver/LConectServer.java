package maoko.net.lconectserver;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import maoko.common.StringUtil;
import maoko.common.conf.ConfException;
import maoko.common.exception.DataIsNullException;
import maoko.common.exception.LoadReflectException;
import maoko.common.log.IWriteLog;
import maoko.common.log.Log4j2Writer;
import maoko.common.model.net.CusHostAndPort;
import maoko.common.tdPool.TdFixedPoolExcCenter;
import maoko.net.conf.Conf;
import maoko.net.conf.CongfigServer;
import maoko.net.exception.ConectClientsFullException;
import maoko.net.exception.ReadbleException;
import maoko.net.ifs.*;
import maoko.net.model.NetByteBuff;
import maoko.net.model.NetEventListener;
import maoko.net.util.NetBuffRealse;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;

/**
 * 网络服务端类
 *
 * @author fanpei
 */
public class LConectServer implements ISvrNet {
    private static final IWriteLog log = new Log4j2Writer(LConectServer.class);
    // private ListenerStore listers = null;

    private IListenerCreator creator = null;
    private CusHostAndPort[] hosts = null;
    private ServerBootstrap svrbootstrap;
    private EventLoopGroup workergroup;
    private EventLoopGroup bossGroup;

    private ServerConMap conStore = null;// 连接仓库
    private CountDownLatch latch = null;
    private TdFixedPoolExcCenter threadSver = null;


    /**
     * 获取网络连接库
     *
     * @return
     */
    public ServerConMap getConStore() {
        return conStore;
    }


    /**
     * 网络服务端【初始化并注册】
     *
     * @param hosts ip数组
     * @throws ConfException
     * @throws IOException
     * @throws LoadReflectException
     * @throws DataIsNullException
     */
    public LConectServer(CusHostAndPort[] hosts, IListenerCreator creator) throws Exception {
        //listers = ListenerStore.initListeners(callbacks);
        this.hosts = hosts;
        if (creator == null)
            throw new DataIsNullException("the creator is null");
        this.creator = creator;

        CongfigServer.init();
        CongfigServer.HOSTS= Arrays.asList(hosts);
        Conf.nettySetting(Conf.BUFFCHECKLEVEL);
        this.conStore = new ServerConMap();

        // int processorsNumber = Runtime.getRuntime().availableProcessors();
        this.svrbootstrap = new ServerBootstrap();// 引导辅助程序
        this.bossGroup = new NioEventLoopGroup(CongfigServer.PARENTGROUPTDCOUNT,
                new DefaultThreadFactory("server1", true));
        this.workergroup = new NioEventLoopGroup(CongfigServer.CHILDGROUPTDCOUNT,
                new DefaultThreadFactory("Netty-Worker", true));
        // this.bossGroup = new NioEventLoopGroup(1);
        // ThreadFactory boosstf = new DefaultThreadFactory("Netty-Worker");
        // this.workergroup = new NioEventLoopGroup(processorsNumber, Executors.,
        // SelectorProvider.provider());
        this.svrbootstrap.group(bossGroup, workergroup);

        // 设置nio类型的channel
        this.svrbootstrap.channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 2048);
        this.svrbootstrap.option(ChannelOption.SO_RCVBUF, 1024 * 32).option(ChannelOption.SO_SNDBUF, 1024 * 32)
                .option(ChannelOption.TCP_NODELAY, false).option(ChannelOption.ALLOW_HALF_CLOSURE, true)// 半关闭
                // 设置立即发送;

                // .option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 512 *
                // 1024).option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 384 *
                // 1024)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10 * 1000); // 最大空闲连接时间

        this.svrbootstrap.childHandler(new ChannelInitializer<NioSocketChannel>() {// 有连接到达时会创建一个channel
            @Override
            protected void initChannel(NioSocketChannel ch) throws Exception {
                if (closed) {// 关闭不接受新的连接
                    ch.close();
                    return;
                }
                //Class<NetEventListener> clazz = listers.getListenerClass(ch.localAddress().toString().replaceAll("/", ""));
                // NetEventListener listener = listers.getListener(ch.localAddress().toString().replaceAll("/", ""));
                NetEventListener listener = creator.getListener(ch);
                LConectServerHandler shEchoServerHandler = new LConectServerHandler(listener);
                if (CongfigServer.CHANLEDATARECVINTERVAL > 0)
                    ch.pipeline().addLast(new ReadTimeoutHandler(CongfigServer.CHANLEDATARECVINTERVAL));// 未收到数据间隔断开
                ch.pipeline().addLast(READHANDLE, shEchoServerHandler);

            }
        });
    }

    private static final String READHANDLE = "MyServerHandler";


    @Override
    public void start() throws Exception {
        try {
            int ipCount = hosts.length;
            latch = new CountDownLatch(ipCount);
            threadSver = new TdFixedPoolExcCenter(ipCount);
            for (int i = 0; i < ipCount; i++) {
                CusHostAndPort address = hosts[i];
                PortInstance pi = new PortInstance(latch, address.getIP(), address.getPort());
                threadSver.execute(pi);
            }
            latch.await();
            throw new Exception("network closed");
        } catch (Exception e) {
            if (!closed)
                throw e;
        } finally {
            close();
            if (threadSver != null)
                threadSver.shutdownNow();
        }

    }

    private boolean closed = false;// 服务端是否已关闭

    @Override
    public void close() {
        closed = true;
        // 关闭所有连接
        conStore.closeAllConnect();

        // 关闭每个通道
        if (bossGroup != null)
            bossGroup.shutdownGracefully();
        if (workergroup != null)
            workergroup.shutdownGracefully();// 关闭EventLoopGroup，释放掉所有资源包括创建的线程

    }

    @Override
    public void sendDataToAllClient(IBytesBuild data) throws Exception {
        validate(data);
        Collection<INetChanel> channels = conStore.getAllChanels();
        if (channels != null && !channels.isEmpty()) {
            for (INetChanel ch : channels) {
                ch.sendData(data);
            }
        }
    }

    @Override
    public void sendDataToAllClient(int localPort, IBytesBuild data) throws Exception {
        validate(data);
        Collection<INetChanel> channels = conStore.getAllChanels(localPort);
        if (channels != null && !channels.isEmpty()) {
            for (INetChanel ch : channels) {
                ch.sendData(data);
            }
        }
    }

    @Override
    public void sendDataToAllClient(Collection<INetChanel> channels, IBytesBuild data) throws Exception {
        validate(data);
        if (channels != null && !channels.isEmpty()) {
            for (INetChanel ch : channels) {
                ch.sendData(data);
            }
        }
    }

    @Override
    public int getAllConnectNum(int localPort) {
        return conStore.getConnectNum(localPort);
    }

    @Override
    public int getAllConnectNum() {
        return conStore.getAllConectNum();
    }

    private static void validate(IBytesBuild data) throws Exception {
        if (data == null) {
            throw new Exception("待发送数据为空，请检查数据完整性");
        }
    }

    /**
     * Sharable表示此对象在channel间共享 handler类是我们的具体业务类
     */
    @Sharable
    // 注解@Sharable可以让它在channels间共享
    public class LConectServerHandler extends ChannelInboundHandlerAdapter {

        private NetEventListener listener;

        public LConectServerHandler(NetEventListener listener) {
            this.listener = listener;
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            closeConnect(ctx);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);

            boolean error = false;

            try {
                if (conStore.getAllConectNum() >= CongfigServer.MAXCLIENTS) {
                    throw new ConectClientsFullException();
                }
                log.info("client has conected success:{}", listener.getNetSource().getRIpPort());
                conStore.addChannel(listener.getNetSource());
                listener.chanelConect();
            } catch (Exception e) {
                error = true;
                log.error("连接初始化异常", e);
            } finally {
                if (error) {
                    listener.release();
                }
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object obj) {

            ByteBuf in = null;
            try {
                if (obj instanceof ByteBuf) {
                    in = (ByteBuf) obj;
                    if (in.isReadable()) {
                        IByteBuff inbuff = new NetByteBuff(in);
                        listener.decode(inbuff);
                    } else
                        throw new ReadbleException(listener.getNetSource().getRIpPort() + " can not readble");
                }
            } catch (Exception e2) {
                log.error("read data exception", e2);
            } finally {
                NetBuffRealse.realse(in);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("server network exception {}", ctx.channel().remoteAddress().toString(), cause);
            closeConnect(ctx);
        }

        private boolean closed = false;

        private void closeConnect(ChannelHandlerContext ctx) {
            try {
                if (!closed) {
                    closed = true;
                    log.info("client has disconected:{}", listener.getNetSource().getRIpPort());
                    conStore.removeChannel(listener.getNetSource());
                    listener.closeEvent();
                }

            } catch (Exception e) {
                log.error("close channel exception {}", ctx.channel().remoteAddress().toString(), e);
            } finally {
                listener.release();// 出现异常时关闭channel
            }
        }
    }

    /**
     * 端口实例
     *
     * @author fanpei
     */
    class PortInstance implements Runnable {

        private CountDownLatch latch;
        private String ip;
        private int port;
        private ChannelFuture f;

        public PortInstance(CountDownLatch latch, String ip, int port) {
            this.latch = latch;
            this.ip = ip;
            this.port = port;
        }

        @Override
        public void run() {
            String ip_port = null;
            try {
                ip_port = StringUtil.getMsgStr("{}:{}", ip, port);
                Thread td = Thread.currentThread();
                td.setName(StringUtil.getMsgStr("网络监听 [{}]", ip_port));

                log.info("server Attemping to listenning on {}", ip_port);
                f = svrbootstrap.bind(ip, port).sync();// 配置完成，开始绑定server，通过调用sync同步方法阻塞直到绑定成功
                log.info("server started and listen on {}", ip_port);
                f.channel().closeFuture().sync();
            } catch (Exception e) {
                log.warn("server will close the: {}", ip_port, e);
            } finally {
                latch.countDown();
            }

        }
    }

}
