import Response.ResponseManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class JobServer {
    private Selector selector;
    private ServerSocketChannel serverSocketChannel;
    private int port;
    private int minBuffer;
    private ResponseManager responseManager;
    private ExecutorService executorService;
    private boolean state;
    private Logger logger;

    public JobServer(int port, int minBuffer, int numThreads, ResponseManager responseManager) {
        this.port = port;
        this.minBuffer = minBuffer;
        this.responseManager = responseManager;
        this.executorService = Executors.newFixedThreadPool(numThreads);
        this.logger = LogManager.getLogger();
    }

    public void startServer() {
        InetSocketAddress inetSocketAddress;
        this.state = true;

        try {
            inetSocketAddress = new InetSocketAddress("localhost", this.port);

            this.selector = Selector.open();
            this.serverSocketChannel = ServerSocketChannel.open();
            this.serverSocketChannel.bind(inetSocketAddress);
            this.serverSocketChannel.configureBlocking(false);
            this.serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);

            while (this.state) {
                this.selector.select();
                Set<SelectionKey> selectedKeys;
                Iterator<SelectionKey> iter;

                selectedKeys = this.selector.selectedKeys();
                iter = selectedKeys.iterator();

                while (iter.hasNext()) {
                    SelectionKey selectionKey = iter.next();

                    if (!selectionKey.isValid()) {
                        continue;
                    }

                    if (selectionKey.isAcceptable()) {
                        this.registerClient();
                    }

                    else if (selectionKey.isReadable()) {
                        // https://community.oracle.com/thread/1146606?start=15&tstart=0
                        selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_READ);
                        this.readAndRespond(selectionKey);
                    }

                    iter.remove();
                }
            }

            this.serverSocketChannel.close();
            this.executorService.shutdownNow();
        }
        catch (IOException e) {
            this.logger.warn(String.format("Unknown failure %s", e.getMessage()));
            System.exit(0);
        }
    }

    public void stopServer() {
        this.state = false;
    }

    private void registerClient(){
        SocketChannel socketChannel;

        try {
            socketChannel = this.serverSocketChannel.accept();
            socketChannel.configureBlocking(false);
            socketChannel.register(this.selector, SelectionKey.OP_READ);
        }
        catch (IOException e) {
            this.logger.error(String.format("Unable to register connection", e.getMessage()));
        }
    }

    private void readAndRespond(SelectionKey selectionKey) {
        this.executorService.submit(new Worker(selectionKey));
    }

    private class Worker implements Runnable {
        private SelectionKey selectionKey;

        private Worker(SelectionKey selectionKey) {
            this.selectionKey = selectionKey;
        }

        @Override
        public void run() {
            ArrayList<ByteBuffer> buffers;
            String data, reply;
            ByteBuffer responseBuffer;
            SocketChannel socketChannel;

            socketChannel = (SocketChannel) this.selectionKey.channel();

            try {
                buffers = this.readRequest(socketChannel);
                data = this.extractData(buffers);

                logger.info(String.format("Data received %s from %s", data,
                        socketChannel.socket().getInetAddress().getHostAddress()));

                if (!data.isEmpty()) {
                    reply = responseManager.reply(data);
                    responseBuffer = ByteBuffer.wrap(reply.getBytes());
                    socketChannel.write(responseBuffer);
                }
            }
            catch (ClosedByInterruptException e) {
                logger.info(String.format("Connection closed %s",
                        socketChannel.socket().getInetAddress().getHostAddress()));
            }
            catch (IOException e) {
                e.printStackTrace();
                logger.warn(String.format("Unable to process response %s", e.getMessage()));
            }

            this.selectionKey.interestOps(this.selectionKey.interestOps() | SelectionKey.OP_READ);
            this.selectionKey.selector().wakeup();
        }

        private ArrayList<ByteBuffer> readRequest(SocketChannel socketChannel) throws IOException {
            int counter;
            ByteBuffer current;
            ArrayList<ByteBuffer> buffers;

            counter = 2;
            buffers = new ArrayList<>();
            current = ByteBuffer.allocate(minBuffer);
            buffers.add(current);

            while (socketChannel.read(current) > 0) {
                if (!current.hasRemaining()) {
                    current = ByteBuffer.allocate(minBuffer * 2 * counter);
                    buffers.add(current);
                    counter++;
                }
            }

            return buffers;
        }

        private String extractData(ArrayList<ByteBuffer> buffers) throws UnsupportedEncodingException {
            StringBuilder stringBuilder;

            stringBuilder = new StringBuilder();
            for(ByteBuffer buffer : buffers) {
                stringBuilder.append(new String(buffer.array(), "UTF-8"));
            }

            return stringBuilder.toString().trim();
        }
    }
}