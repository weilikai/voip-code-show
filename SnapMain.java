import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

public class SnapMain {

    static native byte[] doAEC(byte[] bufferFromRecorder, byte[] bufferFromPlayer);

    public static void main(String[] args) throws IOException {
        // 通话中远端传来的对方的音频流, 该流的实现需要结合具体的传输协议, 分两种情况:
        // TCP: 协议本省保证了包的顺序, 虽然无法避免 "卡顿", 但绝不会导致数据块丢失。
        //      故, remoteInput可以直接从Socket中get到, 无效二次封装
        // UDP: 协议会出现: 包乱序、包缺失等多种可能, 若以该协议为基础的传输协议,
        //      故, remoteInput需要是根据协议特点进行封装, 达到类似TCP传输的效果,
        // 针对UDP协议的逻辑, 需要对没个收到数据块儿都打上时间段标签, 丢掉的数据段在InputStream数据源中应当体现为对应长度的0数据
        final InputStream remoteInput = null;

        // 将从远端传来的音频数据流交给播放的过滤流, 2个功能
        // 1. 播放音频, 有多少播多少, 传来多少播放多少
        // 2. 将因卡顿造成未能播放的音频"打平"
        final InputStream playInput = new PlayerInputStream(remoteInput);

        // 通话的当前客户端的录音机中获得的音频流, android平台可以通过封装AudioRecoder进行封装,使其stream化
        final InputStream localInput = new AudioInputStream(/* audioRecoder */);


        final DataInputStream filterPlayInput = new DataInputStream(playInput);
        final DataInputStream filterLocalInput = new DataInputStream(localInput);

        final byte[] bufferFromRecorder = new byte[PlayerInputStream.BLOCK_SIZE];
        final byte[] bufferFromPlayer = new byte[PlayerInputStream.BLOCK_SIZE];

        while (true) { // 开始AEC逻辑
            filterPlayInput.readFully(bufferFromRecorder);
            filterLocalInput.readFully(bufferFromPlayer);
            byte[] afterAec = doAEC(bufferFromRecorder, bufferFromPlayer); //
            // 将afterAec写入到远程数据流中
        }
    }


    /**
     * 将本地录音机封装为InputStream
     */
    public static class AudioInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            throw new IOException("不支持单字节读取");
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return super.read(b, off, len); // TODO 从系统的录音机中阻塞方式从录音机采样并拷贝的数据源中
        }
    }
}
