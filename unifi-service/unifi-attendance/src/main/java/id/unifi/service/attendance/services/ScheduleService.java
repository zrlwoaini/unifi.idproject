package id.unifi.service.attendance.services;

import id.unifi.service.attendance.AttendanceProcessor;
import id.unifi.service.attendance.OverriddenStatus;
import static id.unifi.service.attendance.OverriddenStatus.ABSENT;
import static id.unifi.service.attendance.OverriddenStatus.AUTH_ABSENT;
import static id.unifi.service.attendance.OverriddenStatus.PRESENT;
import static id.unifi.service.attendance.db.Attendance.ATTENDANCE;
import id.unifi.service.attendance.db.Keys;
import static id.unifi.service.attendance.db.Tables.*;
import id.unifi.service.common.api.annotations.ApiOperation;
import id.unifi.service.common.api.annotations.ApiService;
import id.unifi.service.common.api.errors.Unauthorized;
import id.unifi.service.common.db.Database;
import id.unifi.service.common.db.DatabaseProvider;
import id.unifi.service.common.operator.OperatorPK;
import id.unifi.service.common.operator.OperatorSessionData;
import static id.unifi.service.common.util.TimeUtils.utcLocalFromInstant;
import static id.unifi.service.common.util.TimeUtils.zonedFromUtcLocal;
import static id.unifi.service.core.db.Core.CORE;
import static id.unifi.service.core.db.Tables.ANTENNA;
import static id.unifi.service.core.db.Tables.HOLDER;
import static id.unifi.service.core.db.Tables.ZONE;
import id.unifi.service.core.db.tables.records.HolderRecord;
import static java.time.ZoneOffset.UTC;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import org.jooq.*;
import org.jooq.impl.DSL;
import static org.jooq.impl.DSL.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@ApiService("schedule")
public class ScheduleService {
    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private static final Field<String> CLIENT_ID = unqualified(ATTENDANCE_.CLIENT_ID);
    private static final Field<String> CLIENT_REFERENCE = unqualified(ATTENDANCE_.CLIENT_REFERENCE);
    private static final Field<String> SCHEDULE_ID = unqualified(ATTENDANCE_.SCHEDULE_ID);
    private static final Field<String> BLOCK_ID = unqualified(ATTENDANCE_.BLOCK_ID);
    private static final Field<String> SITE_ID = unqualified(ZONE.SITE_ID);
    private static final Field<String> ZONE_ID = unqualified(ZONE.ZONE_ID);
    private static final Field<String> OVERRIDDEN_STATUS = field(name("overridden_status"), String.class);
    private static final Field<LocalDateTime> EPOCH = value(LocalDateTime.ofInstant(Instant.EPOCH, UTC));

    private static final Table<Record> BLOCK_WITH_TIME_AND_ZONE =
            BLOCK.join(BLOCK_TIME).using(CLIENT_ID, SCHEDULE_ID, BLOCK_ID)
                    .join(BLOCK_ZONE).using(CLIENT_ID, SCHEDULE_ID, BLOCK_ID);

    private static final CommonTableExpression<Record5<String, String, String, String, String>> FULL_ATTENDANCE =
                name("fa").as(select(CLIENT_ID, CLIENT_REFERENCE, SCHEDULE_ID, BLOCK_ID, ATTENDANCE_OVERRIDE.STATUS.as(OVERRIDDEN_STATUS))
                        .distinctOn(CLIENT_ID, CLIENT_REFERENCE, SCHEDULE_ID, BLOCK_ID)
                        .from(ATTENDANCE_)
                        .fullJoin(ATTENDANCE_OVERRIDE)
                        .using(CLIENT_ID, CLIENT_REFERENCE, SCHEDULE_ID, BLOCK_ID));


