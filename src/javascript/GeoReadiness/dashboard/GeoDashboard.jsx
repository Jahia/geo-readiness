import React, {useState} from 'react';
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
import {languageLabel} from '../util/languageFlag';
import styles from './GeoDashboard.module.css';

const NS = 'geo-readiness';

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
    const [tab, setTab] = useState('score');

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

    return (
        <LayoutContent
            hasPadding
            className={styles.layout}
            header={
                <Header
                    title={t('dashboard.title', {site: siteInfo.displayName, language: label})}
                    toolbarLeft={
                        <Tab>
                            <TabItem
                                label={t('dashboard.tab.score')}
                                isSelected={tab === 'score'}
                                onClick={() => setTab('score')}
                            />
                            <TabItem
                                label={t('dashboard.tab.languages')}
                                isSelected={tab === 'languages'}
                                onClick={() => setTab('languages')}
                            />
                            <TabItem
                                label={t('dashboard.tab.sitemap')}
                                isSelected={tab === 'sitemap'}
                                onClick={() => setTab('sitemap')}
                            />
                            <TabItem
                                label={t('dashboard.tab.links')}
                                isSelected={tab === 'links'}
                                onClick={() => setTab('links')}
                            />
                            <TabItem
                                label={t('dashboard.tab.vanity')}
                                isSelected={tab === 'vanity'}
                                onClick={() => setTab('vanity')}
                            />
                            <TabItem
                                label={t('dashboard.tab.freshness')}
                                isSelected={tab === 'freshness'}
                                onClick={() => setTab('freshness')}
                            />
                            <TabItem
                                label={t('dashboard.tab.robots')}
                                isSelected={tab === 'robots'}
                                onClick={() => setTab('robots')}
                            />
                            <TabItem
                                label={t('dashboard.tab.llms')}
                                isSelected={tab === 'llms'}
                                onClick={() => setTab('llms')}
                            />
                            <TabItem
                                label={t('dashboard.tab.visibility')}
                                isSelected={tab === 'visibility'}
                                onClick={() => setTab('visibility')}
                            />
                        </Tab>
                    }
                />
            }
        >
            <div className={styles.content}>
                {tab === 'score' && <SiteScorePanel path={sitePath} language={language}/>}
                {tab === 'languages' && <LanguagesPanel path={sitePath} language={language}/>}
                {tab === 'sitemap' && <SitemapPanel path={sitePath} language={language}/>}
                {tab === 'links' && <LinksPanel path={sitePath} language={language}/>}
                {tab === 'vanity' && <VanityPanel path={sitePath} language={language}/>}
                {tab === 'freshness' && <FreshnessPanel path={sitePath} language={language}/>}
                {tab === 'robots' && <RobotsControlPanel path={sitePath} language={language}/>}
                {tab === 'llms' && <LlmsGeneratorPanel path={sitePath} language={language}/>}
                {tab === 'visibility' && <VisibilityPanel path={sitePath} language={language}/>}
            </div>
        </LayoutContent>
    );
};
