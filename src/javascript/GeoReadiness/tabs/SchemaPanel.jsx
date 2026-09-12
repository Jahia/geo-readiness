import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Button, Chip, Dropdown, Loader, Separator, Typography} from '@jahia/moonstone';
import {checkSchema, saveSchemaMap} from '../api/siteScore';
import {StackedBar} from '../charts/Charts';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';
const UNMAPPED = '';
const MUTED = 'none';

/**
 * GEO-23 across the site: which content types produce structured data.
 *
 * The mapping is the product here. Nothing guesses that a custom type is a
 * Product because of its name - that would be reading somebody's intent out of
 * a string - so a type the module does not know starts unmapped and says so.
 * The person who defined the type is the one who knows what it is.
 */
export const SchemaPanel = ({path, language}) => {
    const {t} = useTranslation(NS);
    const [data, setData] = useState(null);
    const [draft, setDraft] = useState({});
    const [busy, setBusy] = useState(false);
    const [saved, setSaved] = useState(false);
    const [failed, setFailed] = useState(false);

    const load = useCallback(async () => {
        try {
            const d = await checkSchema({path, language});
            setData(d);
            setDraft({...(d.schemaMap || {})});
        } catch (e) {
            setFailed(true);
        }
    }, [path, language]);

    useEffect(() => {
        load();
    }, [load]);

    const save = useCallback(async () => {
        setBusy(true);
        setSaved(false);
        try {
            await saveSchemaMap({path, language, map: draft});
            await load();
            setSaved(true);
        } catch (e) {
            setFailed(true);
        } finally {
            setBusy(false);
        }
    }, [path, language, draft, load]);

    if (!data && !failed) {
        return <Loader size="big"/>;
    }

    const types = (data && data.types) || [];
    const offered = (data && data.vocabulary && data.vocabulary.types) || [];
    const dirty = data && JSON.stringify(draft) !== JSON.stringify(data.schemaMap || {});

    const options = [
        {label: t('schema.mapping.unmapped'), value: UNMAPPED},
        {label: t('schema.mapping.none'), value: MUTED},
        ...offered.map(x => ({label: x, value: x}))
    ];

    return (
        <div>
            <Typography variant="subheading" className={styles.panelTitle}>
                {t('schema.panel.title')}
            </Typography>
            <Typography variant="caption" className={styles.panelIntro}>
                {t('schema.panel.intro')}
            </Typography>

            {failed && (
                <Banner variant="danger" title={t('schema.panel.errorTitle')}>
                    {t('schema.panel.error')}
                </Banner>
            )}

            {data && (
                <>
                    <Separator spacing="big" size="full"/>
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('schema.panel.counts', {
                            mapped: data.mappedItems,
                            complete: data.completeItems,
                            total: data.total
                        })}
                    </Typography>

                    {/* Part-to-whole in one bar: what can emit, what is mapped but thin, what is not mapped. */}
                    <StackedBar
                        total={data.total}
                        restLabel={t('schema.panel.legend.unmapped')}
                        segments={[
                            {key: 'complete', label: t('schema.panel.legend.complete'), value: data.completeItems, tone: 'series1'},
                            {key: 'incomplete', label: t('schema.panel.legend.incomplete'), value: data.mappedItems - data.completeItems, tone: 'series2'}
                        ]}
                    />

                    <ul className={styles.checkList}>
                        {types.map(row => {
                            const current = draft[row.nodeType] !== undefined ?
                                draft[row.nodeType] :
                                (row.mapped ? row.schemaType : UNMAPPED);
                            return (
                                <li key={row.nodeType} className={styles.checkItem}>
                                    <span className={styles.checkText}>
                                        <Typography variant="body" className={styles.checkLabel}>
                                            {row.nodeType}
                                        </Typography>
                                        <Typography variant="caption" className={styles.checkFix}>
                                            {t('schema.panel.items', {count: row.count})}
                                            {row.mapped && (row.missing || []).length > 0 ?
                                                ` · ${t('schema.panel.noSource', {
                                                    properties: row.missing.join(', ')
                                                })}` : ''}
                                            {row.mapped && (row.missing || []).length === 0 ?
                                                ` · ${t('schema.panel.readyType')}` : ''}
                                        </Typography>
                                        {row.mapped && (row.recommendedMissing || []).length > 0 && (
                                            <Typography variant="caption" className={styles.checkFix}>
                                                {t('schema.panel.thin', {
                                                    properties: row.recommendedMissing.join(', ')
                                                })}
                                            </Typography>
                                        )}
                                    </span>
                                    <span className={styles.checkMeta}>
                                        {row.mapped && (
                                            <Chip
                                                label={(row.missing || []).length === 0 ?
                                                    t('schema.panel.ready') :
                                                    t('schema.panel.gaps', {
                                                        count: row.missing.length
                                                    })}
                                                color={(row.missing || []).length === 0 ? 'success' : 'warning'}
                                            />
                                        )}
                                        <Dropdown
                                            size="small"
                                            isDisabled={busy}
                                            value={current}
                                            data={options}
                                            onChange={(e, item) => setDraft(d => ({
                                                ...d,
                                                [row.nodeType]: (item && item.value) || UNMAPPED
                                            }))}
                                        />
                                    </span>
                                </li>
                            );
                        })}
                    </ul>

                    <div className={styles.actions}>
                        <Button
                            size="big"
                            color="accent"
                            isDisabled={busy || !dirty}
                            label={busy ? t('schema.panel.saving') : t('schema.panel.save')}
                            onClick={save}
                        />
                        {saved && !dirty && (
                            <Typography variant="caption">{t('schema.panel.saved')}</Typography>
                        )}
                    </div>

                    <p className={styles.explain}>{t('schema.panel.limits')}</p>
                </>
            )}
        </div>
    );
};

SchemaPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