    private static final CommonTableExpression<Record4<String, String, String, LocalDateTime>> ZONE_PROCESSING_STATE =
            name("z").as(select(ZONE.CLIENT_ID, ZONE.SITE_ID, ZONE.ZONE_ID,
                    min(coalesce(PROCESSING_STATE.PROCESSED_UP_TO, EPOCH)).as("processed_up_to"))
                    .from((ZONE.leftJoin(ANTENNA).onKey())
                    .leftJoin(PROCESSING_STATE).on(
                            ANTENNA.CLIENT_ID.eq(PROCESSING_STATE.CLIENT_ID),
                            ANTENNA.READER_SN.eq(PROCESSING_STATE.READER_SN),
                            ANTENNA.PORT_NUMBER.eq(PROCESSING_STATE.PORT_NUMBER)))
                    .groupBy(ZONE.CLIENT_ID, ZONE.SITE_ID, ZONE.ZONE_ID));

    private static final Field<LocalDateTime> ZONE_PROCESSED_UP_TO = ZONE_PROCESSING_STATE.field("processed_up_to", LocalDateTime.class);
    private static final Field<LocalDateTime> BLOCK_DETECTION_END_TIME = field("{0} + {1} * interval '1 second'",
            LocalDateTime.class, BLOCK_TIME.END_TIME, AttendanceProcessor.DETECTION_AFTER_BLOCK_END.toSeconds());
    private static final Condition ZONE_PROCESSED = ZONE_PROCESSED_UP_TO.ge(BLOCK_DETECTION_END_TIME);

    private static final Field<String> STATUS_DEF = coalesce(
            FULL_ATTENDANCE.field(OVERRIDDEN_STATUS),
            when(FULL_ATTENDANCE.field(BLOCK_ID).isNotNull(), "present").when(ZONE_PROCESSED, "absent")
    );
    private static final Field<String> STATUS = STATUS_DEF.as("status");
    private static final Condition IS_PRESENT_OR_AUTH_ABSENT = condition(coalesce(
            field(FULL_ATTENDANCE.field(OVERRIDDEN_STATUS).in(PRESENT.toString(), AUTH_ABSENT.toString())),
            field(FULL_ATTENDANCE.field(BLOCK_ID).isNotNull())
    ));
    private static final Condition IS_ABSENT = condition(coalesce(
            field(FULL_ATTENDANCE.field(OVERRIDDEN_STATUS).eq(ABSENT.toString())),
            field(ZONE_PROCESSED.and(FULL_ATTENDANCE.field(BLOCK_ID).isNull()))
    ));

    private final Database db;

    public ScheduleService(DatabaseProvider dbProvider) {
        this.db = dbProvider.bySchema(CORE, ATTENDANCE);
    }

    @ApiOperation
    public List<ScheduleInfo> listSchedules(OperatorSessionData session, String clientId) {
        authorize(session, clientId);
        return db.execute(sql -> sql.selectFrom(SCHEDULE)
                .where(SCHEDULE.CLIENT_ID.eq(clientId))
                .fetch(r -> new ScheduleInfo(r.getScheduleId(), r.getName())));
    }

    @ApiOperation
    public List<BlockInfo> listBlocks(OperatorSessionData session, String clientId, String scheduleId) {
        authorize(session, clientId);
        return db.execute(sql -> sql.selectFrom(BLOCK_WITH_TIME_AND_ZONE)
                .where(BLOCK.CLIENT_ID.eq(clientId))
                .and(BLOCK.SCHEDULE_ID.eq(scheduleId))
                .fetch(r -> new BlockInfo(
                        r.get(BLOCK.BLOCK_ID),
                        r.get(BLOCK.NAME),
                        zonedFromUtcLocal(r.get(BLOCK_TIME.START_TIME)),
                        zonedFromUtcLocal(r.get(BLOCK_TIME.END_TIME)),
                        r.get(BLOCK_ZONE.SITE_ID),
                        r.get(BLOCK_ZONE.ZONE_ID))));
    }

    @ApiOperation
    public List<ScheduleStat> listScheduleStats(OperatorSessionData session, String clientId) {
        authorize(session, clientId);
        return db.execute(sql -> fetchScheduleStats(sql, clientId, trueCondition(), trueCondition()));
    }

