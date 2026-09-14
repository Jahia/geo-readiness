import React, {useEffect, useMemo, useState} from 'react';
import {useSelector} from 'react-redux';
import {useTranslation} from 'react-i18next';
import {useSiteInfo} from '@jahia/data-helper';
import {Header, LayoutContent, Tab, TabItem} from '@jahia/moonstone';
import {RobotsControlPanel} from '../tabs/RobotsControlPanel';
import {LlmsGeneratorPanel} from '../tabs/LlmsGeneratorPanel';
import {VisibilityPanel} from '../tabs/VisibilityPanel';
import {SiteScorePanel} from '../tabs/SiteScorePanel';
import {SitemapPanel} from '../tabs/SitemapPanel';
import {LinksPanel} from '../tabs/LinksPanel';
import {VanityPanel} from '../tabs/VanityPanel';
import {FreshnessPanel} from '../tabs/FreshnessPanel';
import {LanguagesPanel} from '../tabs/LanguagesPanel';
import {SchemaPanel} from '../tabs/SchemaPanel';
import {ReportPanel} from '../tabs/ReportPanel';
import {fetchReportStatus} from '../api/geoReport';
import {languageLabel} from '../util/languageFlag';
import styles from './GeoDashboard.module.css';

const NS = 'geo-readiness';

/**
 * Ten panels is a list, not a structure. These are the same three groups the
 * drawer already sorts its checks into - can a crawler reach it, is what
 * arrives usable, site-level files - so an editor learns one vocabulary and
 * meets it again here rather than a second taxonomy invented for the dashboard.
 *
 * The site score sits outside them because it is the summary of all three.
 */
const GROUPS = [
    {id: 'overview', tabs: ['score']},
    // 'report' joins overview when a provider is configured; see below.
    {id: 'reach', tabs: ['visibility', 'links', 'vanity']},
    {id: 'usable', tabs: ['schema', 'languages', 'freshness']},
    {id: 'files', tabs: ['sitemap', 'robots', 'llms']}
];

/**
 * Site-level GEO settings, under Additional > SEO.
 *
 * The drawer answers a question about one page. robots.txt and llms.txt are
 * properties of the whole site, so editing them from a page drawer was the
 * wrong scope: whichever page you had open, you were changing the same two
 * files. They live here instead, and the drawer only reports on them.
 *
 * Laid out like the other settings panels: LayoutContent with a Header whose
 * title names the site and the language, and the two files on their own tabs.
 */
export const GeoDashboard = () => {
    const {t} = useTranslation(NS);
    const siteKey = useSelector(state => state.site);
    const language = useSelector(state => state.language);
    const uilang = useSelector(state => state.uilang);
    const [group, setGroup] = useState(GROUPS[0].id);
    const [tab, setTab] = useState(GROUPS[0].tabs[0]);
    const [reportEnabled, setReportEnabled] = useState(false);

    // The written report needs a configured provider. Asked once; the answer
    // never carries the key, only whether there is one.
    useEffect(() => {
        let alive = true;
        fetchReportStatus()
            .then(s => alive && setReportEnabled(Boolean(s && s.enabled)))
            .catch(() => alive && setReportEnabled(false));
        return () => {
            alive = false;
        };
    }, []);

    const groups = useMemo(() => GROUPS.map(g => (
        g.id === 'overview' && reportEnabled ? {...g, tabs: [...g.tabs, 'report']} : g
    )), [reportEnabled]);

    const {siteInfo} = useSiteInfo({
        siteKey,
        displayLanguage: language,
        uiLanguage: uilang || language
    });

    if (!siteKey) {
        return null;
    }

    if (!siteInfo || !siteInfo.displayName) {
        // LayoutContent's isLoading replaces the content with its own loader.
        return <LayoutContent hasPadding isLoading/>;
    }

    // The site's own name for this language, so the header reads the way the
    // rest of jContent does rather than showing a technical site key.
    const current = (siteInfo.languages || []).find(l => l.language === language);
    const label = languageLabel(language, current && (current.uiLanguageDisplayName || current.displayName));

    // Any path inside the site resolves to the site node server-side, and the
    // site node itself is the most honest thing to send from a site-level page.
    const sitePath = `/sites/${siteKey}`;
    const activeGroup = groups.find(g => g.id === group) || groups[0];

    return (
        <LayoutContent
            hasPadding
            className={styles.layout}
            header={
                <Header
                    title={t('dashboard.title', {site: siteInfo.displayName, language: label})}
                    toolbarLeft={
                        <Tab>
                            {groups.map(g => (
                                <TabItem
                                    key={g.id}
                                    label={t(`dashboard.group.${g.id}`)}
                                    isSelected={group === g.id}
                                    onClick={() => {
                                        setGroup(g.id);
                                        setTab(g.tabs[0]);
                                    }}
                                />
                            ))}
                        </Tab>
                    }
                />
            }
        >
            <div className={styles.content}>
                {/*
                  * Second level, quieter than the first. Skipped entirely for a
                  * group with one panel, where a row of one tab would just be
                  * furniture.
                  */}
                {activeGroup.tabs.length > 1 && (
                    <div className={styles.subTabs}>
                        <Tab>
                            {activeGroup.tabs.map(id => (
                                <TabItem
                                    key={id}
                                    label={t(`dashboard.tab.${id}`)}
                                    isSelected={tab === id}
                                    onClick={() => setTab(id)}
                                />
                            ))}
                        </Tab>
                    </div>
                )}

                {tab === 'score' && <SiteScorePanel path={sitePath} language={language}/>}
                {tab === 'report' && (
                    <ReportPanel
                        path={sitePath}
                        language={language}
                        reportLanguage={uilang || language}
                        siteKey={siteKey}
                        siteName={siteInfo.displayName}
                    />
                )}
                {tab === 'languages' && <LanguagesPanel path={sitePath} language={language}/>}
                {tab === 'sitemap' && <SitemapPanel path={sitePath} language={language}/>}
                {tab === 'links' && <LinksPanel path={sitePath} language={language}/>}
                {tab === 'vanity' && <VanityPanel path={sitePath} language={language}/>}
                {tab === 'freshness' && <FreshnessPanel path={sitePath} language={language}/>}
                {tab === 'schema' && <SchemaPanel path={sitePath} language={language}/>}
                {tab === 'robots' && <RobotsControlPanel path={sitePath} language={language}/>}
                {tab === 'llms' && <LlmsGeneratorPanel path={sitePath} language={language}/>}
                {tab === 'visibility' && <VisibilityPanel path={sitePath} language={language}/>}
            </div>
        </LayoutContent>
    );
};
