/**
 * A flag emoji for a locale, or null when we cannot tell honestly.
 *
 * A flag is a country and a locale is a language, so the mapping is a
 * convention rather than a fact. Where the locale carries a region (fr_BE) we
 * use it, because that is the site's own statement. Otherwise we fall back to
 * the country the language is most commonly written for, and return null
 * rather than guess when there is no reasonable answer: no flag reads better
 * than the wrong one.
 */
const DEFAULT_REGION = {
    ar: 'SA', cs: 'CZ', da: 'DK', de: 'DE', el: 'GR', en: 'GB', es: 'ES',
    fi: 'FI', fr: 'FR', he: 'IL', hi: 'IN', hu: 'HU', it: 'IT', ja: 'JP',
    ko: 'KR', nl: 'NL', no: 'NO', pl: 'PL', pt: 'PT', ro: 'RO', ru: 'RU',
    sk: 'SK', sv: 'SE', th: 'TH', tr: 'TR', uk: 'UA', vi: 'VN', zh: 'CN'
};

export function flagOf(locale) {
    if (!locale) {
        return null;
    }

    const parts = String(locale).replace('-', '_').split('_');
    const base = (parts[0] || '').toLowerCase();
    const region = (parts[1] || DEFAULT_REGION[base] || '').toUpperCase();

    if (!/^[A-Z]{2}$/.test(region)) {
        return null;
    }

    // A flag emoji is the two letters of the country code as regional indicators.
    return String.fromCodePoint(...[...region].map(c => 0x1F1E6 + c.charCodeAt(0) - 65));
}

/** "Français 🇫🇷", or just the name when we have no flag for it. */
export function languageLabel(locale, displayName) {
    const name = displayName || locale;
    const flag = flagOf(locale);
    return flag ? `${name} ${flag}` : name;
}