    @ApiOperation
    public @Nullable ScheduleStat getSchedule(OperatorSessionData session, String clientId, String scheduleId) {
        authorize(session, clientId);
        List<ScheduleStat> stats = db.execute(sql -> fetchScheduleStats(
                sql, clientId, SCHEDULE.SCHEDULE_ID.eq(scheduleId), SCHEDULE_ID.eq(scheduleId)));
        return stats.stream().findFirst().orElse(null);
    }

    @ApiOperation
    public ContactAttendanceInfo getContactAttendanceForSchedule(OperatorSessionData session,
                                                                 String clientId,
                                                                 String scheduleId) {
        authorize(session, clientId);
        return db.execute(sql -> {
            int blockCount = sql.fetchCount(BLOCK, BLOCK.CLIENT_ID.eq(clientId).and(BLOCK.SCHEDULE_ID.eq(scheduleId)));

            Map<String, String> names = sql.select(ASSIGNMENT.CLIENT_REFERENCE, HOLDER.NAME)
                    .from(ASSIGNMENT)
                    .leftJoin(HOLDER)
                    .on(ASSIGNMENT.CLIENT_ID.eq(HOLDER.CLIENT_ID), ASSIGNMENT.CLIENT_REFERENCE.eq(HOLDER.CLIENT_REFERENCE))
                    .where(ASSIGNMENT.CLIENT_ID.eq(clientId))
                    .and(ASSIGNMENT.SCHEDULE_ID.eq(scheduleId))
                    .stream()
                    .collect(toMap(Record2::value1, Record2::value2));

            List<ContactAttendance> attendance = sql
                    .with(FULL_ATTENDANCE, ZONE_PROCESSING_STATE)
                    .select(ASSIGNMENT.CLIENT_REFERENCE,
                            count().filterWhere(IS_PRESENT_OR_AUTH_ABSENT),
                            count().filterWhere(IS_ABSENT))
                    .from(ASSIGNMENT)
                    .join(BLOCK_WITH_TIME_AND_ZONE)
                    .using(CLIENT_ID, SCHEDULE_ID)
                    .join(ZONE_PROCESSING_STATE)
                    .using(CLIENT_ID, SITE_ID, ZONE_ID)
                    .leftJoin(FULL_ATTENDANCE)
                    .using(CLIENT_ID, CLIENT_REFERENCE, SCHEDULE_ID, BLOCK_ID)
                    .where(ASSIGNMENT.CLIENT_ID.eq(clientId))
                    .and(ASSIGNMENT.SCHEDULE_ID.eq(scheduleId))
                    .and(ZONE_PROCESSED)
                    .groupBy(ASSIGNMENT.CLIENT_ID, ASSIGNMENT.CLIENT_REFERENCE, ASSIGNMENT.SCHEDULE_ID)
                    .fetch(r -> new ContactAttendance(r.value1(), names.get(r.value1()), r.value2(), r.value3()));
            return new ContactAttendanceInfo(blockCount, attendance);
        });
    }

    @ApiOperation
    public void putAssignment(OperatorSessionData session,
                              String clientId,
                              String clientReference,
                              String scheduleId) {
        OperatorPK operator = authorize(session, clientId);

        db.execute(sql -> sql.insertInto(ASSIGNMENT)
                .set(ASSIGNMENT.CLIENT_ID, operator.clientId)
                .set(ASSIGNMENT.CLIENT_REFERENCE, clientReference)
                .set(ASSIGNMENT.SCHEDULE_ID, scheduleId)
                .onConflictDoNothing()
                .execute());
    }

