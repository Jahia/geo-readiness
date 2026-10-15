import React, {useCallback, useEffect, useState} from 'react';
import {useSelector} from 'react-redux';
import {useTranslation} from 'react-i18next';
import {useNodeChecks} from '@jahia/data-helper';
import {Banner, Button, Dropdown, Field, Header, Input, LayoutContent, Loader, Switch, Typography} from '@jahia/moonstone';
import {citationSettings} from '../api/citations';
import styles from './CitationsSettings.module.css';

const NS = 'geo-readiness';

const formatDate = iso => {
    if (!iso) {
        return '-';
    }

    try {
        return new Date(iso).toLocaleString();
    } catch (e) {
        return iso;
    }
};

/**
 * Site Settings > AI citations (BotRank): binds a site to a BotRank connection, a project and the
 * site's hosts, schedules the daily sync, and maps cited URLs that no page matched.
 *
 * The route option hides the menu entry on sites without the module; the component checks again,
 * because a direct URL or a site switch renders it anyway. The server checks site-admin on every call.
 */
export const CitationsSettings = () => {
    const {t} = useTranslation(NS);
    const siteKey = useSelector(state => state.site);
    const language = useSelector(state => state.language);
    const sitePath = `/sites/${siteKey}`;
    const {checksResult, loading: checking} = useNodeChecks({path: sitePath},
        {requireModuleInstalledOnSite: ['geo-readiness'], requiredPermission: ['site-admin']});

    const [status, setStatus] = useState(null);
    const [failed, setFailed] = useState(null);
    const [busy, setBusy] = useState(false);
    const [connection, setConnection] = useState('');
    const [projects, setProjects] = useState([]);
    const [projectsError, setProjectsError] = useState(null);
    const [project, setProject] = useState('');
    const [hosts, setHosts] = useState('');
    const [enabled, setEnabled] = useState(false);
    const [time, setTime] = useState('06:30');
    const [dirty, setDirty] = useState(false);

    const apply = useCallback(s => {
        setStatus(s);
        setConnection(s.connection || '');
        setProject(s.project || '');
        const configured = s.hosts || [];
        const suggested = (s.serverNames || []).filter(h => h && h !== 'localhost');
        setHosts((configured.length > 0 ? configured : suggested).join(', '));
        setEnabled(Boolean(s.enabled));
        setTime(s.time || '06:30');
        setDirty(false);
    }, []);

    const call = useCallback(async (action, payload = {}) => {
        setBusy(true);
        setFailed(null);
        try {
            const s = await citationSettings(action, {path: sitePath, language, ...payload});
            if (s && s.connections) {
                apply(s);
            }

            return s;
        } catch (e) {
            setFailed(e.code === 403 ? t('citationsSettings.forbidden') : (e.message || t('citationsSettings.error')));
            return null;
        } finally {
            setBusy(false);
        }
    }, [sitePath, language, apply, t]);

    useEffect(() => {
        if (checksResult) {
            call('status');
        }
    }, [checksResult, call]);

    const loadProjects = useCallback(async id => {
        setProjects([]);
        setProjectsError(null);
        if (!id) {
            return;
        }

        try {
            const r = await citationSettings('projects', {path: sitePath, language, connection: id});
            setProjects(r.projects || []);
        } catch (e) {
            setProjectsError(e.message || t('citationsSettings.error'));
        }
    }, [sitePath, language, t]);

    useEffect(() => {
        loadProjects(connection);
    }, [connection, loadProjects]);

    const save = () => call('save', {
        connection,
        project,
        hosts: hosts.split(',').map(h => h.trim()).filter(Boolean),
        enabled,
        time
    }).then(s => s && window.jahia?.toastDispatcher?.add({message: t('citationsSettings.saved'), variant: 'success'}));

    const syncNow = () => call('syncNow').then(s => s && window.jahia?.toastDispatcher?.add({
        message: t('citationsSettings.syncStarted'),
        variant: 'info'
    }));

    const map = url => {
        if (!window.CE_API?.openPicker) {
            return;
        }

        window.CE_API.openPicker({
            type: 'editorial',
            isMultiple: false,
            site: siteKey,
            lang: language,
            initialSelectedItem: [],
            setValue: ([selected]) => {
                if (selected && selected.path) {
                    call('map', {url, target: selected.path});
                }
            }
        });
    };

    const title = t('citationsSettings.title');
    if (checking) {
        return null;
    }

    if (!checksResult) {
        return (
            <LayoutContent
                hasPadding
                header={<Header title={title}/>}
                content={<div className={styles.card} role="status">{t('citationsSettings.notEnabled')}</div>}
            />
        );
    }

    const connections = (status && status.connections) || [];
    const chosen = connections.find(c => c.id === connection);
    const unresolved = (status && status.unresolved) || [];
    const mappings = (status && status.mappings) || {};
    const edit = setter => value => {
        setter(value);
        setDirty(true);
    };

    return (
        <LayoutContent
            hasPadding
            data-geo-role="citations-settings"
            header={(
                <Header
                    title={title}
                    mainActions={[
                        <Button key="sync" size="big" variant="outlined" label={t('citationsSettings.syncNow')}
                                isDisabled={busy || dirty || !status || !status.connection || status.status === 'running'}
                                onClick={syncNow}/>,
                        <Button key="save" size="big" color="accent" label={t('citationsSettings.save')}
                                isDisabled={busy || !dirty} onClick={save}/>
                    ]}
                />
            )}
            content={(
                <div className={styles.content}>
                    {failed && <div role="alert"><Banner variant="danger" title={t('citationsSettings.errorTitle')}>{failed}</Banner></div>}
                    {!status && !failed && <Loader size="big"/>}
                    {status && (
                        <>
                            <div className={styles.card}>
                                <Typography variant="body">{t('citationsSettings.intro')}</Typography>
                                {connections.length === 0 && (
                                    <Banner variant="info" title={t('citationsSettings.noConnectionTitle')}>
                                        {t('citationsSettings.noConnection')}
                                    </Banner>
                                )}
                                <Field id="brConnection" label={t('citationsSettings.connection')} helper={t('citationsSettings.connectionHelp')}>
                                    <Dropdown
                                        value={connection}
                                        placeholder={t('citationsSettings.choose')}
                                        data={connections.map(c => ({label: c.problem ? `${c.label} (${c.problem})` : c.label, value: c.id, isDisabled: Boolean(c.problem)}))}
                                        onChange={(e, item) => edit(setConnection)(item ? item.value : '')}
                                    />
                                </Field>
                                {chosen && chosen.problem && <Banner variant="warning" title={t('citationsSettings.connectionProblem')}>{chosen.problem}</Banner>}
                                <Field id="brProject" label={t('citationsSettings.project')} helper={t('citationsSettings.projectHelp')}>
                                    <Dropdown
                                        value={project}
                                        placeholder={t('citationsSettings.choose')}
                                        data={projects.map(p => ({label: `${p.name} (${p.id})`, value: p.id}))
                                            .concat(project && !projects.some(p => p.id === project) ? [{label: project, value: project}] : [])}
                                        onChange={(e, item) => edit(setProject)(item ? item.value : '')}
                                    />
                                </Field>
                                {projectsError && <Banner variant="warning" title={t('citationsSettings.projectsErrorTitle')}>{projectsError}</Banner>}
                                <Field id="brHosts" label={t('citationsSettings.hosts')} helper={t('citationsSettings.hostsHelp', {max: status.limits ? status.limits.hosts : 10})}>
                                    <Input value={hosts} aria-label={t('citationsSettings.hosts')} placeholder="www.example.com, fr.example.com"
                                           onChange={e => edit(setHosts)(e.target.value)}/>
                                </Field>
                                <Field id="brEnabled" label={t('citationsSettings.enabled')} helper={t('citationsSettings.enabledHelp')}>
                                    <span className={styles.inline}>
                                        <Switch checked={enabled} aria-label={t('citationsSettings.enabled')}
                                                onChange={(e, v, checked) => edit(setEnabled)(checked)}/>
                                        <Input value={time} aria-label={t('citationsSettings.time')} className={styles.time}
                                               onChange={e => edit(setTime)(e.target.value)}/>
                                        <Typography variant="caption">{t('citationsSettings.timeHelp')}</Typography>
                                    </span>
                                </Field>
                            </div>

                            <div className={styles.card}>
                                <Typography component="h2" variant="heading">{t('citationsSettings.lastSync')}</Typography>
                                <ul className={styles.facts}>
                                    <li>{t(`citationsSettings.status.${status.status}`, status.status)}</li>
                                    <li>{t('citationsSettings.syncedAt', {date: formatDate(status.syncedAt)})}</li>
                                    <li>{t('citationsSettings.latestAnswerAt', {date: formatDate(status.latestAnswerAt)})}</li>
                                    <li>{t('citationsSettings.nextRun', {date: formatDate(status.nextRun)})}</li>
                                    <li>{t('citationsSettings.citedPages', {count: status.citedPages})}</li>
                                </ul>
                                {status.message && <Banner variant={status.status === 'failed' ? 'danger' : 'info'} title={t('citationsSettings.messageTitle')}>{status.message}</Banner>}
                            </div>

                            <div className={styles.card}>
                                <Typography component="h2" variant="heading">{t('citationsSettings.unresolved')}</Typography>
                                <Typography variant="caption">{t('citationsSettings.unresolvedIntro')}</Typography>
                                {unresolved.length === 0 && <Typography variant="body">{t('citationsSettings.unresolvedNone')}</Typography>}
                                <ul className={styles.rows}>
                                    {unresolved.map(u => (
                                        <li key={u.url} className={styles.row}>
                                            <span className={styles.url}>{u.url}</span>
                                            <span className={styles.count}>{t('citationsSettings.citations', {count: u.citations})}</span>
                                            <Button label={t('citationsSettings.map')} isDisabled={busy} onClick={() => map(u.url)}/>
                                        </li>
                                    ))}
                                </ul>
                                {Object.keys(mappings).length > 0 && (
                                    <>
                                        <Typography component="h3" variant="subheading">{t('citationsSettings.mappings')}</Typography>
                                        <ul className={styles.rows}>
                                            {Object.keys(mappings).map(url => (
                                                <li key={url} className={styles.row}>
                                                    <span className={styles.url}>{url}</span>
                                                    <span className={styles.count}>{mappings[url].replace(/@[^@]+$/, '')}</span>
                                                    <Button variant="ghost" label={t('citationsSettings.unmap')} isDisabled={busy}
                                                            onClick={() => call('unmap', {url})}/>
                                                </li>
                                            ))}
                                        </ul>
                                        <Typography variant="caption">{t('citationsSettings.mappingsNote')}</Typography>
                                    </>
                                )}
                            </div>
                        </>
                    )}
                </div>
            )}
        />
    );
};
