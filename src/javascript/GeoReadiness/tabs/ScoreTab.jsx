import React from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {
    Chip, Table, TableBody, TableBodyCell, TableHead, TableHeadCell, TableRow, Typography
} from '@jahia/moonstone';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';
const GROUPS = ['access', 'content', 'files'];

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
    const tone = criticalFailed > 0 ? 'bad' : (importantFailed > 0 ? 'warn' : 'good');

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
                        <Table className={styles.mTable}>
                            <TableHead>
                                <TableRow>
                                    <TableHeadCell width="40px"/>
                                    <TableHeadCell>{t('score.checkColumn')}</TableHeadCell>
                                    <TableHeadCell width="110px" textAlign="right">{t('score.observed')}</TableHeadCell>
                                    <TableHeadCell width="110px">{t('score.severityLabel')}</TableHeadCell>
                                </TableRow>
                            </TableHead>
                            <TableBody>
                                {rows.map(c => (
                                    <TableRow key={c.id} hasMultipleLines={!c.passed}>
                                        <TableBodyCell textAlign="center">
                                            <Chip
                                                label={c.passed ? '✓' : '✕'}
                                                color={c.passed ? 'success' : 'danger'}
                                            />
                                        </TableBodyCell>
                                        <TableBodyCell>
                                            <div>{t(`score.check.${c.id}.label`)}</div>
                                            {!c.passed && (
                                                <div className={styles.checkFix}>{t(`score.check.${c.id}.fix`)}</div>
                                            )}
                                        </TableBodyCell>
                                        <TableBodyCell textAlign="right" className={styles.checkValue}>
                                            {c.value === null || c.value === undefined || c.value === '' ?
                                                '' : String(c.value)}
                                        </TableBodyCell>
                                        <TableBodyCell>
                                            <Chip
                                                label={t(`score.severity.${c.severity}`)}
                                                color={c.severity === 'critical' ? 'warning' : 'default'}
                                            />
                                        </TableBodyCell>
                                    </TableRow>
                                ))}
                            </TableBody>
                        </Table>
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