    @ApiOperation
    public List<BlockAttendance> reportBlockAttendance(OperatorSessionData session,
                                                       String clientId,
                                                       String clientReference,
                                                       String scheduleId) {
        OperatorPK operator = authorize(session, clientId);
        return db.execute(sql -> {
            SelectConditionStep<Record7<String, String, LocalDateTime, LocalDateTime, String, String, String>> q = sql
                    .with(FULL_ATTENDANCE, ZONE_PROCESSING_STATE)
                    .select(BLOCK_ID,
                            BLOCK.NAME,
                            BLOCK_TIME.START_TIME,
                            BLOCK_TIME.END_TIME,
                            BLOCK_ZONE.SITE_ID,
                            BLOCK_ZONE.ZONE_ID,
                            STATUS)
                    .from(ASSIGNMENT)
                    .join(BLOCK_WITH_TIME_AND_ZONE)
                    .using(CLIENT_ID, SCHEDULE_ID)
                    .join(ZONE_PROCESSING_STATE)
                    .using(CLIENT_ID, SITE_ID, ZONE_ID)
                    .leftJoin(FULL_ATTENDANCE)
                    .using(CLIENT_ID, CLIENT_REFERENCE, SCHEDULE_ID, BLOCK_ID)
                    .where(ASSIGNMENT.CLIENT_ID.eq(clientId))
                    .and(ASSIGNMENT.SCHEDULE_ID.eq(scheduleId))
                    .and(ASSIGNMENT.CLIENT_REFERENCE.eq(clientReference));
                    return q
                            .fetch(r -> new BlockAttendance(
                                    scheduleId,
                                    r.get(BLOCK_ID),
                                    r.get(BLOCK.NAME),
                                    zonedFromUtcLocal(r.get(BLOCK_TIME.START_TIME)),
                                    zonedFromUtcLocal(r.get(BLOCK_TIME.END_TIME)),
                                    r.get(BLOCK_ZONE.SITE_ID),
                                    r.get(BLOCK_ZONE.ZONE_ID),
                                    OverriddenStatus.fromString(r.get(STATUS))));
                }
        );
    }

    @ApiOperation
    public ContactScheduleAttendanceInfo reportContactScheduleAttendance(OperatorSessionData session, String clientId) {
        authorize(session, clientId);

        return db.execute(sql -> {
            List<ScheduleInfoWithBlockCount> schedules = sql
                    .select(SCHEDULE.SCHEDULE_ID, SCHEDULE.NAME, count())
                    .from(SCHEDULE.leftJoin(BLOCK).onKey())
                    .where(SCHEDULE.CLIENT_ID.eq(clientId))
                    .groupBy(SCHEDULE.SCHEDULE_ID, SCHEDULE.NAME)
                    .fetch(r -> new ScheduleInfoWithBlockCount(r.get(SCHEDULE.SCHEDULE_ID), r.get(SCHEDULE.NAME), r.value3()));

            Map<String, String> contactNames = sql
                    .selectFrom(HOLDER)
                    .where(HOLDER.CLIENT_ID.eq(clientId))
                    .and(HOLDER.HOLDER_TYPE.eq("contact"))
                    .stream()
                    .collect(toMap(HolderRecord::getClientReference, HolderRecord::getName));

            List<ContactScheduleAttendance> attendance = sql
                    .with(FULL_ATTENDANCE, ZONE_PROCESSING_STATE)
                    .select(ASSIGNMENT.CLIENT_REFERENCE,
                            ASSIGNMENT.SCHEDULE_ID,
                            count().filterWhere(IS_PRESENT_OR_AUTH_ABSENT),
                            count().filterWhere(IS_ABSENT))
                    .from(ASSIGNMENT)
                    .join(BLOCK_WITH_TIME_AND_ZONE)
                    .using(CLIENT_ID, SCHEDULE_ID)
                    .join(ZONE_PROCESSING_STATE)
                    .using(CLIENT_ID, SITE_ID, ZONE_ID)
                    .leftJoin(FULL_ATTENDANCE)
                    .using(CLIENT_ID, CLIENT_REFERENCE, SCHEDULE_ID, BLOCK_ID)
                    .where(CLIENT_ID.eq(clientId))
                    .and(ZONE_PROCESSED)
                    .groupBy(CLIENT_REFERENCE, SCHEDULE_ID)
                    .stream()
                    .collect(groupingBy(r -> r.get(ASSIGNMENT.CLIENT_REFERENCE),
                            mapping(r -> new ScheduleAttendance(r.value2(), r.value3(), r.value4()), toList())))
                    .entrySet().stream()
                    .map(e -> new ContactScheduleAttendance(e.getKey(), contactNames.get(e.getKey()), e.getValue()))
                    .collect(toList());

            return new ContactScheduleAttendanceInfo(schedules, attendance);
        });
    }

