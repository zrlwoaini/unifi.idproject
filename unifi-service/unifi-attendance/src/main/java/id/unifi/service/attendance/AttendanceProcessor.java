package id.unifi.service.attendance;

import static id.unifi.service.attendance.db.Attendance.ATTENDANCE;
import static id.unifi.service.attendance.db.Keys.ATTENDANCE_PKEY;
import static id.unifi.service.attendance.db.Keys.BLOCK_ZONE__FK_BLOCK_ZONE_TO_BLOCK;
import static id.unifi.service.attendance.db.Keys.PROCESSING_STATE_PKEY;
import id.unifi.service.attendance.db.Tables;
import static id.unifi.service.attendance.db.Tables.ATTENDANCE_;
import static id.unifi.service.attendance.db.Tables.BLOCK;
import static id.unifi.service.attendance.db.Tables.BLOCK_TIME;
import static id.unifi.service.attendance.db.Tables.BLOCK_ZONE;
import static id.unifi.service.attendance.db.Tables.PROCESSING_STATE;
import id.unifi.service.common.db.Database;
import id.unifi.service.common.db.DatabaseProvider;
import id.unifi.service.common.detection.AntennaKey;
import id.unifi.service.common.detection.ClientDetectable;
import id.unifi.service.common.detection.DetectableType;
import id.unifi.service.common.detection.Detection;
import static id.unifi.service.common.util.TimeUtils.instantFromUtcLocal;
import static id.unifi.service.common.util.TimeUtils.utcLocalFromInstant;
import static id.unifi.service.core.db.Core.CORE;
import static id.unifi.service.core.db.Keys.*;
import static id.unifi.service.core.db.Tables.*;
import static java.util.Comparator.naturalOrder;
import static java.util.stream.Collectors.*;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record4;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Stream;

public class AttendanceProcessor {
    private static final Logger log = LoggerFactory.getLogger(AttendanceProcessor.class);

    public static final Duration DETECTION_BEFORE_BLOCK_START = Duration.ofMinutes(15);
    public static final Duration DETECTION_AFTER_BLOCK_END = Duration.ofMinutes(15);
    private static final Duration ASSIGNMENT_REFRESH_RATE = Duration.ofMinutes(1);

    private final BlockingQueue<Detection> processingQueue;
    private final Thread processingThread;
    private final Database db;
    private Map<ClientDetectable, String> detectableHolders;
    private Map<AntennaKey, ZoneKey> antennaZones;
    private Set<AssignmentKey> contactSchedules;
    private Map<ZoneKey, ZoneBlocks> zoneBlocks;
    private long lastRefreshMillis;

    private static final Query insertAttendanceQuery = DSL.insertInto(ATTENDANCE_,
            ATTENDANCE_.CLIENT_ID, ATTENDANCE_.CLIENT_REFERENCE, ATTENDANCE_.SCHEDULE_ID, ATTENDANCE_.BLOCK_ID)
            .values((String) null, null, null, null)
            .onConflict(ATTENDANCE_PKEY.getFieldsArray()).doNothing();
    private static final Query processingStateInsert = DSL.insertInto(PROCESSING_STATE,
            PROCESSING_STATE.CLIENT_ID, PROCESSING_STATE.READER_SN, PROCESSING_STATE.PORT_NUMBER, PROCESSING_STATE.PROCESSED_UP_TO)
            .values((String) null, null, null, null)
            .onConflict(PROCESSING_STATE_PKEY.getFieldsArray())
            .doUpdate()
            .set(PROCESSING_STATE.PROCESSED_UP_TO, (LocalDateTime) null)
            .where(PROCESSING_STATE.PROCESSED_UP_TO.lt((LocalDateTime) null));

    public AttendanceProcessor(DatabaseProvider dbProvider) {
        this.db = dbProvider.bySchema(CORE, ATTENDANCE);
        this.processingQueue = new ArrayBlockingQueue<>(100_000);
        this.processingThread = new Thread(this::process, "attendance-processor");
        processingThread.start();
    }

