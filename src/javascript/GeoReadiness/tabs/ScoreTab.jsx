import React from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Chip, Typography} from '@jahia/moonstone';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';
const GROUPS = ['access', 'content', 'files'];

/** What llms.txt says about this page: listed, would be listed, or no file yet. */
function llmsKey(llms) {
    if (llms.listed) {
        return 'context.llms.listed';
    }

    return llms.wouldAdd ? 'context.llms.wouldAdd' : 'context.llms.notGenerated';
}

/**
 * The score is a count of checks, not a rating out of 100. Every row names the
 * fact it read, so an editor can disagree with a specific line rather than with
 * a number they cannot see inside.
 */
export const ScoreTab = ({report}) => {
    const {t} = useTranslation(NS);
    const score = report.score;

    if (!score) {
        return null;
    }

    const {passed, total, criticalFailed, importantFailed, checks = []} = score;
    // GEO-18. Checks the last site scan blames on this page's template, so an
    // author is not sent to fix something that is not theirs to fix.
    const rollup = report.templateRollup || {};
    const shared = new Set(rollup.sharedChecks || []);
    let tone = 'good';
    if (criticalFailed > 0) {
        tone = 'bad';
    } else if (importantFailed > 0) {
        tone = 'warn';
    }

    return (
        <div>
            <div className={`${styles.verdict} ${styles[tone]}`}>
                <span className={styles.scoreHeadline}>{t('score.headline', {passed, total})}</span>
                {criticalFailed > 0 && (
                    <span className={styles.scoreCount}>{t('score.criticalFailed', {count: criticalFailed})}</span>
                )}
                {importantFailed > 0 && (
                    <span className={styles.scoreCount}>{t('score.importantFailed', {count: importantFailed})}</span>
                )}
                {criticalFailed === 0 && importantFailed === 0 && (
                    <span className={styles.scoreCount}>{t('score.allGood')}</span>
                )}
            </div>

            {/*
              * A page score with no reference point is not information: nobody
              * knows whether sixteen of eighteen is good on this site. The site
              * average and this page's own section say whether the page is the
              * problem or the site is. Facts, not findings, so lines rather than
              * banners.
              */}
            {(report.context || report.links) && (
                <ul className={styles.contextList}>
                    {report.context && report.context.sitePercent !== undefined && (
                        <li>
                            {t('context.site', {percent: report.context.sitePercent})}
                            {report.context.section ? ` · ${t('context.section', {
                                section: report.context.section,
                                percent: report.context.sectionPercent
                            })}` : ''}
                        </li>
                    )}
                    {report.links && (
                        <li>
                            {t('context.links', {
                                nav: report.links.nav,
                                content: report.links.content
                            })}
                        </li>
                    )}
                    {report.context && report.context.llms && (
                        <li>
                            {t(llmsKey(report.context.llms))}
                        </li>
                    )}
                </ul>
            )}

            {GROUPS.map(group => {
                const rows = checks.filter(c => c.group === group);
                if (rows.length === 0) {
                    return null;
                }

                return (
                    <div key={group}>
                        <Typography variant="subheading" className={styles.sectionTitle}>
                            {t(`score.group.${group}`)}
                        </Typography>
                        <ul className={styles.checkList}>
                            {rows.map(c => (
                                <li key={c.id} className={styles.checkItem}>
                                    <span className={styles.checkMark}>
                                        <Chip
                                            label={c.passed ? '✓' : '✕'}
                                            color={c.passed ? 'success' : 'danger'}
                                        />
                                    </span>
                                    <span className={styles.checkText}>
                                        <Typography variant="body" className={styles.checkLabel}>
                                            {t(`score.check.${c.id}.label`)}
                                        </Typography>
                                        {!c.passed && (
                                            <Typography variant="caption" className={styles.checkFix}>
                                                {t(`score.check.${c.id}.fix`)}
                                            </Typography>
                                        )}
                                        {!c.passed && shared.has(c.id) && (
                                            <Typography variant="caption" className={styles.fromTemplate}>
                                                {t('score.fromTemplate', {
                                                    template: rollup.template,
                                                    count: rollup.pages
                                                })}
                                            </Typography>
                                        )}
                                    </span>
                                    <span className={styles.checkMeta}>
                                        {c.value !== null && c.value !== undefined && c.value !== '' && (
                                            <Typography variant="caption" className={styles.checkValue}>
                                                {String(c.value)}
                                            </Typography>
                                        )}
                                        <Chip
                                            label={t(`score.severity.${c.severity}`)}
                                            color={c.severity === 'critical' ? 'warning' : 'default'}
                                        />
                                    </span>
                                </li>
                            ))}
                        </ul>
                    </div>
                );
            })}

            <p className={styles.explain}>{t('score.rubric')}</p>
        </div>
    );
};

ScoreTab.propTypes = {
    report: PropTypes.object.isRequired
};
