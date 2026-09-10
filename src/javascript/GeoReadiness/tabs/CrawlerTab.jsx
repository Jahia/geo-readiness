import React from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {
    Chip, Table, TableBody, TableBodyCell, TableHead, TableHeadCell, TableRow, Typography
} from '@jahia/moonstone';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';

/** A crawler that gets far less text than a browser is usually looking at a JS-only page. */
const THIN_RATIO = 0.5;

/**
 * Below this many words the initial HTML is an empty shell, whoever asks for it.
 * A page whose content only arrives through JavaScript looks like this to every
 * agent, control included, so the ratio test above would never fire.
 */
const MIN_CONTROL_WORDS = 50;

function verdictOf(report, t) {
    if (!report.published) {
        return {tone: 'warn', text: t('crawler.verdict.unpublished')};
    }
    if (report.blockedCount > 0) {
        return {tone: 'bad', text: t('crawler.verdict.blocked', {count: report.blockedCount})};
    }
    const control = report.controlWords || 0;
    if (control < MIN_CONTROL_WORDS) {
        return {tone: 'bad', text: t('crawler.verdict.empty', {count: control})};
    }
    const thin = (report.agents || []).some(
        a => a.html && a.html.words < control * THIN_RATIO
    );
    if (thin) {
        return {tone: 'warn', text: t('crawler.verdict.thin')};
    }
    return {tone: 'good', text: t('crawler.verdict.ok')};
}

export const CrawlerTab = ({report}) => {
    const {t} = useTranslation(NS);
    const verdict = verdictOf(report, t);
    const control = report.controlWords || 0;
    const controlAgent = (report.agents || []).find(a => a.status === 200 && a.html);
    const metaRefresh = controlAgent && controlAgent.html.metaRefresh;

    if (!report.published) {
        return <div className={`${styles.verdict} ${styles.warn}`}>{verdict.text}</div>;
    }

    return (
        <div>
            <div className={`${styles.verdict} ${styles[verdict.tone]}`}>{verdict.text}</div>

            <Typography variant="caption" className={styles.url}>
                {t('drawer.url')}: <a href={report.url} target="_blank" rel="noopener noreferrer">{report.url}</a>
            </Typography>
            {metaRefresh && (
                <Typography variant="caption" className={styles.url}>
                    {t('crawler.metaRefresh', {url: metaRefresh})}
                </Typography>
            )}

            <Table className={styles.mTable}>
                <TableHead>
                    <TableRow>
                        <TableHeadCell>{t('crawler.agent')}</TableHeadCell>
                        <TableHeadCell width="80px" textAlign="right">{t('crawler.status')}</TableHeadCell>
                        <TableHeadCell width="80px" textAlign="right">{t('crawler.time')}</TableHeadCell>
                        <TableHeadCell width="80px" textAlign="right">{t('crawler.words')}</TableHeadCell>
                        <TableHeadCell>{t('crawler.initialHtml')}</TableHeadCell>
                    </TableRow>
                </TableHead>
                <TableBody>
                    {(report.agents || []).map(a => {
                        const ok = a.status === 200;
                        const words = a.html ? a.html.words : 0;
                        return (
                            // The marks column wraps to two lines on a narrow drawer, and a
                            // default 48px row would scroll-clip the second one.
                            <TableRow key={a.name} hasMultipleLines>
                                <TableBodyCell>{a.name}</TableBodyCell>
                                <TableBodyCell textAlign="right">
                                    <Chip
                                        label={a.status === null || a.status === undefined ? '—' : String(a.status)}
                                        color={ok ? 'success' : 'danger'}
                                    />
                                </TableBodyCell>
                                <TableBodyCell textAlign="right">{a.ms}ms</TableBodyCell>
                                <TableBodyCell textAlign="right">{words.toLocaleString()}</TableBodyCell>
                                <TableBodyCell>
                                    {a.html ? (
                                        <span className={styles.marks}>
                                            <span className={a.html.h1Count ? styles.on : styles.off}>{t('crawler.h1')}</span>
                                            <span className={a.html.metaDescription ? styles.on : styles.off}>{t('crawler.metaDescription')}</span>
                                            <span className={a.html.canonical ? styles.on : styles.off}>{t('crawler.canonical')}</span>
                                            <span className={styles.plain}>{a.html.links} {t('crawler.links')}</span>
                                            <span className={a.html.jsonLd ? styles.on : styles.off}>{t('crawler.jsonLd')}</span>
                                        </span>
                                    ) : <span className={styles.off}>—</span>}
                                </TableBodyCell>
                            </TableRow>
                        );
                    })}
                </TableBody>
            </Table>

            {report.blockedButAllowedCount > 0 && (
                <p className={styles.mismatch}>
                    {t('crawler.explain.blockedButAllowed', {count: report.blockedButAllowedCount})}
                </p>
            )}
            {report.reachableButDisallowedCount > 0 && (
                <p className={styles.mismatch}>
                    {t('crawler.explain.reachableButDisallowed', {count: report.reachableButDisallowedCount})}
                </p>
            )}
            {(report.agents || []).some(a => a.status === 403) && (
                <p className={styles.explain}>{t('crawler.explain.403')}</p>
            )}
            {control < MIN_CONTROL_WORDS && (
                <p className={styles.explain}>{t('crawler.explain.empty')}</p>
            )}
            {control >= MIN_CONTROL_WORDS && (report.agents || []).some(a => a.html && a.html.words < control * THIN_RATIO) && (
                <p className={styles.explain}>{t('crawler.explain.jsOnly')}</p>
            )}
        </div>
    );
};

CrawlerTab.propTypes = {
    report: PropTypes.object.isRequired
};
