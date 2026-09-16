/**
 * What the four repository-backed scans actually REPORT.
 *
 * Nothing asserted any of it before this file. `languages` was called once in
 * the whole suite, inside rate-limit.spec.ts, as an example of an action that is
 * NOT metered - and that call checks only that the endpoint answered 200.
 * `freshness`, `sitemap` and `schema` were never called at all.
 *
 * So the coverage these four had was the servlet's plumbing: authentication, the
 * permission gate, the shape of a refusal. None of it touched what the checks
 * compute. `Languages.check` could have swapped translated and missing, telling
 * a customer their French site is complete when it is empty, and the suite would
 * have stayed green.
 *
 * They share a reason for being end-to-end rather than unit tests: all four read
 * the repository through a static JCRTemplate or JCRSessionFactory, and three of
 * them go through PublishedMap.forSite, which runs its query inside a real GUEST
 * session. Mocking that session would mock the one thing that decides the
 * answer - the same argument visibility.spec.ts makes for itself.
 *
 * Each block below asserts a PREMISE first. Without it a fixture that failed to
 * build produces an empty scan, and every assertion after it passes by
 * describing nothing.
 */

import {asUser} from '../support/auth';
import {ENDPOINTS, expectJsonOk, geoPost} from '../support/geo';
import {
    configureModuleForTests,
    geoFixture,
    setupGeoFixture,
    teardownGeoFixture
} from '../support/fixtures';

