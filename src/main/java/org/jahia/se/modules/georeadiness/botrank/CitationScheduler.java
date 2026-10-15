package org.jahia.se.modules.georeadiness.botrank;

import org.jahia.services.SpringContextSingleton;
import org.jahia.services.scheduler.SchedulerService;
import org.quartz.CronTrigger;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.TimeZone;

/**
 * The Quartz triggers of the BotRank sync: one daily trigger per site, plus one-shot triggers for
 * "sync now" and for retries while BotRank is still collecting answers.
 *
 * <p>Quartz reads a cron expression in the server's time zone, and the Jahia container runs in UTC;
 * the daily trigger therefore carries {@code Europe/Paris} explicitly, so 06:30 stays 06:30 in Paris
 * across daylight saving changes. A group of its own keeps these triggers apart from the site scans
 * of {@code ScanScheduler}.</p>
 *
 * <p>Jahia's scheduler is the persistent, cluster-shared Quartz store; the job itself only runs on the
 * processing server.</p>
 */
public final class CitationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(CitationScheduler.class);
    static final String GROUP = "geo-readiness-citations";
    static final long RETRY_DELAY_MS = 30L * 60 * 1000;

    private CitationScheduler() {
    }

    static String jobName(String sitePath) {
        return "geoCitations:" + sitePath.replace('/', '_');
    }

    /**
     * Installs or replaces the daily trigger of a site.
     *
     * @param sitePath {@code /sites/<key>}
     * @param time     HH:mm in Europe/Paris
     * @param owner    local path of the user who saved the settings
     * @throws Exception when Quartz refuses the trigger
     */
    public static void scheduleDaily(String sitePath, String time, String owner) throws Exception {
        Scheduler scheduler = scheduler();
        String name = jobName(sitePath);
        unschedule(sitePath);
        scheduler.addJob(job(sitePath, owner), true);
        String[] hm = time.split(":");
        CronTrigger trigger = new CronTrigger(name, GROUP, "0 " + Integer.parseInt(hm[1]) + " " + Integer.parseInt(hm[0]) + " * * ?");
        trigger.setTimeZone(TimeZone.getTimeZone(CitationSync.PARIS));
        trigger.setJobName(name);
        trigger.setJobGroup(GROUP);
        trigger.setMisfireInstruction(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
        trigger.setJobDataMap(attempt(1));
        scheduler.scheduleJob(trigger);
        logger.info("BotRank sync of {} scheduled daily at {} Europe/Paris", sitePath, time);
    }

    /**
     * Runs the sync of a site once, now or after a delay, without touching its daily trigger.
     *
     * @param sitePath {@code /sites/<key>}
     * @param owner    local path of the user who asked
     * @param delayMs  0 to run now
     * @param attempt  1 for a manual run, more for a retry
     * @throws Exception when Quartz refuses the trigger
     */
    public static void runOnce(String sitePath, String owner, long delayMs, int attempt) throws Exception {
        Scheduler scheduler = scheduler();
        String name = jobName(sitePath);
        if (scheduler.getJobDetail(name, GROUP) == null) {
            scheduler.addJob(job(sitePath, owner), true);
        }
        String triggerName = name + ":once:" + System.currentTimeMillis();
        SimpleTrigger trigger = new SimpleTrigger(triggerName, GROUP, new Date(System.currentTimeMillis() + delayMs));
        trigger.setJobName(name);
        trigger.setJobGroup(GROUP);
        JobDataMap data = attempt(attempt);
        data.put(CitationSyncJob.OWNER, owner);
        trigger.setJobDataMap(data);
        scheduler.scheduleJob(trigger);
    }

    /** Removes every trigger and the job of a site. */
    public static void unschedule(String sitePath) {
        String name = jobName(sitePath);
        try {
            Scheduler scheduler = scheduler();
            for (Trigger t : scheduler.getTriggersOfJob(name, GROUP)) {
                scheduler.unscheduleJob(t.getName(), GROUP);
            }
            if (scheduler.getJobDetail(name, GROUP) != null) {
                scheduler.deleteJob(name, GROUP);
            }
        } catch (Exception e) {
            logger.debug("Could not unschedule the BotRank sync of {}", sitePath, e);
        }
    }

    /** When the daily trigger fires next, or null. */
    public static Date nextRun(String sitePath) {
        try {
            Trigger t = scheduler().getTrigger(jobName(sitePath), GROUP);
            return t == null ? null : t.getNextFireTime();
        } catch (Exception e) {
            return null;
        }
    }

    /** Removes every trigger of this group. Used when the bundle stops, on the processing server only. */
    static void unscheduleAll() {
        try {
            Scheduler scheduler = scheduler();
            for (String name : scheduler.getTriggerNames(GROUP)) {
                scheduler.unscheduleJob(name, GROUP);
            }
            for (String name : scheduler.getJobNames(GROUP)) {
                scheduler.deleteJob(name, GROUP);
            }
        } catch (Exception e) {
            logger.warn("Could not remove the BotRank sync triggers: {}", e.getMessage());
        }
    }

    private static JobDetail job(String sitePath, String owner) {
        JobDetail detail = new JobDetail(jobName(sitePath), GROUP, CitationSyncJob.class);
        detail.setDurability(true);
        JobDataMap data = new JobDataMap();
        // Persisted by Quartz: only values that mean the same thing days later.
        data.put(CitationSyncJob.SITE_PATH, sitePath);
        data.put(CitationSyncJob.OWNER, owner);
        detail.setJobDataMap(data);
        return detail;
    }

    private static JobDataMap attempt(int attempt) {
        JobDataMap data = new JobDataMap();
        data.put(CitationSyncJob.ATTEMPT, Integer.toString(attempt));
        return data;
    }

    private static Scheduler scheduler() {
        Object bean = SpringContextSingleton.getBean("SchedulerService");
        if (!(bean instanceof SchedulerService)) {
            throw new IllegalStateException("SchedulerService is unavailable");
        }
        return ((SchedulerService) bean).getScheduler();
    }
}
