package org.jahia.se.modules.georeadiness.check;

import org.jahia.se.modules.georeadiness.util.PublicUrls;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.query.Query;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * GEO-23. Schema.org output derived from the content model.
 *
 * Every other tool infers structured data from rendered text, which means
 * guessing at what a page is about from the words on it. The definition already
 * says what the content *is*: a type with `price`, `images` and `address` is a
 * product listing whatever the prose reads like. Deriving from the model is both
 * cheaper and more accurate, and it is the one thing here that nothing outside
 * the CMS can do.
 *
 * **This generates, it does not inject.** Nothing here writes into a page or a
 * template. The drawer shows the JSON-LD for the page in front of you to copy,
 * and the dashboard shows coverage. That is the same line the rest of the module
 * draws between reporting and writing, and it matters more here than anywhere:
 * structured data that contradicts the visible page is worse than none, so a
 * human confirms every snippet before it ships.
 *
 * **Gaps are the output, not a failure.** A required property with no source in
 * the content model is reported as exactly that. Filling it from somewhere
 * plausible - the page title, a sibling property, a default - is how structured
 * data ends up disagreeing with the page it describes.
 */
public final class StructuredData {

    private static final Logger logger = LoggerFactory.getLogger(StructuredData.class);

    private static final int MAX_NODES = 10_000;
    private static final String TYPE = "@type";
    private static final String MAPPED = "mapped";
    private static final String OPENING_HOURS = "openingHours";
    private static final int MAX_DESC = 320;

    private StructuredData() {
    }

    /**
     * Which content types the site holds, how many of each, and whether a
     * mapping exists for them.
     *
     * The coverage question the dashboard asks: not "is the JSON-LD valid" but
     * "how much of this site could emit any at all".
     */
    public static JSONObject coverage(String sitePath, String language, String base, JSONObject overrides)
            throws RepositoryException {
        JSONObject out = new JSONObject();
        Map<String, PublishedMap.Entry> published = PublishedMap.forSite(sitePath, base);

        Map<String, int[]> counts = new LinkedHashMap<>();
        for (PublishedMap.Entry e : published.values()) {
            if (!language.equals(e.language)) {
                continue;
            }
            counts.computeIfAbsent(e.nodeType, k -> new int[1])[0]++;
        }

        Map<String, Set<String>> propertiesByType = propertiesOf(counts.keySet());

        JSONArray types = new JSONArray();
        int mappedItems = 0;
        int completeItems = 0;
        int total = 0;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int n = e.getValue()[0];
            total += n;
            JSONObject row = typeRow(e.getKey(), n, overrides,
                    propertiesByType.getOrDefault(e.getKey(), new LinkedHashSet<>()));
            if (row.getBoolean(MAPPED)) {
                mappedItems += n;
            }
            if (row.optBoolean("complete")) {
                completeItems += n;
            }
            types.put(row);
        }