describe('site-scan — what the repository-backed scans report', () => {
    const fixture = geoFixture('geoout');

    before(() => {
        setupGeoFixture(fixture);
        configureModuleForTests();
    });

    after(() => {
        teardownGeoFixture(fixture);
    });

    beforeEach(() => {
        asUser(fixture.publisher);
    });

    const post = (action: string, extra: Record<string, unknown> = {}) => geoPost(ENDPOINTS.siteScan, {
        action,
        path: fixture.sitePath,
        language: 'en',
        ...extra
    });

    // ------------------------------------------------------------- languages

    describe('languages', () => {
        it('reports a row per site language, with the counts that row is made of', () => {
            post('languages').then(response => {
                expectJsonOk(response, 'a publisher may ask about their own site');

                // The premise. A site with no languages makes every count below
                // trivially true.
                expect(response.body.languages, 'the site must declare its languages')
                    .to.be.an('array').and.not.be.empty;
                expect(response.body.total, 'the site must have pages to translate')
                    .to.be.greaterThan(0);

                const rows = response.body.rows;
                expect(rows, 'one row per declared language').to.be.an('array');
                expect(rows.length).to.equal(response.body.languages.length);
            });
        });

        it('counts English as translated rather than missing', () => {
            post('languages').then(response => {
                const en = response.body.rows.find(r => r.language === 'en');

                expect(en, 'the fixture builds its pages in English').to.exist;
                // The direction matters and nothing checked it. Swapping these
                // two would report a fully translated site as entirely missing,
                // and the endpoint would still answer 200.
                expect(en.translated, 'English pages are translated').to.be.greaterThan(0);
                expect(en.missing, 'and none of them are missing').to.equal(0);
            });
        });

        it('states coverage as a percentage consistent with its own counts', () => {
            post('languages').then(response => {
                const en = response.body.rows.find(r => r.language === 'en');
                const expected = Math.round((en.translated * 100) / response.body.total);

                // Derived, not asserted as a literal: the number the dashboard
                // quotes must be the one its own row adds up to.
                expect(en.coverage, 'coverage is translated over total').to.equal(expected);
                expect(en.coverage).to.be.within(0, 100);
            });
        });

        it('separates languages it has measured from languages it has not', () => {
            post('languages').then(response => {
                // measured + unmeasured is the whole set, and a language is in
                // exactly one of them. A scan that has not run leaves everything
                // unmeasured, which is a real answer rather than a zero score.
                const measured = response.body.measured || 0;
                const unmeasured = response.body.unmeasured || 0;

                expect(measured + unmeasured, 'every language is in exactly one group')
                    .to.equal(response.body.languages.length);
            });
        });
    });

    // ------------------------------------------------------------- freshness

    describe('freshness', () => {
        it('ages every published page and stores the result', () => {
            post('freshness').then(response => {
                expectJsonOk(response, 'freshness is a repository query, open to a publisher');

                const f = response.body.freshness;
                expect(f, 'the answer is stored on the scan state, not only returned').to.exist;

                // The premise: a site with nothing published ages nothing.
                expect(f.total + f.undated, 'the fixture must have published pages')
                    .to.be.greaterThan(0);
                expect(f.language).to.equal('en');
            });
        });

        it('buckets pages into the five age bands, adding up to the pages it dated', () => {
            post('freshness').then(response => {
                const f = response.body.freshness;
                const dist = f.distribution;

                expect(dist, 'five bands, always present even when empty')
                    .to.be.an('array').and.have.length(5);
                expect(dist.map(b => b.label))
                    .to.deep.equal(['month', 'quarter', 'halfYear', 'year', 'older']);

                // The bands partition the dated pages. If they did not, the
                // histogram would show a different site from the one scanned.
                const summed = dist.reduce((n, b) => n + b.count, 0);
                expect(summed, 'every dated page lands in exactly one band')
                    .to.equal(f.total);
            });
        });

        it('lists pages oldest first, with the undated ahead of them', () => {
            post('freshness').then(response => {
                const items = response.body.freshness.items;
                expect(items, 'the per-page list').to.be.an('array').and.not.be.empty;

                // Undated carries days = null and must come FIRST: "nobody knows
                // when this changed" is its own finding, and the list is capped,
                // so sorting it last is what drops exactly those pages. The code
                // stored days = -1 and sorted descending, which put them last -
                // the opposite of what the README and the changelog promised.
                const days = items.map(i => (i.days === null ? Number.MAX_SAFE_INTEGER : i.days));
                const sorted = [...days].sort((a, b) => b - a);
                expect(days, 'oldest first, undated ahead of all of them')
                    .to.deep.equal(sorted);
            });
        });

        it('honours the threshold it was asked for rather than the default', () => {
            post('freshness', {staleDays: 1}).then(response => {
                expect(response.body.freshness.staleDays,
                    'the caller asked for one day and gets one day').to.equal(1);
            });
        });

        it('groups by section and by type, and each group is flagged on its NEWEST item', () => {
            post('freshness').then(response => {
                const f = response.body.freshness;

                expect(f.bySection, 'editors divide work by section').to.be.an('array');
                expect(f.byType, 'a legal notice and a news article age differently')
                    .to.be.an('array');

                [...f.bySection, ...f.byType].forEach(g => {
                    // "Nothing here has been touched in a year" is actionable.
                    // "The oldest item is old" is true of every site ever made,
                    // so the flag has to come from the newest item, not the oldest.
                    expect(g.newest, `${g.name} newest is at most its oldest`)
                        .to.be.at.most(g.oldest);
                    expect(g.stale).to.equal(g.newest > f.staleDays);
                });
            });
        });
    });

    // --------------------------------------------------------------- sitemap

    describe('sitemap', () => {
        it('reports whether a sitemap was served, and says why when it was not', () => {
            post('sitemap').then(response => {
                expectJsonOk(response, 'a publisher may check their own sitemap');

                const s = response.body.sitemap;
                expect(s, 'stored on the scan state so the drawer can read it back').to.exist;
                expect(s.present, 'a boolean either way, never absent').to.be.a('boolean');
                expect(s.language).to.equal('en');

                // Absent is a real answer, not an error - but it must say which.
                // A site with no sitemap module installed is the common case in a
                // test environment, and the check has to be honest about it
                // rather than reporting an empty sitemap as a complete one.
                if (!s.present) {
                    expect(s.reason, 'an absent sitemap names its reason').to.be.a('string');
                }
            });
        });

        it('names which of the three ways it has no usable sitemap, and stops there', () => {
            post('sitemap').then(response => {
                const s = response.body.sitemap;

                if (s.present) {
                    // Only reached where a sitemap really is served. The
                    // comparison is against the repository, not against the
                    // sitemap's own idea of itself.
                    expect(s.published, 'the published count comes from the repository')
                        .to.be.a('number').and.to.be.greaterThan(0);
                    expect(s.missing, 'published pages the sitemap does not list')
                        .to.be.an('array');
                    expect(s.unknown, 'sitemap entries that are not published pages')
                        .to.be.an('array');
                    return;
                }

                // No sitemap is the case this environment actually exercises,
                // and it is a CONTRACT rather than a gap: everything below
                // `present` is unanswerable, so the check returns early and the
                // keys that would compare against the repository are absent
                // rather than zero. A zero would read as "the sitemap lists
                // nothing", which is a different and much louder finding.
                expect(s.reason, 'three ways to have no usable sitemap, and it says which')
                    .to.be.oneOf(['none', 'unreachable', 'notSitemap']);
                expect(s.published, 'absent, not zero').to.be.undefined;
                expect(s.entries, 'nothing was counted because nothing was read').to.be.undefined;
            });
        });

        it('does NOT reach the published map when there is no sitemap to compare', () => {
            // Worth asserting because it is the difference between covering a
            // code path and appearing to. SitemapCheck calls PublishedMap.forSite
            // only after it has a sitemap in hand; with none, that call never
            // happens. Anyone counting this spec as coverage of forSite should
            // read this test first - `freshness` and `schema` are what exercise
            // it here.
            post('sitemap').then(response => {
                const s = response.body.sitemap;
                if (!s.present) {
                    expect(s.published, 'the repository was never asked').to.be.undefined;
                }
            });
        });
    });

    // ---------------------------------------------------------------- schema

    describe('schema', () => {
        it('reports coverage over the site and the vocabulary the mapper needs', () => {
            post('schema').then(response => {
                expectJsonOk(response, 'the content model is the source; no scan required');

                expect(response.body.total, 'the premise: there are items to map')
                    .to.be.a('number').and.to.be.greaterThan(0);
                expect(response.body.language).to.equal('en');

                // The vocabulary is what the mapping interface offers. Without it
                // the panel renders an empty dropdown and the feature is dead,
                // which no status check would notice.
                expect(response.body.vocabulary, 'the schema.org types on offer')
                    .to.be.an('object').and.to.not.be.empty;
            });
        });

        it('counts mapped and complete items as subsets of the total', () => {
            post('schema').then(response => {
                const {total, mappedItems, completeItems} = response.body;

                // Nested, not independent: an item cannot be complete without
                // being mapped, nor mapped without existing. Reporting more
                // complete than mapped would be a number nobody could act on.
                expect(mappedItems, 'mapped is a subset of everything').to.be.at.most(total);
                expect(completeItems, 'complete is a subset of mapped').to.be.at.most(mappedItems);
            });
        });

        it('returns the stored mapping, so the panel opens on what was saved', () => {
            const map = {'jnt:page': 'WebPage'};

            geoPost(ENDPOINTS.siteScan, {
                action: 'saveSchemaMap',
                path: fixture.sitePath,
                language: 'en',
                map
            }).then(saved => {
                expectJsonOk(saved, 'a publisher may save a mapping for their own site');

                return post('schema');
            }).then(response => {
                // A mapping is a statement about content types and is stored, not
                // applied to a page. Reading it back is how the panel shows the
                // starting point, and nothing asserted the round trip.
                expect(response.body.schemaMap, 'the saved mapping comes back')
                    .to.deep.include(map);
            });
        });
    });
});
