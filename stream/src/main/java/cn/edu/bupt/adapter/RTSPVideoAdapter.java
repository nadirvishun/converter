package cn.edu.bupt.adapter;

import cn.edu.bupt.tasks.CaptureTask;
import cn.edu.bupt.tasks.UnrefTask;
import cn.edu.bupt.event.Event;
import cn.edu.bupt.event.GrabEvent;
import cn.edu.bupt.event.PacketEvent;
import cn.edu.bupt.listener.Listener;
import cn.edu.bupt.listener.RecordListener;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import cn.edu.bupt.util.Constants;
import cn.edu.bupt.util.DirUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public class RTSPVideoAdapter extends VideoAdapter {

    private static long timestamp;
    private final String dataLocation;
    private final String capturesPath;
    private final String videoPath;
    private boolean recording;
    private boolean pushing;
    private boolean stop;
    private FFmpegFrameGrabber grabber;
    private String rTSPPath;
    private String rTMPPath;
    private boolean save;
    private AtomicBoolean capture = new AtomicBoolean(false);
    private Long lastFrameTime = System.currentTimeMillis();
    private AtomicBoolean cv = new AtomicBoolean(false);
    private String result = "";
    private boolean usePacket;
    private static ExecutorService executor = Executors.newSingleThreadExecutor(new BasicThreadFactory.Builder().namingPattern("Rtsp-pool-%d").daemon(false).build());
    // 用于记录每一个frame需要完成的listeners个数，在全部listener完成任务后，调用PointerScope进行内存回收
    private Map<Event, AtomicInteger> frameFinishCount = new HashMap<>();
    private Future<Boolean> captureFuture;
    private final int NULL_FRAME_THRESHOLD = 10;
    private int nullFrames = 0;

    public RTSPVideoAdapter(String rTSPPath, String rTMPPath, VideoAdapterManagement<RTSPVideoAdapter> videoAdapterManagement, boolean save, boolean usePacket) {
        super(rTMPPath, videoAdapterManagement);
        this.rTSPPath = rTSPPath;
        this.rTMPPath = rTMPPath;
        this.save = save;
        this.usePacket = usePacket;
        recording = false;
        stop = false;
        dataLocation = Constants.getRootDir() + rTMPPath.substring(rTMPPath.lastIndexOf("/") + 1) + "/";
        capturesPath = dataLocation + "captures/";
        videoPath = dataLocation + "videos/";
        timestamp = DirUtil.getZeroTimestamp();
        // 设置日志打印等级
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        DirUtil.judgeDirExists(dataLocation);
        DirUtil.judgeDirExists(videoPath);
        DirUtil.judgeDirExists(capturesPath);
    }

    @Override
    public void start() throws Exception {
        log.info("RTSPVideoAdapter is starting, rtsp:[{}], rtmp [{}]", rTSPPath, rTMPPath);
        grabberInit();
        log.info("Grabber started, rtsp:[{}]", rTSPPath);
        startAllListeners();
        int count = 0;
        try {
            while (!stop) {
                //记录帧数
                count++;
                if (count % 100 == 0) {
                    log.debug("Video[{}] counts={}", rTSPPath, count);
                }
                //时间超过零点进行视频录像的切分
                if (recording && timestamp < DirUtil.getZeroTimestamp()) {
                    executor.submit(() -> {
                        timestamp = DirUtil.getZeroTimestamp();
                        restartRecording(videoPath + DirUtil.generateFilenameByDate() + ".flv");
                    });
                }

                if (usePacket) {
                    //使用AVPacket进行推流，目前这种模式下不能对数据帧进行处理
                    handleUsePacket();
                } else {
                    //使用传统方式进行处理，效率较低（增加了编解码的时间），但是可以对画面frame进行处理
                    handleGrab();
                }
            }
        } catch (Exception e) {
            log.warn("Adapter [{}] throws an Exception, e", getName(), e);
        } finally {
            closeAllListeners();
            grabber.stop();
            videoAdapterManagement.stopAdapter(this);
            log.info("Grabber ends for video rtmp:{}", rTMPPath);
        }
    }

    private void handleGrab() throws Exception {
        Frame frame = null;
        try {
            frame = grabber.grabImage();
            lastFrameTime = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("Grab Image Exception!");
        }
        if (frame == null || frame.image == null) {
            nullFrames++;
            if (nullFrames % 5 == 0) {
                log.info("Null Frame number is [{}] and rtmp : [{}]", nullFrames, rTMPPath);
            }
            if (nullFrames >= NULL_FRAME_THRESHOLD) {
                stop();
                log.info("Video[{}] lost!", rTMPPath);
            }
            return;
        }

        // PointScope用于释放frame的内存
        // Pointer会自动attach到PointerScope上。
        // 同样，clone获得的frame需要进行unref并且释放内存
        PointerScope pointerScope = new PointerScope();
        Frame newFrame = frame.clone();

        //进行抓拍操作
        if (capture.get() && newFrame != null) {
            captureFuture = executor.submit(new CaptureTask(newFrame, capturesPath));
            capture.set(false);
        }

        GrabEvent grabEvent = new GrabEvent(this, newFrame, pointerScope, grabber.getTimestamp());
        frameFinishCount.put(grabEvent, new AtomicInteger(listeners.size()));
        for (Listener listener : listeners) {
            listener.fireAfterEventInvoked(grabEvent);
        }
    }

    private void handleUsePacket() throws Exception {
        AVPacket pkt = null;
        try {
            pkt = grabber.grabPacket();
            lastFrameTime = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("Grab Packet Exception, e:", e);
        }

        // 检查是否接收到数据
        if (pkt == null || pkt.size() <= 0 || pkt.data() == null) {
            nullFrames++;
            if (nullFrames % 50 == 0) {
                log.info("Null Frame number is [{}], rtmp: [{}]", nullFrames, rTMPPath);
            }
            //连续帧都是null时判断已经停止推流
            if (nullFrames >= NULL_FRAME_THRESHOLD) {
                stop();
                log.info("Video[{}] stopped!", rTMPPath);
            }
        } else {
            nullFrames = 0;
        }

        PacketEvent.CountEvent countEvent = new PacketEvent.CountEvent();
        frameFinishCount.put(countEvent, new AtomicInteger(listeners.size()));

        //AVPacket采用计数法进行内存的回收，因此在每一个listener进行处理时，
        //都需要创建一个新的ref。由于JavaCV中的方法自带unref，如果没有创建
        //ref，一个listener处理完后就有可能回收内存。为了保险起见，自己实现了一个
        //Unref的逻辑
        for (Listener listener : listeners) {
            AVPacket newPkt = avcodec.av_packet_alloc();
            avcodec.av_packet_ref(newPkt, pkt);
            PacketEvent grabEvent = new PacketEvent(this, newPkt, countEvent);
            listener.fireAfterEventInvoked(grabEvent);
        }
        avcodec.av_packet_unref(pkt);
    }

    @Override
    public void stop() {
        stop = true;
    }

    public boolean capture() {
        if (capture.compareAndSet(false, true)) {
            try {
                return captureFuture.get(5000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                e.printStackTrace();
                log.info("Capture failed!");
                return false;
            }
        } else {
            return false;
        }
    }

    private void startAllListeners() {
        log.info("Start all listeners");
        for (Listener listener : listeners) {
            listener.start();
        }
    }

    private void closeAllListeners() {
        log.info("Close all listeners");
        for (Listener listener : listeners) {
            listener.close();
        }
        listeners.clear();
    }

    private void grabberInit() {
        try {
            // 使用rtsp的时候需要使用 FFmpegFrameGrabber，不能再用FrameGrabber
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(rTSPPath);
            grabber.setOption("rtsp_transport", "tcp");
            this.grabber = grabber;
            this.grabber.start();

            // TODO:设置回调函数后,虚拟机会崩溃

//            // 设置超时,每一次grab的timeout为1000ms
//            AVIOInterruptCB.Callback_Pointer cp = new AVIOInterruptCB.Callback_Pointer() {
//                @Override
//                public int call(Pointer pointer) {
//                    if(System.currentTimeMillis()-lastFrameTime>1000){
//                        return 1;
//                    }else{
//                        return 0;
//                    }
//                }
//            };
//            AVFormatContext oc = grabber.getFormatContext();
//            avformat_alloc_context();
//            AVIOInterruptCB cb = new AVIOInterruptCB();
//            cb.callback(cp);
//            // 设置回调函数
//            oc.interrupt_callback(cb);

        } catch (Exception e) {
            log.error("Grabber failed to initialize");
            e.printStackTrace();
        }
    }

    public void restartRecording(String filename) {
        log.info("Restart recording. New filename is [{}]", filename);
        stopRecording();
        startRecording(filename);
    }

    private void startRecording(String filename) {
        if (recording) {
            log.warn("Video recording has already been started.");
        } else {
            RecordListener recordListener = new RecordListener(filename, getGrabber(), this, usePacket);
            addListener(recordListener);
            recordListener.start();
            recording = true;
        }
    }

    public void startRecording() {
        if (recording) {
            log.warn("Video recording has already been started.");
        } else {
            String videoPath = dataLocation + "videos/";
            RecordListener recordListener = new RecordListener(videoPath + DirUtil.generateFilenameByDate() + ".flv", getGrabber(), this, usePacket);
            recordListener.start();
            addListener(recordListener);
            recording = true;
        }
    }

    public void stopRecording() {
        if (!recording) {
            log.warn("Can not stop recording cause recording has not been started.");
        } else {
            removeListener(RecordListener.class);
            recording = false;
        }
    }

    public void unref(Event event, boolean isSuccess) {
        executor.submit(new UnrefTask(frameFinishCount, event, isSuccess));
    }

    public void send(String result) {
        this.result = result;
        cv.compareAndSet(false, true);
    }
}

