import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <pre>
 * 本类是一个具有filter功能的InputStream，为了逻辑简单本类的实现细节均基于16k采样、16位精度、单通道原始音频设计
 *
 * 对外表现为
 * - 输入：外部传来的带播放的数据源
 * - 输出：被真正播放过的数据
 *
 * 内部实现, 生产者、消费者模型：
 * - 生产者：从外部传来的数据源拷贝数据到循环buffer，供消费者消费
 * - 消费者：从循环buffer中按照特定逻辑去播放，同时将消费后的数据作为对外输出
 *
 * 主要代码
 * @see PlayerInputStream#producerRun()
 * @see PlayerInputStream#consumerRun()
 * @see PlayerInputStream#read(byte[], int, int)
 *
 * </pre>
 */
public class PlayerInputStream extends InputStream {
    public static final int BLOCK_SIZE = 320; // 320换算成16k采样、16位精度、单通道原始音频为10ms, 可以通过调整该块儿的大小适应AEC算法接口的特性

    private final ScheduledExecutorService mProducerThread = Executors.newScheduledThreadPool(1);
    private final ScheduledExecutorService mConsumerTimer = Executors.newScheduledThreadPool(1);
    private final ScheduledExecutorService mPlayerIOThread = Executors.newScheduledThreadPool(1);
    private final DataInputStream mAudioFromRemote; // 外部数据源

    /**
     * 循环buffer，本类的实现保证了对buffer操作均按固定大小的块儿操作，故无需考虑对齐问题
     */
    private final byte[] mSharedRingBuffer = new byte[BLOCK_SIZE * ((BLOCK_SIZE / 32) * 1000 * 15)]; // 声明一个15s大小的循环buffer

    /**
     * 生产者指针，记录从原始数据源中拷贝的数据位置
     */
    private int mProducerPointer;
    /**
     * 消费者指针，记录被播放的音频位置
     */
    private int mConsumerPointer;
    /**
     * 当前的类是InputStream子类，该指针记录该流被读到的位置
     */
    private int mCurInputPointer;

    public PlayerInputStream(InputStream remoteInputStream) {
        mAudioFromRemote = new DataInputStream(remoteInputStream);

        producerRun();

        consumerRun();
    }

    /**
     * <pre>
     * 生产者，通过一个独立的线程，原始的播放源拷贝音频至播放缓冲区
     *
     * 由于可能的卡顿，分两种方法进行生产，如果读取到的音频数据所覆盖的时间段落
     * 1. 未发生（对应未卡顿）
     *          则，复制这块音频到播放缓冲区
     * 2. 已发生（对应卡顿过）
     *          则，复制等长度的0数据到播放缓冲区
     *
     * 总结特性为，卡顿时，生产者会晚点干活（被IO的读阻塞了时间），但不会偷懒（即不拷贝数据）
     * </pre>
     */
    private void producerRun() {
        mProducerThread.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                byte[] buf = new byte[BLOCK_SIZE];
                while (true) {
                    try {
                        mAudioFromRemote.readFully(buf);
                        if (mProducerPointer < mConsumerPointer) { // 卡顿致使消费者提前消费，则生产者用0数据填写被提前消费的"坑"
                            Arrays.fill(mSharedRingBuffer, (mProducerPointer % mSharedRingBuffer.length), buf.length, (byte) 0);
                        } else { // 先生产，后消费，正常生产，将目标音频写入循环buffer用于播放
                            System.arraycopy(buf, 0, mSharedRingBuffer, (int) (mProducerPointer % mSharedRingBuffer.length), buf.length);
                        }
                        mProducerPointer += BLOCK_SIZE;
                    } catch (EOFException e) {
                        break; // 发生EOF异常意味着流被读完了
                    }
                }
                return null;
            }
        });
    }

    /**
     * <pre>
     * 消费者，通过单线程的定时器，定时将播放缓冲区中的音频写入播放器
     *
     * 该代码段中，定时器的准确性是关键
     *
     * 这段代码直接借住jdk的单线程线程池，构造固定时间触发的scheduleAtFixedRate定时器
     *
     * 保证任务的执行时间是准时的，准时意味着要写入真正的播放器的数据：
     * 1. 既不会写的早于计划的时间
     * 2. 也不会过了计划的时间再写
     *
     * 总结特性为，消费者不会再计划的时间点提前消费，也不会滞后消费
     * </pre>
     */
    private void consumerRun() {
        // 作为消费者，
        // - 有产品我消费，即对应时段已经被生产者写入了要播放的数据
        // - 没产品也消费，即对应时段生产者没能即使写入数据，那就播放buffer中的0呗，反正不能停
        mConsumerTimer.scheduleAtFixedRate(new Runnable() { // scheduleAtFixedRate 定时器与时间相隔，与运行效率无关（需要保证运行过程花费的时间短于周期）
            @Override
            public void run() {
                // 为了避免类似AudioTrack播放器的同步IO阻塞，nonblockingPlay方法会将任务转换到另一个线程执行
                nonblockingPlay(mSharedRingBuffer, (mProducerPointer % mSharedRingBuffer.length), BLOCK_SIZE);
                mConsumerPointer += BLOCK_SIZE;
            }
        }, 0, BLOCK_SIZE / 32, TimeUnit.MILLISECONDS);
    }

    /**
     * 在后台线程执行播放的IO写操作
     */
    private void nonblockingPlay(final byte[] audio, final int offset, final int len) {
        mPlayerIOThread.submit(new Runnable() {
            @Override
            public void run() {
                // audioTrack.write(audio, offset, len); // TODO 调用真正的播放逻辑
            }
        });
    }

    @Override
    public int read() throws IOException {
        throw new IOException("不支持单字节读取"); // 单字节读取性能异常底下，不支持
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (mCurInputPointer + len >= mConsumerPointer) {
            return 0; // 不允许读取尚未被播放的数据，因为被类的任务就是读"被真正播放"的音频
        } else {
            System.arraycopy(mSharedRingBuffer, mConsumerPointer, buf, off, len);
            mCurInputPointer += len;
            return len;
        }
    }

    @Override
    public void close() throws IOException {
        mProducerThread.shutdown();
        mConsumerTimer.shutdown();
        mConsumerTimer.shutdown();
        mAudioFromRemote.close();
    }
}