    @ApiOperation
    public List<BlockAttendance> reportContactAttendance(OperatorSessionData session,
                                                         String clientId,
                                                         String clientReference,
                                                         ZonedDateTime startTime,
                                                         ZonedDateTime endTime) {
        authorize(session, clientId);

        return db.execute(sql -> sql
                .with(FULL_ATTENDANCE, ZONE_PROCESSING_STATE)
                .select(BLOCK.SCHEDULE_ID, BLOCK.BLOCK_ID, BLOCK_TIME.START_TIME, BLOCK_TIME.END_TIME, STATUS)
                .from(ASSIGNMENT)
                .join(BLOCK_WITH_TIME_AND_ZONE)
                .using(CLIENT_ID, SCHEDULE_ID)
                .join(ZONE_PROCESSING_STATE)
                .using(CLIENT_ID, SITE_ID, ZONE_ID)
                .leftJoin(FULL_ATTENDANCE)
                .using(CLIENT_ID, CLIENT_REFERENCE, SCHEDULE_ID, BLOCK_ID)
                .where(ASSIGNMENT.CLIENT_ID.eq(clientId))
                .and(ASSIGNMENT.CLIENT_REFERENCE.eq(clientReference))
                .and(between(startTime.toInstant(), endTime.toInstant()))
                .fetch(r -> new BlockAttendance(
                        r.get(BLOCK.SCHEDULE_ID),
                        r.get(BLOCK_ID),
                        r.get(BLOCK.NAME),
                        zonedFromUtcLocal(r.get(BLOCK_TIME.START_TIME)),
                        zonedFromUtcLocal(r.get(BLOCK_TIME.END_TIME)),
                        r.get(BLOCK_ZONE.SITE_ID),
                        r.get(BLOCK_ZONE.ZONE_ID),
                        OverriddenStatus.fromString(r.get(STATUS)))));
    }

    @ApiOperation
    String reportAttendanceByMetadata(OperatorSessionData session,
                                      String clientId,
                                      String key,
                                      String value) {
        return null;
    }

    @ApiOperation
    public void overrideAttendance(OperatorSessionData session,
                                   String clientId,
                                   String clientReference,
                                   String scheduleId,
                                   String blockId,
                                   OverriddenStatus status) {
        OperatorPK operator = authorize(session, clientId);
        db.execute(sql -> {
            sql.insertInto(ATTENDANCE_OVERRIDE)
                    .set(ATTENDANCE_OVERRIDE.CLIENT_ID, operator.clientId)
                    .set(ATTENDANCE_OVERRIDE.CLIENT_REFERENCE, clientReference)
                    .set(ATTENDANCE_OVERRIDE.SCHEDULE_ID, scheduleId)
                    .set(ATTENDANCE_OVERRIDE.BLOCK_ID, blockId)
                    .set(ATTENDANCE_OVERRIDE.STATUS, status.toString())
                    .set(ATTENDANCE_OVERRIDE.OPERATOR, operator.username)
                    .execute();
            return null;
        });
    }

    private Condition between(@Nullable Instant startTime, @Nullable Instant endTime) {
        Condition startCond = startTime != null ? BLOCK_TIME.START_TIME.greaterOrEqual(utcLocalFromInstant(startTime)) : null;
        Condition endCond = endTime != null ? BLOCK_TIME.END_TIME.lessOrEqual(utcLocalFromInstant(endTime)) : null;
        return DSL.and(Stream.of(startCond, endCond).filter(Objects::nonNull).toArray(Condition[]::new));
    }

