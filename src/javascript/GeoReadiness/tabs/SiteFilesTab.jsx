import React from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {
    Chip, Table, TableBody, TableBodyCell, TableHead, TableHeadCell, TableRow, Typography
} from '@jahia/moonstone';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';

export const SiteFilesTab = ({report}) => {
    const {t} = useTranslation(NS);
    const files = report.siteFiles || {};
    const robots = files.robots || {};
    const llms = files.llms || {};
    const llmsFull = files.llmsFull || {};
    const agents = robots.agents || [];

    const named = robots.namedAiBotCount || 0;
    const disallowed = robots.disallowedAiBotCount || 0;

    return (
        <div>
            {/* robots.txt */}
            <Typography variant="subheading" className={styles.sectionTitle}>{t('files.robots.title')}</Typography>

            {!robots.present ? (
                <div className={`${styles.verdict} ${styles.warn}`}>
                    {t('files.robots.absent')}
                </div>
            ) : (
                <>
                    <div className={`${styles.verdict} ${named === 0 ? styles.warn : styles.good}`}>
                        {named === 0
                            ? t('files.robots.noneNamed')
                            : t('files.robots.someNamed', {count: named})}
                        {disallowed > 0 && ' ' + t('files.robots.someDisallowed', {count: disallowed})}
                    </div>

                    <Table className={styles.mTable}>
                        <TableHead>
                            <TableRow>
                                <TableHeadCell>{t('crawler.agent')}</TableHeadCell>
                                <TableHeadCell width="130px">{t('files.robots.declared')}</TableHeadCell>
                                <TableHeadCell width="130px">{t('files.robots.effective')}</TableHeadCell>
                                <TableHeadCell>{t('files.robots.rule')}</TableHeadCell>
                            </TableRow>
                        </TableHead>
                        <TableBody>
                            {agents.map(a => (
                                <TableRow key={a.name}>
                                    <TableBodyCell>{a.name}</TableBodyCell>
                                    <TableBodyCell>
                                        <Chip
                                            label={a.namedExplicitly ? t('files.robots.byName') : t('files.robots.byWildcard')}
                                            color={a.namedExplicitly ? 'accent' : 'default'}
                                        />
                                    </TableBodyCell>
                                    <TableBodyCell>
                                        <Chip
                                            label={a.allowed ? t('files.robots.allowed') : t('files.robots.disallowed')}
                                            color={a.allowed ? 'success' : 'danger'}
                                        />
                                    </TableBodyCell>
                                    <TableBodyCell className={styles.rule}>
                                        {a.rule || t('files.robots.noRule')}
                                    </TableBodyCell>
                                </TableRow>
                            ))}
                        </TableBody>
                    </Table>

                    {(robots.sitemaps || []).length > 0 && (
                        <p className={styles.explain}>
                            {t('files.robots.sitemaps', {count: robots.sitemaps.length})}
                            {' '}
                            {robots.sitemaps.join(', ')}
                        </p>
                    )}
                </>
            )}

            {/* llms.txt */}
            <Typography variant="subheading" className={styles.sectionTitle}>{t('files.llms.title')}</Typography>

            {llms.servedHtmlInstead ? (
                <div className={`${styles.verdict} ${styles.bad}`}>{t('files.llms.servedHtml')}</div>
            ) : llms.present ? (
                <>
                    <div className={`${styles.verdict} ${styles.good}`}>{t('files.llms.present')}</div>
                    <ul className={styles.facts}>
                        <li>{t('files.llms.h1')}: <strong>{llms.h1 || t('crawler.missing')}</strong></li>
                        <li>{t('files.llms.summary')}: <strong>{llms.hasSummary ? t('crawler.present') : t('crawler.missing')}</strong></li>
                        <li>{t('files.llms.sections')}: <strong>{llms.sections ?? 0}</strong></li>
                        <li>{t('files.llms.links')}: <strong>{llms.links ?? 0}</strong></li>
                        <li>{t('files.llms.contentType')}: <strong>{llms.contentType || '—'}</strong></li>
                    </ul>
                </>
            ) : (
                <div className={`${styles.verdict} ${styles.warn}`}>
                    {t('files.llms.absent', {status: llms.status ?? '—'})}
                </div>
            )}

            <p className={styles.explain}>
                {t('files.llmsFull.label')}:{' '}
                <strong>{llmsFull.present ? t('crawler.present') : t('crawler.missing')}</strong>
                {' '}({llmsFull.status ?? '—'})
            </p>

            <p className={styles.explain}>{t('files.editedInSettings')}</p>
        </div>
    );
};

SiteFilesTab.propTypes = {
    report: PropTypes.object.isRequired
};
