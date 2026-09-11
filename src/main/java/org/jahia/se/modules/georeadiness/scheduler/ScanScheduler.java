package org.jahia.se.modules.georeadiness.scheduler;

import org.jahia.services.scheduler.SchedulerService;
import org.jahia.services.SpringContextSingleton;
import org.quartz.CronTrigger;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs and removes the cron trigger for one site and language.
 *
 * Quartz is Jahia's own scheduler, so a cron expression is the native way to
 * say "every night at three" and the running job shows up in the administration
 * job list without us building anything.
 *
 * One trigger per site and language, named after both, so saving a schedule
 * replaces exactly that trigger and leaves every other site alone.
 */
public final class ScanScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ScanScheduler.class);
    private static final String GROUP = "geo-readiness";

    private ScanScheduler() {
    }

    public static String triggerName(String sitePath, String language) {
        return "geoScan:" + sitePath.replace('/', '_') + ":" + language;
    }

    /** Replaces any existing schedule for this site and language. */
    public static void schedule(String sitePath, String language, String cron, JobDataMap data)
            throws Exception {
        Scheduler scheduler = scheduler();
        String name = triggerName(sitePath, language);
        unschedule(sitePath, language);

        JobDetail detail = new JobDetail(name, GROUP, SiteScanJob.class);
        detail.setDurability(true);
        // Quartz persists the whole map, so it must hold only values that mean
        // the same thing days later. Paths and a base url do; a session does not.
        detail.setJobDataMap(data);

        CronTrigger trigger = new CronTrigger(name, GROUP, cron);
        trigger.setJobName(name);
        trigger.setJobGroup(GROUP);
        // A missed window is not worth catching up on: the next run reads the
        // same site and produces the same answer, only fresher.
        trigger.setMisfireInstruction(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);

        scheduler.addJob(detail, true);
        scheduler.scheduleJob(trigger);
        logger.info("GEO scan scheduled for {} [{}] with cron {}", sitePath, language, cron);
    }

    public static void unschedule(String sitePath, String language) {
        String name = triggerName(sitePath, language);
        try {
            Scheduler scheduler = scheduler();
            Trigger existing = scheduler.getTrigger(name, GROUP);
            if (existing != null) {
                scheduler.unscheduleJob(name, GROUP);
            }
            if (scheduler.getJobDetail(name, GROUP) != null) {
                scheduler.deleteJob(name, GROUP);
            }
        } catch (Exception e) {
            logger.debug("Could not unschedule GEO scan for {} [{}]", sitePath, language, e);
        }
    }

    /** When the next run is due, or null when nothing is scheduled. */
    public static java.util.Date nextRun(String sitePath, String language) {
        try {
            Trigger t = scheduler().getTrigger(triggerName(sitePath, language), GROUP);
            return t == null ? null : t.getNextFireTime();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the expression is one Quartz will accept, checked before anything is stored. */
    public static boolean isValidCron(String cron) {
        if (cron == null || cron.trim().isEmpty()) {
            return false;
        }
        try {
            new CronTrigger("probe", GROUP, cron.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Scheduler scheduler() {
        SchedulerService service = (SchedulerService) SpringContextSingleton.getBean("SchedulerService");
        return service.getScheduler();
    }
}