    private static List<ScheduleStat> fetchScheduleStats(DSLContext sql,
                                                         String clientId,
                                                         Condition scheduleCondition,
                                                         Condition condition) {
        Map<String, Record5<String, Integer, LocalDateTime, LocalDateTime, Integer>> blockSummary = sql
                .with(ZONE_PROCESSING_STATE)
                .select(SCHEDULE.SCHEDULE_ID,
                        count(BLOCK.BLOCK_ID),
                        min(BLOCK_TIME.START_TIME),
                        max(BLOCK_TIME.END_TIME),
                        count().filterWhere(ZONE_PROCESSED))
                .from(SCHEDULE)
                .leftJoin(BLOCK_WITH_TIME_AND_ZONE).using(CLIENT_ID, SCHEDULE_ID)
                .leftJoin(ZONE_PROCESSING_STATE)
                .using(CLIENT_ID, SITE_ID, ZONE_ID)
                .where(CLIENT_ID.eq(clientId))
                .and(scheduleCondition)
                .groupBy(Keys.SCHEDULE_PKEY.getFieldsArray())
                .stream()
                .collect(toMap(r -> r.get(SCHEDULE.SCHEDULE_ID), identity()));
        Map<String, Integer> scheduleAttendance = sql
                .with(FULL_ATTENDANCE, ZONE_PROCESSING_STATE)
                .select(SCHEDULE_ID, count())
                .from(FULL_ATTENDANCE)
                .join(BLOCK_WITH_TIME_AND_ZONE)
                .using(CLIENT_ID, SCHEDULE_ID, BLOCK_ID)
                .join(ZONE_PROCESSING_STATE)
                .using(CLIENT_ID, SITE_ID, ZONE_ID)
                .where(FULL_ATTENDANCE.field(CLIENT_ID).eq(clientId))
                .and(condition)
                .and(ZONE_PROCESSED) // so that overall attendance doesn't go over 100%; TODO: elaborate
                .and(IS_PRESENT_OR_AUTH_ABSENT)
                .groupBy(SCHEDULE_ID)
                .stream()
                .collect(toMap(r -> r.get(SCHEDULE_ID), Record2::value2));
        return sql
                .select(SCHEDULE.SCHEDULE_ID, SCHEDULE.NAME, count(ASSIGNMENT.CLIENT_REFERENCE))
                .from(SCHEDULE)
                .leftJoin(ASSIGNMENT).onKey(Keys.ASSIGNMENT__FK_ASSIGNMENT_TO_SCHEDULE)
                .where(SCHEDULE.CLIENT_ID.eq(clientId))
                .and(scheduleCondition)
                .groupBy(Keys.SCHEDULE_PKEY.getFieldsArray())
                .fetch(r -> {
                    String scheduleId = r.get(SCHEDULE.SCHEDULE_ID);
                    String scheduleName = r.get(SCHEDULE.NAME);
                    Record5<String, Integer, LocalDateTime, LocalDateTime, Integer> stats =
                            blockSummary.get(scheduleId);
                    if (stats == null) {
                        log.error("N'stats");
                    }
                    return new ScheduleStat(
                            scheduleId,
                            scheduleName,
                            zonedFromUtcLocal(stats.value3()),
                            zonedFromUtcLocal(stats.value4()),
                            r.value3(),
                            stats.value2(),
                            Optional.ofNullable(scheduleAttendance.get(r.get(ASSIGNMENT.SCHEDULE_ID))).orElse(0),
                            stats.value5());
                });
    }

    private static <R extends Record, T> Field<T> unqualified(TableField<R, T> field) {
        return field(name(field.getUnqualifiedName()), field.getType());
    }

    private static OperatorPK authorize(OperatorSessionData sessionData, String clientId) {
        return Optional.ofNullable(sessionData.getOperator())
                .filter(op -> op.clientId.equals(clientId))
                .orElseThrow(Unauthorized::new);
    }

    public static class ScheduleInfo {
        public final String scheduleId;
        public final String name;

        public ScheduleInfo(String scheduleId, String name) {
            this.scheduleId = scheduleId;
            this.name = name;
        }
    }