        out.put("types", types);
        out.put("total", total);
        out.put("mappedItems", mappedItems);
        out.put("completeItems", completeItems);
        out.put("language", language);
        return out;
    }

    /**
     * One content type's line of the coverage: its schema type, a suggestion from its properties,
     * and what the model cannot fill. One answer for the type, because a missing property is a
     * modelling gap: it is absent from every item of that type, not from some.
     */
    static JSONObject typeRow(String nodeType, int count, JSONObject overrides, Set<String> available) {
        JSONObject row = new JSONObject();
        row.put("nodeType", nodeType);
        row.put("count", count);
        String schemaType = SchemaMap.typeFor(overrides, nodeType);
        row.put("muted", SchemaMap.muted(overrides, nodeType));
        // A hint from what the model holds, shown beside the choice and never applied.
        String suggested = SchemaMap.suggestFor(available);
        if (suggested != null && !suggested.equals(schemaType)) {
            row.put("suggested", suggested);
        }
        row.put(MAPPED, schemaType != null);
        if (schemaType == null) {
            return row;
        }
        row.put("schemaType", schemaType);
        JSONArray missing = unsourced(available, SchemaMap.requiredFor(schemaType));
        row.put("missing", missing);
        row.put("recommendedMissing", unsourced(available, SchemaMap.recommendedFor(schemaType)));
        row.put("complete", missing.length() == 0);
        return row;
    }

    /** The schema properties of the list that the declared properties cannot fill. */
    private static JSONArray unsourced(Set<String> available, String[] schemaProperties) {
        JSONArray out = new JSONArray();
        for (String p : schemaProperties) {
            if (!hasSource(available, p)) {
                out.put(p);
            }
        }
        return out;
    }

    /**
     * The JSON-LD for one node, with an account of what could not be filled.
     *
     * `pageTitle` is what the rendered page actually says, when it is known. A
     * generated name that disagrees with it is reported rather than silently
     * emitted: the story's last criterion is that structured data must never
     * contradict the page, and this is the only place that can be checked.
     */
    public static JSONObject forNode(String jcrPath, String language, String base, JSONObject overrides,
            String pageTitle) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "live",
                Locale.forLanguageTag(language),
                (JCRCallback<JSONObject>) session -> forNodeIn(session, jcrPath, base, overrides, pageTitle));
    }

    /** {@link #forNode} inside its live session. */
    private static JSONObject forNodeIn(JCRSessionWrapper session, String jcrPath, String base, JSONObject overrides,
            String pageTitle) throws RepositoryException {
        JSONObject out = new JSONObject();
        JCRNodeWrapper n;
        try {
            n = session.getNode(jcrPath);
        } catch (javax.jcr.PathNotFoundException e) {
            out.put("published", false);
            return out;
        }
        out.put("published", true);
        String nodeType = n.getPrimaryNodeTypeName();
        out.put("nodeType", nodeType);

        String schemaType = SchemaMap.typeFor(overrides, nodeType);
        out.put(MAPPED, schemaType != null);
        if (schemaType == null) {
            return out;
        }
        out.put("schemaType", schemaType);

        JSONObject ld = new JSONObject();
        ld.put("@context", "https://schema.org");
        ld.put(TYPE, schemaType);
        try {
            ld.put("url", PublicUrls.forNode(n, base));
        } catch (Exception e) {
            logger.debug("no url for {}", jcrPath, e);
        }

        JSONArray missing = new JSONArray();
        JSONArray sourced = new JSONArray();
        fillAll(n, schemaType, base, ld, missing, sourced);
        out.put("jsonLd", ld);
        out.put("missing", missing);
        out.put("sourced", sourced);
        out.put("valid", missing.length() == 0);

        // The contradiction check. Only meaningful when the page's
        // own title is known, which is why it is passed in.
        JSONObject conflict = conflict(ld, pageTitle);
        if (conflict != null) {
            out.put("conflict", conflict);
        }
        return out;
    }

    /** Fills every property of the schema type it can, and lists the required ones it cannot. */
    private static void fillAll(JCRNodeWrapper n, String schemaType, String base, JSONObject ld, JSONArray missing,
            JSONArray sourced) throws RepositoryException {
        Set<String> available = new LinkedHashSet<>();
        javax.jcr.PropertyIterator pi = n.getProperties();
        while (pi.hasNext()) {
            available.add(pi.nextProperty().getName());
        }
        for (String prop : allProperties(schemaType)) {
            Filled filled = fill(n, available, prop, base);
            if (filled != null) {
                put(ld, prop, filled.value);
                sourced.put(new JSONObject().put("property", prop).put("from", filled.from));
            } else if (isRequired(schemaType, prop)) {
                missing.put(prop);
            }
        }
    }

    /**
     * Same text allowing for the suffixes a template adds - "Buy | Luxe" is the
     * page saying the same thing as "Buy", not contradicting it.
     */
    private static boolean alike(String a, String b) {
        String x = normalise(a);
        String y = normalise(b);
        return x.equals(y) || y.startsWith(x) || x.startsWith(y);
    }

    private static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", " ").trim();
    }

    private static List<String> allProperties(String schemaType) {
        List<String> out = new ArrayList<>();
        for (String p : SchemaMap.requiredFor(schemaType)) {
            out.add(p);
        }
        for (String p : SchemaMap.recommendedFor(schemaType)) {
            if (!out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private static boolean isRequired(String schemaType, String property) {
        for (String p : SchemaMap.requiredFor(schemaType)) {
            if (p.equals(property)) {
                return true;
            }
        }
        return false;
    }

    /** A value read for a schema property, and the content properties it came from. */
    private static final class Filled {
        final Object value;
        final String from;

        Filled(Object value, String from) {
            this.value = value;
            this.from = from;
        }
    }

    /** The value of one schema property for a node, or null when nothing fills it. */
    private static Filled fill(JCRNodeWrapper n, Set<String> available, String prop, String base) {
        String source = sourceFor(available, prop);
        if (source != null) {
            Object value = OPENING_HOURS.equals(prop) ? openingHours(allValues(n, source)) : valueOf(n, source, prop, base);
            return value == null ? null : new Filled(value, source);
        }
        SchemaMap.Composite composite = SchemaMap.compositeFor(prop);
        if (composite == null) {
            return null;
        }
        Map<String, String> parts = partsOf(n, available, composite, base);
        JSONObject value = composite(composite, parts);
        return value == null ? null : new Filled(value, String.join(", ", parts.keySet()));
    }

    /** The generated name against the page's own title, when they disagree; else null. */
    private static JSONObject conflict(JSONObject ld, String pageTitle) {
        String name = ld.optString("name", ld.optString("headline", ""));
        if (pageTitle == null || pageTitle.isEmpty() || name.isEmpty() || alike(name, pageTitle)) {
            return null;
        }
        JSONObject conflict = new JSONObject();
        conflict.put("property", ld.has("name") ? "name" : "headline");
        conflict.put("generated", name);
        conflict.put("page", pageTitle);
        return conflict;
    }

    /** Whether a schema property has a source: one property, or the parts of a composite value. */
    static boolean hasSource(Set<String> available, String schemaProperty) {
        if (sourceFor(available, schemaProperty) != null) {
            return true;
        }
        SchemaMap.Composite c = SchemaMap.compositeFor(schemaProperty);
        return c != null && c.madeOf(available);
    }

    /** The parts of a composite this node holds, by schema.org field name, in the composite's order. */
    private static Map<String, String> partsOf(JCRNodeWrapper n, Set<String> available, SchemaMap.Composite c,
            String base) {
        Map<String, String> parts = new LinkedHashMap<>();
        for (String part : c.parts) {
            String actual = SchemaMap.declares(available, part);
            Object v = actual == null ? null : valueOf(n, actual, part, base);
            if (v instanceof String) {
                parts.put(part, (String) v);
            }
        }
        return parts;
    }

    /**
     * A composite value from its parts, or null when its anchors are missing: an address needs a
     * street or a town, coordinates need both numbers. Coordinates are written as numbers, as
     * schema.org expects them.
     */
    static JSONObject composite(SchemaMap.Composite c, Map<String, String> parts) {
        if (!c.madeOf(parts.keySet())) {
            return null;
        }
        JSONObject out = new JSONObject();
        out.put(TYPE, c.type);
        for (Map.Entry<String, String> e : parts.entrySet()) {
            if ("GeoCoordinates".equals(c.type)) {
                try {
                    out.put(e.getKey(), Double.parseDouble(e.getValue()));
                } catch (NumberFormatException ex) {
                    return null;
                }
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static final Pattern TIME = Pattern.compile("\\d{1,2}:\\d{2}");
    private static final Pattern DAY = Pattern.compile("[A-Za-z]{3,12}");

    /**
     * Opening hours as schema.org writes them. Values stored as JSON objects
     * ({@code {"dayOfWeek": "Monday", "opens": "09:00", "closes": "18:00"}}, the shape the store
     * locator saves) become an {@code openingHoursSpecification}; plain text such as
     * {@code Mo-Fr 09:00-18:00} stays {@code openingHours}. Anything else is left out rather than
     * guessed.
     *
     * @return {@code {openingHoursSpecification: [...]}}, {@code {openingHours: [...]}} or null
     */
    static JSONObject openingHours(List<String> values) {
        JSONArray specs = new JSONArray();
        JSONArray text = new JSONArray();
        for (String raw : values) {
            String v = raw == null ? "" : raw.trim();
            if (v.startsWith("{")) {
                JSONObject spec = specification(v);
                if (spec != null) {
                    specs.put(spec);
                }
            } else if (!v.isEmpty() && v.length() <= 80) {
                text.put(v);
            }
        }
        if (specs.length() > 0) {
            return new JSONObject().put("openingHoursSpecification", specs);
        }
        return text.length() > 0 ? new JSONObject().put(OPENING_HOURS, text) : null;
    }

    /** One OpeningHoursSpecification from its JSON form, or null when a field is not what it claims. */
    private static JSONObject specification(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String day = o.optString("dayOfWeek", "");
            String opens = o.optString("opens", "");
            String closes = o.optString("closes", "");
            if (!DAY.matcher(day).matches() || !TIME.matcher(opens).matches() || !TIME.matcher(closes).matches()) {
                return null;
            }
            return new JSONObject().put(TYPE, "OpeningHoursSpecification")
                    .put("dayOfWeek", day).put("opens", opens).put("closes", closes);
        } catch (Exception e) {
            // Not the JSON it looked like: left out.
            return null;
        }
    }

    /** Every value of a property as text, single or multiple. */
    private static List<String> allValues(JCRNodeWrapper n, String source) {
        List<String> out = new ArrayList<>();
        try {
            if (!n.hasProperty(source)) {
                return out;
            }
            Property p = n.getProperty(source);
            if (p.isMultiple()) {
                for (Value v : p.getValues()) {
                    out.add(v.getString());
                }
            } else {
                out.add(p.getString());
            }
        } catch (RepositoryException e) {
            logger.debug("could not read {}", source, e);
        }
        return out;
    }

    /** The first candidate source this node type actually declares. */
    private static String sourceFor(Set<String> available, String schemaProperty) {
        for (String candidate : SchemaMap.sourcesFor(schemaProperty)) {
            for (String actual : available) {
                if (actual.equalsIgnoreCase(candidate)) {
                    return actual;
                }
            }
        }
        return null;
    }

    /** Reads one property, shaped for the schema property it is filling. */
    private static Object valueOf(JCRNodeWrapper n, String source, String schemaProperty, String base) {
        try {
            if (!n.hasProperty(source)) {
                return null;
            }
            Property p = n.getProperty(source);
            if (p.isMultiple()) {
                Value[] values = p.getValues();
                if (values.length == 0) {
                    return null;
                }
                if (p.getType() == PropertyType.WEAKREFERENCE || p.getType() == PropertyType.REFERENCE) {
                    JSONArray urls = new JSONArray();
                    for (Value v : values) {
                        String url = referencedUrl(n, v.getString(), base);
                        if (url != null) {
                            urls.put(url);
                        }
                    }
                    return urls.length() == 0 ? null : urls;
                }
                return values[0].getString();
            }

            switch (p.getType()) {
                case PropertyType.WEAKREFERENCE:
                case PropertyType.REFERENCE:
                    return referencedUrl(n, p.getString(), base);
                case PropertyType.DATE:
                    Calendar c = p.getDate();
                    return c == null ? null : String.format("%1$tY-%1$tm-%1$td", c);
                case PropertyType.LONG:
                case PropertyType.DOUBLE:
                case PropertyType.DECIMAL:
                    return p.getString();
                default:
                    String s = p.getString();
                    if (s == null || s.trim().isEmpty()) {
                        return null;
                    }
                    s = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                    if ("description".equals(schemaProperty) && s.length() > MAX_DESC) {
                        s = s.substring(0, MAX_DESC).trim() + "…";
                    }
                    return s.isEmpty() ? null : s;
            }
        } catch (RepositoryException e) {
            return null;
        }
    }

    /** A referenced node's public address, which is what schema.org wants. */
    private static String referencedUrl(JCRNodeWrapper from, String uuid, String base) {
        try {
            JCRNodeWrapper target = (JCRNodeWrapper) from.getSession().getNodeByIdentifier(uuid);
            String url = target.getUrl();
            if (url == null || url.isEmpty()) {
                return null;
            }
            return url.startsWith("http") ? url : base.replaceAll("/+$", "") + url;
        } catch (RepositoryException e) {
            return null;
        }
    }

    /** `price` is only meaningful to schema.org wrapped in an Offer. */
    private static void put(JSONObject ld, String property, Object value) {
        if (OPENING_HOURS.equals(property) && value instanceof JSONObject) {
            // openingHours() names its own key: the specification or the text form.
            JSONObject hours = (JSONObject) value;
            for (String key : hours.keySet()) {
                ld.put(key, hours.get(key));
            }
            return;
        }
        if ("price".equals(property)) {
            JSONObject offer = ld.optJSONObject("offers");
            if (offer == null) {
                offer = new JSONObject();
                offer.put(TYPE, "Offer");
                ld.put("offers", offer);
            }
            offer.put("price", value);
            return;
        }
        if ("priceCurrency".equals(property)) {
            JSONObject offer = ld.optJSONObject("offers");
            if (offer != null) {
                offer.put("priceCurrency", value);
            }
            return;
        }
        if ("offers".equals(property)) {
            // `offers` sources from the price, because that is the property a
            // content model actually has. schema.org wants it wrapped, so wrap
            // it here rather than asking anyone to model an Offer node.
            JSONObject offer = ld.optJSONObject("offers");
            if (offer == null) {
                offer = new JSONObject();
                offer.put(TYPE, "Offer");
                ld.put("offers", offer);
            }
            offer.put("price", value);
            return;
        }
        ld.put(property, value);
    }

    /** The property names each node type declares, asked once per type. */
    private static Map<String, Set<String>> propertiesOf(Set<String> nodeTypes) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String type : nodeTypes) {
            Set<String> names = new LinkedHashSet<>();
            try {
                org.jahia.services.content.nodetypes.ExtendedNodeType nt =
                        org.jahia.services.content.nodetypes.NodeTypeRegistry.getInstance().getNodeType(type);
                for (org.jahia.services.content.nodetypes.ExtendedPropertyDefinition pd
                        : nt.getPropertyDefinitions()) {
                    names.add(pd.getName());
                }
            } catch (Exception e) {
                logger.debug("unknown node type {}", type, e);
            }
            // Always present on any node, and a legitimate source.
            names.add("jcr:title");
            names.add("jcr:created");
            names.add("jcr:lastModified");
            out.put(type, names);
        }
        return out;
    }

    /** Every published node type in the site, for the mapping interface. */
    public static Set<String> nodeTypesOf(String sitePath) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "live",
                (JCRCallback<Set<String>>) session -> {
                    Set<String> out = new LinkedHashSet<>();
                    for (String type : new String[]{"jnt:page", "jmix:mainResource"}) {
                        String sql = "select * from [" + type + "] as n where isdescendantnode(n, '"
                                + sitePath.replace("'", "''") + "')";
                        Query q = session.getWorkspace().getQueryManager().createQuery(sql, Query.JCR_SQL2);
                        q.setLimit(MAX_NODES);
                        NodeIterator it = q.execute().getNodes();
                        while (it.hasNext()) {
                            out.add(((JCRNodeWrapper) it.nextNode()).getPrimaryNodeTypeName());
                        }
                    }
                    return out;
                });
    }
}
