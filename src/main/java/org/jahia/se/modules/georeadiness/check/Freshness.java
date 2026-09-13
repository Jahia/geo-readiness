package org.jahia.se.modules.georeadiness.check;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GEO-24. Where the site has gone stale.
 *
 * Content freshness is one of the criteria a crawl-based tool scores a site on,
 * and it is one of the few it cannot compute properly: it sees the dates a site
 * chooses to publish, on the pages it happened to crawl. The repository has the
 * real modification date of everything published, so the whole distribution is
 * one query.
 *
 * **Grouped, never a single list.** Ranking every page by age would put a legal
 * notice next to a news article and call both old, which is how a freshness
 * report gets ignored. The two breakdowns are the distinction the story asks
 * for: by **type**, because a property listing and a legal page age for
 * different reasons and at different rates, and by **section**, because that is
 * how an editor divides up the work. A group is flagged only when its *newest*
 * item is past the threshold - not its oldest, and not its average. "Nothing
 * here has been touched in a year" is a fact somebody can act on; "the oldest
 * item is old" is true of every site that has ever existed.
 *
 * No drawer line. An author editing a page already knows how old it is.
 */
public final class Freshness {

    /** A year. Long enough that a deliberately stable page is not nagged about. */
    public static final int DEFAULT_STALE_DAYS = 365;

    private static final long DAY_MS = 86_400_000L;
    /**
     * The per-item report is a list an editor reads, not a data export. Past
     * this many rows the answer is the grouping above it, so the list says it
     * was cut rather than growing without limit.
     */
    private static final int MAX_ITEMS = 5000;
    private static final int[] BUCKET_DAYS = {30, 90, 180, 365};
    private static final String[] BUCKET_LABELS = {"month", "quarter", "halfYear", "year", "older"};

    private Freshness() {
    }

    /**
     * Ages everything a visitor can read, in one language.
     *
     * Per language because that is what the dates mean: a translation carries
     * its own modification date, and a French page left behind while the English
     * one is maintained is exactly the finding worth having.
     */
    public static JSONObject check(String sitePath, String language, String base, int staleDays)
            throws RepositoryException {
        int threshold = staleDays > 0 ? staleDays : DEFAULT_STALE_DAYS;
        JSONObject out = new JSONObject();
        out.put("language", language);
        out.put("staleDays", threshold);

        Map<String, PublishedMap.Entry> published = PublishedMap.forSite(sitePath, base);
        long now = System.currentTimeMillis();

        int[] buckets = new int[BUCKET_LABELS.length];
        Map<String, Group> bySection = new LinkedHashMap<>();
        Map<String, Group> byType = new LinkedHashMap<>();
        int dated = 0;
        int undated = 0;
        List<Item> items = new ArrayList<>();

        for (Map.Entry<String, PublishedMap.Entry> row : published.entrySet()) {
            PublishedMap.Entry e = row.getValue();
            if (!language.equals(e.language)) {
                continue;
            }
            String section = PublishedMap.sectionOf(sitePath, e.jcrPath);
            if (e.modifiedAt <= 0) {
                undated++;
                items.add(new Item(row.getKey(), e, section, -1));
                continue;
            }
            dated++;
            int days = (int) ((now - e.modifiedAt) / DAY_MS);
            buckets[bucketOf(days)]++;
            bySection.computeIfAbsent(section, Group::new).add(days);
            byType.computeIfAbsent(e.nodeType, Group::new).add(days);
            items.add(new Item(row.getKey(), e, section, days));
        }

        // An item with no date first: "nobody knows when this changed" is a
        // finding of its own. Then oldest to newest, so the list opens on what
        // needs attention rather than on whatever the repository returned first.
        items.sort((a, b) -> a.days == b.days ? a.path.compareTo(b.path) : Integer.compare(b.days, a.days));

        out.put("total", dated);
        out.put("undated", undated);

        JSONArray dist = new JSONArray();
        for (int i = 0; i < buckets.length; i++) {
            JSONObject b = new JSONObject();
            b.put("label", BUCKET_LABELS[i]);
            b.put("count", buckets[i]);
            dist.put(b);
        }
        out.put("distribution", dist);

        JSONArray list = new JSONArray();
        for (Item i : items.subList(0, Math.min(items.size(), MAX_ITEMS))) {
            list.put(i.toJson(threshold));
        }
        out.put("items", list);
        out.put("itemsTruncated", items.size() > MAX_ITEMS);

        out.put("bySection", groupsOf(bySection, threshold));
        out.put("byType", groupsOf(byType, threshold));
        out.put("stale", countStale(bySection, threshold) + countStale(byType, threshold));
        return out;
    }

    private static int countStale(Map<String, Group> groups, int threshold) {
        int n = 0;
        for (Group g : groups.values()) {
            if (g.newest > threshold) {
                n++;
            }
        }
        return n;
    }

    /** Oldest-first, because that is the order somebody would work through them. */
    private static JSONArray groupsOf(Map<String, Group> groups, int threshold) {
        List<Group> all = new ArrayList<>(groups.values());
        all.sort(Comparator.comparingInt((Group g) -> g.newest).reversed());
        JSONArray out = new JSONArray();
        for (Group g : all) {
            JSONObject o = new JSONObject();
            o.put("name", g.name);
            o.put("count", g.days.size());
            o.put("newest", g.newest);
            o.put("median", g.median());
            o.put("oldest", g.oldest);
            o.put("stale", g.newest > threshold);
            out.put(o);
        }
        return out;
    }

    private static int bucketOf(int days) {
        for (int i = 0; i < BUCKET_DAYS.length; i++) {
            if (days <= BUCKET_DAYS[i]) {
                return i;
            }
        }
        return BUCKET_LABELS.length - 1;
    }

    /** One published item, as the full report lists it. */
    private static final class Item {
        private final String path;
        private final String jcrPath;
        private final String title;
        private final String type;
        private final String section;
        private final String modifiedOn;
        /** Days since the last change, or -1 when the item carries no date. */
        private final int days;

        private Item(String path, PublishedMap.Entry e, String section, int days) {
            this.path = path;
            this.jcrPath = e.jcrPath;
            this.title = e.title;
            this.type = e.nodeType;
            this.section = section;
            this.modifiedOn = e.modifiedOn;
            this.days = days;
        }

        private JSONObject toJson(int threshold) {
            JSONObject o = new JSONObject();
            o.put("path", path);
            o.put("jcrPath", jcrPath);
            o.put("title", title == null || title.isEmpty() ? path : title);
            o.put("type", type);
            o.put("section", section);
            o.put("modifiedOn", modifiedOn == null ? JSONObject.NULL : modifiedOn);
            o.put("days", days < 0 ? JSONObject.NULL : days);
            o.put("stale", days >= 0 && days > threshold);
            return o;
        }
    }

    private static final class Group {
        final String name;
        final List<Integer> days = new ArrayList<>();
        int newest = Integer.MAX_VALUE;
        int oldest;

        Group(String name) {
            this.name = name;
        }

        void add(int d) {
            days.add(d);
            newest = Math.min(newest, d);
            oldest = Math.max(oldest, d);
        }

        int median() {
            List<Integer> sorted = new ArrayList<>(days);
            sorted.sort(Comparator.naturalOrder());
            return sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        }
    }
}