    public static class ScheduleStat {
        public final String scheduleId;
        public final String name;
        public final ZonedDateTime startTime;
        public final ZonedDateTime endTime;
        public final int committerCount;
        public final int blockCount;
        public final int overallAttendance;
        public final int processedBlockCount;

        public ScheduleStat(String scheduleId,
                            String name,
                            ZonedDateTime startTime,
                            ZonedDateTime endTime,
                            int committerCount,
                            int blockCount,
                            int overallAttendance,
                            int processedBlockCount) {
            this.scheduleId = scheduleId;
            this.name = name;
            this.startTime = startTime;
            this.endTime = endTime;
            this.committerCount = committerCount;
            this.blockCount = blockCount;
            this.processedBlockCount = processedBlockCount;
            this.overallAttendance = overallAttendance;
        }
    }

    public class BlockInfo {
        public final String blockId;
        public final String name;
        public final ZonedDateTime startTime;
        public final ZonedDateTime endTime;
        public final String siteId;
        public final String zoneId;

        public BlockInfo(String blockId,
                         String name,
                         ZonedDateTime startTime,
                         ZonedDateTime endTime,
                         String siteId,
                         String zoneId) {
            this.blockId = blockId;
            this.name = name;
            this.startTime = startTime;
            this.endTime = endTime;
            this.siteId = siteId;
            this.zoneId = zoneId;
        }
    }

    public static class ContactAttendance {
        public final String clientReference;
        public final String name;
        public final int presentCount;
        public final int absentCount;

        public ContactAttendance(String clientReference, String name, int presentCount, int absentCount) {
            this.clientReference = clientReference;
            this.name = name;
            this.presentCount = presentCount;
            this.absentCount = absentCount;
        }
    }

    public static class ContactAttendanceInfo {
        public final int blockCount;
        public final List<ContactAttendance> attendance;

        public ContactAttendanceInfo(int blockCount, List<ContactAttendance> attendance) {
            this.blockCount = blockCount;
            this.attendance = attendance;
        }
    }

    public static class BlockAttendance {
        public final String scheduleId;
        public final String blockId;
        public final String name;
        public final ZonedDateTime startTime;
        public final ZonedDateTime endTime;
        public final String siteId;
        public final String zoneId;
        public final OverriddenStatus status;

        public BlockAttendance(String scheduleId,
                               String blockId,
                               String name,
                               ZonedDateTime startTime,
                               ZonedDateTime endTime,
                               String siteId,
                               String zoneId,
                               OverriddenStatus status) {
            this.scheduleId = scheduleId;
            this.blockId = blockId;
            this.name = name;
            this.startTime = startTime;
            this.endTime = endTime;
            this.siteId = siteId;
            this.zoneId = zoneId;
            this.status = status;
        }
    }

    public static class ScheduleAttendance {
        public final String scheduleId;
        public final int count;

        public ScheduleAttendance(String scheduleId, int presentCount, int absentCount) {
            this.scheduleId = scheduleId;
            this.count = presentCount;
        }
    }

    public static class ContactScheduleAttendance {
        public final String clientReference;
        public final String name;
        public final List<ScheduleAttendance> attendance;

        public ContactScheduleAttendance(String clientReference, String name, List<ScheduleAttendance> attendance) {
            this.clientReference = clientReference;
            this.name = name;
            this.attendance = attendance;
        }
    }

    public static class ContactScheduleAttendanceInfo {
        public final List<ScheduleInfoWithBlockCount> schedules;
        public final List<ContactScheduleAttendance> attendance;

        public ContactScheduleAttendanceInfo(List<ScheduleInfoWithBlockCount> schedules, List<ContactScheduleAttendance> attendance) {
            this.schedules = schedules;
            this.attendance = attendance;
        }
    }

    public static class ScheduleInfoWithBlockCount {
        public final String scheduleId;
        public final String name;
        public final int blockCount;

        public ScheduleInfoWithBlockCount(String scheduleId, String name, int blockCount) {
            this.scheduleId = scheduleId;
            this.name = name;
            this.blockCount = blockCount;
        }
    }
}
