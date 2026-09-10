import React from 'react';
import i18next from 'i18next';
import {registry} from '@jahia/ui-extender';
import {GeoReadinessAction} from './GeoReadiness/GeoReadinessAction';
import {GeoDashboard} from './GeoReadiness/dashboard/GeoDashboard';
import en from '../main/resources/javascript/locales/en.json';
import fr from '../main/resources/javascript/locales/fr.json';

const NS = 'geo-readiness';

function registerTranslations() {
    [['en', en], ['fr', fr]].forEach(([lang, resource]) => {
        if (!i18next.hasResourceBundle(lang, NS)) {
            i18next.addResourceBundle(lang, NS, resource[NS], true, true);
        }
    });
}

function register() {
    const buttonIcon = window.jahia?.moonstone?.toIconComponent(
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24" fill="currentColor">' +
        '<path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 2c1.7 0 3.2.6 4.4 1.5l-1.5 1.5A5.9 5.9 0 0 0 12 6a6 6 0 1 0 6 6c0-1-.3-2-.8-2.9l1.5-1.5A8 8 0 1 1 12 4z"/>' +
        '<circle cx="12" cy="12" r="2.6"/>' +
        '</svg>'
    );

    registry.add('action', 'geoReadiness', {
        targets: ['headerPrimaryActions:890'],
        buttonIcon,
        buttonLabel: `${NS}:action.open`,
        component: GeoReadinessAction
    });

    // Site-level settings live under Additional > SEO, next to Robots.txt and
    // Sitemap, because robots.txt and llms.txt belong to the site and not to
    // whichever page an editor happens to have open.
    registry.add('adminRoute', 'siteSettingsSeo/geoReadiness', {
        targets: ['jcontent-siteSettingsSeo:80'],
        label: `${NS}:dashboard.navLabel`,
        isSelectable: true,
        requiredPermission: 'publish',
        requireModuleInstalledOnSite: 'geo-readiness',
        render: () => <GeoDashboard/>
    });
}

export default function () {
    registerTranslations();
    register();
}
