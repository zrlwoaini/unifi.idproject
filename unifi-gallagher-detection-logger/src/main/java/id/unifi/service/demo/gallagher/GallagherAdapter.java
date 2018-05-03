package id.unifi.service.demo.gallagher;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import static com.codahale.metrics.MetricRegistry.name;
import id.unifi.service.common.detection.DetectableType;
import id.unifi.service.common.detection.DetectionMatch;
import id.unifi.service.provider.security.gallagher.FtcApi;
import id.unifi.service.provider.security.gallagher.IFTMiddleware2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;

public class GallagherAdapter implements IFTMiddleware2 {
    private static final String METRIC_NAME_PREFIX = "id.unifi.service.demo.gallagher";

    private static final Logger log = LoggerFactory.getLogger(GallagherAdapter.class);
    private final Meter detectionsMeter;

    private FtcApi ftcApi;
    private final Thread processingThread;
    private final BlockingQueue<Runnable> detectionQueue;
    private final FtcApiConfig config;

    private volatile CountDownLatch registerLatch;
    private volatile boolean connected;

    public static GallagherAdapter create(MetricRegistry registry, FtcApiConfig config) {
        var adapter = new GallagherAdapter(registry, config);
        adapter.start();
        return adapter;
    }

    private GallagherAdapter(MetricRegistry registry, FtcApiConfig config) {
        this.config = config;
        this.detectionQueue = new SynchronousQueue<>();

        registry.gauge(name(METRIC_NAME_PREFIX, "ftcapi", "health"), () -> () -> connected ? 1 : 0);
        this.detectionsMeter = registry.meter(name(METRIC_NAME_PREFIX, "detections"));

        log.info("Connecting to FTC server {}", config.server());
        this.processingThread = new Thread(() -> {
            while (!Thread.interrupted()) {
                this.ftcApi = new FtcApi(config.server(), config.domain(), config.username(), config.password());
                try {
                    registerLatch = new CountDownLatch(1);
                    log.info("Registering middleware");
                    ftcApi.registerMiddleware(this);
                    registerLatch.await();
                    connected = true;
                    log.info("System registered");
                    Thread.sleep(5000);

                    while (true) {
                        try {
                            detectionQueue.take().run();
                        } catch (Exception e) {
                            log.error("Error processing detection, closing connection", e);
                            break;
                        }
                    }
                    
                    ftcApi.close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                connected = false;
            }
        });
    }

    private void start() {
        processingThread.start();
    }

    public void process(DetectionMatch match, Runnable onSuccess) throws InterruptedException {
        detectionQueue.put(() -> {
            if (match.detection.detectable.detectableType == DetectableType.UHF_TID) {
                logDetection(match);
                detectionsMeter.mark();
            }
            onSuccess.run();
        });
    }

    private void logDetection(DetectionMatch match) {
        var eventId = 0; // corr ID; 0 for none
        var detection = match.detection;
        var detectableId = detection.detectable.detectableId;
        final var hasRestoral = false;

        var cardNumberFormatType = 2;
        var itemId = String.format("%s:%d", detection.readerSn, detection.portNumber);

        log.trace("Logging {} to Gallagher", detection);

        ftcApi.logLongCardEvent2(
                config.eventType(),
                eventId,
                detection.detectionTime,
                hasRestoral,
                cardNumberFormatType,
                detectableId,
                config.facilityCode(),
                config.systemId(),
                itemId,
                String.format("Card %s detected on %s", detectableId, itemId),
                "No details.");
        log.trace("Logged {} to Gallagher", detection);
    }

    public void notifyItemRegistered(String systemId, String itemId, String config) {
        log.info("Item registered: {} / {}", systemId, itemId);
        //registerLatch.countDown();
    }

    public void notifyItemDeregistered(String systemId, String itemId) {
        log.info("Item deregistered: {} / {}", systemId, itemId);
    }

    public void notifySystemRegistered(String systemId, String typeId, String config) {
        log.info("System registered: {} / {} / {}", systemId, typeId, config);
        registerLatch.countDown();
    }

    public void notifySystemDeregistered(String systemId) {
        log.info("System deregistered: {}", systemId);
    }

    public void notifyAlarmAcknowledged(String systemId, int eventId) {
        log.info("Alarm acknowledged: {} / {}", systemId, eventId);
    }
}