    private void process() {
        List<Detection> detections = fetchDetectionsFromDatabase(); // TODO: in chunks
        processAttendance(detections);
        while (true) {
            if (processingQueue.size() < 200) {
                if (System.currentTimeMillis() >= lastRefreshMillis + ASSIGNMENT_REFRESH_RATE.toMillis()) {
                    db.execute(this::refreshAssignments);
                } else {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (processingQueue.isEmpty()) continue;
            }
            detections = new ArrayList<>(10_000);
            processingQueue.drainTo(detections, 10_000);
            processAttendance(detections);
        }
    }

    private void processAttendance(List<Detection> detections) {
        log.debug("Processing {} detections", detections.size());
        Set<AttendanceKey> attendances = detections.stream().flatMap(detection -> {
            String clientId = detection.detectable.clientId;

            String clientReference = detectableHolders.get(detection.detectable);
            if (clientReference == null) {
                log.trace("Skipping unknown {}", detection.detectable);
                return Stream.empty();
            }

            AntennaKey antennaKey = new AntennaKey(clientId, detection.readerSn, detection.portNumber);
            ZoneKey zoneKey = antennaZones.get(antennaKey);
            if (zoneKey == null) {
                log.trace("Skipping unassigned {}", antennaKey);
                return Stream.empty();
            }

            ZoneBlocks blocks = zoneBlocks.get(zoneKey);
            if (blocks == null) {
                log.trace("No blocks in {}", zoneKey);
                return Stream.empty();
            }

            Instant detectionStartTime = detection.detectionTime.minus(blocks.maxBlockDuration);
            SortedMap<Instant, Block> subMap = blocks.blocks.subMap(detectionStartTime, true, detection.detectionTime, true);
            return subMap.values().stream()
                    .filter(b -> contactSchedules.contains(new AssignmentKey(clientId, clientReference, b.scheduleId)) &&
                            !detection.detectionTime.isBefore(b.detectionStartTime) &&
                            detection.detectionTime.isBefore(b.detectionEndTime))
                    .map(b -> new AttendanceKey(clientId, clientReference, b.scheduleId, b.blockId));
        }).collect(toSet());

        Map<AntennaKey, Instant> newProcessingStates = detections.stream()
                .collect(toMap(
                        d -> new AntennaKey(d.detectable.clientId, d.readerSn, d.portNumber),
                        d -> d.detectionTime,
                        BinaryOperator.maxBy(Comparator.<Instant>naturalOrder())));

        if (!detections.isEmpty()) {
            db.execute(sql -> {
                if (!attendances.isEmpty()) {
                    BatchBindStep batch = sql.batch(insertAttendanceQuery);
                    for (AttendanceKey attendance : attendances) {
                        batch.bind(attendance.clientId, attendance.clientReference, attendance.scheduleId, attendance.blockId);
                    }

                    int[] attendanceRowsInserted = batch.execute();
                    int newAttendanceRows = Arrays.stream(attendanceRowsInserted).sum();
                    if (newAttendanceRows > 0) {
                        log.debug("Persisting: {}", attendances);
                        log.debug("Attendance persisted: {}, of which new: {}",
                                attendanceRowsInserted.length, newAttendanceRows);
                    }
                }

                BatchBindStep stateBatch = sql.batch(processingStateInsert);
                for (Map.Entry<AntennaKey, Instant> entry : newProcessingStates.entrySet()) {
                    AntennaKey antenna = entry.getKey();
                    LocalDateTime processedUpTo = utcLocalFromInstant(entry.getValue());
                    stateBatch.bind(antenna.clientId, antenna.readerSn, antenna.portNumber,
                            processedUpTo, processedUpTo, processedUpTo);
                }
                int[] stateRowsInserted = stateBatch.execute();
                int updatedStateRows = Arrays.stream(stateRowsInserted).sum();
                if (updatedStateRows > 0) {
                    log.debug("New processing state: {}", newProcessingStates);
                }
                return null;
            });
        }
    }

    private List<Detection> fetchDetectionsFromDatabase() {
        List<Detection> detections = db.execute(sql -> {
            refreshAssignments(sql);

            return sql.select(
                    UHF_DETECTION.CLIENT_ID,
                    UHF_DETECTION.DETECTABLE_ID,
                    UHF_DETECTION.DETECTABLE_TYPE,
                    UHF_DETECTION.READER_SN,
                    UHF_DETECTION.PORT_NUMBER,
                    UHF_DETECTION.DETECTION_TIME)
                    .from(UHF_DETECTION.join(ANTENNA).onKey())
                    .leftJoin(PROCESSING_STATE).on(ANTENNA.CLIENT_ID.eq(PROCESSING_STATE.CLIENT_ID), ANTENNA.READER_SN.eq(PROCESSING_STATE.READER_SN), ANTENNA.PORT_NUMBER.eq(PROCESSING_STATE.PORT_NUMBER))
                    .where(PROCESSING_STATE.PROCESSED_UP_TO.isNull())
                    .or(UHF_DETECTION.DETECTION_TIME.gt(PROCESSING_STATE.PROCESSED_UP_TO))
                    .stream()
                    .map(d -> new Detection(new ClientDetectable(d.value1(), d.value2(), DetectableType.fromString(d.value3())), d.value4(), d.value5(), instantFromUtcLocal(d.value6())))
                    .collect(toList());
        });

        log.debug("Fetched {} detections from the database pending processing", detections.size());
        return detections;
    }

    private Void refreshAssignments(DSLContext sql) {
        long timerStart = System.currentTimeMillis();

        detectableHolders = sql
                .select(DETECTABLE.CLIENT_ID, DETECTABLE.DETECTABLE_ID, DETECTABLE.DETECTABLE_TYPE, ASSIGNMENT.CLIENT_REFERENCE)
                .from(ASSIGNMENT.join(CARRIER).onKey())
                .join(DETECTABLE).onKey(DETECTABLE__FK_DETECTABLE_TO_CARRIER)
                .stream()
                .collect(toMap(
                        d -> new ClientDetectable(d.value1(), d.value2(), DetectableType.fromString(d.value3())),
                        Record4::value4));

        antennaZones = sql
                .selectFrom(ANTENNA)
                .stream()
                .collect(toMap(
                        a -> new AntennaKey(a.getClientId(), a.getReaderSn(), a.getPortNumber()),
                        a -> new ZoneKey(a.getClientId(), a.getSiteId(), a.getZoneId())
                ));

        contactSchedules = sql
                .selectFrom(Tables.ASSIGNMENT)
                .stream()
                .map(r -> new AssignmentKey(r.getClientId(), r.getClientReference(), r.getScheduleId()))
                .collect(toSet());

        Map<ZoneKey, TreeMap<Instant, Block>> rawBlocksByZone = sql
                .select(BLOCK.CLIENT_ID, BLOCK.SCHEDULE_ID, BLOCK.BLOCK_ID,
                        BLOCK_ZONE.SITE_ID, BLOCK_ZONE.ZONE_ID,
                        BLOCK_TIME.START_TIME,
                        BLOCK_TIME.END_TIME)
                .from(BLOCK.join(BLOCK_TIME).onKey().join(BLOCK_ZONE).onKey(BLOCK_ZONE__FK_BLOCK_ZONE_TO_BLOCK))
                .stream()
                .collect(groupingBy(r -> new ZoneKey(r.value1(), r.value4(), r.value5()),
                        mapping(r -> new Block(instantFromUtcLocal(r.value6()).minus(DETECTION_BEFORE_BLOCK_START),
                                        instantFromUtcLocal(r.value7()).plus(DETECTION_AFTER_BLOCK_END),
                                        r.value2(),
                                        r.value3()),
                                toMap(b -> b.detectionStartTime, Function.identity(), (a, b) -> a, TreeMap::new))));
        zoneBlocks = rawBlocksByZone.entrySet().stream()
                .collect(toMap(Map.Entry::getKey, e -> new ZoneBlocks(e.getValue())));

        lastRefreshMillis = System.currentTimeMillis();
        log.info("Refreshed assignments in {} ms: {} detectables, {} antennae, {} schedule assignments, {} zones",
                lastRefreshMillis - timerStart, detectableHolders.size(), antennaZones.size(), contactSchedules.size(), zoneBlocks.size());
        return null;
    }

    public void processDetections(Collection<Detection> detections) {
        try {
            for (Detection detection : detections) {
                processingQueue.put(detection);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class Block {
        final Instant detectionStartTime;
        final Instant detectionEndTime;
        final String scheduleId;
        final String blockId;

        Block(Instant detectionStartTime, Instant detectionEndTime, String scheduleId, String blockId) {
            this.detectionStartTime = detectionStartTime;
            this.detectionEndTime = detectionEndTime;
            this.scheduleId = scheduleId;
            this.blockId = blockId;
        }

        public String toString() {
            return "Block{" +
                    "detectionStartTime=" + detectionStartTime +
                    ", detectionEndTime=" + detectionEndTime +
                    ", scheduleId='" + scheduleId + '\'' +
                    ", blockId='" + blockId + '\'' +
                    '}';
        }
    }

    private static class ZoneBlocks {
        final NavigableMap<Instant, Block> blocks;
        final Duration maxBlockDuration;

        private ZoneBlocks(NavigableMap<Instant, Block> blocks) {
            this.blocks = blocks;
            this.maxBlockDuration = blocks.values().stream()
                    .map(b -> Duration.between(b.detectionStartTime, b.detectionEndTime))
                    .max(naturalOrder())
                    .orElse(Duration.ZERO);
        }
    }
}